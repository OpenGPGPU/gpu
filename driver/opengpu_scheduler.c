// SPDX-License-Identifier: GPL-2.0
/* Shared scheduling, dependency publication and job retirement for every opcode. */
#include <linux/slab.h>
#include "opengpu_scheduler.h"

static struct opengpu_sched_job *
opengpu_sched_job_from_base(struct drm_sched_job *base)
{
    return container_of(base, struct opengpu_sched_job, base);
}

static struct dma_fence *opengpu_sched_run_job(struct drm_sched_job *base)
{
    return opengpu_job_run(opengpu_sched_job_from_base(base));
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
    opengpu_buffer_free(job->gpu, &job->vertex_shader);
    opengpu_buffer_free(job->gpu, &job->shader);
    opengpu_buffer_free(job->gpu, &job->render_desc);
    opengpu_buffer_free(job->gpu, &job->commands);
    kfree(job);
}

/* Initialize, populate, arm and push one scheduler job: register the optional
 * input sync object and every reservation dependency, take a job reference for
 * each dependency object, then publish the completion fence to the context,
 * each dependency reservation and the optional output sync object.  On any
 * failure after job initialization the scheduler job is torn down in place, so
 * callers only free their own staging; on success the scheduler owns the job. */
int opengpu_sched_job_submit(
    struct opengpu_file *render_file, struct drm_file *file,
    struct opengpu_render_context *context, struct opengpu_sched_job *job,
    u32 in_syncobj, struct drm_syncobj *out_sync,
    const struct opengpu_sched_dep *deps, u32 dep_count, u64 *fence_seqno)
{
    struct dma_fence *fence;
    u32 i;
    int ret;

    ret = drm_sched_job_init(&job->base, &context->entity, 1, render_file,
                             file->client_id);
    if (ret)
        return ret;
    job->context = context;
    if (in_syncobj)
        ret = drm_sched_job_add_syncobj_dependency(&job->base, file,
                                                    in_syncobj, 0);
    for (i = 0; !ret && i < dep_count; i++)
        ret = drm_sched_job_add_resv_dependencies(&job->base,
                                                   deps[i].object->resv,
                                                   deps[i].wait_usage);
    if (ret) {
        drm_sched_job_cleanup(&job->base);
        return ret;
    }

    for (i = 0; i < dep_count; i++) {
        drm_gem_object_get(deps[i].object);
        job->objects[job->object_count++] = deps[i].object;
    }
    drm_sched_job_arm(&job->base);
    fence = dma_fence_get(&job->base.s_fence->finished);
    if (fence_seqno)
        *fence_seqno = fence->seqno;
    dma_fence_put(context->last_fence);
    context->last_fence = dma_fence_get(fence);
    for (i = 0; i < dep_count; i++)
        dma_resv_add_fence(deps[i].object->resv, fence,
                           deps[i].signal_usage);
    if (out_sync)
        drm_syncobj_replace_fence(out_sync, fence);
    drm_sched_entity_push_job(&job->base);
    dma_fence_put(fence);
    return 0;
}

static const struct drm_sched_backend_ops opengpu_sched_ops = {
    .run_job = opengpu_sched_run_job,
    .timedout_job = opengpu_sched_timedout_job,
    .free_job = opengpu_sched_free_job,
};

int opengpu_sched_init(struct opengpu_device *gpu)
{
    const struct drm_sched_init_args sched_args = {
        .ops = &opengpu_sched_ops,
        .num_rqs = DRM_SCHED_PRIORITY_COUNT,
        .credit_limit = 1,
        .hang_limit = 0,
        .timeout = msecs_to_jiffies(OPENGPU_DRAW_WAIT_MS + 250),
        .name = "opengpu-render",
        .dev = gpu->dev,
    };
    return drm_sched_init(&gpu->compute.scheduler, &sched_args);
}

void opengpu_sched_fini(struct opengpu_device *gpu)
{
    drm_sched_fini(&gpu->compute.scheduler);
}
