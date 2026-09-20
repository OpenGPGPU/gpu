/* SPDX-License-Identifier: GPL-2.0 */
#ifndef OPENGPU_SCHEDULER_H
#define OPENGPU_SCHEDULER_H
#include <linux/dma-resv.h>
#include <linux/idr.h>
#include <drm/drm_file.h>
#include <drm/drm_gem.h>
#include <drm/drm_syncobj.h>
#include "opengpu_device.h"
#include "opengpu_drm.h"

struct opengpu_render_context {
    u32 id;
    struct drm_sched_entity entity;
    struct dma_fence *last_fence;
    struct opengpu_resource_binding *bindings[OPENGPU_MAX_RESOURCE_SLOTS];
    /* Address space every job from this context runs in.  Disabled on
     * builds without the MMU control path (Bare), which then use ASID 0. */
    struct opengpu_vm vm;
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
    /* Private virtual address in this context's VM that `dma` is mapped at;
     * 0 means the binding is only reachable at its physical address. */
    dma_addr_t va;
};

enum opengpu_sched_job_type {
    OPENGPU_SCHED_RENDER,
    OPENGPU_SCHED_COMPUTE,
    OPENGPU_SCHED_BLIT,
    OPENGPU_SCHED_FILL,
    OPENGPU_SCHED_STRIDED_BLIT,
    OPENGPU_SCHED_RESOLVE,
    OPENGPU_SCHED_INVALIDATE,
};

struct opengpu_sched_job {
    struct drm_sched_job base;
    struct opengpu_device *gpu;
    enum opengpu_sched_job_type type;
    /* Address space of the submitting context, or NULL for the global ASID-0
     * identity map (bring-up self-test). */
    const struct opengpu_vm *vm;
    /* Owning context, for mappings that must be (re)established at run time
     * once the previous job has drained (per-job command snapshot). */
    struct opengpu_render_context *context;
    dma_addr_t dma_source;
    dma_addr_t dma_destination;
    u32 dma_bytes;
    u32 dma_height;
    u32 dma_source_stride;
    u32 dma_destination_stride;
    u32 dma_sample_mode;
    u32 fill_pattern;
    struct opengpu_command_events events;
    struct opengpu_kernel_launch kernel;
    struct opengpu_job hw;
    struct opengpu_buffer commands;
    /* 16-word unified render descriptor, filled at run time and submitted by
     * virtual address so the device fetches it through the context VM. */
    struct opengpu_buffer render_desc;
    struct opengpu_buffer shader;
    struct opengpu_buffer vertex_shader;
    struct opengpu_buffer depth;
    /* Depth-plane clear staging. A user-supplied persistent attachment is not
     * owned by the job (so `depth` stays empty), but the pass-start clear and
     * the CPU fallback still target its range. */
    bool depth_user;
    bool depth_load;
    dma_addr_t depth_clear_dma;
    size_t depth_clear_bytes;
    u8 *depth_clear_cpu;
    struct drm_gem_object *objects[6];
    u32 object_count;
};

struct opengpu_file {
    struct opengpu_device *gpu;
    struct mutex lock;
    struct idr contexts;
};

/* One scheduler job dependency: the object the job waits on, the usage it
 * waits for and the usage its completion fence is published under.  The wait
 * and signal usages differ deliberately - a blit waits on source writes yet
 * publishes a source read fence, and waits on destination reads yet publishes
 * a destination write fence. */
struct opengpu_sched_dep {
    struct drm_gem_object *object;
    enum dma_resv_usage wait_usage;
    enum dma_resv_usage signal_usage;
};

int opengpu_sched_init(struct opengpu_device *gpu);
void opengpu_sched_fini(struct opengpu_device *gpu);
struct dma_fence *opengpu_job_run(struct opengpu_sched_job *job);
int opengpu_sched_job_submit(
    struct opengpu_file *render_file, struct drm_file *file,
    struct opengpu_render_context *context, struct opengpu_sched_job *job,
    u32 in_syncobj, struct drm_syncobj *out_sync,
    const struct opengpu_sched_dep *deps, u32 dep_count, u64 *fence_seqno);
#endif
