// SPDX-License-Identifier: GPL-2.0
/*
 * RISC-V SIMT GPU host driver (M6: char-device-before-DRM).
 *
 * This is the Linux side of the graphics host interface.  It binds to the
 * `riscv-simt,opengpu` device-tree node, programmes the AXI4 control registers
 * (see gpu_abi.h), and submits command-driven draws whose command buffer,
 * kernarg, colour and depth buffers all live in software-allocated shared
 * memory.  Completion is signalled by the RTL interrupt; the driver waits on
 * it after START (with a STATUS-based fallback for environments without an
 * IRQ line), then reads the framebuffer back.
 *
 * It exposes a misc char device (/dev/gpu0) so userspace can submit a draw
 * and read the result; the probe does a self-test draw and prints
 * "OPENGPU DRIVER PASS" so the ARTI harness can detect success.
 */
#include <linux/dma-mapping.h>
#include <linux/fs.h>
#include <linux/interrupt.h>
#include <linux/io.h>
#include <linux/kernel.h>
#include <linux/miscdevice.h>
#include <linux/module.h>
#include <linux/of.h>
#include <linux/platform_device.h>
#include <linux/sched.h>
#include <linux/slab.h>
#include <linux/uaccess.h>
#include <linux/wait.h>

#include "gpu_abi.h"

#define GPU_NAME           "riscv-simt-opengpu"
#define GPU_DEV_NAME       "opengpu0"
#define GPU_DRAW_WAIT_MS   200

#define GPU_IOCTL_SUBMIT   _IO('G', 0x01)

struct gpu_device {
    struct device *dev;
    struct miscdevice misc;
    void __iomem *ctrl;
    resource_size_t ctrl_phys;
    resource_size_t ctrl_size;
    int irq;
    wait_queue_head_t irq_wait;
    atomic_t irq_count;

    /* Software-allocated shared memory. */
    void *cmd_buf;
    dma_addr_t cmd_phys;
    size_t cmd_size;
    void *color_buf;
    dma_addr_t color_phys;
    size_t color_size;
    void *depth_buf;
    dma_addr_t depth_phys;
    size_t depth_size;

    u32 width;
    u32 height;
    u32 stride;
};

static inline u32 gpu_read(struct gpu_device *g, u32 off)
{
    return ioread32(g->ctrl + off);
}

static inline void gpu_write(struct gpu_device *g, u32 off, u32 v)
{
    iowrite32(v, g->ctrl + off);
}

static irqreturn_t gpu_irq(int irq, void *data)
{
    struct gpu_device *g = data;

    atomic_inc(&g->irq_count);
    gpu_write(g, GPU_REG_IRQ, GPU_IRQ_PENDING); /* w1c */
    wake_up(&g->irq_wait);
    return IRQ_HANDLED;
}

static int gpu_start_and_wait(struct gpu_device *g)
{
    unsigned long j = msecs_to_jiffies(GPU_DRAW_WAIT_MS);

    atomic_set(&g->irq_count, 0);
    gpu_write(g, GPU_REG_IRQ, GPU_IRQ_ENABLE);
    gpu_write(g, GPU_REG_CONTROL, GPU_CTRL_START);

    if (wait_event_timeout(g->irq_wait, atomic_read(&g->irq_count) != 0, j))
        goto done;

    {
        unsigned long p = jiffies + msecs_to_jiffies(100);
        while (time_before(jiffies, p)) {
            if (gpu_read(g, GPU_REG_STATUS) & GPU_STATUS_DONE)
                break;
            cond_resched();
        }
    }
done:
    gpu_write(g, GPU_REG_IRQ, 0);
    return gpu_read(g, GPU_REG_STATUS) & GPU_STATUS_DONE ? 0 : -ETIMEDOUT;
}

static void gpu_program_config(struct gpu_device *g)
{
    gpu_write(g, GPU_REG_CMD_BASE, lower_32_bits(g->cmd_phys));
    gpu_write(g, GPU_REG_CMD_COUNT, 1);
    gpu_write(g, GPU_REG_COLOR_BASE, lower_32_bits(g->color_phys));
    gpu_write(g, GPU_REG_DEPTH_BASE, lower_32_bits(g->depth_phys));
    gpu_write(g, GPU_REG_STRIDE, g->stride);
    gpu_write(g, GPU_REG_DEPTH_TEST, 1);
    gpu_write(g, GPU_REG_DEPTH_FUNC, GPU_DEPTH_FUNC_LESS);
    gpu_write(g, GPU_REG_DEPTH_WRITE, 1);
    gpu_write(g, GPU_REG_CULL_MODE, GPU_CULL_NONE);
}

/* A CCW triangle in clip space (Q16.16) covering most of the mode; constant
 * red colour, near depth 0x10. */
static void gpu_fill_command(struct gpu_device *g)
{
    struct gpu_draw_record *r = g->cmd_buf;

    BUILD_BUG_ON(sizeof(*r) != GPU_DRAW_WORDS * sizeof(u32));

    memset(g->cmd_buf, 0, sizeof(*r));
    memset(g->depth_buf, 0xff, g->stride * g->height);

    r->v0[0] = -0x10000; r->v0[1] = -0x10000; r->v0[2] = 0; r->v0[3] = 0x10000;
    r->v1[0] =  0x10000; r->v1[1] = -0x10000; r->v1[2] = 0; r->v1[3] = 0x10000;
    r->v2[0] = -0x10000; r->v2[1] =  0x10000; r->v2[2] = 0; r->v2[3] = 0x10000;

    r->c0[0] = 255; r->c0[1] = 0; r->c0[2] = 0;
    r->c1[0] = 255; r->c1[1] = 0; r->c1[2] = 0;
    r->c2[0] = 255; r->c2[1] = 0; r->c2[2] = 0;

    r->d0 = 0x10;
    r->d1 = 0x10;
    r->d2 = 0x10;
    r->shader_pc = 0;
    r->kernarg = 0;
}

static void gpu_self_test(struct gpu_device *g)
{
    u32 pixel;
    u32 rgb;

    gpu_program_config(g);
    gpu_fill_command(g);

    if (gpu_start_and_wait(g)) {
        dev_err(g->dev, "OPENGPU DRIVER FAIL: draw did not complete\n");
        return;
    }

    pixel = ((u32 *)g->color_buf)[1 * (g->stride / 4) + 1];
    rgb = (pixel >> 8) & 0x00ffffffu;
    if (rgb == 0x00ff0000u)
        dev_info(g->dev, "OPENGPU DRIVER PASS: draw submitted and read back\n");
    else
        dev_err(g->dev, "OPENGPU DRIVER FAIL: expected red (0xff0000) got 0x%06x\n",
                rgb);
}

static int gpu_probe(struct platform_device *pdev)
{
    struct gpu_device *g;
    struct resource *res;
    u32 id;
    int ret;

    g = devm_kzalloc(&pdev->dev, sizeof(*g), GFP_KERNEL);
    if (!g)
        return -ENOMEM;
    g->dev = &pdev->dev;
    init_waitqueue_head(&g->irq_wait);
    atomic_set(&g->irq_count, 0);

    of_property_read_u32(pdev->dev.of_node, "opengpu,width", &g->width);
    of_property_read_u32(pdev->dev.of_node, "opengpu,height", &g->height);
    of_property_read_u32(pdev->dev.of_node, "opengpu,stride", &g->stride);
    if (!g->width) g->width = 16;
    if (!g->height) g->height = 16;
    if (!g->stride) g->stride = g->width * 4;

    res = platform_get_resource_byname(pdev, IORESOURCE_MEM, "ctrl");
    if (!res)
        res = platform_get_resource(pdev, IORESOURCE_MEM, 0);
    if (!res)
        return dev_err_probe(&pdev->dev, -ENODEV, "missing ctrl resource\n");
    g->ctrl_phys = res->start;
    g->ctrl_size = resource_size(res);
    g->ctrl = devm_ioremap_resource(&pdev->dev, res);
    if (IS_ERR(g->ctrl))
        return PTR_ERR(g->ctrl);

    id = gpu_read(g, GPU_REG_ID);
    if ((id >> 16) != GPU_DEVICE_ID)
        return dev_err_probe(&pdev->dev, -ENODEV, "bad device id 0x%08x\n", id);
    dev_info(&pdev->dev, "GPU ABI device=0x%08x version=0x%04x\n", id,
             id & 0xffff);

    g->cmd_size = max_t(size_t, sizeof(struct gpu_draw_record), 256);
    g->color_size = (size_t)g->stride * g->height;
    g->depth_size = (size_t)g->stride * g->height;
    g->cmd_buf = dma_alloc_coherent(g->dev, g->cmd_size, &g->cmd_phys,
                                    GFP_KERNEL);
    g->color_buf = dma_alloc_coherent(g->dev, g->color_size, &g->color_phys,
                                      GFP_KERNEL);
    g->depth_buf = dma_alloc_coherent(g->dev, g->depth_size, &g->depth_phys,
                                      GFP_KERNEL);
    if (!g->cmd_buf || !g->color_buf || !g->depth_buf) {
        dev_err(&pdev->dev, "cannot allocate shared buffers\n");
        return -ENOMEM;
    }

    g->irq = platform_get_irq_optional(pdev, 0);
    if (g->irq > 0) {
        ret = devm_request_irq(&pdev->dev, g->irq, gpu_irq, 0, GPU_NAME, g);
        if (ret)
            return dev_err_probe(&pdev->dev, ret, "cannot request irq\n");
    }

    platform_set_drvdata(pdev, g);
    gpu_self_test(g);

    g->misc = (struct miscdevice) {
        .minor = MISC_DYNAMIC_MINOR,
        .name = GPU_DEV_NAME,
        .mode = 0666,
        .fops = &gpu_fops,
    };
    ret = misc_register(&g->misc);
    if (ret)
        return dev_err_probe(&pdev->dev, ret, "cannot register char dev\n");

    dev_info(&pdev->dev, "GPU probe: ctrl=%pa+%pa fb=%pa stride=%u mode=%ux%u\n",
             &g->ctrl_phys, &g->ctrl_size, &g->color_phys, g->stride,
             g->width, g->height);
    return 0;
}

static void gpu_remove(struct platform_device *pdev)
{
    struct gpu_device *g = platform_get_drvdata(pdev);

    misc_deregister(&g->misc);
    if (g->cmd_buf)
        dma_free_coherent(g->dev, g->cmd_size, g->cmd_buf, g->cmd_phys);
    if (g->color_buf)
        dma_free_coherent(g->dev, g->color_size, g->color_buf, g->color_phys);
    if (g->depth_buf)
        dma_free_coherent(g->dev, g->depth_size, g->depth_buf, g->depth_phys);
}

static int gpu_open(struct inode *inode, struct file *filp)
{
    return 0;
}

static ssize_t gpu_read(struct file *filp, char __user *buf,
                        size_t count, loff_t *off)
{
    struct miscdevice *misc = filp->private_data;
    struct gpu_device *g = container_of(misc, struct gpu_device, misc);

    if (*off >= g->color_size)
        return 0;
    if (count > g->color_size - *off)
        count = g->color_size - *off;
    if (copy_to_user(buf, g->color_buf + *off, count))
        return -EFAULT;
    *off += count;
    return count;
}

static long gpu_ioctl(struct file *filp, unsigned int cmd, unsigned long arg)
{
    struct miscdevice *misc = filp->private_data;
    struct gpu_device *g = container_of(misc, struct gpu_device, misc);
    int ret;

    if (cmd != GPU_IOCTL_SUBMIT)
        return -ENOTTY;

    gpu_program_config(g);
    gpu_fill_command(g);
    ret = gpu_start_and_wait(g);
    if (!ret)
        dev_info(g->dev, "GPU userspace submit complete\n");
    return ret;
}

static const struct file_operations gpu_fops = {
    .owner = THIS_MODULE,
    .open = gpu_open,
    .read = gpu_read,
    .unlocked_ioctl = gpu_ioctl,
    .compat_ioctl = gpu_ioctl,
    .llseek = default_llseek,
};

static const struct of_device_id gpu_of_match[] = {
    { .compatible = "riscv-simt,opengpu" },
    { }
};
MODULE_DEVICE_TABLE(of, gpu_of_match);

static struct platform_driver gpu_platform_driver = {
    .probe = gpu_probe,
    .remove = gpu_remove,
    .driver = {
        .name = GPU_NAME,
        .of_match_table = gpu_of_match,
    },
};
module_platform_driver(gpu_platform_driver);

MODULE_LICENSE("GPL");
MODULE_DESCRIPTION("RISC-V SIMT GPU host-driver (M6 char-device interface)");
