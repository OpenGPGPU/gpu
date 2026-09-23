/* SPDX-License-Identifier: MIT */
#include "pipe_opengpu.h"
#include <errno.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#define PIPE_FS_SLOT 2u
#define PIPE_KERNARG_SLOT 3u
#define PIPE_FS_GEM_BYTES 128u
#define PIPE_KERNARG_BYTES 288u
#define PIPE_CS_SLOT 1u
#define PIPE_CS_KERNARG_SLOT 2u
#define PIPE_CS_GEM_BYTES 64u
#define PIPE_CS_KERNARG_BYTES 64u

struct pipe_opengpu_screen {
    int fd;
    uint64_t caps;
};

struct pipe_opengpu_resource {
    struct opengpu_buffer buffer;
};

struct pipe_opengpu_fence {
    int fd;
    uint32_t handle;
};

struct pipe_opengpu_context {
    struct pipe_opengpu_screen *screen;
    uint32_t context_id;
    struct pipe_opengpu_resource *color;
    uint32_t fb_width;
    uint32_t fb_height;
    struct opengpu_buffer shader;
    struct opengpu_buffer kernarg;
    int fs_bound;
    int cs_bound;
};

static struct pipe_opengpu_fence *alloc_fence(int fd);
static int finish_or_hand_off(struct pipe_opengpu_context *ctx,
                              struct pipe_opengpu_fence *fence,
                              struct pipe_opengpu_fence **out_fence);

struct pipe_opengpu_screen *pipe_opengpu_screen_create(const char *path)
{
    struct pipe_opengpu_screen *screen;
    int fd;

    screen = calloc(1, sizeof(*screen));
    if (!screen) {
        errno = ENOMEM;
        return NULL;
    }
    fd = opengpu_open(path);
    if (fd < 0) {
        free(screen);
        return NULL;
    }
    if (opengpu_capabilities(fd, &screen->caps)) {
        int saved = errno;
        close(fd);
        free(screen);
        errno = saved;
        return NULL;
    }
    screen->fd = fd;
    return screen;
}

void pipe_opengpu_screen_destroy(struct pipe_opengpu_screen *screen)
{
    if (!screen)
        return;
    if (screen->fd >= 0)
        close(screen->fd);
    free(screen);
}

int pipe_opengpu_screen_fd(const struct pipe_opengpu_screen *screen)
{
    return screen ? screen->fd : -1;
}

uint64_t pipe_opengpu_screen_capabilities(const struct pipe_opengpu_screen *screen)
{
    return screen ? screen->caps : 0;
}

struct pipe_opengpu_context *pipe_opengpu_context_create(
    struct pipe_opengpu_screen *screen)
{
    struct pipe_opengpu_context *ctx;

    if (!screen) {
        errno = EINVAL;
        return NULL;
    }
    ctx = calloc(1, sizeof(*ctx));
    if (!ctx) {
        errno = ENOMEM;
        return NULL;
    }
    ctx->screen = screen;
    if (opengpu_context_create(screen->fd, &ctx->context_id)) {
        free(ctx);
        return NULL;
    }
    return ctx;
}

void pipe_opengpu_context_destroy(struct pipe_opengpu_context *ctx)
{
    if (!ctx)
        return;
    if (ctx->fs_bound) {
        opengpu_unbind(ctx->screen->fd, ctx->context_id, PIPE_FS_SLOT);
        opengpu_unbind(ctx->screen->fd, ctx->context_id, PIPE_KERNARG_SLOT);
    }
    if (ctx->cs_bound) {
        opengpu_unbind(ctx->screen->fd, ctx->context_id, PIPE_CS_SLOT);
        opengpu_unbind(ctx->screen->fd, ctx->context_id, PIPE_CS_KERNARG_SLOT);
    }
    if (ctx->shader.handle)
        opengpu_buffer_destroy(ctx->screen->fd, &ctx->shader);
    if (ctx->kernarg.handle)
        opengpu_buffer_destroy(ctx->screen->fd, &ctx->kernarg);
    if (ctx->context_id)
        opengpu_context_destroy(ctx->screen->fd, ctx->context_id);
    free(ctx);
}

struct pipe_opengpu_resource *pipe_opengpu_resource_create(
    struct pipe_opengpu_screen *screen, uint32_t bytes)
{
    struct pipe_opengpu_resource *res;

    if (!screen || !bytes) {
        errno = EINVAL;
        return NULL;
    }
    res = calloc(1, sizeof(*res));
    if (!res) {
        errno = ENOMEM;
        return NULL;
    }
    if (opengpu_buffer_create(screen->fd, bytes, &res->buffer)) {
        free(res);
        return NULL;
    }
    return res;
}

void *pipe_opengpu_resource_map(struct pipe_opengpu_resource *res)
{
    return res ? res->buffer.map : NULL;
}

uint64_t pipe_opengpu_resource_size(const struct pipe_opengpu_resource *res)
{
    return res ? res->buffer.size : 0;
}

void pipe_opengpu_resource_destroy(struct pipe_opengpu_screen *screen,
                                   struct pipe_opengpu_resource *res)
{
    if (!screen || !res)
        return;
    if (res->buffer.handle)
        opengpu_buffer_destroy(screen->fd, &res->buffer);
    free(res);
}

void pipe_opengpu_set_framebuffer(struct pipe_opengpu_context *ctx,
                                  struct pipe_opengpu_resource *color,
                                  uint32_t width, uint32_t height)
{
    if (!ctx)
        return;
    ctx->color = color;
    ctx->fb_width = width;
    ctx->fb_height = height;
}

int pipe_opengpu_bind_fs(struct pipe_opengpu_context *ctx,
                         const void *code, size_t bytes)
{
    struct drm_opengpu_resource binding = { 0 };
    int fd;

    if (!ctx) {
        errno = EINVAL;
        return -1;
    }
    fd = ctx->screen->fd;
    if (ctx->cs_bound) {
        opengpu_unbind(fd, ctx->context_id, PIPE_CS_SLOT);
        opengpu_unbind(fd, ctx->context_id, PIPE_CS_KERNARG_SLOT);
        ctx->cs_bound = 0;
    }
    if (ctx->fs_bound) {
        opengpu_unbind(fd, ctx->context_id, PIPE_FS_SLOT);
        opengpu_unbind(fd, ctx->context_id, PIPE_KERNARG_SLOT);
        ctx->fs_bound = 0;
    }
    if (ctx->shader.handle) {
        opengpu_buffer_destroy(fd, &ctx->shader);
        memset(&ctx->shader, 0, sizeof(ctx->shader));
    }
    if (ctx->kernarg.handle) {
        opengpu_buffer_destroy(fd, &ctx->kernarg);
        memset(&ctx->kernarg, 0, sizeof(ctx->kernarg));
    }
    if (!code) {
        if (ctx->screen->caps & OPENGPU_CAP_FRAGMENT_CORE) {
            errno = EINVAL;
            return -1;
        }
        return 0;
    }
    if (!(ctx->screen->caps & OPENGPU_CAP_FRAGMENT_CORE) ||
        bytes == 0 || bytes > PIPE_FS_GEM_BYTES || (bytes & 3)) {
        errno = EINVAL;
        return -1;
    }
    if (opengpu_buffer_create(fd, PIPE_FS_GEM_BYTES, &ctx->shader) ||
        opengpu_buffer_create(fd, PIPE_KERNARG_BYTES, &ctx->kernarg))
        return -1;
    memcpy(ctx->shader.map, code, bytes);
    binding.context_id = ctx->context_id;
    binding.slot = PIPE_FS_SLOT;
    binding.handle = ctx->shader.handle;
    binding.type = OPENGPU_RESOURCE_SHADER;
    binding.size = PIPE_FS_GEM_BYTES;
    if (opengpu_bind(fd, &binding))
        return -1;
    binding.slot = PIPE_KERNARG_SLOT;
    binding.handle = ctx->kernarg.handle;
    binding.type = OPENGPU_RESOURCE_KERNARG;
    binding.size = PIPE_KERNARG_BYTES;
    binding.flags = OPENGPU_RESOURCE_UNCACHED;
    if (opengpu_bind(fd, &binding))
        return -1;
    ctx->fs_bound = 1;
    return 0;
}

int pipe_opengpu_bind_cs(struct pipe_opengpu_context *ctx,
                         const void *code, size_t bytes)
{
    struct drm_opengpu_resource binding = { 0 };
    int fd;

    if (!ctx || !code || bytes == 0 || bytes > PIPE_CS_GEM_BYTES || (bytes & 3)) {
        errno = EINVAL;
        return -1;
    }
    fd = ctx->screen->fd;
    if (ctx->fs_bound) {
        opengpu_unbind(fd, ctx->context_id, PIPE_FS_SLOT);
        opengpu_unbind(fd, ctx->context_id, PIPE_KERNARG_SLOT);
        ctx->fs_bound = 0;
    }
    if (ctx->cs_bound) {
        opengpu_unbind(fd, ctx->context_id, PIPE_CS_SLOT);
        opengpu_unbind(fd, ctx->context_id, PIPE_CS_KERNARG_SLOT);
        ctx->cs_bound = 0;
    }
    if (ctx->shader.handle) {
        opengpu_buffer_destroy(fd, &ctx->shader);
        memset(&ctx->shader, 0, sizeof(ctx->shader));
    }
    if (ctx->kernarg.handle) {
        opengpu_buffer_destroy(fd, &ctx->kernarg);
        memset(&ctx->kernarg, 0, sizeof(ctx->kernarg));
    }
    if (opengpu_buffer_create(fd, PIPE_CS_GEM_BYTES, &ctx->shader) ||
        opengpu_buffer_create(fd, PIPE_CS_KERNARG_BYTES, &ctx->kernarg))
        return -1;
    memcpy(ctx->shader.map, code, bytes);
    memset(ctx->kernarg.map, 0, PIPE_CS_KERNARG_BYTES);
    binding.context_id = ctx->context_id;
    binding.slot = PIPE_CS_SLOT;
    binding.handle = ctx->shader.handle;
    binding.type = OPENGPU_RESOURCE_COMPUTE_SHADER;
    binding.size = PIPE_CS_GEM_BYTES;
    if (opengpu_bind(fd, &binding))
        return -1;
    binding.slot = PIPE_CS_KERNARG_SLOT;
    binding.handle = ctx->kernarg.handle;
    binding.type = OPENGPU_RESOURCE_COMPUTE_KERNARG;
    binding.size = PIPE_CS_KERNARG_BYTES;
    binding.flags = OPENGPU_RESOURCE_UNCACHED;
    if (opengpu_bind(fd, &binding))
        return -1;
    ctx->cs_bound = 1;
    return 0;
}

void *pipe_opengpu_cs_kernarg_map(struct pipe_opengpu_context *ctx)
{
    if (!ctx || !ctx->cs_bound)
        return NULL;
    return ctx->kernarg.map;
}

int pipe_opengpu_launch_grid(struct pipe_opengpu_context *ctx,
                             const uint32_t grid[3], const uint32_t local[3],
                             struct pipe_opengpu_fence **out_fence)
{
    struct drm_opengpu_compute command = { 0 };
    struct pipe_opengpu_fence *fence;

    if (!ctx || !ctx->cs_bound || !grid || !local) {
        errno = EINVAL;
        return -1;
    }
    fence = alloc_fence(ctx->screen->fd);
    if (!fence)
        return -1;
    command.context_id = ctx->context_id;
    command.shader_slot = PIPE_CS_SLOT;
    command.kernarg_slot = PIPE_CS_KERNARG_SLOT;
    command.grid[0] = grid[0];
    command.grid[1] = grid[1];
    command.grid[2] = grid[2];
    command.local[0] = local[0];
    command.local[1] = local[1];
    command.local[2] = local[2];
    command.out_syncobj = fence->handle;
    if (opengpu_compute(ctx->screen->fd, &command)) {
        pipe_opengpu_fence_reference(&fence, NULL);
        return -1;
    }
    return finish_or_hand_off(ctx, fence, out_fence);
}

static struct pipe_opengpu_fence *alloc_fence(int fd)
{
    struct pipe_opengpu_fence *fence = calloc(1, sizeof(*fence));

    if (!fence) {
        errno = ENOMEM;
        return NULL;
    }
    if (opengpu_sync_create(fd, &fence->handle)) {
        free(fence);
        return NULL;
    }
    fence->fd = fd;
    return fence;
}

static int finish_or_hand_off(struct pipe_opengpu_context *ctx,
                              struct pipe_opengpu_fence *fence,
                              struct pipe_opengpu_fence **out_fence)
{
    if (out_fence) {
        *out_fence = fence;
        return 0;
    }
    if (pipe_opengpu_fence_finish(ctx, fence, 30000)) {
        pipe_opengpu_fence_reference(&fence, NULL);
        return -1;
    }
    pipe_opengpu_fence_reference(&fence, NULL);
    return 0;
}

int pipe_opengpu_clear(struct pipe_opengpu_context *ctx, uint32_t pattern,
                       struct pipe_opengpu_fence **out_fence)
{
    struct drm_opengpu_fill fill = { 0 };
    struct pipe_opengpu_fence *fence;
    uint64_t bytes;

    if (!ctx || !ctx->color || !ctx->fb_width || !ctx->fb_height) {
        errno = EINVAL;
        return -1;
    }
    bytes = (uint64_t)ctx->fb_width * ctx->fb_height * 4u;
    if ((bytes & 63u) || bytes > ctx->color->buffer.size) {
        errno = EINVAL;
        return -1;
    }
    fence = alloc_fence(ctx->screen->fd);
    if (!fence)
        return -1;
    fill.context_id = ctx->context_id;
    fill.destination_handle = ctx->color->buffer.handle;
    fill.pattern = pattern;
    fill.bytes = bytes;
    fill.out_syncobj = fence->handle;
    if (opengpu_fill(ctx->screen->fd, &fill)) {
        pipe_opengpu_fence_reference(&fence, NULL);
        return -1;
    }
    return finish_or_hand_off(ctx, fence, out_fence);
}

int pipe_opengpu_draw_vbo(struct pipe_opengpu_context *ctx,
                          const struct drm_opengpu_draw *draw,
                          struct pipe_opengpu_fence **out_fence)
{
    struct opengpu_buffer commands = { 0 };
    struct drm_opengpu_submit submit = { 0 };
    struct pipe_opengpu_fence *fence = NULL;
    int fd, status = -1;

    if (!ctx || !draw || !ctx->color || !ctx->fb_width) {
        errno = EINVAL;
        return -1;
    }
    if ((ctx->screen->caps & OPENGPU_CAP_FRAGMENT_CORE) && !ctx->fs_bound) {
        errno = EINVAL;
        return -1;
    }
    if (!(ctx->screen->caps & OPENGPU_CAP_FRAGMENT_CORE) && ctx->fs_bound) {
        errno = EINVAL;
        return -1;
    }
    fd = ctx->screen->fd;
    fence = alloc_fence(fd);
    if (!fence)
        return -1;
    if (opengpu_buffer_create(fd, sizeof(struct drm_opengpu_draw), &commands))
        goto out;
    memcpy(commands.map, draw, sizeof(*draw));
    submit.context_id = ctx->context_id;
    submit.command_handle = commands.handle;
    submit.color_handle = ctx->color->buffer.handle;
    submit.stride = ctx->fb_width * 4u;
    submit.command_count = 1;
    submit.out_syncobj = fence->handle;
    if (ctx->fs_bound) {
        submit.shader_slot = PIPE_FS_SLOT;
        submit.kernarg_slot = PIPE_KERNARG_SLOT;
    }
    if (opengpu_render(fd, &submit))
        goto out;
    if (finish_or_hand_off(ctx, fence, out_fence))
        goto out;
    fence = NULL;
    status = 0;
out:
    if (fence)
        pipe_opengpu_fence_reference(&fence, NULL);
    if (commands.handle)
        opengpu_buffer_destroy(fd, &commands);
    return status;
}

int pipe_opengpu_fence_finish(struct pipe_opengpu_context *ctx,
                              struct pipe_opengpu_fence *fence,
                              int64_t timeout_ms)
{
    (void)ctx;
    if (!fence) {
        errno = EINVAL;
        return -1;
    }
    return opengpu_sync_wait_success(fence->fd, fence->handle, timeout_ms);
}

void pipe_opengpu_fence_reference(struct pipe_opengpu_fence **dst,
                                  struct pipe_opengpu_fence *src)
{
    struct pipe_opengpu_fence *old;

    if (!dst)
        return;
    old = *dst;
    if (old == src)
        return;
    *dst = src;
    if (old) {
        opengpu_sync_destroy(old->fd, old->handle);
        free(old);
    }
}
