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

/* Optional persistent depth GEM (D24S8). Pass depth=NULL for the driver's
 * private cleared plane. load!=0 sets OPENGPU_SUBMIT_DEPTH_LOAD. */
void pipe_opengpu_set_depth(struct pipe_opengpu_context *ctx,
                            struct pipe_opengpu_resource *depth, int load);

/* Bind a validator-admitted FS binary (+ empty kernarg). Pass code=NULL for
 * fixed-function draws (no fragment core). */
int pipe_opengpu_bind_fs(struct pipe_opengpu_context *ctx,
                         const void *code, size_t bytes);

/* Bind a validator-admitted compute shader (+ uncached kernarg GEM). */
int pipe_opengpu_bind_cs(struct pipe_opengpu_context *ctx,
                         const void *code, size_t bytes);

/* Kernarg mapping after a successful bind_cs (64 bytes for the corpus). */
void *pipe_opengpu_cs_kernarg_map(struct pipe_opengpu_context *ctx);

/* Gallium launch_grid → opengpu_compute. */
int pipe_opengpu_launch_grid(struct pipe_opengpu_context *ctx,
                             const uint32_t grid[3], const uint32_t local[3],
                             struct pipe_opengpu_fence **out_fence);

/* Fill colour GEM with a 32-bit pattern. Offset/bytes must be 64-byte aligned. */
int pipe_opengpu_clear(struct pipe_opengpu_context *ctx, uint32_t pattern,
                       struct pipe_opengpu_fence **out_fence);

/* Whole-buffer blit (64-byte aligned offset/bytes). Gallium resource_copy. */
int pipe_opengpu_blit(struct pipe_opengpu_context *ctx,
                      struct pipe_opengpu_resource *dst,
                      struct pipe_opengpu_resource *src,
                      uint64_t dst_offset, uint64_t src_offset, uint64_t bytes,
                      struct pipe_opengpu_fence **out_fence);

/* 2D cache-line copy. Width and both strides are multiples of 64. */
int pipe_opengpu_strided_blit(struct pipe_opengpu_context *ctx,
                              struct pipe_opengpu_resource *dst,
                              struct pipe_opengpu_resource *src,
                              uint64_t dst_offset, uint64_t src_offset,
                              uint32_t width_bytes, uint32_t height,
                              uint32_t dst_stride, uint32_t src_stride,
                              struct pipe_opengpu_fence **out_fence);

/* Drop L2 lines so a later GPU read sees CPU writes (64-byte aligned). */
int pipe_opengpu_invalidate(struct pipe_opengpu_context *ctx,
                            struct pipe_opengpu_resource *res,
                            uint64_t offset, uint64_t bytes,
                            struct pipe_opengpu_fence **out_fence);

/* MSAA resolve (sample_mode 0/1/2). Source/dest GEMs must not overlap. */
int pipe_opengpu_resolve(struct pipe_opengpu_context *ctx,
                         struct pipe_opengpu_resource *dst,
                         struct pipe_opengpu_resource *src,
                         uint32_t width, uint32_t height,
                         uint32_t src_stride, uint32_t dst_stride,
                         uint32_t sample_mode,
                         struct pipe_opengpu_fence **out_fence);

/* Bind a texture GEM on slot 1 (width/height base; size covers the mip chain).
 * Pass tex=NULL to unbind. Own the GEM for the bind lifetime. */
int pipe_opengpu_bind_texture(struct pipe_opengpu_context *ctx,
                              struct pipe_opengpu_resource *tex,
                              uint32_t width, uint32_t height, uint32_t size,
                              uint32_t flags);

/* Bind a validator-admitted VS binary (+ empty vertex kernarg). */
int pipe_opengpu_bind_vs(struct pipe_opengpu_context *ctx,
                         const void *code, size_t bytes);

/* Bind a vertex-buffer GEM (fixed 32-byte vertex format). Pass vb=NULL to
 * unbind. */
int pipe_opengpu_set_vertex_buffer(struct pipe_opengpu_context *ctx,
                                   struct pipe_opengpu_resource *vb,
                                   uint32_t stride, uint32_t bytes);

/* One triangle via drm_opengpu_draw → opengpu_render (Gallium draw_vbo map). */
int pipe_opengpu_draw_vbo(struct pipe_opengpu_context *ctx,
                          const struct drm_opengpu_draw *draw,
                          struct pipe_opengpu_fence **out_fence);

/* Vertex-core draw via drm_opengpu_vertex_draw + OPENGPU_SUBMIT_VERTEX_CORE. */
int pipe_opengpu_draw_vertex(struct pipe_opengpu_context *ctx,
                             const struct drm_opengpu_vertex_draw *draw,
                             struct pipe_opengpu_fence **out_fence);

int pipe_opengpu_fence_finish(struct pipe_opengpu_context *ctx,
                              struct pipe_opengpu_fence *fence,
                              int64_t timeout_ms);
void pipe_opengpu_fence_reference(struct pipe_opengpu_fence **dst,
                                  struct pipe_opengpu_fence *src);

#endif
