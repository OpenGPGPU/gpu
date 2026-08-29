// SPDX-License-Identifier: GPL-2.0
/* Bring-up render/compute client and its temporary misc-device ABI. */
#include <linux/fs.h>
#include <linux/module.h>
#include <linux/slab.h>
#include <linux/uaccess.h>

#include "opengpu_device.h"

static const struct file_operations opengpu_compute_fops;

static void opengpu_fill_test_command(struct opengpu_device *gpu)
{
    struct gpu_draw_record *r = gpu->compute.cmd.cpu;

    BUILD_BUG_ON(sizeof(*r) != GPU_DRAW_WORDS * sizeof(u32));

    memset(gpu->compute.cmd.cpu, 0, sizeof(*r));
    memset(gpu->compute.depth.cpu, 0xff, gpu->compute.depth.size);

    r->v0[0] = -0x10000; r->v0[1] = -0x10000;
    r->v0[2] = 0; r->v0[3] = 0x10000;
    r->v1[0] =  0x10000; r->v1[1] = -0x10000;
    r->v1[2] = 0; r->v1[3] = 0x10000;
    r->v2[0] = -0x10000; r->v2[1] =  0x10000;
    r->v2[2] = 0; r->v2[3] = 0x10000;

    r->c0[0] = 255; r->c0[1] = 0; r->c0[2] = 0;
    r->c1[0] = 255; r->c1[1] = 0; r->c1[2] = 0;
    r->c2[0] = 255; r->c2[1] = 0; r->c2[2] = 0;
    r->d0 = 0x10;
    r->d1 = 0x10;
    r->d2 = 0x10;
}

static int opengpu_submit_test(struct opengpu_device *gpu)
{
    const struct opengpu_job job = {
        .cmd = gpu->compute.cmd.dma,
        .cmd_count = 1,
        .color = gpu->compute.color.dma,
        .depth = gpu->compute.depth.dma,
        .stride = gpu->stride,
        .depth_test = true,
        .depth_func = GPU_DEPTH_FUNC_LESS,
        .depth_write = true,
        .cull_mode = GPU_CULL_NONE,
    };

    opengpu_fill_test_command(gpu);
    return opengpu_hw_submit(gpu, &job);
}

static int opengpu_self_test(struct opengpu_device *gpu)
{
    u32 pixel;
    u32 rgb;
    int ret;

    ret = opengpu_submit_test(gpu);
    if (ret) {
        dev_err(gpu->dev, "OPENGPU DRIVER FAIL: draw did not complete\n");
        return ret;
    }

    pixel = ((u32 *)gpu->compute.color.cpu)[gpu->stride / 4 + 1];
    rgb = (pixel >> 8) & 0x00ffffffu;
    if (rgb == 0x00ff0000u) {
        dev_info(gpu->dev,
                 "OPENGPU DRIVER PASS: draw submitted and read back\n");
        return 0;
    }

    dev_err(gpu->dev,
            "OPENGPU DRIVER FAIL: expected red (0xff0000) got 0x%06x\n",
            rgb);
    return -EIO;
}

static ssize_t opengpu_compute_read(struct file *file, char __user *buf,
                                    size_t count, loff_t *offset)
{
    struct miscdevice *misc = file->private_data;
    struct opengpu_compute *compute;
    struct opengpu_device *gpu;
    ssize_t ret;

    compute = container_of(misc, struct opengpu_compute, misc);
    gpu = container_of(compute, struct opengpu_device, compute);
    if (*offset >= compute->color.size)
        return 0;
    if (count > compute->color.size - *offset)
        count = compute->color.size - *offset;

    mutex_lock(&compute->lock);
    if (copy_to_user(buf, compute->color.cpu + *offset, count)) {
        ret = -EFAULT;
    } else {
        *offset += count;
        ret = count;
    }
    mutex_unlock(&compute->lock);
    return ret;
}

static long opengpu_compute_ioctl(struct file *file, unsigned int cmd,
                                  unsigned long arg)
{
    struct miscdevice *misc = file->private_data;
    struct opengpu_compute *compute;
    struct opengpu_device *gpu;
    int ret;

    if (cmd != OPENGPU_IOCTL_SUBMIT)
        return -ENOTTY;

    compute = container_of(misc, struct opengpu_compute, misc);
    gpu = container_of(compute, struct opengpu_device, compute);
    mutex_lock(&compute->lock);
    ret = opengpu_submit_test(gpu);
    mutex_unlock(&compute->lock);
    if (!ret)
        dev_info(gpu->dev, "GPU userspace submit complete\n");
    return ret;
}

static const struct file_operations opengpu_compute_fops = {
    .owner = THIS_MODULE,
    .read = opengpu_compute_read,
    .unlocked_ioctl = opengpu_compute_ioctl,
    .compat_ioctl = opengpu_compute_ioctl,
    .llseek = default_llseek,
};

int opengpu_compute_init(struct opengpu_device *gpu)
{
    struct opengpu_compute *compute = &gpu->compute;
    int ret;

    mutex_init(&compute->lock);
    ret = opengpu_buffer_alloc(gpu, &compute->cmd,
                               max_t(size_t, sizeof(struct gpu_draw_record),
                                     256));
    if (ret)
        return ret;
    ret = opengpu_buffer_alloc(gpu, &compute->color,
                               (size_t)gpu->stride * gpu->height);
    if (ret)
        goto err_cmd;
    ret = opengpu_buffer_alloc(gpu, &compute->depth,
                               (size_t)gpu->stride * gpu->height);
    if (ret)
        goto err_color;

    ret = opengpu_self_test(gpu);
    if (ret)
        goto err_depth;
    compute->misc = (struct miscdevice) {
        .minor = MISC_DYNAMIC_MINOR,
        .name = OPENGPU_COMPUTE_NAME,
        .mode = 0666,
        .fops = &opengpu_compute_fops,
        .parent = gpu->dev,
    };
    ret = misc_register(&compute->misc);
    if (ret)
        goto err_depth;
    return 0;

err_depth:
    opengpu_buffer_free(gpu, &compute->depth);
err_color:
    opengpu_buffer_free(gpu, &compute->color);
err_cmd:
    opengpu_buffer_free(gpu, &compute->cmd);
    return ret;
}

void opengpu_compute_fini(struct opengpu_device *gpu)
{
    misc_deregister(&gpu->compute.misc);
    opengpu_buffer_free(gpu, &gpu->compute.depth);
    opengpu_buffer_free(gpu, &gpu->compute.color);
    opengpu_buffer_free(gpu, &gpu->compute.cmd);
}
