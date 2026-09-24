/* SPDX-License-Identifier: MIT */
/* Gallium spike: persistent depth clear then DEPTH_LOAD continuation (FF). */
#include "../pipe_opengpu.h"
#include <errno.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

static void fill_triangle(struct drm_opengpu_draw *draw, int32_t depth,
                          uint32_t depth_func, uint32_t blend)
{
    memset(draw, 0, sizeof(*draw));
    draw->v0[0] = -0x10000;
    draw->v0[1] = -0x10000;
    draw->v1[0] = 0x10000;
    draw->v1[1] = -0x10000;
    draw->v2[0] = -0x10000;
    draw->v2[1] = 0x10000;
    draw->v0[3] = draw->v1[3] = draw->v2[3] = 0x10000;
    draw->c0[0] = draw->c1[0] = draw->c2[0] = 255;
    draw->c0[1] = draw->c1[1] = draw->c2[1] = 255;
    draw->c0[2] = draw->c1[2] = draw->c2[2] = 255;
    draw->d0 = draw->d1 = draw->d2 = depth;
    draw->state = OPENGPU_DRAW_STATE_OVERRIDE |
        OPENGPU_DRAW_STATE_DEPTH_TEST |
        (depth_func << OPENGPU_DRAW_STATE_DEPTH_FUNC_SHIFT) |
        OPENGPU_DRAW_STATE_DEPTH_WRITE;
    draw->blend_config = blend;
}

int main(int argc, char **argv)
{
    struct pipe_opengpu_screen *screen = NULL;
    struct pipe_opengpu_context *ctx = NULL;
    struct pipe_opengpu_resource *color = NULL, *depth = NULL;
    struct pipe_opengpu_fence *fence = NULL;
    struct drm_opengpu_draw draw;
    uint64_t caps;
    uint32_t width = 0, height = 0;
    unsigned i, written = 0, zeroed = 0;
    int status = 1;

    screen = pipe_opengpu_screen_create(argc > 1 ? argv[1] : NULL);
    if (!screen)
        goto done;
    caps = pipe_opengpu_screen_capabilities(screen);
    if (!(caps & OPENGPU_CAP_PERSISTENT_DEPTH)) {
        puts("pipe depth_pass skipped; persistent depth not advertised");
        status = 0;
        goto done;
    }
    if (caps & OPENGPU_CAP_FRAGMENT_CORE) {
        /* Shader depth makes GREATER continuation FF-specific. */
        puts("pipe depth_pass skipped; fixed-function depth test only");
        status = 0;
        goto done;
    }
    if (pipe_opengpu_screen_display_size(screen, &width, &height))
        goto done;

    ctx = pipe_opengpu_context_create(screen);
    color = pipe_opengpu_resource_create_2d(screen, width, height);
    depth = pipe_opengpu_resource_create_2d(screen, width, height);
    if (!ctx || !color || !depth)
        goto done;

    memset(pipe_opengpu_resource_map(color), 0x5a, width * height * 4u);
    pipe_opengpu_set_framebuffer(ctx, color, width, height);
    if (pipe_opengpu_bind_fs(ctx, NULL, 0))
        goto done;

    /* Pass 1: clear depth plane, store near depth 0x10. */
    pipe_opengpu_set_depth(ctx, depth, 0);
    fill_triangle(&draw, 0x10, 0 /* LESS */, 0);
    if (pipe_opengpu_draw_vbo(ctx, &draw, &fence) ||
        pipe_opengpu_fence_finish(ctx, fence, 300000))
        goto done;
    pipe_opengpu_fence_reference(&fence, NULL);

    for (i = 0; i < width * height; i++) {
        if (((uint32_t *)pipe_opengpu_resource_map(color))[i] != 0x5a5a5a5au)
            written++;
    }
    if (!written) {
        fprintf(stderr, "depth_pass first draw painted nothing\n");
        errno = EIO;
        goto done;
    }

    /* Pass 2: load depth, farther 0x20 with GREATER + REV_SUB zeroes colour. */
    pipe_opengpu_set_depth(ctx, depth, 1);
    fill_triangle(&draw, 0x20, 2 /* GREATER */,
                  OPENGPU_DRAW_BLEND_PRESENT |
                      (1u << OPENGPU_DRAW_BLEND_SRC_SHIFT) |
                      (1u << OPENGPU_DRAW_BLEND_DST_SHIFT) |
                      (2u << OPENGPU_DRAW_BLEND_EQ_SHIFT));
    if (pipe_opengpu_draw_vbo(ctx, &draw, &fence) ||
        pipe_opengpu_fence_finish(ctx, fence, 300000))
        goto done;
    pipe_opengpu_fence_reference(&fence, NULL);

    for (i = 0; i < width * height; i++) {
        if (((uint32_t *)pipe_opengpu_resource_map(color))[i] == 0)
            zeroed++;
    }
    if (zeroed != written) {
        fprintf(stderr, "depth_pass continuation zeroed=%u written=%u\n",
                zeroed, written);
        errno = EIO;
        goto done;
    }
    printf("pipe depth_pass completed; continued=%u\n", written);
    status = 0;
done:
    if (status)
        perror("pipe_depth_pass");
    pipe_opengpu_fence_reference(&fence, NULL);
    if (depth)
        pipe_opengpu_resource_destroy(screen, depth);
    if (color)
        pipe_opengpu_resource_destroy(screen, color);
    if (ctx)
        pipe_opengpu_context_destroy(ctx);
    if (screen)
        pipe_opengpu_screen_destroy(screen);
    return status;
}
