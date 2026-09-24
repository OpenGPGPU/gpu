/* SPDX-License-Identifier: MIT */
/* Gallium spike smoke: clear + draw_vbo through pipe_opengpu.
 * Fixed-function builds draw a solid triangle; fragment-core builds load
 * /opengpu_fragment_tint.bin (override with argv[2]). */
#include "../pipe_opengpu.h"
#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

enum { FB_W = 16, FB_H = 16 };

static void fill_triangle(struct drm_opengpu_draw *draw, int tint)
{
    memset(draw, 0, sizeof(*draw));
    draw->v0[0] = -0x10000;
    draw->v0[1] = -0x10000;
    draw->v1[0] = 0x10000;
    draw->v1[1] = -0x10000;
    draw->v2[0] = -0x10000;
    draw->v2[1] = 0x10000;
    draw->v0[3] = draw->v1[3] = draw->v2[3] = 0x10000;
    if (tint) {
        draw->c0[0] = draw->c1[0] = draw->c2[0] = 0xfe;
        draw->c0[1] = draw->c1[1] = draw->c2[1] = 0x00;
        draw->c0[2] = draw->c1[2] = draw->c2[2] = 0xff;
    } else {
        draw->c0[0] = draw->c1[0] = draw->c2[0] = 255;
    }
    draw->d0 = draw->d1 = draw->d2 = 0x10;
}

static int load_shader(const char *path, uint8_t *buf, size_t cap, size_t *out)
{
    int fd = open(path, O_RDONLY);
    ssize_t n;

    if (fd < 0)
        return -1;
    n = read(fd, buf, cap);
    close(fd);
    if (n <= 0 || (size_t)n > cap || (n & 3)) {
        errno = EINVAL;
        return -1;
    }
    *out = (size_t)n;
    return 0;
}

int main(int argc, char **argv)
{
    struct pipe_opengpu_screen *screen = NULL;
    struct pipe_opengpu_context *ctx = NULL;
    struct pipe_opengpu_resource *color = NULL;
    struct pipe_opengpu_fence *fence = NULL;
    struct drm_opengpu_draw draw;
    uint8_t shader[128];
    size_t shader_bytes = 0;
    uint64_t caps;
    unsigned painted = 0, i;
    uint32_t sample = 0;
    int status = 1;
    int fragment;

    screen = pipe_opengpu_screen_create(argc > 1 ? argv[1] : NULL);
    if (!screen)
        goto done;
    caps = pipe_opengpu_screen_capabilities(screen);
    fragment = !!(caps & OPENGPU_CAP_FRAGMENT_CORE);
    if ((caps & OPENGPU_CAP_VERTEX_CORE) && !fragment) {
        fprintf(stderr, "pipe_clear_draw: unexpected vertex-only config\n");
        errno = EINVAL;
        goto done;
    }

    ctx = pipe_opengpu_context_create(screen);
    color = pipe_opengpu_resource_create(screen, FB_W * FB_H * 4u);
    if (!ctx || !color)
        goto done;
    pipe_opengpu_set_framebuffer(ctx, color, FB_W, FB_H);

    if (fragment) {
        if (load_shader(argc > 2 ? argv[2] : "/opengpu_fragment_tint.bin",
                        shader, sizeof(shader), &shader_bytes) ||
            pipe_opengpu_bind_fs(ctx, shader, shader_bytes))
            goto done;
    } else if (pipe_opengpu_bind_fs(ctx, NULL, 0)) {
        goto done;
    }

    if (pipe_opengpu_clear(ctx, 0u, &fence) ||
        pipe_opengpu_fence_finish(ctx, fence, 300000))
        goto done;
    pipe_opengpu_fence_reference(&fence, NULL);

    fill_triangle(&draw, fragment);
    if (pipe_opengpu_draw_vbo(ctx, &draw, &fence) ||
        pipe_opengpu_fence_finish(ctx, fence, 300000))
        goto done;
    pipe_opengpu_fence_reference(&fence, NULL);

    for (i = 0; i < FB_W * FB_H; i++) {
        uint32_t pixel = ((uint32_t *)pipe_opengpu_resource_map(color))[i];

        if (pixel == 0)
            continue;
        if (!painted)
            sample = pixel;
        else if (fragment && pixel != sample) {
            fprintf(stderr, "inconsistent tinted pixels\n");
            errno = EIO;
            goto done;
        }
        painted++;
    }
    if (!painted) {
        fprintf(stderr, "pipe_clear_draw rendered no coloured pixels\n");
        errno = EIO;
        goto done;
    }
    printf("pipe clear+draw completed; mode=%s coloured=%u sample=0x%08x\n",
           fragment ? "fragment" : "fixed", painted, sample);
    status = 0;
done:
    if (status)
        perror("pipe_clear_draw");
    pipe_opengpu_fence_reference(&fence, NULL);
    if (color)
        pipe_opengpu_resource_destroy(screen, color);
    if (ctx)
        pipe_opengpu_context_destroy(ctx);
    if (screen)
        pipe_opengpu_screen_destroy(screen);
    return status;
}
