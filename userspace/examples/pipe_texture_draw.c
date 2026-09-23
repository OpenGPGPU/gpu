/* SPDX-License-Identifier: MIT */
/* Gallium spike: bind a 4x4 mip chain and draw a textured triangle (FF). */
#include "../pipe_opengpu.h"
#include <errno.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

enum { FB_W = 16, FB_H = 16, TEX_WORDS = 21 };

static void fill_textured_triangle(struct drm_opengpu_draw *draw)
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
    draw->d0 = draw->d1 = draw->d2 = 0x10;
    /* Eight UV repeats over 16 pixels => mip 1 on a 4x4 base. */
    draw->uv0[0] = 0;
    draw->uv0[1] = 0;
    draw->uv1[0] = 0x80000;
    draw->uv1[1] = 0;
    draw->uv2[0] = 0;
    draw->uv2[1] = 0x80000;
    draw->state = OPENGPU_DRAW_STATE_OVERRIDE |
        OPENGPU_DRAW_STATE_DEPTH_TEST |
        OPENGPU_DRAW_STATE_DEPTH_WRITE |
        OPENGPU_DRAW_STATE_TEX_ENABLE |
        OPENGPU_DRAW_STATE_TEX_CLAMP |
        (2u << OPENGPU_DRAW_STATE_MAX_MIP_SHIFT);
}

int main(int argc, char **argv)
{
    struct pipe_opengpu_screen *screen = NULL;
    struct pipe_opengpu_context *ctx = NULL;
    struct pipe_opengpu_resource *color = NULL, *tex = NULL;
    struct pipe_opengpu_fence *fence = NULL;
    struct drm_opengpu_draw draw;
    uint32_t *texels;
    uint64_t caps;
    unsigned painted = 0, i;
    uint32_t sample = 0;
    int status = 1;

    screen = pipe_opengpu_screen_create(argc > 1 ? argv[1] : NULL);
    if (!screen)
        goto done;
    caps = pipe_opengpu_screen_capabilities(screen);
    if (caps & OPENGPU_CAP_FRAGMENT_CORE) {
        puts("pipe texture_draw skipped; fixed-function path only");
        status = 0;
        goto done;
    }

    ctx = pipe_opengpu_context_create(screen);
    color = pipe_opengpu_resource_create(screen, FB_W * FB_H * 4u);
    tex = pipe_opengpu_resource_create(screen, TEX_WORDS * 4u);
    if (!ctx || !color || !tex)
        goto done;

    texels = pipe_opengpu_resource_map(tex);
    for (i = 0; i < 16; i++)
        texels[i] = 0x00ff00ffu;
    for (i = 16; i < 20; i++)
        texels[i] = 0xff0000ffu;
    texels[20] = 0x0000ffffu;

    pipe_opengpu_set_framebuffer(ctx, color, FB_W, FB_H);
    if (pipe_opengpu_bind_fs(ctx, NULL, 0) ||
        pipe_opengpu_bind_texture(ctx, tex, 4, 4, TEX_WORDS * 4u,
                                  OPENGPU_RESOURCE_TEXTURE_CLAMP |
                                      (2u << OPENGPU_RESOURCE_TEXTURE_MAX_MIP_SHIFT) |
                                      OPENGPU_RESOURCE_UNCACHED))
        goto done;

    if (pipe_opengpu_clear(ctx, 0x000000ffu, &fence) ||
        pipe_opengpu_fence_finish(ctx, fence, 30000))
        goto done;
    pipe_opengpu_fence_reference(&fence, NULL);

    fill_textured_triangle(&draw);
    if (pipe_opengpu_draw_vbo(ctx, &draw, &fence) ||
        pipe_opengpu_fence_finish(ctx, fence, 30000))
        goto done;
    pipe_opengpu_fence_reference(&fence, NULL);

    for (i = 0; i < FB_W * FB_H; i++) {
        uint32_t pixel = ((uint32_t *)pipe_opengpu_resource_map(color))[i];

        if (pixel == 0x000000ffu)
            continue;
        if (!painted)
            sample = pixel;
        else if (pixel != sample) {
            fprintf(stderr, "inconsistent textured pixels\n");
            errno = EIO;
            goto done;
        }
        painted++;
    }
    /* Matches opengpu_drm_test fixed-function textured expectation. */
    if (!painted || sample != 0x00fe00ffu) {
        fprintf(stderr, "texture_draw coloured=%u sample=0x%08x\n", painted,
                sample);
        errno = EIO;
        goto done;
    }
    printf("pipe texture_draw completed; coloured=%u sample=0x%08x\n", painted,
           sample);
    status = 0;
done:
    if (status)
        perror("pipe_texture_draw");
    pipe_opengpu_fence_reference(&fence, NULL);
    if (tex)
        pipe_opengpu_resource_destroy(screen, tex);
    if (color)
        pipe_opengpu_resource_destroy(screen, color);
    if (ctx)
        pipe_opengpu_context_destroy(ctx);
    if (screen)
        pipe_opengpu_screen_destroy(screen);
    return status;
}
