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
#include <drm/drm_exec.h>
#include <drm/drm_file.h>
#include <drm/drm_gem.h>
#include <drm/drm_gem_dma_helper.h>
#include <drm/drm_syncobj.h>
#include <drm/gpu_scheduler.h>

#include "opengpu_device.h"
#include "opengpu_drm.h"
#include "opengpu_shader_validator.h"

static const struct file_operations opengpu_compute_fops;
static u32 opengpu_fragment_batch_capacity(struct opengpu_device *gpu);

struct opengpu_render_context {
    u32 id;
    struct drm_sched_entity entity;
    struct dma_fence *last_fence;
    struct opengpu_resource_binding *bindings[OPENGPU_MAX_RESOURCE_SLOTS];
};

struct opengpu_resource_binding {
    struct drm_gem_object *object;
    dma_addr_t dma;
    u64 offset;
    u64 size;
    u32 type;
    u32 width;
    u32 height;
    u32 flags;
};

struct opengpu_sched_job {
    struct drm_sched_job base;
    struct opengpu_device *gpu;
    struct opengpu_job hw;
    struct opengpu_buffer commands;
    struct opengpu_buffer shader;
    struct opengpu_buffer depth;
    struct drm_gem_object *objects[3];
    u32 object_count;
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
    if (gpu->compute.kernarg.cpu)
        memset(gpu->compute.kernarg.cpu, 0, gpu->compute.kernarg.size);

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
    if (gpu->hw.capabilities & GPU_CAP_FRAGMENT_CORE) {
        r->shader_pc = lower_32_bits(gpu->compute.shader.dma);
        r->kernarg = lower_32_bits(gpu->compute.kernarg.dma);
        r->kernarg_bank_stride = gpu->compute.kernarg.size / GPU_KERNARG_BANKS;
    }
}

static int opengpu_init_test_shader(struct opengpu_device *gpu)
{
    u32 batch = opengpu_fragment_batch_capacity(gpu);
    u32 stride, input_offset, output_offset, vector_length;
    u32 *program;
    int ret;

    if (!batch)
        return -EINVAL;
    stride = 4 * batch;
    vector_length = min(batch, 4u);
    input_offset = GPU_KERNARG_COLOR_OFF(stride);
    output_offset = GPU_KERNARG_OUT_OFF(stride);
    ret = opengpu_buffer_alloc(gpu, &gpu->compute.shader, 64);
    if (ret)
        return ret;
    ret = opengpu_buffer_alloc(gpu, &gpu->compute.kernarg,
                               GPU_KERNARG_BANKS *
                               ALIGN(GPU_KERNARG_UNIFORM_OFF(stride),
                                     GPU_KERNARG_BANK_ALIGN));
    if (ret) {
        opengpu_buffer_free(gpu, &gpu->compute.shader);
        return ret;
    }

    program = gpu->compute.shader.cpu;
    memset(program, 0, gpu->compute.shader.size);
    /* Per-warp vector pass-through over the SoA kernarg arrays:
     * x5 = x1 + 4*x8; vle32 colours; vse32 outputs. */
    program[0] = 2u << 20 | 8u << 15 | 1u << 12 | 5u << 7 | 0x13;
    program[1] = 5u << 20 | 1u << 15 | 5u << 7 | 0x33;
    program[2] = 0xc1007057u | vector_length << 15;
    program[3] = (input_offset & 0xfff) << 20 | 5u << 15 |
                 6u << 7 | 0x13;
    program[4] = 0x02006007u | 6u << 15 | 2u << 7;
    program[5] = (output_offset & 0xfff) << 20 | 5u << 15 |
                 6u << 7 | 0x13;
    program[6] = 0x02006027u | 6u << 15 | 2u << 7;
    program[7] = OPENGPU_SHADER_CEASE;
    return 0;
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
    u32 i;

    drm_sched_entity_destroy(&context->entity);
    opengpu_wait_fence(&context->last_fence, false);
    for (i = 0; i < ARRAY_SIZE(context->bindings); i++) {
        if (!context->bindings[i])
            continue;
        drm_gem_object_put(context->bindings[i]->object);
        kfree(context->bindings[i]);
    }
    kfree(context);
}

static struct opengpu_render_context *
opengpu_context_lookup(struct opengpu_file *render_file, u32 id)
{
    if (!id)
        return NULL;
    return idr_find(&render_file->contexts, id);
}

static int opengpu_context_quiesce(struct opengpu_file *render_file,
                                   struct opengpu_render_context *context)
{
    int ret;

    ret = opengpu_wait_fence(&context->last_fence, true);
    return ret;
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

int opengpu_compute_get_param_ioctl(struct drm_device *drm, void *data,
                                    struct drm_file *file)
{
    struct drm_opengpu_param *args = data;
    struct opengpu_device *gpu = dev_get_drvdata(drm->dev);

    if (args->pad)
        return -EINVAL;
    if (args->param != OPENGPU_PARAM_CAPABILITIES)
        return -EINVAL;
    args->value = gpu->hw.capabilities;
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
    {
        struct drm_gpu_scheduler *scheduler = &gpu->compute.scheduler;

        ret = drm_sched_entity_init(&context->entity,
                                    DRM_SCHED_PRIORITY_NORMAL,
                                    &scheduler, 1, NULL);
    }
    if (ret)
        goto err_context;

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

    drm_sched_entity_destroy(&context->entity);
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

static bool opengpu_is_power_of_two(u32 value)
{
    return value && !(value & (value - 1));
}

int opengpu_compute_resource_bind_ioctl(struct drm_device *drm, void *data,
                                        struct drm_file *file)
{
    struct drm_opengpu_resource *args = data;
    struct opengpu_file *render_file = file->driver_priv;
    struct opengpu_render_context *context;
    struct opengpu_resource_binding *binding, *old;
    struct drm_gem_dma_object *dma_object;
    struct drm_gem_object *object;
    u64 end, dma_end, texture_bytes;
    u32 mip_level, mip_width, mip_height, level;
    int ret;

    if (!args->context_id || !args->slot ||
        args->slot > OPENGPU_MAX_RESOURCE_SLOTS || !args->handle ||
        args->type < OPENGPU_RESOURCE_SHADER ||
        args->type > OPENGPU_RESOURCE_TEXTURE || !args->size ||
        (args->offset & 3) || args->pad ||
        check_add_overflow(args->offset, args->size, &end))
        return -EINVAL;
    if (args->type == OPENGPU_RESOURCE_SHADER &&
        ((args->offset & 63) || (args->size & 63) ||
         args->size > OPENGPU_SHADER_MAX_INSTRUCTIONS * sizeof(u32)))
        return -EINVAL;
    if (args->type == OPENGPU_RESOURCE_TEXTURE) {
        mip_level = (args->flags & OPENGPU_RESOURCE_TEXTURE_MAX_MIP_MASK) >>
                    OPENGPU_RESOURCE_TEXTURE_MAX_MIP_SHIFT;
        if (!args->width || !args->height ||
            args->width > 0x3fff || args->height > 0x3fff ||
            (args->flags & ~(OPENGPU_RESOURCE_TEXTURE_CLAMP |
                             OPENGPU_RESOURCE_TEXTURE_MAX_MIP_MASK)) ||
            mip_level >= 32 - __builtin_clz(max(args->width, args->height)) ||
            (!(args->flags & OPENGPU_RESOURCE_TEXTURE_CLAMP) &&
             (!opengpu_is_power_of_two(args->width) ||
              !opengpu_is_power_of_two(args->height))))
            return -EINVAL;
        texture_bytes = 0;
        mip_width = args->width;
        mip_height = args->height;
        for (level = 0; level <= mip_level; level++) {
            u64 level_bytes;

            if (check_mul_overflow((u64)mip_width, (u64)mip_height,
                                   &level_bytes) ||
                check_mul_overflow(level_bytes, 4ull, &level_bytes) ||
                check_add_overflow(texture_bytes, level_bytes,
                                   &texture_bytes))
                return -EINVAL;
            mip_width = max(1u, mip_width >> 1);
            mip_height = max(1u, mip_height >> 1);
        }
        if (texture_bytes > args->size)
            return -EINVAL;
    } else if (args->width || args->height || args->flags) {
        return -EINVAL;
    }

    object = drm_gem_object_lookup(file, args->handle);
    if (!object)
        return -ENOENT;
    if (end > object->size) {
        ret = -EINVAL;
        goto out_object;
    }
    dma_object = to_drm_gem_dma_obj(object);
    if (check_add_overflow((u64)dma_object->dma_addr, args->offset, &end) ||
        check_add_overflow(end, args->size - 1, &dma_end) ||
        upper_32_bits(dma_end)) {
        ret = -ERANGE;
        goto out_object;
    }
    binding = kzalloc(sizeof(*binding), GFP_KERNEL);
    if (!binding) {
        ret = -ENOMEM;
        goto out_object;
    }
    binding->object = object;
    binding->dma = end;
    binding->offset = args->offset;
    binding->size = args->size;
    binding->type = args->type;
    binding->width = args->width;
    binding->height = args->height;
    binding->flags = args->flags;

    mutex_lock(&render_file->lock);
    context = opengpu_context_lookup(render_file, args->context_id);
    if (!context) {
        ret = -ENOENT;
        goto out_file;
    }
    ret = opengpu_context_quiesce(render_file, context);
    if (ret)
        goto out_file;
    old = context->bindings[args->slot - 1];
    context->bindings[args->slot - 1] = binding;
    mutex_unlock(&render_file->lock);
    if (old) {
        drm_gem_object_put(old->object);
        kfree(old);
    }
    return 0;

out_file:
    mutex_unlock(&render_file->lock);
    kfree(binding);
out_object:
    drm_gem_object_put(object);
    return ret;
}

int opengpu_compute_resource_unbind_ioctl(struct drm_device *drm, void *data,
                                          struct drm_file *file)
{
    struct drm_opengpu_resource *args = data;
    struct opengpu_file *render_file = file->driver_priv;
    struct opengpu_render_context *context;
    struct opengpu_resource_binding *binding;
    int ret;

    if (!args->context_id || !args->slot ||
        args->slot > OPENGPU_MAX_RESOURCE_SLOTS || args->handle ||
        args->type || args->offset || args->size || args->width ||
        args->height || args->flags || args->pad)
        return -EINVAL;
    mutex_lock(&render_file->lock);
    context = opengpu_context_lookup(render_file, args->context_id);
    if (!context) {
        ret = -ENOENT;
        goto out_file;
    }
    binding = context->bindings[args->slot - 1];
    if (!binding) {
        ret = -ENOENT;
        goto out_file;
    }
    ret = opengpu_context_quiesce(render_file, context);
    if (ret)
        goto out_file;
    context->bindings[args->slot - 1] = NULL;
    mutex_unlock(&render_file->lock);
    drm_gem_object_put(binding->object);
    kfree(binding);
    return 0;

out_file:
    mutex_unlock(&render_file->lock);
    return ret;
}

static struct opengpu_resource_binding *
opengpu_binding_lookup(struct opengpu_render_context *context, u32 slot,
                       u32 type)
{
    struct opengpu_resource_binding *binding;

    if (!slot || slot > OPENGPU_MAX_RESOURCE_SLOTS)
        return NULL;
    binding = context->bindings[slot - 1];
    return binding && binding->type == type ? binding : NULL;
}

static int opengpu_resolve_bindings(
    struct opengpu_device *gpu,
    struct opengpu_render_context *context,
    const struct drm_opengpu_submit *args,
    struct opengpu_resource_binding **shader,
    struct opengpu_resource_binding **kernarg,
    struct opengpu_resource_binding **texture)
{
    if (!!args->shader_slot != !!args->kernarg_slot)
        return -EINVAL;
    if (args->shader_slot &&
        !(gpu->hw.capabilities & GPU_CAP_FRAGMENT_CORE))
        return -EOPNOTSUPP;
    if (!args->shader_slot &&
        (gpu->hw.capabilities & GPU_CAP_FRAGMENT_CORE))
        return -EOPNOTSUPP;
    *shader = args->shader_slot ?
        opengpu_binding_lookup(context, args->shader_slot,
                               OPENGPU_RESOURCE_SHADER) : NULL;
    *kernarg = args->kernarg_slot ?
        opengpu_binding_lookup(context, args->kernarg_slot,
                               OPENGPU_RESOURCE_KERNARG) : NULL;
    *texture = args->texture_slot ?
        opengpu_binding_lookup(context, args->texture_slot,
                               OPENGPU_RESOURCE_TEXTURE) : NULL;
    if ((args->shader_slot && (!*shader || !*kernarg)) ||
        (args->texture_slot && !*texture))
        return -EINVAL;
    return 0;
}

static u32 opengpu_fragment_batch_capacity(struct opengpu_device *gpu)
{
    return (gpu->hw.capabilities & GPU_CAP_FRAGMENT_BATCH_MASK) >>
           GPU_CAP_FRAGMENT_BATCH_SHIFT;
}

static bool opengpu_validate_shader(
    struct opengpu_device *gpu, struct opengpu_buffer *shader,
    u64 kernarg_size, u32 entry, bool texture_enabled)
{
    const u32 *program;
    u64 available;
    u32 words;

    if ((entry & 3) || entry >= shader->size)
        return false;
    available = shader->size - entry;
    words = min_t(u64, available / sizeof(u32),
                  OPENGPU_SHADER_MAX_INSTRUCTIONS);
    program = (const u32 *)((const u8 *)shader->cpu + entry);
    return opengpu_shader_validate_words_with_texture(
        program, words, kernarg_size,
        opengpu_fragment_batch_capacity(gpu), texture_enabled);
}

static int opengpu_validate_commands(struct opengpu_device *gpu,
                                     struct opengpu_buffer *commands,
                                     const struct drm_opengpu_submit *args,
                                     struct opengpu_buffer *shader,
                                     struct opengpu_resource_binding *kernarg,
                                     struct opengpu_resource_binding *texture)
{
    struct gpu_draw_record *records = commands->cpu;
    u64 address, kernarg_min, kernarg_span, shader_kernarg_size;
    u32 i, j;

    BUILD_BUG_ON(sizeof(struct drm_opengpu_draw) !=
                 sizeof(struct gpu_draw_record));
    for (i = 0; i < args->command_count; i++) {
        struct gpu_draw_record *record = &records[i];

        if (record->v0[3] <= 0 || record->v1[3] <= 0 ||
            record->v2[3] <= 0)
            return -EINVAL;
        for (j = 0; j < ARRAY_SIZE(record->c0); j++) {
            if (record->c0[j] > 255 || record->c1[j] > 255 ||
                record->c2[j] > 255)
                return -EINVAL;
        }
        for (j = 0; j < ARRAY_SIZE(record->reserved); j++)
            if (record->reserved[j])
                return -EINVAL;
        if (record->state & ~OPENGPU_DRAW_STATE_VALID_MASK)
            return -EINVAL;
        if (record->sampler & ~OPENGPU_DRAW_SAMPLER_VALID_MASK)
            return -EINVAL;
        if (!(record->state & OPENGPU_DRAW_STATE_OVERRIDE)) {
            if (record->state || record->sampler)
                return -EINVAL;
        } else {
            u32 depth_func = (record->state &
                OPENGPU_DRAW_STATE_DEPTH_FUNC_MASK) >>
                OPENGPU_DRAW_STATE_DEPTH_FUNC_SHIFT;
            u32 cull = (record->state & OPENGPU_DRAW_STATE_CULL_MASK) >>
                OPENGPU_DRAW_STATE_CULL_SHIFT;
            u32 max_mip = (record->state &
                OPENGPU_DRAW_STATE_MAX_MIP_MASK) >>
                OPENGPU_DRAW_STATE_MAX_MIP_SHIFT;
            u32 bound_max_mip = texture ?
                (texture->flags & OPENGPU_RESOURCE_TEXTURE_MAX_MIP_MASK) >>
                OPENGPU_RESOURCE_TEXTURE_MAX_MIP_SHIFT : 0;
            u32 min_mip = (record->sampler &
                OPENGPU_DRAW_SAMPLER_MIN_LOD_MASK) >>
                OPENGPU_DRAW_SAMPLER_MIN_LOD_SHIFT;

            if (depth_func > GPU_DEPTH_FUNC_ALWAYS ||
                cull > GPU_CULL_FRONT ||
                ((record->state & OPENGPU_DRAW_STATE_TEX_ENABLE) &&
                 !texture) ||
                (record->sampler && !texture) ||
                max_mip > bound_max_mip || min_mip > max_mip)
                return -EINVAL;
        }
        if (!shader) {
            if (record->shader_pc || record->kernarg || record->sampler ||
                record->kernarg_bank_stride)
                return -EINVAL;
            continue;
        }
        kernarg_min = 9ull * 4ull * opengpu_fragment_batch_capacity(gpu);
        kernarg_span = kernarg_min;
        shader_kernarg_size = kernarg->size - record->kernarg;
        if (record->kernarg_bank_stride) {
            if ((record->kernarg & (GPU_KERNARG_BANK_ALIGN - 1)) ||
                (record->kernarg_bank_stride &
                 (GPU_KERNARG_BANK_ALIGN - 1)) ||
                record->kernarg_bank_stride < kernarg_min ||
                check_mul_overflow((u64)record->kernarg_bank_stride,
                                   (u64)GPU_KERNARG_BANKS,
                                   &kernarg_span))
                return -EINVAL;
            shader_kernarg_size = record->kernarg_bank_stride;
        }
        if (!kernarg_min ||
            check_add_overflow((u64)record->shader_pc, 4ull, &address) ||
            address > shader->size ||
            check_add_overflow((u64)record->kernarg,
                               kernarg_span, &address) ||
            address > kernarg->size ||
            !opengpu_validate_shader(gpu, shader,
                                     shader_kernarg_size,
                                     record->shader_pc, texture != NULL))
            return -EINVAL;
        record->shader_pc += lower_32_bits(shader->dma);
        record->kernarg += lower_32_bits(kernarg->dma);
    }
    return 0;
}

static int opengpu_prepare_submit_objects(
    struct drm_exec *exec, struct drm_gem_object *command,
    struct drm_gem_object *color,
    struct opengpu_resource_binding *shader,
    struct opengpu_resource_binding *kernarg,
    struct opengpu_resource_binding *texture)
{
    int ret;

    drm_exec_until_all_locked(exec) {
        ret = drm_exec_lock_obj(exec, command);
        if (!ret)
            ret = drm_exec_prepare_obj(exec, color, 1);
        if (!ret && shader)
            ret = drm_exec_prepare_obj(exec, shader->object, 1);
        if (!ret && kernarg)
            ret = drm_exec_prepare_obj(exec, kernarg->object, 1);
        if (!ret && texture)
            ret = drm_exec_prepare_obj(exec, texture->object, 1);
        drm_exec_retry_on_contention(exec);
        if (ret)
            return ret;
    }
    return 0;
}

static int opengpu_wait_reservation(struct drm_gem_object *object,
                                    enum dma_resv_usage usage)
{
    long wait;

    if (!object)
        return 0;
    wait = dma_resv_wait_timeout(object->resv, usage, true,
                                 MAX_SCHEDULE_TIMEOUT);
    if (wait < 0)
        return wait;
    return wait ? 0 : -ETIMEDOUT;
}

static struct opengpu_sched_job *
opengpu_sched_job_from_base(struct drm_sched_job *base)
{
    return container_of(base, struct opengpu_sched_job, base);
}

static struct dma_fence *opengpu_sched_run_job(struct drm_sched_job *base)
{
    struct opengpu_sched_job *job = opengpu_sched_job_from_base(base);
    struct dma_fence *fence;
    int ret;

    ret = opengpu_hw_submit_async(job->gpu, &job->hw, &fence);
    if (ret)
        return ERR_PTR(ret);
    return fence;
}

static enum drm_gpu_sched_stat
opengpu_sched_timedout_job(struct drm_sched_job *base)
{
    struct opengpu_sched_job *job = opengpu_sched_job_from_base(base);

    dev_err(job->gpu->dev, "render scheduler timeout\n");
    opengpu_hw_abort(job->gpu, -ETIMEDOUT);
    return DRM_GPU_SCHED_STAT_RESET;
}

static void opengpu_sched_free_job(struct drm_sched_job *base)
{
    struct opengpu_sched_job *job = opengpu_sched_job_from_base(base);
    u32 i;

    drm_sched_job_cleanup(base);
    for (i = 0; i < job->object_count; i++)
        drm_gem_object_put(job->objects[i]);
    opengpu_buffer_free(job->gpu, &job->depth);
    opengpu_buffer_free(job->gpu, &job->shader);
    opengpu_buffer_free(job->gpu, &job->commands);
    kfree(job);
}

static const struct drm_sched_backend_ops opengpu_sched_ops = {
    .run_job = opengpu_sched_run_job,
    .timedout_job = opengpu_sched_timedout_job,
    .free_job = opengpu_sched_free_job,
};

int opengpu_compute_drm_ioctl(struct drm_device *drm, void *data,
                              struct drm_file *file)
{
    struct drm_opengpu_submit *args = data;
    struct opengpu_device *gpu = dev_get_drvdata(drm->dev);
    struct opengpu_file *render_file = file->driver_priv;
    struct opengpu_render_context *context;
    struct drm_gem_object *command_object;
    struct drm_gem_object *color_object;
    struct drm_gem_dma_object *command_dma;
    struct drm_gem_dma_object *color_dma;
    struct opengpu_resource_binding *shader, *kernarg, *texture;
    struct opengpu_sched_job *sched_job = NULL;
    struct drm_syncobj *out_sync = NULL;
    struct drm_exec exec;
    struct dma_fence *fence = NULL;
    u64 command_end;
    size_t command_bytes;
    size_t color_required;
    bool sched_initialized = false;
    int ret;

    if ((args->flags & ~OPENGPU_SUBMIT_TEST_FENCE_DELAY) ||
        !args->context_id || !args->command_handle || !args->color_handle ||
        args->command_handle == args->color_handle ||
        !args->command_count || args->command_count > OPENGPU_MAX_COMMANDS ||
        (args->command_offset & 3) || args->pad ||
        args->shader_slot > OPENGPU_MAX_RESOURCE_SLOTS ||
        args->kernarg_slot > OPENGPU_MAX_RESOURCE_SLOTS ||
        args->texture_slot > OPENGPU_MAX_RESOURCE_SLOTS ||
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
    if (args->out_syncobj) {
        out_sync = drm_syncobj_find(file, args->out_syncobj);
        if (!out_sync) {
            ret = -ENOENT;
            goto out_color_object;
        }
    }

    mutex_lock(&render_file->lock);
    context = idr_find(&render_file->contexts, args->context_id);
    if (!context) {
        ret = -ENOENT;
        goto out_file;
    }
    ret = opengpu_resolve_bindings(gpu, context, args, &shader, &kernarg,
                                   &texture);
    if (ret)
        goto out_file;
    if (command_object == color_object ||
        (shader && (shader->object == command_object ||
                    shader->object == color_object)) ||
        (kernarg && (kernarg->object == command_object ||
                     kernarg->object == color_object ||
                     (shader && kernarg->object == shader->object))) ||
        (texture && (texture->object == command_object ||
                     texture->object == color_object ||
                     (shader && texture->object == shader->object) ||
                     (kernarg && texture->object == kernarg->object)))) {
        ret = -EINVAL;
        goto out_file;
    }

    drm_exec_init(&exec, DRM_EXEC_INTERRUPTIBLE_WAIT |
                         DRM_EXEC_IGNORE_DUPLICATES, 0);
    ret = opengpu_prepare_submit_objects(&exec, command_object, color_object,
                                         shader, kernarg, texture);
    if (ret)
        goto out_exec;
    /* Commands are snapshotted by the CPU, so a previous writer must finish
     * before validation. Device-side BO dependencies remain asynchronous. */
    ret = opengpu_wait_reservation(command_object, DMA_RESV_USAGE_WRITE);
    if (!ret && shader)
        ret = opengpu_wait_reservation(shader->object,
                                       DMA_RESV_USAGE_WRITE);
    if (ret)
        goto out_exec;

    sched_job = kzalloc(sizeof(*sched_job), GFP_KERNEL);
    if (!sched_job) {
        ret = -ENOMEM;
        goto out_exec;
    }
    sched_job->gpu = gpu;
    ret = opengpu_buffer_alloc(gpu, &sched_job->commands, command_bytes);
    if (ret)
        goto out_job;
    if (shader) {
        struct drm_gem_dma_object *shader_dma;

        shader_dma = to_drm_gem_dma_obj(shader->object);
        if (!shader_dma->vaddr) {
            ret = -EINVAL;
            goto out_job;
        }
        ret = opengpu_buffer_alloc(gpu, &sched_job->shader, shader->size);
        if (ret)
            goto out_job;
        memcpy(sched_job->shader.cpu,
               (const u8 *)shader_dma->vaddr + shader->offset,
               shader->size);
    }
    ret = opengpu_buffer_alloc(gpu, &sched_job->depth,
                               (size_t)gpu->stride * gpu->height);
    if (ret)
        goto out_job;

    memcpy(sched_job->commands.cpu,
           (u8 *)command_dma->vaddr + args->command_offset,
           command_bytes);
    ret = opengpu_validate_commands(gpu, &sched_job->commands, args,
                                    shader ? &sched_job->shader : NULL,
                                    kernarg, texture);
    if (ret)
        goto out_job;
    memset(sched_job->depth.cpu, 0xff, sched_job->depth.size);
    sched_job->hw = (struct opengpu_job) {
        .cmd = sched_job->commands.dma,
        .cmd_count = args->command_count,
        .color = color_dma->dma_addr,
        .depth = sched_job->depth.dma,
        .stride = args->stride,
        .depth_test = true,
        .depth_func = GPU_DEPTH_FUNC_LESS,
        .depth_write = true,
        .cull_mode = GPU_CULL_NONE,
        .texture = texture ? texture->dma : 0,
        .texture_width = texture ? texture->width : 0,
        .texture_height = texture ? texture->height : 0,
        .texture_config = texture ? GPU_TEX_ENABLE |
            (texture->flags & OPENGPU_RESOURCE_TEXTURE_CLAMP ?
             GPU_TEX_WRAP_CLAMP : 0) |
            (((texture->flags & OPENGPU_RESOURCE_TEXTURE_MAX_MIP_MASK) >>
              OPENGPU_RESOURCE_TEXTURE_MAX_MIP_SHIFT) <<
             GPU_TEX_MAX_MIP_SHIFT) : 0,
        .completion_delay_ms =
            args->flags & OPENGPU_SUBMIT_TEST_FENCE_DELAY ? 50 : 0,
    };

    ret = drm_sched_job_init(&sched_job->base, &context->entity, 1,
                             render_file, file->client_id);
    if (ret)
        goto out_job;
    sched_initialized = true;
    if (args->in_syncobj)
        ret = drm_sched_job_add_syncobj_dependency(&sched_job->base, file,
                                                    args->in_syncobj, 0);
    if (!ret)
        ret = drm_sched_job_add_resv_dependencies(&sched_job->base,
                                                   color_object->resv,
                                                   DMA_RESV_USAGE_READ);
    if (!ret && kernarg)
        ret = drm_sched_job_add_resv_dependencies(&sched_job->base,
                                                   kernarg->object->resv,
                                                   DMA_RESV_USAGE_READ);
    if (!ret && texture)
        ret = drm_sched_job_add_resv_dependencies(&sched_job->base,
                                                   texture->object->resv,
                                                   DMA_RESV_USAGE_WRITE);
    if (ret)
        goto out_job;

    drm_gem_object_get(color_object);
    sched_job->objects[sched_job->object_count++] = color_object;
    if (kernarg) {
        drm_gem_object_get(kernarg->object);
        sched_job->objects[sched_job->object_count++] = kernarg->object;
    }
    if (texture) {
        drm_gem_object_get(texture->object);
        sched_job->objects[sched_job->object_count++] = texture->object;
    }

    drm_sched_job_arm(&sched_job->base);
    fence = dma_fence_get(&sched_job->base.s_fence->finished);
    dma_fence_put(context->last_fence);
    context->last_fence = dma_fence_get(fence);
    dma_resv_add_fence(color_object->resv, fence, DMA_RESV_USAGE_WRITE);
    if (kernarg)
        dma_resv_add_fence(kernarg->object->resv, fence,
                           DMA_RESV_USAGE_WRITE);
    if (texture)
        dma_resv_add_fence(texture->object->resv, fence,
                           DMA_RESV_USAGE_READ);
    if (out_sync)
        drm_syncobj_replace_fence(out_sync, fence);
    drm_sched_entity_push_job(&sched_job->base);
    sched_job = NULL;
    dev_info(gpu->dev,
             "context %u queued %u draw(s), fence %llu on GEM handle %u\n",
             context->id, args->command_count, fence->seqno,
             args->color_handle);
    dma_fence_put(fence);
    fence = NULL;
    ret = 0;
    goto out_exec;

out_job:
    if (sched_initialized)
        drm_sched_job_cleanup(&sched_job->base);
    if (sched_job) {
        opengpu_buffer_free(gpu, &sched_job->depth);
        opengpu_buffer_free(gpu, &sched_job->shader);
        opengpu_buffer_free(gpu, &sched_job->commands);
        kfree(sched_job);
    }
out_exec:
    drm_exec_fini(&exec);
out_file:
    mutex_unlock(&render_file->lock);
    if (out_sync)
        drm_syncobj_put(out_sync);
out_color_object:
    drm_gem_object_put(color_object);
out_command_object:
    drm_gem_object_put(command_object);
    return ret;
}

static int opengpu_self_test(struct opengpu_device *gpu)
{
    u32 x, y, rgb = 0;
    int ret;

    ret = opengpu_submit_test(gpu);
    if (ret) {
        dev_err(gpu->dev, "OPENGPU DRIVER FAIL: draw did not complete\n");
        return ret;
    }

    for (y = 0; y < gpu->height; y++) {
        u32 *row = gpu->compute.color.cpu + y * gpu->stride;

        for (x = 0; x < gpu->width; x++) {
            rgb = (row[x] >> 8) & 0x00ffffffu;
            if (rgb == 0x00ff0000u) {
                dev_info(gpu->dev,
                         "OPENGPU DRIVER PASS: draw submitted and read back\n");
                return 0;
            }
        }
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
    const struct drm_sched_init_args sched_args = {
        .ops = &opengpu_sched_ops,
        .num_rqs = DRM_SCHED_PRIORITY_COUNT,
        .credit_limit = 1,
        .hang_limit = 0,
        .timeout = msecs_to_jiffies(OPENGPU_DRAW_WAIT_MS + 250),
        .name = "opengpu-render",
        .dev = gpu->dev,
    };
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
    if (gpu->hw.capabilities & GPU_CAP_FRAGMENT_CORE) {
        ret = opengpu_init_test_shader(gpu);
        if (ret)
            goto err_depth;
    }

    ret = opengpu_self_test(gpu);
    if (ret)
        goto err_shader;
    ret = drm_sched_init(&compute->scheduler, &sched_args);
    if (ret)
        goto err_shader;
    compute->misc = (struct miscdevice) {
        .minor = MISC_DYNAMIC_MINOR,
        .name = OPENGPU_COMPUTE_NAME,
        .mode = 0666,
        .fops = &opengpu_compute_fops,
        .parent = gpu->dev,
    };
    ret = misc_register(&compute->misc);
    if (ret)
        goto err_scheduler;
    return 0;

err_scheduler:
    drm_sched_fini(&compute->scheduler);
err_shader:
    opengpu_buffer_free(gpu, &compute->kernarg);
    opengpu_buffer_free(gpu, &compute->shader);
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
    drm_sched_fini(&gpu->compute.scheduler);
    opengpu_buffer_free(gpu, &gpu->compute.kernarg);
    opengpu_buffer_free(gpu, &gpu->compute.shader);
    opengpu_buffer_free(gpu, &gpu->compute.depth);
    opengpu_buffer_free(gpu, &gpu->compute.color);
    opengpu_buffer_free(gpu, &gpu->compute.cmd);
}
