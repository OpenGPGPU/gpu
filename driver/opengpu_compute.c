// SPDX-License-Identifier: GPL-2.0
/* Bring-up render/compute client and its temporary misc-device ABI. */
#include <linux/dma-resv.h>
#include <linux/fs.h>
#include <linux/module.h>
#include <linux/overflow.h>
#include <linux/slab.h>
#include <linux/uaccess.h>

#include <drm/drm_device.h>
#include <drm/drm_file.h>
#include <drm/drm_gem.h>
#include <drm/drm_gem_dma_helper.h>

#include "opengpu_device.h"
#include "opengpu_drm.h"

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

static int opengpu_compute_wait_last(struct opengpu_compute *compute)
{
    long timeout;
    int ret;

    if (!compute->last_fence)
        return 0;
    timeout = dma_fence_wait_timeout(compute->last_fence, true,
                                     msecs_to_jiffies(OPENGPU_DRAW_WAIT_MS +
                                                      100));
    if (timeout <= 0)
        ret = timeout < 0 ? timeout : -ETIMEDOUT;
    else {
        ret = dma_fence_get_status(compute->last_fence);
        if (ret > 0)
            ret = 0;
    }
    dma_fence_put(compute->last_fence);
    compute->last_fence = NULL;
    return ret;
}

int opengpu_compute_drm_ioctl(struct drm_device *drm, void *data,
                              struct drm_file *file)
{
    struct drm_opengpu_submit *args = data;
    struct opengpu_device *gpu = dev_get_drvdata(drm->dev);
    struct opengpu_compute *compute = &gpu->compute;
    struct drm_gem_object *object;
    struct drm_gem_dma_object *dma_object;
    struct dma_fence *fence;
    struct opengpu_job job;
    size_t required;
    int ret;

    if ((args->flags & ~OPENGPU_SUBMIT_TEST_FENCE_DELAY) || args->pad ||
        !args->color_handle ||
        args->stride < gpu->width * 4 || (args->stride & 3) ||
        check_mul_overflow((size_t)args->stride, (size_t)gpu->height,
                           &required))
        return -EINVAL;

    object = drm_gem_object_lookup(file, args->color_handle);
    if (!object)
        return -ENOENT;
    if (required > object->size) {
        ret = -EINVAL;
        goto out_object;
    }
    dma_object = to_drm_gem_dma_obj(object);

    ret = dma_resv_lock_interruptible(object->resv, NULL);
    if (ret)
        goto out_object;
    ret = dma_resv_reserve_fences(object->resv, 1);
    if (ret)
        goto out_resv;

    ret = mutex_lock_interruptible(&compute->lock);
    if (ret)
        goto out_resv;
    ret = opengpu_compute_wait_last(compute);
    if (ret)
        goto out_compute;

    opengpu_fill_test_command(gpu);
    job = (struct opengpu_job) {
        .cmd = compute->cmd.dma,
        .cmd_count = 1,
        .color = dma_object->dma_addr,
        .depth = compute->depth.dma,
        .stride = args->stride,
        .depth_test = true,
        .depth_func = GPU_DEPTH_FUNC_LESS,
        .depth_write = true,
        .cull_mode = GPU_CULL_NONE,
        .completion_delay_ms =
            args->flags & OPENGPU_SUBMIT_TEST_FENCE_DELAY ? 50 : 0,
    };
    ret = opengpu_hw_submit_async(gpu, &job, &fence);
    if (ret)
        goto out_compute;

    compute->last_fence = fence;
    dma_resv_add_fence(object->resv, fence, DMA_RESV_USAGE_WRITE);
    dev_info(gpu->dev, "render fence %llu attached to GEM handle %u\n",
             fence->seqno, args->color_handle);

out_compute:
    mutex_unlock(&compute->lock);
out_resv:
    dma_resv_unlock(object->resv);
out_object:
    drm_gem_object_put(object);
    return ret;
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
    mutex_lock(&gpu->compute.lock);
    opengpu_compute_wait_last(&gpu->compute);
    mutex_unlock(&gpu->compute.lock);
    opengpu_buffer_free(gpu, &gpu->compute.depth);
    opengpu_buffer_free(gpu, &gpu->compute.color);
    opengpu_buffer_free(gpu, &gpu->compute.cmd);
}
