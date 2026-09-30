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

/* Fence wait in ms. A slow reference backend can need far longer than the
 * interactive default, so the benchmark harness may raise it. */
static unsigned fence_timeout_ms(void)
{
    const char *env = getenv("OPENGPU_PRESENT_TIMEOUT_MS");
    unsigned long value;

    if (!env || !env[0])
        return 300000;
    value = strtoul(env, NULL, 10);
    if (!value || value > 3600000ul)
        return 300000;
    return (unsigned)value;
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

/* Vertex format 0 fixed 32-byte layout, mirrored from gpu_abi.h. */
struct vertex {
    int32_t x, y, z, w;
    uint32_t color;
    int32_t depth;
    uint32_t u, v;
};

/* Pass-through VS: relays all eight attributes so the tint FS can
 * interpolate them. Required whenever the core exposes a vertex unit,
 * because the driver rejects fixed-function submits on such a device. */
static size_t write_vertex_shader(uint32_t *program)
{
    uint32_t pc = 0, field;

    program[pc++] = 0x00241293u;
    program[pc++] = 0x005082b3u;
    program[pc++] = 0xc1027057u;
    for (field = 0; field < 8; field++) {
        uint32_t input_offset = field * 32;
        uint32_t output_offset = (8 + field) * 32;

        program[pc++] = (input_offset << 20) | 0x00028313u;
        program[pc++] = 0x02036087u;
        program[pc++] = (output_offset << 20) | 0x00028313u;
        program[pc++] = 0x020360a7u;
    }
    program[pc++] = 0x30500073u;
    return (size_t)pc * 4u;
}

static void fill_vertex_triangle(struct vertex *verts)
{
    memset(verts, 0, 3 * sizeof(*verts));
    verts[0].x = -0x10000;
    verts[0].y = -0x10000;
    verts[1].x = 0x10000;
    verts[1].y = -0x10000;
    verts[2].x = -0x10000;
    verts[2].y = 0x10000;
    verts[0].w = verts[1].w = verts[2].w = 0x10000;
    verts[0].color = 0x101010ffu;
    verts[1].color = 0x202020ffu;
    verts[2].color = 0x303030ffu;
    verts[0].depth = verts[1].depth = verts[2].depth = 0x10;
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
    struct pipe_opengpu_resource *vb = NULL;
    struct pipe_opengpu_fence *fence = NULL;
    struct drm_opengpu_draw draw;
    struct drm_opengpu_vertex_draw vdraw;
    struct vertex verts[3];
    uint32_t vs[64] = { 0 };
    size_t vs_bytes = 0;
    uint8_t shader[128];
    size_t shader_bytes = 0;
    uint32_t width = 0, height = 0;
    uint64_t caps;
    unsigned painted = 0;
    uint32_t *pixels;
    int status = 1;
    int fragment, hold, vertex;
    unsigned timeout_ms = fence_timeout_ms();

    hold = want_hold(argc, argv);
    screen = pipe_opengpu_screen_create(card_path(argc, argv));
    if (!screen)
        goto done;
    caps = pipe_opengpu_screen_capabilities(screen);
    fragment = !!(caps & OPENGPU_CAP_FRAGMENT_CORE);
    vertex = !!(caps & OPENGPU_CAP_VERTEX_CORE);
    if (vertex && !fragment) {
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

    if (vertex) {
        fill_vertex_triangle(verts);
        vs_bytes = write_vertex_shader(vs);
        vb = pipe_opengpu_resource_create(screen, sizeof(verts));
        if (!vb)
            goto done;
        memcpy(pipe_opengpu_resource_map(vb), verts, sizeof(verts));
    }

    if (fragment) {
        if (load_shader(shader_path(argc, argv), shader, sizeof(shader),
                        &shader_bytes) ||
            pipe_opengpu_bind_fs(ctx, shader, shader_bytes))
            goto done;
    } else if (pipe_opengpu_bind_fs(ctx, NULL, 0)) {
        goto done;
    }

    if (vertex &&
        (pipe_opengpu_bind_vs(ctx, vs, vs_bytes) ||
         pipe_opengpu_set_vertex_buffer(ctx, vb, sizeof(struct vertex),
                                        sizeof(verts))))
        goto done;

    if (pipe_opengpu_clear(ctx, 0x102040ffu, &fence) ||
        pipe_opengpu_fence_finish(ctx, fence, timeout_ms))
        goto done;
    pipe_opengpu_fence_reference(&fence, NULL);

    if (vertex) {
        memset(&vdraw, 0, sizeof(vdraw));
        vdraw.vertex_buffer = 0;
        vdraw.vertex_count = 3;
        vdraw.vertex_stride = sizeof(struct vertex);
        vdraw.fragment_kernarg_bank_stride = 320;
        vdraw.state = OPENGPU_DRAW_STATE_OVERRIDE;
        vdraw.blend_config = OPENGPU_DRAW_BLEND_PRESENT |
            (1u << OPENGPU_DRAW_BLEND_SRC_SHIFT);
        if (pipe_opengpu_draw_vertex(ctx, &vdraw, &fence) ||
            pipe_opengpu_fence_finish(ctx, fence, timeout_ms))
            goto done;
    } else {
        fill_triangle(&draw, fragment);
        if (pipe_opengpu_draw_vbo(ctx, &draw, &fence) ||
            pipe_opengpu_fence_finish(ctx, fence, timeout_ms))
            goto done;
    }
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
    if (vb)
        pipe_opengpu_resource_destroy(screen, vb);
    if (color)
        pipe_opengpu_resource_destroy(screen, color);
    if (ctx)
        pipe_opengpu_context_destroy(ctx);
    if (screen)
        pipe_opengpu_screen_destroy(screen);
    return status;
}
