// SPDX-License-Identifier: GPL-2.0
/* Bring-up render/compute client and its temporary misc-device ABI. */
#include <linux/dma-resv.h>
#include <linux/fs.h>
#include <linux/idr.h>
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

struct opengpu_render_context {
    u32 id;
    struct opengpu_buffer commands;
    struct opengpu_buffer depth;
    struct dma_fence *last_fence;
};

struct opengpu_file {
    struct opengpu_device *gpu;
    struct mutex lock;
    struct idr contexts;
};

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

static int opengpu_wait_fence(struct dma_fence **fence, bool interruptible);

static int opengpu_compute_wait_last(struct opengpu_compute *compute)
{
    return opengpu_wait_fence(&compute->last_fence, true);
}

static int opengpu_wait_fence(struct dma_fence **fence, bool interruptible)
{
    long timeout;
    int ret;

    if (!*fence)
        return 0;
    timeout = dma_fence_wait_timeout(*fence, interruptible,
                                     msecs_to_jiffies(OPENGPU_DRAW_WAIT_MS +
                                                      100));
    if (timeout <= 0)
        ret = timeout < 0 ? timeout : -ETIMEDOUT;
    else {
        ret = dma_fence_get_status(*fence);
        if (ret > 0)
            ret = 0;
    }
    dma_fence_put(*fence);
    *fence = NULL;
    return ret;
}

static void opengpu_context_free(struct opengpu_file *render_file,
                                 struct opengpu_render_context *context)
{
    struct opengpu_device *gpu = render_file->gpu;

    mutex_lock(&gpu->compute.lock);
    opengpu_wait_fence(&context->last_fence, false);
    mutex_unlock(&gpu->compute.lock);
    opengpu_buffer_free(gpu, &context->depth);
    opengpu_buffer_free(gpu, &context->commands);
    kfree(context);
}

int opengpu_compute_drm_open(struct drm_device *drm, struct drm_file *file)
{
    struct opengpu_file *render_file;

    render_file = kzalloc(sizeof(*render_file), GFP_KERNEL);
    if (!render_file)
        return -ENOMEM;
    render_file->gpu = dev_get_drvdata(drm->dev);
    mutex_init(&render_file->lock);
    idr_init(&render_file->contexts);
    file->driver_priv = render_file;
    return 0;
}

void opengpu_compute_drm_postclose(struct drm_device *drm,
                                   struct drm_file *file)
{
    struct opengpu_file *render_file = file->driver_priv;
    struct opengpu_render_context *context;
    int id = 0;

    if (!render_file)
        return;
    while ((context = idr_get_next(&render_file->contexts, &id))) {
        idr_remove(&render_file->contexts, id);
        opengpu_context_free(render_file, context);
    }
    idr_destroy(&render_file->contexts);
    kfree(render_file);
    file->driver_priv = NULL;
}

int opengpu_compute_context_create_ioctl(struct drm_device *drm, void *data,
                                         struct drm_file *file)
{
    struct drm_opengpu_context *args = data;
    struct opengpu_file *render_file = file->driver_priv;
    struct opengpu_device *gpu = render_file->gpu;
    struct opengpu_render_context *context;
    int ret;

    if (args->flags || args->id)
        return -EINVAL;
    context = kzalloc(sizeof(*context), GFP_KERNEL);
    if (!context)
        return -ENOMEM;
    ret = opengpu_buffer_alloc(gpu, &context->commands,
                               OPENGPU_MAX_COMMANDS *
                               sizeof(struct gpu_draw_record));
    if (ret)
        goto err_context;
    ret = opengpu_buffer_alloc(gpu, &context->depth,
                               (size_t)gpu->stride * gpu->height);
    if (ret)
        goto err_commands;

    mutex_lock(&render_file->lock);
    ret = idr_alloc(&render_file->contexts, context, 1, 0, GFP_KERNEL);
    if (ret >= 0) {
        context->id = ret;
        args->id = ret;
        ret = 0;
    }
    mutex_unlock(&render_file->lock);
    if (!ret)
        return 0;

    opengpu_buffer_free(gpu, &context->depth);
err_commands:
    opengpu_buffer_free(gpu, &context->commands);
err_context:
    kfree(context);
    return ret;
}

int opengpu_compute_context_destroy_ioctl(struct drm_device *drm, void *data,
                                          struct drm_file *file)
{
    struct drm_opengpu_context *args = data;
    struct opengpu_file *render_file = file->driver_priv;
    struct opengpu_render_context *context;

    if (args->flags || !args->id)
        return -EINVAL;
    mutex_lock(&render_file->lock);
    context = idr_remove(&render_file->contexts, args->id);
    mutex_unlock(&render_file->lock);
    if (!context)
        return -ENOENT;
    opengpu_context_free(render_file, context);
    return 0;
}

static int opengpu_validate_commands(struct opengpu_render_context *context,
                                     u32 count)
{
    struct gpu_draw_record *records = context->commands.cpu;
    u32 i, j;

    BUILD_BUG_ON(sizeof(struct drm_opengpu_draw) !=
                 sizeof(struct gpu_draw_record));
    for (i = 0; i < count; i++) {
        struct gpu_draw_record *record = &records[i];

        if (record->shader_pc || record->kernarg ||
            record->v0[3] <= 0 || record->v1[3] <= 0 ||
            record->v2[3] <= 0)
            return -EINVAL;
        for (j = 0; j < ARRAY_SIZE(record->c0); j++) {
            if (record->c0[j] > 255 || record->c1[j] > 255 ||
                record->c2[j] > 255)
                return -EINVAL;
        }
    }
    return 0;
}

int opengpu_compute_drm_ioctl(struct drm_device *drm, void *data,
                              struct drm_file *file)
{
    struct drm_opengpu_submit *args = data;
    struct opengpu_device *gpu = dev_get_drvdata(drm->dev);
    struct opengpu_compute *compute = &gpu->compute;
    struct opengpu_file *render_file = file->driver_priv;
    struct opengpu_render_context *context;
    struct drm_gem_object *command_object;
    struct drm_gem_object *color_object;
    struct drm_gem_dma_object *command_dma;
    struct drm_gem_dma_object *color_dma;
    struct dma_fence *fence;
    struct opengpu_job job;
    u64 command_end;
    size_t command_bytes;
    size_t color_required;
    long wait;
    int ret;

    if ((args->flags & ~OPENGPU_SUBMIT_TEST_FENCE_DELAY) ||
        !args->context_id || !args->command_handle || !args->color_handle ||
        args->command_handle == args->color_handle ||
        !args->command_count || args->command_count > OPENGPU_MAX_COMMANDS ||
        (args->command_offset & 3) ||
        args->stride != gpu->stride ||
        check_mul_overflow((size_t)args->stride, (size_t)gpu->height,
                           &color_required) ||
        check_mul_overflow((size_t)args->command_count,
                           sizeof(struct gpu_draw_record), &command_bytes) ||
        check_add_overflow(args->command_offset, (u64)command_bytes,
                           &command_end))
        return -EINVAL;

    command_object = drm_gem_object_lookup(file, args->command_handle);
    if (!command_object)
        return -ENOENT;
    color_object = drm_gem_object_lookup(file, args->color_handle);
    if (!color_object) {
        ret = -ENOENT;
        goto out_command_object;
    }
    if (command_end > command_object->size ||
        color_required > color_object->size) {
        ret = -EINVAL;
        goto out_color_object;
    }
    command_dma = to_drm_gem_dma_obj(command_object);
    color_dma = to_drm_gem_dma_obj(color_object);
    if (!command_dma->vaddr) {
        ret = -EINVAL;
        goto out_color_object;
    }

    wait = dma_resv_wait_timeout(command_object->resv,
                                 DMA_RESV_USAGE_WRITE, true,
                                 MAX_SCHEDULE_TIMEOUT);
    if (wait <= 0) {
        ret = wait < 0 ? wait : -ETIMEDOUT;
        goto out_color_object;
    }

    mutex_lock(&render_file->lock);
    context = idr_find(&render_file->contexts, args->context_id);
    if (!context) {
        ret = -ENOENT;
        goto out_file;
    }
    memcpy(context->commands.cpu,
           (u8 *)command_dma->vaddr + args->command_offset,
           command_bytes);
    ret = opengpu_validate_commands(context, args->command_count);
    if (ret)
        goto out_file;

    ret = dma_resv_lock_interruptible(color_object->resv, NULL);
    if (ret)
        goto out_file;
    ret = dma_resv_reserve_fences(color_object->resv, 1);
    if (ret)
        goto out_resv;

    ret = mutex_lock_interruptible(&compute->lock);
    if (ret)
        goto out_resv;
    ret = opengpu_compute_wait_last(compute);
    if (ret)
        goto out_compute;

    memset(context->depth.cpu, 0xff, context->depth.size);
    job = (struct opengpu_job) {
        .cmd = context->commands.dma,
        .cmd_count = args->command_count,
        .color = color_dma->dma_addr,
        .depth = context->depth.dma,
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
    dma_fence_put(context->last_fence);
    context->last_fence = dma_fence_get(fence);
    dma_resv_add_fence(color_object->resv, fence, DMA_RESV_USAGE_WRITE);
    dev_info(gpu->dev,
             "context %u submitted %u draw(s), fence %llu on GEM handle %u\n",
             context->id, args->command_count, fence->seqno,
             args->color_handle);

out_compute:
    mutex_unlock(&compute->lock);
out_resv:
    dma_resv_unlock(color_object->resv);
out_file:
    mutex_unlock(&render_file->lock);
out_color_object:
    drm_gem_object_put(color_object);
out_command_object:
    drm_gem_object_put(command_object);
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
    ssize_t ret;

    compute = container_of(misc, struct opengpu_compute, misc);
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
