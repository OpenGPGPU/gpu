/* SPDX-License-Identifier: MIT */
/* Gallium spike: clear + draw_vbo + present through pipe_opengpu.
 * Allocates a mode-sized 2D colour GEM, draws one triangle, and programs the
 * OpenGPU CRTC so ARTI/QEMU scanout shows the result. Pass --hold (or
 * OPENGPU_PRESENT_HOLD=1) to keep the framebuffer live. */
#include "../pipe_opengpu.h"
#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

static int want_hold(int argc, char **argv)
{
    const char *env = getenv("OPENGPU_PRESENT_HOLD");

    if (env && env[0] && strcmp(env, "0") != 0)
        return 1;
    for (int i = 1; i < argc; i++) {
        if (strcmp(argv[i], "--hold") == 0)
            return 1;
    }
    return 0;
}

static const char *card_path(int argc, char **argv)
{
    for (int i = 1; i < argc; i++) {
        if (strcmp(argv[i], "--hold") == 0)
            continue;
        if (argv[i][0] == '-')
            continue;
        return argv[i];
    }
    return NULL;
}

static const char *shader_path(int argc, char **argv)
{
    int seen_card = 0;

    for (int i = 1; i < argc; i++) {
        if (strcmp(argv[i], "--hold") == 0)
            continue;
        if (argv[i][0] == '-')
            continue;
        if (!seen_card) {
            seen_card = 1;
            continue;
        }
        return argv[i];
    }
    return "/opengpu_fragment_tint.bin";
}

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
        fd = open("/root/opengpu_fragment_tint.bin", O_RDONLY);
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
    uint32_t width = 0, height = 0;
    uint64_t caps;
    unsigned painted = 0;
    uint32_t *pixels;
    int status = 1;
    int fragment, hold;

    hold = want_hold(argc, argv);
    screen = pipe_opengpu_screen_create(card_path(argc, argv));
    if (!screen)
        goto done;
    caps = pipe_opengpu_screen_capabilities(screen);
    fragment = !!(caps & OPENGPU_CAP_FRAGMENT_CORE);
    if ((caps & OPENGPU_CAP_VERTEX_CORE) && !fragment) {
        fprintf(stderr, "pipe_present: unexpected vertex-only config\n");
        errno = EINVAL;
        goto done;
    }
    if (pipe_opengpu_screen_display_size(screen, &width, &height))
        goto done;

    ctx = pipe_opengpu_context_create(screen);
    color = pipe_opengpu_resource_create_2d(screen, width, height);
    if (!ctx || !color)
        goto done;
    pipe_opengpu_set_framebuffer(ctx, color, width, height);

    if (fragment) {
        if (load_shader(shader_path(argc, argv), shader, sizeof(shader),
                        &shader_bytes) ||
            pipe_opengpu_bind_fs(ctx, shader, shader_bytes))
            goto done;
    } else if (pipe_opengpu_bind_fs(ctx, NULL, 0)) {
        goto done;
    }

    if (pipe_opengpu_clear(ctx, 0x102040ffu, &fence) ||
        pipe_opengpu_fence_finish(ctx, fence, 300000))
        goto done;
    pipe_opengpu_fence_reference(&fence, NULL);

    fill_triangle(&draw, fragment);
    if (pipe_opengpu_draw_vbo(ctx, &draw, &fence) ||
        pipe_opengpu_fence_finish(ctx, fence, 300000))
        goto done;
    pipe_opengpu_fence_reference(&fence, NULL);

    pixels = pipe_opengpu_resource_map(color);
    for (uint32_t y = 0; y < height; y++) {
        uint32_t *row =
            (uint32_t *)((uint8_t *)pixels + y * pipe_opengpu_resource_pitch(color));

        for (uint32_t x = 0; x < width; x++) {
            if (row[x] != 0x102040ffu)
                painted++;
        }
    }
    if (!painted) {
        fprintf(stderr, "pipe_present rendered no coloured pixels\n");
        errno = EIO;
        goto done;
    }
    if (pipe_opengpu_present(screen, color))
        goto done;

    printf("OPENGPU PIPE PRESENT PASS: %ux%u painted=%u%s\n", width, height,
           painted, fragment ? " tint" : "");
    fflush(stdout);
    status = 0;
    if (hold) {
        for (;;)
            pause();
    }

done:
    if (status)
        perror("pipe_present");
    pipe_opengpu_fence_reference(&fence, NULL);
    if (color)
        pipe_opengpu_resource_destroy(screen, color);
    if (ctx)
        pipe_opengpu_context_destroy(ctx);
    if (screen)
        pipe_opengpu_screen_destroy(screen);
    return status;
}
