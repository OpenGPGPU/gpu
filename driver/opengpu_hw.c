// SPDX-License-Identifier: GPL-2.0
/* Low-level MMIO, interrupt and execution control. */
#include <linux/dma-fence.h>
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

static void opengpu_timeout_work(struct work_struct *work)
{
    struct opengpu_hw *hw;
    struct opengpu_device *gpu;
    u32 status;

    hw = container_of(to_delayed_work(work), struct opengpu_hw,
                      timeout_work);
    gpu = container_of(hw, struct opengpu_device, hw);
    status = opengpu_reg_read(gpu, GPU_REG_STATUS);
    opengpu_reg_write(gpu, GPU_REG_IRQ, 0);
    if (status & GPU_STATUS_ERROR)
        opengpu_hw_complete(gpu, -EIO);
    else if (status & GPU_STATUS_DONE)
        opengpu_hw_complete(gpu, 0);
    else
        opengpu_hw_complete(gpu, -ETIMEDOUT);
}

static irqreturn_t opengpu_irq_handler(int irq, void *data)
{
    struct opengpu_device *gpu = data;
    u32 status;

    status = opengpu_reg_read(gpu, GPU_REG_STATUS);
    opengpu_reg_write(gpu, GPU_REG_IRQ, GPU_IRQ_PENDING);
    opengpu_reg_write(gpu, GPU_REG_IRQ, 0);
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
    dev_info(gpu->dev, "GPU ABI device=0x%08x version=0x%04x\n",
             id, id & 0xffff);

    gpu->hw.irq = platform_get_irq_optional(pdev, 0);
    if (gpu->hw.irq == -EPROBE_DEFER)
        return -EPROBE_DEFER;
    if (gpu->hw.irq > 0) {
        ret = devm_request_irq(gpu->dev, gpu->hw.irq,
                               opengpu_irq_handler, 0, OPENGPU_NAME, gpu);
        if (ret)
            return dev_err_probe(gpu->dev, ret, "cannot request irq\n");
    }

    return 0;
}

void opengpu_hw_fini(struct opengpu_device *gpu)
{
    cancel_delayed_work_sync(&gpu->hw.timeout_work);
    opengpu_reg_write(gpu, GPU_REG_IRQ, 0);
    opengpu_hw_complete(gpu, -ECANCELED);
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
        upper_32_bits(job->depth))
        return -ERANGE;

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
