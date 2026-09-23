/* SPDX-License-Identifier: MIT */
/* Thin Gallium-shaped winsys over userspace/opengpu.h. Not Mesa: no NIR,
 * softpipe, or upstream pipe headers. See docs/GALLIUM_SPIKE.md. */
#ifndef PIPE_OPENGPU_H
#define PIPE_OPENGPU_H

#include "opengpu.h"
#include <stddef.h>
#include <stdint.h>

struct pipe_opengpu_screen;
struct pipe_opengpu_context;
struct pipe_opengpu_resource;
struct pipe_opengpu_fence;

struct pipe_opengpu_screen *pipe_opengpu_screen_create(const char *path);
void pipe_opengpu_screen_destroy(struct pipe_opengpu_screen *screen);
int pipe_opengpu_screen_fd(const struct pipe_opengpu_screen *screen);
uint64_t pipe_opengpu_screen_capabilities(const struct pipe_opengpu_screen *screen);

struct pipe_opengpu_context *pipe_opengpu_context_create(
    struct pipe_opengpu_screen *screen);
void pipe_opengpu_context_destroy(struct pipe_opengpu_context *ctx);

struct pipe_opengpu_resource *pipe_opengpu_resource_create(
    struct pipe_opengpu_screen *screen, uint32_t bytes);
void *pipe_opengpu_resource_map(struct pipe_opengpu_resource *res);
uint64_t pipe_opengpu_resource_size(const struct pipe_opengpu_resource *res);
void pipe_opengpu_resource_destroy(struct pipe_opengpu_screen *screen,
                                   struct pipe_opengpu_resource *res);

/* Colour target for clear / draw_vbo. Width*height*4 must fit the GEM. */
void pipe_opengpu_set_framebuffer(struct pipe_opengpu_context *ctx,
                                  struct pipe_opengpu_resource *color,
                                  uint32_t width, uint32_t height);

/* Bind a validator-admitted FS binary (+ empty kernarg). Pass code=NULL for
 * fixed-function draws (no fragment core). */
int pipe_opengpu_bind_fs(struct pipe_opengpu_context *ctx,
                         const void *code, size_t bytes);

/* Fill colour GEM with a 32-bit pattern. Offset/bytes must be 64-byte aligned. */
int pipe_opengpu_clear(struct pipe_opengpu_context *ctx, uint32_t pattern,
                       struct pipe_opengpu_fence **out_fence);

/* One triangle via drm_opengpu_draw → opengpu_render (Gallium draw_vbo map). */
int pipe_opengpu_draw_vbo(struct pipe_opengpu_context *ctx,
                          const struct drm_opengpu_draw *draw,
                          struct pipe_opengpu_fence **out_fence);

int pipe_opengpu_fence_finish(struct pipe_opengpu_context *ctx,
                              struct pipe_opengpu_fence *fence,
                              int64_t timeout_ms);
void pipe_opengpu_fence_reference(struct pipe_opengpu_fence **dst,
                                  struct pipe_opengpu_fence *src);

#endif
