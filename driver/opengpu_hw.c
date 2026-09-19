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
#include "opengpu_resolve_validator.h"
#include "opengpu_tlb_flush.h"

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

/* Completion poll interval. The register read doubles as a watchdog: it
 * drains IH records that raced the interrupt, and emulated hardware models
 * only advance while the host touches device registers, so a polling read
 * keeps slow-clocked models moving toward completion. */
#define OPENGPU_HW_POLL_INTERVAL_MS 5

/* Recovery from a timeout or abort on the unified-command path.
 *
 * The abort paths only clean up driver-side fences; a command that timed out
 * may still be executing and may still own in-flight memory transactions.
 * When the device advertises GPU_CAP_UNIFIED_RESET the driver asks the
 * hardware for a safe reset: it stops dispatch, drains every in-flight
 * command and memory transaction, resets the command-path state, and then
 * clears STATUS.RESET_BUSY and raises the completion IRQ.  Submissions are
 * refused with -EBUSY until that acknowledgement arrives; if it never does
 * (a hung engine or lower memory), the drain is abandoned and the device is
 * marked wedged so unified submissions fail with -EIO until it is reloaded.
 */
static void opengpu_hw_unified_reset_request(struct opengpu_device *gpu)
{
    if (!(gpu->hw.capabilities & GPU_CAP_UNIFIED_RESET))
        return;

    WRITE_ONCE(gpu->hw.reset_pending, true);
    opengpu_reg_write(gpu, GPU_REG_UCMD_RESET, GPU_UCMD_RESET_REQUEST);
    /* Abort disables the IRQ; re-enable it so the drain acknowledgement is
     * delivered, and keep the progress poll running because emulated models
     * only advance while the host touches device registers. */
    opengpu_reg_write(gpu, GPU_REG_IRQ, GPU_IRQ_ENABLE);
    mod_delayed_work(system_dfl_wq, &gpu->hw.reset_work,
                     msecs_to_jiffies(OPENGPU_RESET_WAIT_MS));
    if (gpu->hw.queue_ready)
        mod_delayed_work(system_dfl_wq, &gpu->hw.poll_work,
                         msecs_to_jiffies(OPENGPU_HW_POLL_INTERVAL_MS));
}

/* True while a requested reset is still draining; clears the request once the
 * hardware drops RESET_BUSY.  Safe from IRQ context. */
static bool opengpu_hw_unified_reset_pending(struct opengpu_device *gpu)
{
    if (!READ_ONCE(gpu->hw.reset_pending))
        return false;
    if (opengpu_reg_read(gpu, GPU_REG_UCMD_STATUS) &
        GPU_UCMD_STATUS_RESET_BUSY)
        return true;
    WRITE_ONCE(gpu->hw.reset_pending, false);
    cancel_delayed_work(&gpu->hw.reset_work);
    dev_info(gpu->dev, "unified command reset drained\n");
    return false;
}

static void opengpu_reset_work(struct work_struct *work)
{
    struct opengpu_hw *hw;
    struct opengpu_device *gpu;
    unsigned long flags;
    u32 status;

    hw = container_of(to_delayed_work(work), struct opengpu_hw, reset_work);
    gpu = container_of(hw, struct opengpu_device, hw);
    if (!READ_ONCE(gpu->hw.reset_pending))
        return;

    status = opengpu_reg_read(gpu, GPU_REG_UCMD_STATUS);
    if (status & GPU_UCMD_STATUS_RESET_BUSY) {
        spin_lock_irqsave(&gpu->hw.fence_lock, flags);
        opengpu_hw_record_fault_locked(
            gpu, -EIO,
            OPENGPU_FAULT_RESET_ISSUED | OPENGPU_FAULT_RESET_TIMEOUT,
            gpu->hw.unified_command_id, gpu->hw.unified_opcode,
            gpu->hw.unified_command_id, gpu->hw.unified_opcode,
            status, 0, 0);
        spin_unlock_irqrestore(&gpu->hw.fence_lock, flags);
        WRITE_ONCE(gpu->hw.wedged, true);
        dev_err(gpu->dev,
                "unified command reset did not drain; device wedged\n");
    }
    WRITE_ONCE(gpu->hw.reset_pending, false);
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
    rec->stencil_config = job->stencil_config;
    rec->stencil_ref_masks = job->stencil_ref_masks;
    rec->blend_config = job->blend_config;
    if (job->stencil_test)
        rec->state |= GPU_JOB_STATE_STENCIL_TEST;
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
        if (gpu->hw.capabilities & GPU_CAP_UNIFIED_RESET)
            fault_flags |= OPENGPU_FAULT_RESET_ISSUED;
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

    opengpu_hw_unified_reset_request(gpu);

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
        u32 fault_flags = OPENGPU_FAULT_TIMEOUT | OPENGPU_FAULT_ABORTED;

        gpu->hw.unified_fence = NULL;
        if (gpu->hw.capabilities & GPU_CAP_UNIFIED_RESET)
            fault_flags |= OPENGPU_FAULT_RESET_ISSUED;
        opengpu_hw_record_fault_locked(
            gpu, -ETIMEDOUT, fault_flags,
            gpu->hw.unified_command_id, gpu->hw.unified_opcode,
            gpu->hw.unified_command_id, gpu->hw.unified_opcode, 0, 0,
            gpu->hw.unified_expected_bytes);
        dma_fence_set_error(fence, -ETIMEDOUT);
        dma_fence_signal_locked(fence);
    }
    spin_unlock_irqrestore(&gpu->hw.fence_lock, flags);
    if (fence) {
        /* The timed-out command may still be executing, so recover the
         * command path before the next submission. */
        opengpu_hw_unified_reset_request(gpu);
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

    /* A requested safe reset is observed here and in the IRQ handler; keep
     * the poll ticking so the drain makes progress on emulated hardware. */
    if (opengpu_hw_unified_reset_pending(gpu)) {
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
    opengpu_hw_unified_reset_pending(gpu);
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
    INIT_DELAYED_WORK(&gpu->hw.reset_work, opengpu_reset_work);

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

    /* The host-memory job ring is retired: user draws are submitted as unified
     * render commands and the self-test/fallback uses the legacy START
     * snapshot, so the driver no longer programs the admin ring.  The RTL
     * module is left idle until it is removed. */
    return 0;
}

void opengpu_hw_fini(struct opengpu_device *gpu)
{
    opengpu_hw_abort(gpu, -ECANCELED);
    /* abort may have requested a safe reset and re-armed this work; it must
     * not outlive the register aperture. */
    cancel_delayed_work_sync(&gpu->hw.reset_work);
    cancel_delayed_work_sync(&gpu->hw.poll_work);
    if (gpu->hw.queue_ready) {
        opengpu_reg_write(gpu, GPU_REG_JOB_CONTROL, 0);
        opengpu_buffer_free(gpu, &gpu->hw.ih_ring);
        opengpu_buffer_free(gpu, &gpu->hw.job_ring);
        gpu->hw.queue_ready = false;
    }
}

/* Program the address space a submission will run in.  Called with submit_lock
 * held, immediately before the doorbell, so the switch cannot race another
 * submission.  A NULL or disabled VM selects the global ASID-0 identity map.
 * The CU TLBs and the graphics address translator are ASID-tagged, so a switch
 * needs no flush; global mappings stay resident and private entries only
 * resolve under their own ASID.  Bare builds (MMU never enabled) skip satp. */
static int opengpu_hw_activate_vm_locked(struct opengpu_device *gpu,
                                         const struct opengpu_vm *vm)
{
    if (!gpu->mmu.enabled)
        return 0;
    if (!vm || !vm->enabled)
        return opengpu_hw_set_satp(gpu, gpu->mmu.root.dma, 0);
    return opengpu_hw_set_satp(gpu, vm->root.dma, vm->asid);
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
    /* The register file packs the stencil enable at bit0 with func/ops one
     * nibble above the draw-record word-36 layout; the ref/masks and blend
     * words are register-compatible with job words 11/12. */
    opengpu_reg_write(gpu, GPU_REG_STENCIL_CONFIG,
                      (job->stencil_test ? 1u : 0u) |
                      ((job->stencil_config & 0xfffu) << 4));
    opengpu_reg_write(gpu, GPU_REG_STENCIL_REF_MASKS, job->stencil_ref_masks);
    opengpu_reg_write(gpu, GPU_REG_BLEND_CONFIG, job->blend_config);

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
                                    const struct opengpu_vm *vm,
                                    struct dma_fence **out_fence)
{
    int ret;

    ret = opengpu_hw_activate_vm_locked(gpu, vm);
    if (ret)
        return ret;
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
    if (opcode == GPU_UCMD_OP_RENDER)
        return status == GPU_UCMD_RESULT_SUCCESS ? 0 : -EIO;
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
    u32 bytes, u32 pattern, u32 sample_mode, u32 height,
    u32 source_stride, u32 destination_stride, u64 expected_bytes,
    const struct opengpu_vm *vm,
    struct dma_fence **out_fence)
{
    struct opengpu_fence *fence;
    unsigned long flags;
    u32 command_id;
    u32 status;
    int ret = 0;

    if (!out_fence)
        return -EINVAL;
    *out_fence = NULL;
    if (READ_ONCE(gpu->hw.wedged))
        return -EIO;
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
    /* A safe reset owns the command path until it drains: report busy rather
     * than letting a submission race the state reset. */
    if (READ_ONCE(gpu->hw.reset_pending) ||
        (status & GPU_UCMD_STATUS_RESET_BUSY))
        return -EBUSY;
    if (!(status & GPU_UCMD_STATUS_READY) ||
        (status & GPU_UCMD_STATUS_COMPLETION))
        return -EBUSY;
    if (status & GPU_UCMD_STATUS_OVERFLOW)
        opengpu_reg_write(gpu, GPU_REG_UCMD_STATUS,
                          GPU_UCMD_STATUS_OVERFLOW);
    if (status & GPU_UCMD_STATUS_RESET_REJECTED)
        opengpu_reg_write(gpu, GPU_REG_UCMD_STATUS,
                          GPU_UCMD_STATUS_RESET_REJECTED);

    /* Select the submitting address space before any command register is
     * staged, still under submit_lock. */
    ret = opengpu_hw_activate_vm_locked(gpu, vm);
    if (ret)
        return ret;

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
    opengpu_reg_write(gpu, GPU_REG_UCMD_SAMPLE_MODE, sample_mode & 0x3u);
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
    const struct opengpu_vm *vm,
    struct dma_fence **out_fence)
{
    return opengpu_hw_unified_submit_locked(
        gpu, NULL, events, opcode, source, destination, bytes, pattern, 0,
        height, source_stride, destination_stride, expected_bytes, vm,
        out_fence);
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
        source_stride, destination_stride, expected_bytes, NULL, NULL,
        &fence);
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
                            const struct opengpu_vm *vm,
                            struct dma_fence **out_fence)
{
    return opengpu_hw_draw_submit_async(gpu, job, NULL, 0, false, 0, 0, 0, vm,
                                        out_fence);
}

int opengpu_hw_submit(struct opengpu_device *gpu,
                      const struct opengpu_job *job)
{
    struct dma_fence *fence;
    long timeout;
    int ret;

    ret = opengpu_hw_submit_async(gpu, job, NULL, &fence);
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
    /* A pending safe reset owns the command path, so it reports busy just
     * like an in-flight command. */
    busy = READ_ONCE(gpu->hw.reset_pending) || gpu->hw.unified_fence ||
        (gpu->hw.queue_ready ? gpu->hw.job_wptr != gpu->hw.job_done :
         gpu->hw.active_fence != NULL);
    spin_unlock_irqrestore(&gpu->hw.fence_lock, flags);
    return busy;
}

/* Keep submissions excluded until the caller has published its page tables
 * and flushed translations. Progress polling also services emulator fences. */
int opengpu_hw_wait_idle_locked(struct opengpu_device *gpu)
{
    unsigned long timeout = jiffies + msecs_to_jiffies(OPENGPU_DRAW_WAIT_MS);

    lockdep_assert_held(&gpu->hw.submit_lock);
    while (opengpu_hw_execution_busy(gpu)) {
        if (READ_ONCE(gpu->hw.wedged))
            return -EIO;
        if (time_after(jiffies, timeout))
            return -ETIMEDOUT;
        opengpu_hw_progress_tick(gpu);
        cond_resched();
    }
    return READ_ONCE(gpu->hw.wedged) ? -EIO : 0;
}

int opengpu_hw_clear_async(struct opengpu_device *gpu, u32 base, u32 bytes,
                           u32 pattern,
                           const struct opengpu_command_events *events,
                           const struct opengpu_vm *vm,
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
            bytes, events, vm, out_fence);
    mutex_unlock(&gpu->hw.submit_lock);
    return ret;
}

int opengpu_hw_blit_async(struct opengpu_device *gpu, u32 source,
                          u32 destination, u32 bytes,
                          const struct opengpu_command_events *events,
                          const struct opengpu_vm *vm,
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
            bytes, events, vm, out_fence);
    mutex_unlock(&gpu->hw.submit_lock);
    return ret;
}

int opengpu_hw_strided_blit_async(struct opengpu_device *gpu, u32 source,
                                  u32 destination, u32 width, u32 height,
                                  u32 source_stride,
                                  u32 destination_stride,
                                  const struct opengpu_command_events *events,
                                  const struct opengpu_vm *vm,
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
            (u64)width * height, events, vm, out_fence);
    mutex_unlock(&gpu->hw.submit_lock);
    return ret;
}

int opengpu_hw_invalidate_async(struct opengpu_device *gpu, u32 address,
                                u32 bytes,
                                const struct opengpu_command_events *events,
                                const struct opengpu_vm *vm,
                                struct dma_fence **out_fence)
{
    int ret;

    if (!out_fence)
        return -EINVAL;
    *out_fence = NULL;
    if (!(gpu->hw.capabilities & GPU_CAP_UNIFIED_COMMANDS))
        return -EOPNOTSUPP;
    /* The L2 line is 64 bytes; the walker rounds the extent up. */
    if ((address & 63u) || !bytes || (bytes & 63u) ||
        (u64)address + bytes > (1ull << 32))
        return -ERANGE;

    mutex_lock(&gpu->hw.submit_lock);
    ret = opengpu_hw_execution_busy(gpu) ? -EBUSY :
        opengpu_hw_unified_submit_locked(
            gpu, NULL, events, GPU_UCMD_OP_INVALIDATE, address, 0, bytes, 0,
            0, 0, 0, 0, bytes, vm, out_fence);
    mutex_unlock(&gpu->hw.submit_lock);
    return ret;
}

int opengpu_hw_resolve_async(struct opengpu_device *gpu, u32 source,
                             u32 destination, u32 width, u32 height,
                             u32 source_stride, u32 destination_stride,
                             u32 sample_mode,
                             const struct opengpu_command_events *events,
                             const struct opengpu_vm *vm,
                             struct dma_fence **out_fence)
{
    struct opengpu_resolve_desc desc;
    struct opengpu_resolve_layout layout;
    u32 max_sample_mode;
    int ret;

    if (!out_fence)
        return -EINVAL;
    *out_fence = NULL;
    /* Resolve averages the samples of an MSAA target through the unified
     * command path; both capabilities must be present.  The target may be
     * fixed-function or programmable (GPU_CAP_FRAGMENT_CORE), since the
     * unified path reads the resolved sample layout either way. */
    if (!(gpu->hw.capabilities & GPU_CAP_UNIFIED_COMMANDS) ||
        !(gpu->hw.capabilities & GPU_CAP_MSAA))
        return -EOPNOTSUPP;

    max_sample_mode = (gpu->hw.capabilities & GPU_CAP_MSAA_MAX_MODE_MASK) >>
                      GPU_CAP_MSAA_MAX_MODE_SHIFT;

    desc.source_offset = source;
    desc.destination_offset = destination;
    desc.width = width;
    desc.height = height;
    desc.source_stride = source_stride;
    desc.destination_stride = destination_stride;
    desc.sample_mode = sample_mode;

    /* The ioctl already validated the ranges against the GEM objects; here the
     * absolute DMA addresses are re-checked for arithmetic, the 32-bit address
     * window and overlap before programming the registers. */
    ret = opengpu_resolve_validate(&desc, 1ull << 32, 1ull << 32,
                                   max_sample_mode, &layout);
    if (ret)
        return ret;

    mutex_lock(&gpu->hw.submit_lock);
    ret = opengpu_hw_execution_busy(gpu) ? -EBUSY :
        opengpu_hw_unified_submit_locked(
            gpu, NULL, events, GPU_UCMD_OP_RESOLVE, source, destination,
            width, 0, sample_mode, height, source_stride,
            destination_stride, (u64)width * height * 4ull, vm, out_fence);
    mutex_unlock(&gpu->hw.submit_lock);
    return ret;
}

/** Point both CU `satp` registers at `root_table` under `asid`, without a TLB
  * flush.  Used for a coarse VM switch: the caller quiesces execution first
  * and shoots down the ASID if it was recycled; other address spaces' TLB
  * entries stay resident. */
int opengpu_hw_set_satp(struct opengpu_device *gpu, dma_addr_t root_table,
                        u32 asid)
{
    u32 satp;

    if (!(gpu->hw.capabilities & GPU_CAP_UNIFIED_COMMANDS))
        return -EOPNOTSUPP;
    if (upper_32_bits(root_table) || (root_table & 0xfff))
        return -EINVAL;
    if (asid > GPU_SATP_ASID_MASK)
        return -EINVAL;
    /* satp: bit 31 enables translation, ASID in [30:22], root PPN in [19:0]. */
    satp = GPU_SATP_ENABLE | (asid << GPU_SATP_ASID_SHIFT) |
        (lower_32_bits(root_table) >> 12);
    opengpu_reg_write(gpu, GPU_REG_UCMD_VECTOR_SATP, satp);
    opengpu_reg_write(gpu, GPU_REG_UCMD_INSTRUCTION_SATP, satp);
    return 0;
}

/** Switch the active address space outside a submission.  Quiesces execution,
 * then programs `satp`; `vm == NULL` restores the global ASID-0 identity map.
 * A per-submission switch happens inside the submit lock instead (see
 * opengpu_hw_activate_vm_locked). */
int opengpu_hw_activate_vm(struct opengpu_device *gpu,
                           const struct opengpu_vm *vm)
{
    int ret;

    mutex_lock(&gpu->hw.submit_lock);
    ret = opengpu_hw_wait_idle_locked(gpu);
    if (!ret)
        ret = opengpu_hw_activate_vm_locked(gpu, vm);
    mutex_unlock(&gpu->hw.submit_lock);
    return ret;
}

/** Enable Sv32 translation with a driver-built identity map.  `root_table` is
 * the physical address of a 4 KiB root page table whose 1024 entries are
 * 4 MiB identity superpages; a full TLB flush is issued to commit the change.
 * The map is deliberately identity so existing physical-address bindings keep
 * working; a later mapping layer can override individual pages (for example to
 * mark a CPU-written buffer uncached). */
int opengpu_hw_enable_mmu(struct opengpu_device *gpu, dma_addr_t root_table)
{
    int ret;

    ret = opengpu_hw_set_satp(gpu, root_table, 0);
    if (ret)
        return ret;
    opengpu_reg_write(gpu, GPU_REG_UCMD_TLB_FLUSH, opengpu_tlb_flush_full());
    return 0;
}

/** Invalidate every entry of both translation caches. */
int opengpu_hw_flush_tlbs(struct opengpu_device *gpu)
{
    if (!(gpu->hw.capabilities & GPU_CAP_UNIFIED_COMMANDS))
        return -EOPNOTSUPP;
    opengpu_reg_write(gpu, GPU_REG_UCMD_TLB_FLUSH, opengpu_tlb_flush_full());
    return 0;
}

/** Invalidate only the TLB entries tagged with `asid`.  Global mappings and
  * entries of other address spaces stay resident, so switching between
  * address spaces does not pay a full-flush refill.  The fixed-function
  * texture translator has no ASID and is cleared by the same pulse. */
int opengpu_hw_flush_tlb_asid(struct opengpu_device *gpu, u32 asid)
{
    opengpu_tlb_u32 word;
    int ret;

    if (!(gpu->hw.capabilities & GPU_CAP_UNIFIED_COMMANDS))
        return -EOPNOTSUPP;
    ret = opengpu_tlb_flush_asid(asid, &word);
    if (ret)
        return ret;
    opengpu_reg_write(gpu, GPU_REG_UCMD_TLB_FLUSH, word);
    return 0;
}

/** Invalidate only the TLB entries for virtual page number `vpn`.  Useful for
  * a single mapping change; other entries, including global ones, survive. */
int opengpu_hw_flush_tlb_vpn(struct opengpu_device *gpu, u32 vpn)
{
    opengpu_tlb_u32 word;
    int ret;

    if (!(gpu->hw.capabilities & GPU_CAP_UNIFIED_COMMANDS))
        return -EOPNOTSUPP;
    ret = opengpu_tlb_flush_vpn(vpn, &word);
    if (ret)
        return ret;
    opengpu_reg_write(gpu, GPU_REG_UCMD_TLB_FLUSH, word);
    return 0;
}

int opengpu_hw_compute_async(struct opengpu_device *gpu,
                             const struct opengpu_kernel_launch *launch,
                             const struct opengpu_command_events *events,
                             const struct opengpu_vm *vm,
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
            0, vm, out_fence);
    mutex_unlock(&gpu->hw.submit_lock);
    return ret;
}

/* Drop shared-L2 lines covering a CPU-written coherent DMA buffer.  dma_alloc
 * reuses physical pages whose prior GPU-L2 tags can otherwise shadow the
 * memcpy the ioctl just performed.  Called with submit_lock held; waits for
 * the invalidate to retire before the caller doorbells the draw. */
static int opengpu_hw_invalidate_buffer_locked(
    struct opengpu_device *gpu, const struct opengpu_buffer *buffer)
{
    struct dma_fence *fence;
    u64 start, end;
    u32 bytes;
    long timeout;
    int ret;

    if (!(gpu->hw.capabilities & GPU_CAP_UNIFIED_COMMANDS) || !buffer ||
        !buffer->cpu || !buffer->size)
        return 0;
    start = (u64)buffer->dma & ~(u64)63;
    if (check_add_overflow((u64)buffer->dma, (u64)buffer->size, &end))
        return -ERANGE;
    end = (end + 63ull) & ~(u64)63;
    if (start > U32_MAX || end > (1ull << 32) || end - start > U32_MAX)
        return -ERANGE;
    bytes = (u32)(end - start);
    if (!bytes)
        return 0;
    if (opengpu_hw_execution_busy(gpu))
        return -EBUSY;

    ret = opengpu_hw_unified_submit_locked(
        gpu, NULL, NULL, GPU_UCMD_OP_INVALIDATE, (u32)start, 0, bytes, 0, 0,
        0, 0, 0, bytes, NULL, &fence);
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

static int opengpu_hw_invalidate_snapshots_locked(
    struct opengpu_device *gpu,
    const struct opengpu_buffer *const *snapshots, unsigned int count)
{
    unsigned int i;
    int ret;

    for (i = 0; i < count; i++) {
        ret = opengpu_hw_invalidate_buffer_locked(gpu, snapshots[i]);
        if (ret)
            return ret;
    }
    return 0;
}

/** Invalidate CPU-written snapshots, optionally clear private depth storage,
 * and publish the draw without an intervening submission.  Holding
 * submit_lock across the sequence keeps the legacy misc ABI and DRM contexts
 * from inserting work between the precursors and the doorbell.  The DRM
 * scheduler's one-credit policy guarantees that the previous scheduled job
 * has completed before this entry point runs. */
int opengpu_hw_draw_submit_async(struct opengpu_device *gpu,
                                 const struct opengpu_job *job,
                                 const struct opengpu_buffer *const *snapshots,
                                 unsigned int snapshot_count,
                                 bool clear_depth, u32 clear_base,
                                 u32 clear_bytes, u32 clear_pattern,
                                 const struct opengpu_vm *vm,
                                 struct dma_fence **out_fence)
{
    int ret;

    ret = opengpu_hw_validate_job(job, out_fence);
    if (ret)
        return ret;
    mutex_lock(&gpu->hw.submit_lock);
    ret = opengpu_hw_invalidate_snapshots_locked(gpu, snapshots,
                                                 snapshot_count);
    if (!ret && clear_depth)
        ret = opengpu_hw_clear_locked(gpu, clear_base, clear_bytes,
                                      clear_pattern);
    if (!ret)
        ret = opengpu_hw_submit_locked(gpu, job, vm, out_fence);
    mutex_unlock(&gpu->hw.submit_lock);
    return ret;
}

/* Publish one draw as a unified render command.  The 16-word render descriptor
 * is filled into driver-owned memory and submitted by virtual address, so the
 * device fetches the descriptor and the command buffer through the context VM.
 * Snapshots are invalidated and private depth is cleared under the same
 * submit_lock hold, exactly as the legacy/job-ring draw path does. */
int opengpu_hw_render_async(struct opengpu_device *gpu,
                            const struct opengpu_job *job,
                            void *descriptor_cpu,
                            u32 descriptor_va, u32 descriptor_bytes,
                            const struct opengpu_buffer *const *snapshots,
                            unsigned int snapshot_count,
                            bool clear_depth, u32 clear_base, u32 clear_bytes,
                            u32 clear_pattern,
                            const struct opengpu_command_events *events,
                            const struct opengpu_vm *vm,
                            struct dma_fence **out_fence)
{
    int ret;

    ret = opengpu_hw_validate_job(job, out_fence);
    if (ret)
        return ret;
    if (!descriptor_cpu || descriptor_bytes < sizeof(struct gpu_job_record))
        return -EINVAL;
    memset(descriptor_cpu, 0, descriptor_bytes);
    opengpu_job_fill((struct gpu_job_record *)descriptor_cpu, 0, job);

    mutex_lock(&gpu->hw.submit_lock);
    ret = opengpu_hw_invalidate_snapshots_locked(gpu, snapshots,
                                                 snapshot_count);
    if (!ret && clear_depth)
        ret = opengpu_hw_clear_locked(gpu, clear_base, clear_bytes,
                                      clear_pattern);
    if (!ret)
        ret = opengpu_hw_unified_submit_locked(
            gpu, NULL, events, GPU_UCMD_OP_RENDER, descriptor_va, 0,
            descriptor_bytes, 0, 0, 0, 0, 0, descriptor_bytes, vm, out_fence);
    mutex_unlock(&gpu->hw.submit_lock);
    return ret;
}

/** Clear private job storage and publish the corresponding draw without an
 * intervening submission. */
int opengpu_hw_clear_and_submit_async(struct opengpu_device *gpu,
                                      const struct opengpu_job *job,
                                      u32 clear_base, u32 clear_bytes,
                                      u32 clear_pattern,
                                      const struct opengpu_vm *vm,
                                      struct dma_fence **out_fence)
{
    return opengpu_hw_draw_submit_async(gpu, job, NULL, 0, true, clear_base,
                                        clear_bytes, clear_pattern, vm,
                                        out_fence);
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
