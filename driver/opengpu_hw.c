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
#include <linux/overflow.h>
#include <linux/slab.h>
#include <linux/platform_device.h>

#include "opengpu_device.h"
#include "opengpu_drm.h"

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

static int opengpu_hw_unified_result_error(u32 opcode, u32 status);
static bool opengpu_hw_unified_drain(struct opengpu_device *gpu);

static void opengpu_hw_record_fault_locked(struct opengpu_device *gpu,
                                            int error, u32 flags,
                                            u32 command_id, u32 opcode,
                                            u32 expected_command_id,
                                            u32 expected_opcode,
                                            u32 status, u64 processed,
                                            u64 expected)
{
    struct opengpu_fault_snapshot *fault = &gpu->hw.last_fault;

    fault->sequence++;
    if (!fault->sequence)
        fault->sequence++;
    fault->bytes_processed = processed;
    fault->expected_bytes = expected;
    fault->error = error;
    fault->command_id = command_id;
    fault->opcode = opcode;
    fault->status = status;
    fault->flags = flags | OPENGPU_FAULT_VALID;
    fault->expected_command_id = expected_command_id;
    fault->expected_opcode = expected_opcode;
}

void opengpu_hw_get_fault(struct opengpu_device *gpu,
                          struct opengpu_fault_snapshot *fault)
{
    unsigned long flags;

    spin_lock_irqsave(&gpu->hw.fence_lock, flags);
    *fault = gpu->hw.last_fault;
    spin_unlock_irqrestore(&gpu->hw.fence_lock, flags);
}

void opengpu_hw_progress_tick(struct opengpu_device *gpu)
{
    /* Heartbeat read: RO register, no side effects. Emulated hardware
     * models only advance while the host touches device registers, so
     * fence waiters poll this to keep their draw moving. */
    opengpu_reg_read(gpu, GPU_REG_IH_WPTR);
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
    rec->msaa = job->sample_mode & 0x3u;
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
                dev_info(gpu->dev, "OPENGPU ih defer: id=%u slot=%u\n",
                         p->id, rec->slot & gpu->hw.job_mask);
            } else {
                dma_fence_signal_locked(p->fence);
                finished[n++] = p->fence;
                p->fence = NULL;
                p->delay_ms = 0;
                gpu->hw.job_done++;
            }
        } else {
            dev_info(gpu->dev,
                     "OPENGPU ih skip: hdr=%08x slot=%u fence=%d id=%u\n",
                     rec->header, rec->slot & gpu->hw.job_mask,
                     !!p->fence, p->id);
        }
        gpu->hw.ih_rptr = (gpu->hw.ih_rptr + 1) & 0xffff;
    }
    spin_unlock_irqrestore(&gpu->hw.fence_lock, flags);

    opengpu_reg_write(gpu, GPU_REG_IH_RPTR, gpu->hw.ih_rptr);
    dev_info(gpu->dev, "OPENGPU drain: dev_wptr=%u rptr=%u->%u retired=%u\n",
             wptr_dev, (unsigned)(gpu->hw.ih_rptr - n), gpu->hw.ih_rptr, n);

    for (i = 0; i < n; i++)
        dma_fence_put(finished[i]);

    if (have_delayed)
        mod_delayed_work(system_dfl_wq, &gpu->hw.completion_work,
                         msecs_to_jiffies(delayed_ms));
    if (gpu->hw.job_wptr != gpu->hw.job_done) {
        /* Watchdog restarts for the jobs still in flight. */
        mod_delayed_work(system_dfl_wq, &gpu->hw.timeout_work,
                         msecs_to_jiffies(OPENGPU_DRAW_WAIT_MS));
    } else
        cancel_delayed_work(&gpu->hw.timeout_work);
}

void opengpu_hw_abort(struct opengpu_device *gpu, int error)
{
    struct dma_fence *unified = NULL;
    unsigned long unified_flags;

    cancel_delayed_work_sync(&gpu->hw.timeout_work);
    cancel_delayed_work_sync(&gpu->hw.completion_work);
    cancel_delayed_work_sync(&gpu->hw.poll_work);
    opengpu_reg_write(gpu, GPU_REG_IRQ, 0);

    spin_lock_irqsave(&gpu->hw.fence_lock, unified_flags);
    unified = gpu->hw.unified_fence;
    gpu->hw.unified_fence = NULL;
    if (unified) {
        u32 fault_flags = OPENGPU_FAULT_ABORTED;

        if (error == -ETIMEDOUT)
            fault_flags |= OPENGPU_FAULT_TIMEOUT;
        opengpu_hw_record_fault_locked(
            gpu, error ?: -ECANCELED, fault_flags,
            gpu->hw.unified_command_id, gpu->hw.unified_opcode,
            gpu->hw.unified_command_id, gpu->hw.unified_opcode, 0, 0,
            gpu->hw.unified_expected_bytes);
        dma_fence_set_error(unified, error ?: -ECANCELED);
        dma_fence_signal_locked(unified);
    }
    spin_unlock_irqrestore(&gpu->hw.fence_lock, unified_flags);
    if (unified) {
        if (opengpu_reg_read(gpu, GPU_REG_UCMD_STATUS) &
            GPU_UCMD_STATUS_COMPLETION)
            opengpu_reg_write(gpu, GPU_REG_UCMD_COMPLETION_POP, 1);
        dma_fence_put(unified);
    }

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

    hw = container_of(to_delayed_work(work), struct opengpu_hw,
                      timeout_work);
    gpu = container_of(hw, struct opengpu_device, hw);

    spin_lock_irqsave(&gpu->hw.fence_lock, flags);
    fence = gpu->hw.unified_fence;
    if (fence) {
        gpu->hw.unified_fence = NULL;
        opengpu_hw_record_fault_locked(
            gpu, -ETIMEDOUT,
            OPENGPU_FAULT_TIMEOUT | OPENGPU_FAULT_ABORTED,
            gpu->hw.unified_command_id, gpu->hw.unified_opcode,
            gpu->hw.unified_command_id, gpu->hw.unified_opcode, 0, 0,
            gpu->hw.unified_expected_bytes);
        dma_fence_set_error(fence, -ETIMEDOUT);
        dma_fence_signal_locked(fence);
    }
    spin_unlock_irqrestore(&gpu->hw.fence_lock, flags);
    if (fence) {
        dma_fence_put(fence);
        return;
    }

    if (gpu->hw.queue_ready) {
        spin_lock_irqsave(&gpu->hw.fence_lock, flags);
        {
            struct opengpu_pending_job *p =
                &gpu->hw.pending[gpu->hw.job_done & gpu->hw.job_mask];

            if (p->fence) {
                fence = p->fence;
                p->fence = NULL;
                p->delay_ms = 0;
                gpu->hw.job_done++;
            }
        }
        if (fence)
            dma_fence_set_error(fence, -ETIMEDOUT);
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

static void opengpu_completion_work(struct work_struct *work)
{
    struct opengpu_hw *hw;
    struct opengpu_device *gpu;
    struct dma_fence *fence = NULL;
    unsigned long flags;

    hw = container_of(to_delayed_work(work), struct opengpu_hw,
                      completion_work);
    gpu = container_of(hw, struct opengpu_device, hw);
    if (!gpu->hw.queue_ready) {
        opengpu_hw_complete(gpu, 0);
        return;
    }

    spin_lock_irqsave(&gpu->hw.fence_lock, flags);
    fence = gpu->hw.delayed_fence;
    gpu->hw.delayed_fence = NULL;
    if (fence)
        dma_fence_signal_locked(fence);
    spin_unlock_irqrestore(&gpu->hw.fence_lock, flags);
    if (fence)
        dma_fence_put(fence);
}

/* Completion poll interval. The register read doubles as a watchdog: it
 * drains IH records that raced the interrupt, and emulated hardware models
 * only advance while the host touches device registers, so a polling read
 * keeps slow-clocked models moving toward completion. */
#define OPENGPU_HW_POLL_INTERVAL_MS 5

static void opengpu_poll_work(struct work_struct *work)
{
    struct opengpu_hw *hw;
    struct opengpu_device *gpu;

    hw = container_of(to_delayed_work(work), struct opengpu_hw, poll_work);
    gpu = container_of(hw, struct opengpu_device, hw);

    if (opengpu_hw_unified_drain(gpu))
        return;
    if (READ_ONCE(gpu->hw.unified_fence)) {
        opengpu_hw_progress_tick(gpu);
        mod_delayed_work(system_dfl_wq, &gpu->hw.poll_work,
                         msecs_to_jiffies(OPENGPU_HW_POLL_INTERVAL_MS));
        return;
    }

    if (!gpu->hw.queue_ready)
        return;
    if (gpu->hw.job_wptr == gpu->hw.job_done)
        return;
    dev_info(gpu->dev, "OPENGPU poll: wptr=%u done=%u\n",
             gpu->hw.job_wptr, gpu->hw.job_done);
    opengpu_ih_drain(gpu);
    if (gpu->hw.job_wptr != gpu->hw.job_done)
        mod_delayed_work(system_dfl_wq, &gpu->hw.poll_work,
                         msecs_to_jiffies(OPENGPU_HW_POLL_INTERVAL_MS));
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

    dev_info(gpu->dev, "OPENGPU irq: status=%08x wptr=%u done=%u\n",
             status, gpu->hw.job_wptr, gpu->hw.job_done);
    opengpu_hw_unified_drain(gpu);
    if (gpu->hw.queue_ready) {
        opengpu_ih_drain(gpu);
        return IRQ_HANDLED;
    }

    if (gpu->hw.active_completion_delay_ms) {
        cancel_delayed_work(&gpu->hw.timeout_work);
        mod_delayed_work(system_dfl_wq, &gpu->hw.completion_work,
                         msecs_to_jiffies(
                             gpu->hw.active_completion_delay_ms));
        return IRQ_HANDLED;
    }
    cancel_delayed_work(&gpu->hw.timeout_work);
    cancel_delayed_work(&gpu->hw.poll_work);
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
    INIT_DELAYED_WORK(&gpu->hw.completion_work, opengpu_completion_work);
    INIT_DELAYED_WORK(&gpu->hw.poll_work, opengpu_poll_work);

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

    if (gpu->hw.capabilities & GPU_CAP_UNIFIED_COMMANDS) {
        u32 command_status = opengpu_reg_read(gpu, GPU_REG_UCMD_STATUS);

        /* Firmware must hand the device to Linux idle. Discard a consumed
         * boot-time result and clear the sticky submit-overflow diagnostic so
         * the first scheduler job starts from an owned completion slot. */
        if (command_status & GPU_UCMD_STATUS_COMPLETION)
            opengpu_reg_write(gpu, GPU_REG_UCMD_COMPLETION_POP, 1);
        if (command_status & GPU_UCMD_STATUS_OVERFLOW)
            opengpu_reg_write(gpu, GPU_REG_UCMD_STATUS,
                              GPU_UCMD_STATUS_OVERFLOW);
    }

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
static int opengpu_hw_submit_queue_locked(struct opengpu_device *gpu,
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
        goto out;

    opengpu_reg_write(gpu, GPU_REG_JOB_WPTR, gpu->hw.job_wptr & 0xffff);
    mod_delayed_work(system_dfl_wq, &gpu->hw.timeout_work,
                     msecs_to_jiffies(OPENGPU_DRAW_WAIT_MS));
    mod_delayed_work(system_dfl_wq, &gpu->hw.poll_work,
                     msecs_to_jiffies(OPENGPU_HW_POLL_INTERVAL_MS));

out:
    if (ret)
        dma_fence_put(&fence->base);
    return ret;
}

static int opengpu_hw_validate_job(const struct opengpu_job *job,
                                   struct dma_fence **out_fence)
{
    if (!out_fence)
        return -EINVAL;
    if (upper_32_bits(job->cmd) || upper_32_bits(job->color) ||
        upper_32_bits(job->depth) || upper_32_bits(job->texture))
        return -ERANGE;
    return 0;
}

static int opengpu_hw_submit_legacy_locked(struct opengpu_device *gpu,
                                           const struct opengpu_job *job,
                                           struct dma_fence **out_fence)
{
    struct opengpu_fence *fence;
    unsigned long flags;
    int ret = 0;

    fence = kzalloc(sizeof(*fence), GFP_KERNEL);
    if (!fence)
        return -ENOMEM;

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
        goto out;

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
    opengpu_reg_write(gpu, GPU_REG_MSAA_CONFIG, job->sample_mode & 0x3u);

    opengpu_reg_write(gpu, GPU_REG_IRQ, GPU_IRQ_ENABLE);
    schedule_delayed_work(&gpu->hw.timeout_work,
                          msecs_to_jiffies(OPENGPU_DRAW_WAIT_MS));
    opengpu_reg_write(gpu, GPU_REG_CONTROL, GPU_CTRL_START);

out:
    if (ret)
        dma_fence_put(&fence->base);
    return ret;
}

static int opengpu_hw_submit_locked(struct opengpu_device *gpu,
                                    const struct opengpu_job *job,
                                    struct dma_fence **out_fence)
{
    if (gpu->hw.queue_ready)
        return opengpu_hw_submit_queue_locked(gpu, job, out_fence);
    return opengpu_hw_submit_legacy_locked(gpu, job, out_fence);
}

static int opengpu_hw_unified_result_error(u32 opcode, u32 status)
{
    if (status == GPU_UCMD_RESULT_EVENT_DEPENDENCY_FAILED)
        return -ECANCELED;
    if (opcode == GPU_UCMD_OP_KERNEL) {
        switch (status) {
        case GPU_UCMD_RESULT_SUCCESS:
            return 0;
        case GPU_UCMD_KERNEL_INVALID_PC:
        case GPU_UCMD_KERNEL_INVALID_GRID:
        case GPU_UCMD_KERNEL_INVALID_LOCAL_SIZE:
        case GPU_UCMD_KERNEL_MISALIGNED_KERNARG:
        case GPU_UCMD_KERNEL_DMA_DEPENDENCY:
            return -EINVAL;
        case GPU_UCMD_KERNEL_EXECUTION_FAILED:
        default:
            return -EIO;
        }
    }
    switch (status) {
    case GPU_UCMD_RESULT_SUCCESS:
        return 0;
    case GPU_UCMD_RESULT_INVALID_ALIGNMENT:
    case GPU_UCMD_RESULT_INVALID_LENGTH:
    case GPU_UCMD_RESULT_ADDRESS_OVERFLOW:
        return -ERANGE;
    case GPU_UCMD_RESULT_OVERLAP_UNSUPPORTED:
        return -EINVAL;
    case GPU_UCMD_RESULT_READ_FAULT:
    case GPU_UCMD_RESULT_WRITE_FAULT:
    default:
        return -EIO;
    }
}

static bool opengpu_hw_unified_drain(struct opengpu_device *gpu)
{
    struct dma_fence *fence = NULL;
    unsigned long flags;
    u64 processed;
    u32 completion;
    u32 command_id;
    u32 opcode;
    u64 expected_bytes;
    int ret;

    if (!(gpu->hw.capabilities & GPU_CAP_UNIFIED_COMMANDS) ||
        !(opengpu_reg_read(gpu, GPU_REG_UCMD_STATUS) &
          GPU_UCMD_STATUS_COMPLETION))
        return false;

    completion = opengpu_reg_read(gpu, GPU_REG_UCMD_COMPLETION);
    processed = opengpu_reg_read(gpu,
        GPU_REG_UCMD_COMPLETION_BYTES_LO);
    processed |= (u64)opengpu_reg_read(gpu,
        GPU_REG_UCMD_COMPLETION_BYTES_HI) << 32;
    opengpu_reg_write(gpu, GPU_REG_UCMD_COMPLETION_POP, 1);

    spin_lock_irqsave(&gpu->hw.fence_lock, flags);
    fence = gpu->hw.unified_fence;
    command_id = gpu->hw.unified_command_id;
    opcode = gpu->hw.unified_opcode;
    expected_bytes = gpu->hw.unified_expected_bytes;
    gpu->hw.unified_fence = NULL;
    if (fence) {
        u32 fault_flags = 0;
        u32 actual_id = GPU_UCMD_COMPLETION_ID(completion);
        u32 actual_opcode = GPU_UCMD_COMPLETION_OPCODE(completion);
        u32 actual_status = GPU_UCMD_COMPLETION_STATUS(completion);
        bool actual_success = GPU_UCMD_COMPLETION_SUCCESS(completion);

        ret = opengpu_hw_unified_result_error(opcode,
            actual_status);
        if (ret)
            fault_flags |= OPENGPU_FAULT_COMPLETION_ERROR;
        if (actual_id != command_id)
            fault_flags |= OPENGPU_FAULT_COMMAND_ID_MISMATCH;
        if (actual_opcode != opcode)
            fault_flags |= OPENGPU_FAULT_OPCODE_MISMATCH;
        if (actual_success != !ret)
            fault_flags |= OPENGPU_FAULT_SUCCESS_MISMATCH;
        if (!ret && processed != expected_bytes)
            fault_flags |= OPENGPU_FAULT_BYTES_MISMATCH;
        if (fault_flags & ~OPENGPU_FAULT_COMPLETION_ERROR)
            ret = -EIO;
        if (ret) {
            opengpu_hw_record_fault_locked(
                gpu, ret, fault_flags, actual_id, actual_opcode,
                command_id, opcode, actual_status, processed, expected_bytes);
            dev_err(gpu->dev,
                    "unified command %u opcode %u failed: completion=%08x bytes=%llu error=%d\n",
                    command_id, opcode, completion, processed, ret);
            dma_fence_set_error(fence, ret);
        }
        dma_fence_signal_locked(fence);
    }
    spin_unlock_irqrestore(&gpu->hw.fence_lock, flags);
    if (fence)
        dma_fence_put(fence);
    cancel_delayed_work(&gpu->hw.timeout_work);
    return true;
}

static int opengpu_hw_unified_submit_locked(
    struct opengpu_device *gpu,
    const struct opengpu_kernel_launch *launch,
    const struct opengpu_command_events *events,
    u32 opcode, u32 source, u32 destination,
    u32 bytes, u32 pattern, u32 height, u32 source_stride,
    u32 destination_stride, u64 expected_bytes, struct dma_fence **out_fence)
{
    struct opengpu_fence *fence;
    unsigned long flags;
    u32 command_id;
    u32 status;
    int ret = 0;

    if (!out_fence)
        return -EINVAL;
    *out_fence = NULL;
    if (events &&
        ((events->flags & ~(GPU_UCMD_FLAG_WAIT_EVENT |
                            GPU_UCMD_FLAG_SIGNAL_EVENT)) ||
         (events->wait_event & ~0xffffu) ||
         (events->signal_event & ~0xffffu) ||
         (!(events->flags & GPU_UCMD_FLAG_WAIT_EVENT) &&
          events->wait_event) ||
         (!(events->flags & GPU_UCMD_FLAG_SIGNAL_EVENT) &&
          events->signal_event)))
        return -EINVAL;
    status = opengpu_reg_read(gpu, GPU_REG_UCMD_STATUS);
    if (!(status & GPU_UCMD_STATUS_READY) ||
        (status & GPU_UCMD_STATUS_COMPLETION))
        return -EBUSY;
    if (status & GPU_UCMD_STATUS_OVERFLOW)
        opengpu_reg_write(gpu, GPU_REG_UCMD_STATUS,
                          GPU_UCMD_STATUS_OVERFLOW);

    fence = kzalloc(sizeof(*fence), GFP_KERNEL);
    if (!fence)
        return -ENOMEM;
    dma_fence_init(&fence->base, &opengpu_fence_ops,
                   &gpu->hw.fence_lock, gpu->hw.fence_context,
                   ++gpu->hw.fence_seqno);

    spin_lock_irqsave(&gpu->hw.fence_lock, flags);
    if (gpu->hw.unified_fence) {
        ret = -EBUSY;
    } else {
        command_id = opengpu_next_job_id(gpu) & 0xffu;
        gpu->hw.unified_fence = &fence->base;
        gpu->hw.unified_command_id = command_id;
        gpu->hw.unified_opcode = opcode;
        gpu->hw.unified_expected_bytes = expected_bytes;
        *out_fence = dma_fence_get(&fence->base);
    }
    spin_unlock_irqrestore(&gpu->hw.fence_lock, flags);
    if (ret) {
        dma_fence_put(&fence->base);
        return ret;
    }

    opengpu_reg_write(gpu, GPU_REG_UCMD_ID, command_id);
    opengpu_reg_write(gpu, GPU_REG_UCMD_OPCODE, opcode);
    opengpu_reg_write(gpu, GPU_REG_UCMD_KERNEL_PC,
                      launch ? lower_32_bits(launch->kernel_pc) : 0);
    opengpu_reg_write(gpu, GPU_REG_UCMD_KERNARG,
                      launch ? lower_32_bits(launch->kernarg) : 0);
    opengpu_reg_write(gpu, GPU_REG_UCMD_GRID_X,
                      launch ? launch->grid[0] : 1);
    opengpu_reg_write(gpu, GPU_REG_UCMD_GRID_Y,
                      launch ? launch->grid[1] : 1);
    opengpu_reg_write(gpu, GPU_REG_UCMD_GRID_Z,
                      launch ? launch->grid[2] : 1);
    opengpu_reg_write(gpu, GPU_REG_UCMD_LOCAL_X,
                      launch ? launch->local[0] : 1);
    opengpu_reg_write(gpu, GPU_REG_UCMD_LOCAL_Y,
                      launch ? launch->local[1] : 1);
    opengpu_reg_write(gpu, GPU_REG_UCMD_LOCAL_Z,
                      launch ? launch->local[2] : 1);
    opengpu_reg_write(gpu, GPU_REG_UCMD_FLAGS,
                      events ? events->flags : 0);
    opengpu_reg_write(gpu, GPU_REG_UCMD_WAIT_EVENT,
                      events ? events->wait_event : 0);
    opengpu_reg_write(gpu, GPU_REG_UCMD_SIGNAL_EVENT,
                      events ? events->signal_event : 0);
    opengpu_reg_write(gpu, GPU_REG_UCMD_SOURCE, source);
    opengpu_reg_write(gpu, GPU_REG_UCMD_DESTINATION, destination);
    opengpu_reg_write(gpu, GPU_REG_UCMD_BYTES, bytes);
    opengpu_reg_write(gpu, GPU_REG_UCMD_PATTERN, pattern);
    opengpu_reg_write(gpu, GPU_REG_UCMD_WIDTH, bytes);
    opengpu_reg_write(gpu, GPU_REG_UCMD_HEIGHT, height);
    opengpu_reg_write(gpu, GPU_REG_UCMD_SOURCE_STRIDE, source_stride);
    opengpu_reg_write(gpu, GPU_REG_UCMD_DEST_STRIDE,
                      destination_stride);
    opengpu_reg_write(gpu, GPU_REG_UCMD_SUBMIT, 1);
    mod_delayed_work(system_dfl_wq, &gpu->hw.timeout_work,
                     msecs_to_jiffies(OPENGPU_DRAW_WAIT_MS));
    mod_delayed_work(system_dfl_wq, &gpu->hw.poll_work,
                     msecs_to_jiffies(OPENGPU_HW_POLL_INTERVAL_MS));
    return 0;
}

static int opengpu_hw_unified_dma_submit_locked(
    struct opengpu_device *gpu, u32 opcode, u32 source, u32 destination,
    u32 bytes, u32 pattern, u32 height, u32 source_stride,
    u32 destination_stride, u64 expected_bytes,
    const struct opengpu_command_events *events,
    struct dma_fence **out_fence)
{
    return opengpu_hw_unified_submit_locked(
        gpu, NULL, events, opcode, source, destination, bytes, pattern, height,
        source_stride, destination_stride, expected_bytes, out_fence);
}

/* Submit one DMA operation through the integrated common command payload.
 * DRM scheduler credit_limit=1 and submit_lock serialize this single MMIO
 * completion slot. Older tops retain the dedicated-engine fallback below. */
static int opengpu_hw_unified_dma_locked(struct opengpu_device *gpu,
                                         u32 opcode, u32 source,
                                         u32 destination, u32 bytes,
                                         u32 pattern, u32 height,
                                         u32 source_stride,
                                         u32 destination_stride,
                                         u64 expected_bytes)
{
    struct dma_fence *fence;
    long timeout;
    int ret;

    ret = opengpu_hw_unified_dma_submit_locked(
        gpu, opcode, source, destination, bytes, pattern, height,
        source_stride, destination_stride, expected_bytes, NULL, &fence);
    if (ret)
        return ret;
    timeout = dma_fence_wait_timeout(
        fence, false, msecs_to_jiffies(OPENGPU_DRAW_WAIT_MS + 100));
    if (timeout <= 0) {
        opengpu_hw_abort(gpu, timeout < 0 ? (int)timeout : -ETIMEDOUT);
        ret = timeout < 0 ? (int)timeout : -ETIMEDOUT;
    } else {
        ret = dma_fence_get_status(fence);
        if (ret > 0)
            ret = 0;
    }
    dma_fence_put(fence);
    return ret;
}

int opengpu_hw_submit_async(struct opengpu_device *gpu,
                            const struct opengpu_job *job,
                            struct dma_fence **out_fence)
{
    int ret;

    ret = opengpu_hw_validate_job(job, out_fence);
    if (ret)
        return ret;
    mutex_lock(&gpu->hw.submit_lock);
    ret = opengpu_hw_submit_locked(gpu, job, out_fence);
    mutex_unlock(&gpu->hw.submit_lock);
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

/** Hardware clear through the FillEngine: program base/count/pattern, kick
 * CLEAR_START and poll STATUS.CLEAR_BUSY.  The destination must be 64-byte
 * aligned with a byte count that is a multiple of 64 (the engine rejects
 * anything else; this helper returns -ERANGE for a misprogrammed range).
 * A clear is rejected while a draw is in flight. */
static int opengpu_hw_clear_locked(struct opengpu_device *gpu, u32 base,
                                   u32 bytes, u32 pattern)
{
    u64 end = (u64)base + bytes;
    unsigned long flags;
    unsigned long timeout;
    bool engine_busy;
    u32 status;

    if (!(gpu->hw.capabilities & GPU_CAP_CLEAR_ENGINE))
        return -EOPNOTSUPP;
    if ((base & 63u) || !bytes || (bytes & 63u))
        return -ERANGE;
    if (end > (1ull << 32))
        return -ERANGE;

    spin_lock_irqsave(&gpu->hw.fence_lock, flags);
    engine_busy = gpu->hw.queue_ready ?
        gpu->hw.job_wptr != gpu->hw.job_done :
        gpu->hw.active_fence != NULL;
    spin_unlock_irqrestore(&gpu->hw.fence_lock, flags);
    if (engine_busy)
        return -EBUSY;

    if (gpu->hw.capabilities & GPU_CAP_UNIFIED_COMMANDS)
        return opengpu_hw_unified_dma_locked(
            gpu, GPU_UCMD_OP_FILL, 0, base, bytes, pattern, 0, 0, 0,
            bytes);

    opengpu_reg_write(gpu, GPU_REG_STATUS, GPU_STATUS_ERROR);
    opengpu_reg_write(gpu, GPU_REG_CLEAR_BASE, base);
    opengpu_reg_write(gpu, GPU_REG_CLEAR_BYTES, bytes);
    opengpu_reg_write(gpu, GPU_REG_CLEAR_PATTERN, pattern);
    opengpu_reg_write(gpu, GPU_REG_CLEAR_START, 1u);

    /* The emulated model only advances while the host touches device
     * registers, so poll through opengpu_hw_progress_tick() like the fence
     * waiters do. */
    timeout = jiffies + msecs_to_jiffies(OPENGPU_DRAW_WAIT_MS);
    do {
        status = opengpu_reg_read(gpu, GPU_REG_STATUS);
        if (!(status & GPU_STATUS_CLEAR_BUSY))
            break;
        if (time_after(jiffies, timeout))
            return -ETIMEDOUT;
        opengpu_hw_progress_tick(gpu);
        cond_resched();
    } while (true);
    return status & GPU_STATUS_ERROR ? -EIO : 0;
}

static int opengpu_hw_blit_locked(struct opengpu_device *gpu, u32 source,
                                  u32 destination, u32 bytes)
{
    u64 source_end = (u64)source + bytes;
    u64 destination_end = (u64)destination + bytes;
    unsigned long flags;
    unsigned long timeout;
    bool engine_busy;
    u32 status;

    if (!(gpu->hw.capabilities & GPU_CAP_BLIT_ENGINE))
        return -EOPNOTSUPP;
    if ((source & 63u) || (destination & 63u) || !bytes || (bytes & 63u))
        return -ERANGE;
    if (source_end > (1ull << 32) || destination_end > (1ull << 32))
        return -ERANGE;
    if ((u64)source < destination_end &&
        (u64)destination < source_end)
        return -EINVAL;

    spin_lock_irqsave(&gpu->hw.fence_lock, flags);
    engine_busy = gpu->hw.queue_ready ?
        gpu->hw.job_wptr != gpu->hw.job_done :
        gpu->hw.active_fence != NULL;
    spin_unlock_irqrestore(&gpu->hw.fence_lock, flags);
    if (engine_busy)
        return -EBUSY;

    if (gpu->hw.capabilities & GPU_CAP_UNIFIED_COMMANDS)
        return opengpu_hw_unified_dma_locked(
            gpu, GPU_UCMD_OP_COPY, source, destination, bytes, 0, 0, 0, 0,
            bytes);

    opengpu_reg_write(gpu, GPU_REG_STATUS, GPU_STATUS_ERROR);
    opengpu_reg_write(gpu, GPU_REG_BLIT_SRC_BASE, source);
    opengpu_reg_write(gpu, GPU_REG_BLIT_DST_BASE, destination);
    opengpu_reg_write(gpu, GPU_REG_BLIT_BYTES, bytes);
    opengpu_reg_write(gpu, GPU_REG_BLIT_START, 1u);

    timeout = jiffies + msecs_to_jiffies(OPENGPU_DRAW_WAIT_MS);
    do {
        status = opengpu_reg_read(gpu, GPU_REG_STATUS);
        if (!(status & GPU_STATUS_BLIT_BUSY))
            break;
        if (time_after(jiffies, timeout))
            return -ETIMEDOUT;
        opengpu_hw_progress_tick(gpu);
        cond_resched();
    } while (true);
    return status & GPU_STATUS_ERROR ? -EIO : 0;
}

static int opengpu_hw_strided_blit_locked(struct opengpu_device *gpu,
                                          u32 source, u32 destination,
                                          u32 width, u32 height,
                                          u32 source_stride,
                                          u32 destination_stride)
{
    u64 source_span, destination_span, source_end, destination_end;
    unsigned long flags;
    unsigned long timeout;
    bool engine_busy;
    u32 status;

    if (!(gpu->hw.capabilities & GPU_CAP_STRIDED_ENGINE))
        return -EOPNOTSUPP;
    if ((source & 63u) || (destination & 63u) || !width || !height ||
        (width & 63u) || (source_stride & 63u) ||
        (destination_stride & 63u) || source_stride < width ||
        destination_stride < width)
        return -ERANGE;
    if (check_mul_overflow((u64)(height - 1), (u64)source_stride,
                           &source_span) ||
        check_mul_overflow((u64)(height - 1), (u64)destination_stride,
                           &destination_span) ||
        check_add_overflow((u64)source, source_span, &source_end) ||
        check_add_overflow(source_end, (u64)width, &source_end) ||
        check_add_overflow((u64)destination, destination_span,
                           &destination_end) ||
        check_add_overflow(destination_end, (u64)width, &destination_end) ||
        source_end > (1ull << 32) || destination_end > (1ull << 32))
        return -ERANGE;
    if ((u64)source < destination_end &&
        (u64)destination < source_end)
        return -EINVAL;

    spin_lock_irqsave(&gpu->hw.fence_lock, flags);
    engine_busy = gpu->hw.queue_ready ?
        gpu->hw.job_wptr != gpu->hw.job_done :
        gpu->hw.active_fence != NULL;
    spin_unlock_irqrestore(&gpu->hw.fence_lock, flags);
    if (engine_busy)
        return -EBUSY;

    if (gpu->hw.capabilities & GPU_CAP_UNIFIED_COMMANDS)
        return opengpu_hw_unified_dma_locked(
            gpu, GPU_UCMD_OP_STRIDED_COPY, source, destination, width, 0,
            height, source_stride, destination_stride,
            (u64)width * height);

    opengpu_reg_write(gpu, GPU_REG_STATUS, GPU_STATUS_ERROR);
    opengpu_reg_write(gpu, GPU_REG_STRIDED_SRC_BASE, source);
    opengpu_reg_write(gpu, GPU_REG_STRIDED_DST_BASE, destination);
    opengpu_reg_write(gpu, GPU_REG_STRIDED_WIDTH, width);
    opengpu_reg_write(gpu, GPU_REG_STRIDED_HEIGHT, height);
    opengpu_reg_write(gpu, GPU_REG_STRIDED_SRC_STRIDE, source_stride);
    opengpu_reg_write(gpu, GPU_REG_STRIDED_DST_STRIDE,
                      destination_stride);
    opengpu_reg_write(gpu, GPU_REG_STRIDED_START, 1u);

    timeout = jiffies + msecs_to_jiffies(OPENGPU_DRAW_WAIT_MS);
    do {
        status = opengpu_reg_read(gpu, GPU_REG_STATUS);
        if (!(status & GPU_STATUS_STRIDED_BUSY))
            break;
        if (time_after(jiffies, timeout))
            return -ETIMEDOUT;
        opengpu_hw_progress_tick(gpu);
        cond_resched();
    } while (true);
    return status & GPU_STATUS_ERROR ? -EIO : 0;
}

int opengpu_hw_clear(struct opengpu_device *gpu, u32 base, u32 bytes,
                     u32 pattern)
{
    int ret;

    mutex_lock(&gpu->hw.submit_lock);
    ret = opengpu_hw_clear_locked(gpu, base, bytes, pattern);
    mutex_unlock(&gpu->hw.submit_lock);
    return ret;
}

int opengpu_hw_blit(struct opengpu_device *gpu, u32 source, u32 destination,
                    u32 bytes)
{
    int ret;

    mutex_lock(&gpu->hw.submit_lock);
    ret = opengpu_hw_blit_locked(gpu, source, destination, bytes);
    mutex_unlock(&gpu->hw.submit_lock);
    return ret;
}

int opengpu_hw_strided_blit(struct opengpu_device *gpu, u32 source,
                            u32 destination, u32 width, u32 height,
                            u32 source_stride, u32 destination_stride)
{
    int ret;

    mutex_lock(&gpu->hw.submit_lock);
    ret = opengpu_hw_strided_blit_locked(gpu, source, destination, width,
                                         height, source_stride,
                                         destination_stride);
    mutex_unlock(&gpu->hw.submit_lock);
    return ret;
}

static bool opengpu_hw_execution_busy(struct opengpu_device *gpu)
{
    unsigned long flags;
    bool busy;

    spin_lock_irqsave(&gpu->hw.fence_lock, flags);
    busy = gpu->hw.unified_fence || (gpu->hw.queue_ready ?
        gpu->hw.job_wptr != gpu->hw.job_done :
        gpu->hw.active_fence != NULL);
    spin_unlock_irqrestore(&gpu->hw.fence_lock, flags);
    return busy;
}

int opengpu_hw_clear_async(struct opengpu_device *gpu, u32 base, u32 bytes,
                           u32 pattern,
                           const struct opengpu_command_events *events,
                           struct dma_fence **out_fence)
{
    u64 end = (u64)base + bytes;
    int ret;

    if (!out_fence)
        return -EINVAL;
    *out_fence = NULL;
    if (!(gpu->hw.capabilities & GPU_CAP_UNIFIED_COMMANDS)) {
        if (events && events->flags)
            return -EOPNOTSUPP;
        return opengpu_hw_clear(gpu, base, bytes, pattern);
    }
    if (!(gpu->hw.capabilities & GPU_CAP_CLEAR_ENGINE))
        return -EOPNOTSUPP;
    if ((base & 63u) || !bytes || (bytes & 63u) || end > (1ull << 32))
        return -ERANGE;

    mutex_lock(&gpu->hw.submit_lock);
    ret = opengpu_hw_execution_busy(gpu) ? -EBUSY :
        opengpu_hw_unified_dma_submit_locked(
            gpu, GPU_UCMD_OP_FILL, 0, base, bytes, pattern, 0, 0, 0,
            bytes, events, out_fence);
    mutex_unlock(&gpu->hw.submit_lock);
    return ret;
}

int opengpu_hw_blit_async(struct opengpu_device *gpu, u32 source,
                          u32 destination, u32 bytes,
                          const struct opengpu_command_events *events,
                          struct dma_fence **out_fence)
{
    u64 source_end = (u64)source + bytes;
    u64 destination_end = (u64)destination + bytes;
    int ret;

    if (!out_fence)
        return -EINVAL;
    *out_fence = NULL;
    if (!(gpu->hw.capabilities & GPU_CAP_UNIFIED_COMMANDS)) {
        if (events && events->flags)
            return -EOPNOTSUPP;
        return opengpu_hw_blit(gpu, source, destination, bytes);
    }
    if (!(gpu->hw.capabilities & GPU_CAP_BLIT_ENGINE))
        return -EOPNOTSUPP;
    if ((source & 63u) || (destination & 63u) || !bytes ||
        (bytes & 63u) || source_end > (1ull << 32) ||
        destination_end > (1ull << 32))
        return -ERANGE;
    if ((u64)source < destination_end &&
        (u64)destination < source_end)
        return -EINVAL;

    mutex_lock(&gpu->hw.submit_lock);
    ret = opengpu_hw_execution_busy(gpu) ? -EBUSY :
        opengpu_hw_unified_dma_submit_locked(
            gpu, GPU_UCMD_OP_COPY, source, destination, bytes, 0, 0, 0, 0,
            bytes, events, out_fence);
    mutex_unlock(&gpu->hw.submit_lock);
    return ret;
}

int opengpu_hw_strided_blit_async(struct opengpu_device *gpu, u32 source,
                                  u32 destination, u32 width, u32 height,
                                  u32 source_stride,
                                  u32 destination_stride,
                                  const struct opengpu_command_events *events,
                                  struct dma_fence **out_fence)
{
    u64 source_span, destination_span, source_end, destination_end;
    int ret;

    if (!out_fence)
        return -EINVAL;
    *out_fence = NULL;
    if (!(gpu->hw.capabilities & GPU_CAP_UNIFIED_COMMANDS)) {
        if (events && events->flags)
            return -EOPNOTSUPP;
        return opengpu_hw_strided_blit(gpu, source, destination, width,
                                       height, source_stride,
                                       destination_stride);
    }
    if (!(gpu->hw.capabilities & GPU_CAP_STRIDED_ENGINE))
        return -EOPNOTSUPP;
    if ((source & 63u) || (destination & 63u) || !width || !height ||
        (width & 63u) || (source_stride & 63u) ||
        (destination_stride & 63u) || source_stride < width ||
        destination_stride < width)
        return -ERANGE;
    if (check_mul_overflow((u64)(height - 1), (u64)source_stride,
                           &source_span) ||
        check_mul_overflow((u64)(height - 1), (u64)destination_stride,
                           &destination_span) ||
        check_add_overflow((u64)source, source_span, &source_end) ||
        check_add_overflow(source_end, (u64)width, &source_end) ||
        check_add_overflow((u64)destination, destination_span,
                           &destination_end) ||
        check_add_overflow(destination_end, (u64)width,
                           &destination_end) ||
        source_end > (1ull << 32) || destination_end > (1ull << 32))
        return -ERANGE;
    if ((u64)source < destination_end &&
        (u64)destination < source_end)
        return -EINVAL;

    mutex_lock(&gpu->hw.submit_lock);
    ret = opengpu_hw_execution_busy(gpu) ? -EBUSY :
        opengpu_hw_unified_dma_submit_locked(
            gpu, GPU_UCMD_OP_STRIDED_COPY, source, destination, width, 0,
            height, source_stride, destination_stride,
            (u64)width * height, events, out_fence);
    mutex_unlock(&gpu->hw.submit_lock);
    return ret;
}

int opengpu_hw_compute_async(struct opengpu_device *gpu,
                             const struct opengpu_kernel_launch *launch,
                             const struct opengpu_command_events *events,
                             struct dma_fence **out_fence)
{
    u64 local_xy, local_items;
    int ret;

    if (!launch || !out_fence)
        return -EINVAL;
    *out_fence = NULL;
    if (!(gpu->hw.capabilities & GPU_CAP_UNIFIED_COMMANDS))
        return -EOPNOTSUPP;
    if (!launch->kernel_pc || upper_32_bits(launch->kernel_pc) ||
        (launch->kernel_pc & 3) || !launch->kernarg ||
        upper_32_bits(launch->kernarg) || (launch->kernarg & 3) ||
        !launch->grid[0] || !launch->grid[1] || !launch->grid[2] ||
        !launch->local[0] || !launch->local[1] || !launch->local[2] ||
        check_mul_overflow((u64)launch->local[0],
                           (u64)launch->local[1], &local_xy) ||
        check_mul_overflow(local_xy, (u64)launch->local[2], &local_items) ||
        local_items > 32)
        return -EINVAL;

    mutex_lock(&gpu->hw.submit_lock);
    ret = opengpu_hw_execution_busy(gpu) ? -EBUSY :
        opengpu_hw_unified_submit_locked(
            gpu, launch, events, GPU_UCMD_OP_KERNEL, 0, 0, 0, 0, 0, 0, 0, 0,
            out_fence);
    mutex_unlock(&gpu->hw.submit_lock);
    return ret;
}

/** Clear private job storage and publish the corresponding draw without an
 * intervening submission.  Holding submit_lock across both operations keeps
 * the legacy misc ABI and DRM contexts from inserting a draw between the
 * clear and its owner.  The DRM scheduler's one-credit policy guarantees that
 * the previous scheduled draw has completed before this entry point runs. */
int opengpu_hw_clear_and_submit_async(struct opengpu_device *gpu,
                                      const struct opengpu_job *job,
                                      u32 clear_base, u32 clear_bytes,
                                      u32 clear_pattern,
                                      struct dma_fence **out_fence)
{
    int ret;

    ret = opengpu_hw_validate_job(job, out_fence);
    if (ret)
        return ret;
    mutex_lock(&gpu->hw.submit_lock);
    ret = opengpu_hw_clear_locked(gpu, clear_base, clear_bytes,
                                  clear_pattern);
    if (!ret)
        ret = opengpu_hw_submit_locked(gpu, job, out_fence);
    mutex_unlock(&gpu->hw.submit_lock);
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
