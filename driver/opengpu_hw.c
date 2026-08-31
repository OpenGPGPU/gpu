// SPDX-License-Identifier: GPL-2.0
/* Low-level MMIO, interrupt and execution control.
 *
 * When the device advertises GPU_CAP_JOB_QUEUE, submissions go through the
 * host-memory job ring and completions are retired from the host-memory IH
 * (interrupt history) ring, AMDGPU-style: the hardware writes a record with
 * the job id, ring slot and status into shared memory *before* raising the
 * IRQ, and the IRQ handler drains records instead of guessing which
 * submission completed.  Devices without the capability fall back to the
 * single-job register path. */
#include <linux/dma-fence.h>
#include <linux/dma-mapping.h>
#include <linux/interrupt.h>
#include <linux/io.h>
#include <linux/slab.h>
#include <linux/platform_device.h>

#include "opengpu_device.h"

struct opengpu_fence {
    struct dma_fence base;
};

static const char *opengpu_fence_driver_name(struct dma_fence *fence)
{
    return "opengpu";
}

static const char *opengpu_fence_timeline_name(struct dma_fence *fence)
{
    return "render";
}

static void opengpu_fence_release(struct dma_fence *base)
{
    struct opengpu_fence *fence;

    fence = container_of(base, struct opengpu_fence, base);
    kfree(fence);
}

static const struct dma_fence_ops opengpu_fence_ops = {
    .get_driver_name = opengpu_fence_driver_name,
    .get_timeline_name = opengpu_fence_timeline_name,
    .release = opengpu_fence_release,
};

static u32 opengpu_reg_read(struct opengpu_device *gpu, u32 offset)
{
    return ioread32(gpu->hw.regs + offset);
}

static void opengpu_reg_write(struct opengpu_device *gpu, u32 offset, u32 value)
{
    iowrite32(value, gpu->hw.regs + offset);
}

static void opengpu_hw_complete(struct opengpu_device *gpu, int error)
{
    struct dma_fence *fence;
    unsigned long flags;

    spin_lock_irqsave(&gpu->hw.fence_lock, flags);
    fence = gpu->hw.active_fence;
    if (fence) {
        gpu->hw.active_fence = NULL;
        gpu->hw.active_completion_delay_ms = 0;
        if (error)
            dma_fence_set_error(fence, error);
        dma_fence_signal_locked(fence);
    }
    spin_unlock_irqrestore(&gpu->hw.fence_lock, flags);

    if (fence)
        dma_fence_put(fence);
}

static u32 opengpu_next_job_id(struct opengpu_device *gpu)
{
    u32 id = ++gpu->hw.job_seqno & 0xffff;

    if (!id)
        id = ++gpu->hw.job_seqno & 0xffff;
    return id;
}

static void opengpu_job_fill(struct gpu_job_record *rec, u32 id,
                             const struct opengpu_job *job)
{
    memset(rec, 0, sizeof(*rec));
    rec->header = GPU_JOB_HDR(id, job->cmd_count);
    rec->cmd_base = lower_32_bits(job->cmd);
    rec->color_base = lower_32_bits(job->color);
    rec->depth_base = lower_32_bits(job->depth);
    rec->stride = job->stride;
    rec->state = GPU_JOB_STATE(job->depth_test, job->depth_func,
                               job->depth_write, job->cull_mode);
    rec->tex_base = lower_32_bits(job->texture);
    rec->tex_size = GPU_JOB_TEX_SIZE(job->texture_width,
                                     job->texture_height);
    rec->tex_config = job->texture_config;
}

/* Drain pending interrupt-history records and retire the fences they name.
 * Records are only trusted when the job id matches the submission tracked in
 * that ring slot, so records left behind by an aborted generation are
 * skipped instead of retiring the wrong fence. */
static void opengpu_ih_drain(struct opengpu_device *gpu)
{
    struct dma_fence *finished[OPENGPU_JOB_RING_ENTRIES];
    unsigned long flags;
    u32 wptr_dev;
    u32 delayed_ms = 0;
    bool have_delayed = false;
    int n = 0;
    int i;

    wptr_dev = opengpu_reg_read(gpu, GPU_REG_IH_WPTR) & 0xffff;

    spin_lock_irqsave(&gpu->hw.fence_lock, flags);
    while (gpu->hw.ih_rptr != wptr_dev && n < OPENGPU_JOB_RING_ENTRIES) {
        const struct gpu_ih_record *rec =
            (const struct gpu_ih_record *)gpu->hw.ih_ring.cpu +
            (gpu->hw.ih_rptr & gpu->hw.ih_mask);
        struct opengpu_pending_job *p =
            &gpu->hw.pending[rec->slot & gpu->hw.job_mask];

        if (p->fence && p->id == GPU_IH_HDR_ID(rec->header)) {
            if (GPU_IH_HDR_ERROR(rec->header))
                dma_fence_set_error(p->fence, -EIO);
            if (p->delay_ms && !gpu->hw.delayed_fence &&
                !GPU_IH_HDR_ERROR(rec->header)) {
                /* Test hook: defer the completion to the delayed work,
                 * mirroring the legacy slow-completion behaviour. */
                delayed_ms = p->delay_ms;
                have_delayed = true;
                gpu->hw.delayed_fence = p->fence;
                p->fence = NULL;
                p->delay_ms = 0;
                gpu->hw.job_done++;
            } else {
                dma_fence_signal_locked(p->fence);
                finished[n++] = p->fence;
                p->fence = NULL;
                p->delay_ms = 0;
                gpu->hw.job_done++;
            }
        }
        gpu->hw.ih_rptr = (gpu->hw.ih_rptr + 1) & 0xffff;
    }
    spin_unlock_irqrestore(&gpu->hw.fence_lock, flags);

    opengpu_reg_write(gpu, GPU_REG_IH_RPTR, gpu->hw.ih_rptr);

    for (i = 0; i < n; i++)
        dma_fence_put(finished[i]);

    if (have_delayed) {
        mod_delayed_work(system_dfl_wq, &gpu->hw.timeout_work,
                         msecs_to_jiffies(delayed_ms));
    } else if (gpu->hw.job_wptr != gpu->hw.job_done) {
        /* Watchdog restarts for the jobs still in flight. */
        mod_delayed_work(system_dfl_wq, &gpu->hw.timeout_work,
                         msecs_to_jiffies(OPENGPU_DRAW_WAIT_MS));
    }
}

void opengpu_hw_abort(struct opengpu_device *gpu, int error)
{
    cancel_delayed_work_sync(&gpu->hw.timeout_work);
    opengpu_reg_write(gpu, GPU_REG_IRQ, 0);

    if (gpu->hw.queue_ready) {
        struct dma_fence *abandoned[OPENGPU_JOB_RING_ENTRIES + 1];
        unsigned long flags;
        int i, n = 0;

        /* Drop the queue's state (RESET is a pulse and keeps ENABLE set); a
         * job already handed to the engine may still complete later, but its
         * records name job ids that no longer match any tracked submission
         * and are skipped by the drain. */
        opengpu_reg_write(gpu, GPU_REG_JOB_CONTROL,
                          GPU_JOB_RESET | GPU_JOB_ENABLE);
        opengpu_reg_write(gpu, GPU_REG_IH_RPTR, 0);

        spin_lock_irqsave(&gpu->hw.fence_lock, flags);
        for (i = 0; i < OPENGPU_JOB_RING_ENTRIES; i++) {
            struct opengpu_pending_job *p = &gpu->hw.pending[i];

            if (p->fence) {
                dma_fence_set_error(p->fence, error ?: -ECANCELED);
                dma_fence_signal_locked(p->fence);
                abandoned[n++] = p->fence;
                p->fence = NULL;
                p->delay_ms = 0;
            }
        }
        if (gpu->hw.delayed_fence) {
            dma_fence_set_error(gpu->hw.delayed_fence, error ?: -ECANCELED);
            dma_fence_signal_locked(gpu->hw.delayed_fence);
            abandoned[n++] = gpu->hw.delayed_fence;
            gpu->hw.delayed_fence = NULL;
        }
        /* The device reset rewound its ring pointers to zero; keep the
         * host's free-running counters in lockstep. */
        gpu->hw.job_wptr = 0;
        gpu->hw.job_done = 0;
        gpu->hw.ih_rptr = 0;
        spin_unlock_irqrestore(&gpu->hw.fence_lock, flags);

        opengpu_reg_write(gpu, GPU_REG_JOB_WPTR, gpu->hw.job_wptr & 0xffff);
        for (i = 0; i < n; i++)
            dma_fence_put(abandoned[i]);
        return;
    }

    opengpu_hw_complete(gpu, error ?: -ECANCELED);
}

static void opengpu_timeout_work(struct work_struct *work)
{
    struct opengpu_hw *hw;
    struct opengpu_device *gpu;
    unsigned long flags;
    struct dma_fence *fence = NULL;
    int error = -ETIMEDOUT;

    hw = container_of(to_delayed_work(work), struct opengpu_hw,
                      timeout_work);
    gpu = container_of(hw, struct opengpu_device, hw);

    if (gpu->hw.queue_ready) {
        spin_lock_irqsave(&gpu->hw.fence_lock, flags);
        if (gpu->hw.delayed_fence) {
            /* Simulated slow completion (test hook): the IH record said the
             * job completed, so retire it successfully. */
            fence = gpu->hw.delayed_fence;
            gpu->hw.delayed_fence = NULL;
            error = 0;
        } else {
            struct opengpu_pending_job *p =
                &gpu->hw.pending[gpu->hw.job_done & gpu->hw.job_mask];

            if (p->fence) {
                fence = p->fence;
                p->fence = NULL;
                p->delay_ms = 0;
                gpu->hw.job_done++;
            }
        }
        if (fence && error)
            dma_fence_set_error(fence, error);
        if (fence)
            dma_fence_signal_locked(fence);
        spin_unlock_irqrestore(&gpu->hw.fence_lock, flags);
        if (fence)
            dma_fence_put(fence);
        return;
    }

    {
        u32 status;

        status = opengpu_reg_read(gpu, GPU_REG_STATUS);
        opengpu_reg_write(gpu, GPU_REG_IRQ, 0);
        if (status & GPU_STATUS_ERROR)
            opengpu_hw_complete(gpu, -EIO);
        else if (status & GPU_STATUS_DONE)
            opengpu_hw_complete(gpu, 0);
        else
            opengpu_hw_complete(gpu, -ETIMEDOUT);
    }
}

static irqreturn_t opengpu_irq_handler(int irq, void *data)
{
    struct opengpu_device *gpu = data;
    u32 status;

    status = opengpu_reg_read(gpu, GPU_REG_STATUS);
    /* IRQ combines an enable bit with W1C pending.  Preserve enable while
     * acknowledging, otherwise the first completion permanently masks all
     * later queue interrupts. */
    opengpu_reg_write(gpu, GPU_REG_IRQ,
                      GPU_IRQ_ENABLE | GPU_IRQ_PENDING);
    opengpu_reg_write(gpu, GPU_REG_IRQ, GPU_IRQ_ENABLE);

    if (gpu->hw.queue_ready) {
        opengpu_ih_drain(gpu);
        return IRQ_HANDLED;
    }

    if (gpu->hw.active_completion_delay_ms) {
        mod_delayed_work(system_dfl_wq, &gpu->hw.timeout_work,
                         msecs_to_jiffies(
                             gpu->hw.active_completion_delay_ms));
        return IRQ_HANDLED;
    }
    cancel_delayed_work(&gpu->hw.timeout_work);
    opengpu_hw_complete(gpu, status & GPU_STATUS_ERROR ? -EIO : 0);
    return IRQ_HANDLED;
}

int opengpu_hw_init(struct opengpu_device *gpu, struct platform_device *pdev)
{
    struct resource *res;
    u32 id;
    int ret;

    mutex_init(&gpu->hw.submit_lock);
    spin_lock_init(&gpu->hw.fence_lock);
    gpu->hw.fence_context = dma_fence_context_alloc(1);
    INIT_DELAYED_WORK(&gpu->hw.timeout_work, opengpu_timeout_work);

    ret = dma_set_mask_and_coherent(gpu->dev, DMA_BIT_MASK(32));
    if (ret)
        return dev_err_probe(gpu->dev, ret, "32-bit DMA is unavailable\n");

    res = platform_get_resource_byname(pdev, IORESOURCE_MEM, "ctrl");
    if (!res)
        res = platform_get_resource(pdev, IORESOURCE_MEM, 0);
    if (!res)
        return dev_err_probe(gpu->dev, -ENODEV, "missing ctrl resource\n");

    gpu->hw.regs_phys = res->start;
    gpu->hw.regs_size = resource_size(res);
    gpu->hw.regs = devm_ioremap_resource(gpu->dev, res);
    if (IS_ERR(gpu->hw.regs))
        return PTR_ERR(gpu->hw.regs);

    id = opengpu_reg_read(gpu, GPU_REG_ID);
    if ((id >> 16) != GPU_DEVICE_ID)
        return dev_err_probe(gpu->dev, -ENODEV,
                             "bad device id 0x%08x\n", id);
    gpu->hw.capabilities = opengpu_reg_read(gpu, GPU_REG_CAPABILITIES);
    dev_info(gpu->dev,
             "GPU ABI device=0x%08x version=0x%04x capabilities=0x%08x\n",
             id, id & 0xffff, gpu->hw.capabilities);

    gpu->hw.irq = platform_get_irq_optional(pdev, 0);
    if (gpu->hw.irq == -EPROBE_DEFER)
        return -EPROBE_DEFER;
    if (gpu->hw.irq > 0) {
        ret = devm_request_irq(gpu->dev, gpu->hw.irq,
                               opengpu_irq_handler, 0, OPENGPU_NAME, gpu);
        if (ret)
            return dev_err_probe(gpu->dev, ret, "cannot request irq\n");
    }

    /* Host-memory job queue + IH ring, when the device advertises it. */
    if (gpu->hw.capabilities & GPU_CAP_JOB_QUEUE) {
        ret = opengpu_buffer_alloc(gpu, &gpu->hw.job_ring,
            OPENGPU_JOB_RING_ENTRIES * GPU_JOB_WORDS * 4);
        if (ret)
            return ret;
        ret = opengpu_buffer_alloc(gpu, &gpu->hw.ih_ring,
            OPENGPU_IH_RING_ENTRIES * GPU_IH_WORDS * 4);
        if (ret) {
            opengpu_buffer_free(gpu, &gpu->hw.job_ring);
            return ret;
        }
        memset(gpu->hw.job_ring.cpu, 0, gpu->hw.job_ring.size);
        memset(gpu->hw.ih_ring.cpu, 0, gpu->hw.ih_ring.size);
        gpu->hw.job_mask = OPENGPU_JOB_RING_ENTRIES - 1;
        gpu->hw.ih_mask = OPENGPU_IH_RING_ENTRIES - 1;

        opengpu_reg_write(gpu, GPU_REG_JOB_RING_BASE,
                          lower_32_bits(gpu->hw.job_ring.dma));
        opengpu_reg_write(gpu, GPU_REG_JOB_RING_SIZE,
                          OPENGPU_JOB_RING_ENTRIES);
        opengpu_reg_write(gpu, GPU_REG_IH_BASE,
                          lower_32_bits(gpu->hw.ih_ring.dma));
        opengpu_reg_write(gpu, GPU_REG_IH_SIZE,
                          OPENGPU_IH_RING_ENTRIES);
        opengpu_reg_write(gpu, GPU_REG_IH_RPTR, 0);
        /* Queue completions are delivered through the IH ring and the same
         * completion IRQ as the legacy START path.  Enable it before the
         * first doorbell; the queue submit path does not have a separate
         * interrupt-programming phase. */
        opengpu_reg_write(gpu, GPU_REG_IRQ, GPU_IRQ_ENABLE);
        opengpu_reg_write(gpu, GPU_REG_JOB_CONTROL, GPU_JOB_ENABLE);
        gpu->hw.queue_ready = true;
        dev_info(gpu->dev,
                 "GPU job queue ready: ring=%u entries ih=%u records\n",
                 OPENGPU_JOB_RING_ENTRIES, OPENGPU_IH_RING_ENTRIES);
    }

    return 0;
}

void opengpu_hw_fini(struct opengpu_device *gpu)
{
    opengpu_hw_abort(gpu, -ECANCELED);
    if (gpu->hw.queue_ready) {
        opengpu_reg_write(gpu, GPU_REG_JOB_CONTROL, 0);
        opengpu_buffer_free(gpu, &gpu->hw.ih_ring);
        opengpu_buffer_free(gpu, &gpu->hw.job_ring);
        gpu->hw.queue_ready = false;
    }
}

/* Queue submission: publish a descriptor into the host-memory job ring and
 * ring the doorbell.  Several jobs may be in flight; the device runs them
 * strictly in order and records each completion in the IH ring. */
static int opengpu_hw_submit_queue(struct opengpu_device *gpu,
                                   const struct opengpu_job *job,
                                   struct dma_fence **out_fence)
{
    struct opengpu_fence *fence;
    struct gpu_job_record *rec;
    struct opengpu_pending_job *p;
    unsigned long flags;
    u32 slot, id;
    int ret = 0;

    fence = kzalloc(sizeof(*fence), GFP_KERNEL);
    if (!fence)
        return -ENOMEM;

    mutex_lock(&gpu->hw.submit_lock);
    dma_fence_init(&fence->base, &opengpu_fence_ops,
                   &gpu->hw.fence_lock, gpu->hw.fence_context,
                   ++gpu->hw.fence_seqno);

    spin_lock_irqsave(&gpu->hw.fence_lock, flags);
    if (gpu->hw.job_wptr - gpu->hw.job_done >= OPENGPU_JOB_RING_ENTRIES) {
        ret = -EBUSY;
    } else {
        id = opengpu_next_job_id(gpu);
        slot = gpu->hw.job_wptr & gpu->hw.job_mask;
        rec = (struct gpu_job_record *)gpu->hw.job_ring.cpu + slot;
        opengpu_job_fill(rec, id, job);
        /* The descriptor must be visible before the doorbell. */
        dma_wmb();
        p = &gpu->hw.pending[slot];
        p->fence = &fence->base;
        p->id = id;
        p->delay_ms = job->completion_delay_ms;
        gpu->hw.job_wptr++;
        *out_fence = dma_fence_get(&fence->base);
    }
    spin_unlock_irqrestore(&gpu->hw.fence_lock, flags);
    if (ret)
        goto out_unlock;

    opengpu_reg_write(gpu, GPU_REG_JOB_WPTR, gpu->hw.job_wptr & 0xffff);
    mod_delayed_work(system_dfl_wq, &gpu->hw.timeout_work,
                     msecs_to_jiffies(OPENGPU_DRAW_WAIT_MS));

out_unlock:
    mutex_unlock(&gpu->hw.submit_lock);
    if (ret)
        dma_fence_put(&fence->base);
    return ret;
}

int opengpu_hw_submit_async(struct opengpu_device *gpu,
                            const struct opengpu_job *job,
                            struct dma_fence **out_fence)
{
    struct opengpu_fence *fence;
    unsigned long flags;
    int ret = 0;

    if (!out_fence)
        return -EINVAL;
    if (upper_32_bits(job->cmd) || upper_32_bits(job->color) ||
        upper_32_bits(job->depth) || upper_32_bits(job->texture))
        return -ERANGE;

    if (gpu->hw.queue_ready)
        return opengpu_hw_submit_queue(gpu, job, out_fence);

    fence = kzalloc(sizeof(*fence), GFP_KERNEL);
    if (!fence)
        return -ENOMEM;

    mutex_lock(&gpu->hw.submit_lock);
    dma_fence_init(&fence->base, &opengpu_fence_ops,
                   &gpu->hw.fence_lock, gpu->hw.fence_context,
                   ++gpu->hw.fence_seqno);

    spin_lock_irqsave(&gpu->hw.fence_lock, flags);
    if (gpu->hw.active_fence) {
        ret = -EBUSY;
    } else {
        gpu->hw.active_fence = &fence->base;
        gpu->hw.active_completion_delay_ms = job->completion_delay_ms;
        *out_fence = dma_fence_get(&fence->base);
    }
    spin_unlock_irqrestore(&gpu->hw.fence_lock, flags);
    if (ret)
        goto out_unlock;

    opengpu_reg_write(gpu, GPU_REG_CMD_BASE, lower_32_bits(job->cmd));
    opengpu_reg_write(gpu, GPU_REG_CMD_COUNT, job->cmd_count);
    opengpu_reg_write(gpu, GPU_REG_COLOR_BASE, lower_32_bits(job->color));
    opengpu_reg_write(gpu, GPU_REG_DEPTH_BASE, lower_32_bits(job->depth));
    opengpu_reg_write(gpu, GPU_REG_STRIDE, job->stride);
    opengpu_reg_write(gpu, GPU_REG_DEPTH_TEST, job->depth_test);
    opengpu_reg_write(gpu, GPU_REG_DEPTH_FUNC, job->depth_func);
    opengpu_reg_write(gpu, GPU_REG_DEPTH_WRITE, job->depth_write);
    opengpu_reg_write(gpu, GPU_REG_CULL_MODE, job->cull_mode);
    opengpu_reg_write(gpu, GPU_REG_TEX_BASE, lower_32_bits(job->texture));
    opengpu_reg_write(gpu, GPU_REG_TEX_WIDTH, job->texture_width);
    opengpu_reg_write(gpu, GPU_REG_TEX_HEIGHT, job->texture_height);
    opengpu_reg_write(gpu, GPU_REG_TEX_CONFIG, job->texture_config);

    opengpu_reg_write(gpu, GPU_REG_IRQ, GPU_IRQ_ENABLE);
    schedule_delayed_work(&gpu->hw.timeout_work,
                          msecs_to_jiffies(OPENGPU_DRAW_WAIT_MS));
    opengpu_reg_write(gpu, GPU_REG_CONTROL, GPU_CTRL_START);

out_unlock:
    mutex_unlock(&gpu->hw.submit_lock);
    if (ret)
        dma_fence_put(&fence->base);
    return ret;
}

int opengpu_hw_submit(struct opengpu_device *gpu,
                      const struct opengpu_job *job)
{
    struct dma_fence *fence;
    long timeout;
    int ret;

    ret = opengpu_hw_submit_async(gpu, job, &fence);
    if (ret)
        return ret;

    timeout = dma_fence_wait_timeout(fence, false,
                                     msecs_to_jiffies(OPENGPU_DRAW_WAIT_MS +
                                                      100));
    if (timeout <= 0) {
        if (gpu->hw.queue_ready)
            opengpu_hw_abort(gpu, timeout < 0 ? (int)timeout : -ETIMEDOUT);
        else
            opengpu_hw_complete(gpu, timeout < 0 ? timeout : -ETIMEDOUT);
        ret = timeout < 0 ? timeout : -ETIMEDOUT;
    } else {
        ret = dma_fence_get_status(fence);
        if (ret > 0)
            ret = 0;
    }
    dma_fence_put(fence);
    return ret;
}

int opengpu_hw_display_commit(struct opengpu_device *gpu,
                              const struct opengpu_scanout *scanout)
{
    if (scanout->enable &&
        (!scanout->base || upper_32_bits(scanout->base) ||
         !scanout->stride || !scanout->width || !scanout->height))
        return -EINVAL;

    /* Disable first and publish BASE last. ARTI uses the BASE write as the
     * point at which a new guest-memory scanout becomes visible. */
    opengpu_reg_write(gpu, GPU_REG_SCANOUT_CONTROL, 0);
    opengpu_reg_write(gpu, GPU_REG_SCANOUT_STRIDE, scanout->stride);
    opengpu_reg_write(gpu, GPU_REG_SCANOUT_WIDTH, scanout->width);
    opengpu_reg_write(gpu, GPU_REG_SCANOUT_HEIGHT, scanout->height);
    opengpu_reg_write(gpu, GPU_REG_SCANOUT_FORMAT, scanout->format);
    opengpu_reg_write(gpu, GPU_REG_SCANOUT_BASE,
                      scanout->enable ? lower_32_bits(scanout->base) : 0);
    if (scanout->enable)
        opengpu_reg_write(gpu, GPU_REG_SCANOUT_CONTROL,
                          GPU_SCANOUT_ENABLE);
    if (!!(opengpu_reg_read(gpu, GPU_REG_SCANOUT_STATUS) &
           GPU_SCANOUT_ACTIVE) != scanout->enable)
        return -EIO;
    return 0;
}
