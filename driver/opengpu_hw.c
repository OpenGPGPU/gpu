// SPDX-License-Identifier: GPL-2.0
/* Low-level MMIO, interrupt and execution control. */
#include <linux/interrupt.h>
#include <linux/io.h>
#include <linux/platform_device.h>
#include <linux/sched.h>

#include "opengpu_device.h"

static u32 opengpu_reg_read(struct opengpu_device *gpu, u32 offset)
{
    return ioread32(gpu->hw.regs + offset);
}

static void opengpu_reg_write(struct opengpu_device *gpu, u32 offset, u32 value)
{
    iowrite32(value, gpu->hw.regs + offset);
}

static irqreturn_t opengpu_irq_handler(int irq, void *data)
{
    struct opengpu_device *gpu = data;

    atomic_inc(&gpu->hw.irq_count);
    opengpu_reg_write(gpu, GPU_REG_IRQ, GPU_IRQ_PENDING);
    wake_up(&gpu->hw.irq_wait);
    return IRQ_HANDLED;
}

int opengpu_hw_init(struct opengpu_device *gpu, struct platform_device *pdev)
{
    struct resource *res;
    u32 id;
    int ret;

    init_waitqueue_head(&gpu->hw.irq_wait);
    atomic_set(&gpu->hw.irq_count, 0);

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

static int opengpu_hw_wait(struct opengpu_device *gpu)
{
    unsigned long timeout = msecs_to_jiffies(OPENGPU_DRAW_WAIT_MS);
    u32 status;

    if (gpu->hw.irq > 0)
        wait_event_timeout(gpu->hw.irq_wait,
                           atomic_read(&gpu->hw.irq_count) != 0, timeout);

    if (atomic_read(&gpu->hw.irq_count) == 0) {
        unsigned long poll_end = jiffies + msecs_to_jiffies(100);

        do {
            status = opengpu_reg_read(gpu, GPU_REG_STATUS);
            if (status & (GPU_STATUS_DONE | GPU_STATUS_ERROR))
                break;
            cond_resched();
        } while (time_before(jiffies, poll_end));
    }

    opengpu_reg_write(gpu, GPU_REG_IRQ, 0);
    status = opengpu_reg_read(gpu, GPU_REG_STATUS);
    if (status & GPU_STATUS_ERROR)
        return -EIO;
    return status & GPU_STATUS_DONE ? 0 : -ETIMEDOUT;
}

int opengpu_hw_submit(struct opengpu_device *gpu,
                      const struct opengpu_job *job)
{
    if (upper_32_bits(job->cmd) || upper_32_bits(job->color) ||
        upper_32_bits(job->depth))
        return -ERANGE;

    opengpu_reg_write(gpu, GPU_REG_CMD_BASE, lower_32_bits(job->cmd));
    opengpu_reg_write(gpu, GPU_REG_CMD_COUNT, job->cmd_count);
    opengpu_reg_write(gpu, GPU_REG_COLOR_BASE, lower_32_bits(job->color));
    opengpu_reg_write(gpu, GPU_REG_DEPTH_BASE, lower_32_bits(job->depth));
    opengpu_reg_write(gpu, GPU_REG_STRIDE, job->stride);
    opengpu_reg_write(gpu, GPU_REG_DEPTH_TEST, job->depth_test);
    opengpu_reg_write(gpu, GPU_REG_DEPTH_FUNC, job->depth_func);
    opengpu_reg_write(gpu, GPU_REG_DEPTH_WRITE, job->depth_write);
    opengpu_reg_write(gpu, GPU_REG_CULL_MODE, job->cull_mode);

    atomic_set(&gpu->hw.irq_count, 0);
    opengpu_reg_write(gpu, GPU_REG_IRQ, GPU_IRQ_ENABLE);
    opengpu_reg_write(gpu, GPU_REG_CONTROL, GPU_CTRL_START);
    return opengpu_hw_wait(gpu);
}
