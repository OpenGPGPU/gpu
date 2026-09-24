/* SPDX-License-Identifier: MIT */
/* Gallium spike: 2x MSAA draw then resolve through pipe_opengpu. */
#include "../pipe_opengpu.h"
#include <errno.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

enum { MODE = 1 }; /* 2x */

static void fill_triangle(struct drm_opengpu_draw *draw)
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
    draw->d0 = draw->d1 = draw->d2 = 0x10;
}

int main(int argc, char **argv)
{
    struct pipe_opengpu_screen *screen = NULL;
    struct pipe_opengpu_context *ctx = NULL;
    struct pipe_opengpu_resource *msaa = NULL, *resolved = NULL;
    struct pipe_opengpu_fence *fence = NULL;
    struct drm_opengpu_draw draw;
    uint32_t samples = 1u << MODE;
    uint32_t width = 0, height = 0, src_stride, dst_stride;
    uint64_t caps;
    uint32_t max_mode;
    unsigned painted = 0, empty = 0, i;
    int status = 1;

    screen = pipe_opengpu_screen_create(argc > 1 ? argv[1] : NULL);
    if (!screen)
        goto done;
    caps = pipe_opengpu_screen_capabilities(screen);
    if (!(caps & OPENGPU_CAP_MSAA)) {
        puts("pipe msaa_draw skipped; MSAA not advertised");
        status = 0;
        goto done;
    }
    max_mode = (uint32_t)((caps & OPENGPU_CAP_MSAA_MAX_MODE_MASK) >>
                          OPENGPU_CAP_MSAA_MAX_MODE_SHIFT);
    if (MODE > max_mode) {
        puts("pipe msaa_draw skipped; 2x above device max");
        status = 0;
        goto done;
    }
    if (caps & OPENGPU_CAP_FRAGMENT_CORE) {
        /* Keep the first MSAA pipe smoke on the fixed-function path. */
        puts("pipe msaa_draw skipped; fixed-function path only");
        status = 0;
        goto done;
    }
    if (pipe_opengpu_screen_display_size(screen, &width, &height))
        goto done;
    src_stride = width * samples * 4u;
    dst_stride = width * 4u;

    ctx = pipe_opengpu_context_create(screen);
    msaa = pipe_opengpu_resource_create(screen, src_stride * height);
    resolved = pipe_opengpu_resource_create(screen, dst_stride * height);
    if (!ctx || !msaa || !resolved)
        goto done;

    memset(pipe_opengpu_resource_map(msaa), 0, src_stride * height);
    memset(pipe_opengpu_resource_map(resolved), 0, dst_stride * height);
    pipe_opengpu_set_framebuffer(ctx, msaa, width, height);
    if (pipe_opengpu_set_sample_mode(ctx, MODE) ||
        pipe_opengpu_bind_fs(ctx, NULL, 0))
        goto done;

    if (pipe_opengpu_clear(ctx, 0u, &fence) ||
        pipe_opengpu_fence_finish(ctx, fence, 300000))
        goto done;
    pipe_opengpu_fence_reference(&fence, NULL);

    fill_triangle(&draw);
    if (pipe_opengpu_draw_vbo(ctx, &draw, &fence) ||
        pipe_opengpu_fence_finish(ctx, fence, 300000))
        goto done;
    pipe_opengpu_fence_reference(&fence, NULL);

    if (pipe_opengpu_resolve(ctx, resolved, msaa, width, height, src_stride,
                             dst_stride, MODE, &fence) ||
        pipe_opengpu_fence_finish(ctx, fence, 300000))
        goto done;
    pipe_opengpu_fence_reference(&fence, NULL);

    for (i = 0; i < width * height; i++) {
        uint32_t pixel = ((uint32_t *)pipe_opengpu_resource_map(resolved))[i];

        if (pixel == 0)
            empty++;
        else
            painted++;
    }
    if (!painted || !empty) {
        fprintf(stderr, "msaa_draw painted=%u empty=%u\n", painted, empty);
        errno = EIO;
        goto done;
    }
    printf("pipe msaa_draw completed; mode=2x painted=%u empty=%u\n", painted,
           empty);
    status = 0;
done:
    if (status)
        perror("pipe_msaa_draw");
    pipe_opengpu_fence_reference(&fence, NULL);
    if (resolved)
        pipe_opengpu_resource_destroy(screen, resolved);
    if (msaa)
        pipe_opengpu_resource_destroy(screen, msaa);
    if (ctx)
        pipe_opengpu_context_destroy(ctx);
    if (screen)
        pipe_opengpu_screen_destroy(screen);
    return status;
}
