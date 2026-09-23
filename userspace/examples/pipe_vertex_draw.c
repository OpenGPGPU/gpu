/* SPDX-License-Identifier: MIT */
/* Gallium spike: vertex-core draw with pass-through VS + textured FS. */
#include "../pipe_opengpu.h"
#include <errno.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

enum { FB_W = 16, FB_H = 16, TEX_WORDS = 21 };

struct vertex {
    int32_t x, y, z, w;
    uint32_t color;
    int32_t depth;
    uint32_t u, v;
};

static void write_fragment_shader(uint32_t *program)
{
    program[0] = 0x00241293u;
    program[1] = 0x005082b3u;
    program[2] = 0xc1027057u;
    program[3] = 0x08028313u;
    program[4] = 0x02036087u;
    program[5] = 0x0a028313u;
    program[6] = 0x02036107u;
    program[7] = 0x0620812bu;
    program[8] = 0x00041a63u;
    program[9] = 0x2e1081d7u;
    program[10] = 0x10028313u;
    program[11] = 0x020361a7u;
    program[12] = 0x30500073u;
    program[13] = 0x04028313u;
    program[14] = 0x02036207u;
    program[15] = 0x3210022bu;
    program[16] = 0x0e028313u;
    program[17] = 0x02036227u;
    program[18] = 0x0c028313u;
    program[19] = 0x02036127u;
    program[20] = 0x30500073u;
}

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

int main(int argc, char **argv)
{
    struct pipe_opengpu_screen *screen = NULL;
    struct pipe_opengpu_context *ctx = NULL;
    struct pipe_opengpu_resource *color = NULL, *tex = NULL, *vb = NULL;
    struct pipe_opengpu_fence *fence = NULL;
    struct drm_opengpu_vertex_draw draw;
    struct vertex verts[3] = {
        { -0x10000, -0x10000, 0, 0x10000, 0x101010ffu, 0x10, 0, 0 },
        { 0x10000, -0x10000, 0, 0x10000, 0x202020ffu, 0x10, 0x80000, 0 },
        { -0x10000, 0x10000, 0, 0x10000, 0x303030ffu, 0x10, 0, 0x80000 },
    };
    uint32_t fs[32] = { 0 }, vs[64] = { 0 }, *texels;
    size_t vs_bytes;
    uint64_t caps;
    unsigned painted = 0, i;
    uint32_t sample = 0;
    int status = 1;

    screen = pipe_opengpu_screen_create(argc > 1 ? argv[1] : NULL);
    if (!screen)
        goto done;
    caps = pipe_opengpu_screen_capabilities(screen);
    if (!(caps & OPENGPU_CAP_VERTEX_CORE) ||
        !(caps & OPENGPU_CAP_FRAGMENT_CORE)) {
        puts("pipe vertex_draw skipped; needs vertex+fragment cores");
        status = 0;
        goto done;
    }

    ctx = pipe_opengpu_context_create(screen);
    color = pipe_opengpu_resource_create(screen, FB_W * FB_H * 4u);
    tex = pipe_opengpu_resource_create(screen, TEX_WORDS * 4u);
    vb = pipe_opengpu_resource_create(screen, sizeof(verts));
    if (!ctx || !color || !tex || !vb)
        goto done;

    texels = pipe_opengpu_resource_map(tex);
    for (i = 0; i < 16; i++)
        texels[i] = 0x00ff00ffu;
    for (i = 16; i < 20; i++)
        texels[i] = 0xff0000ffu;
    texels[20] = 0x0000ffffu;
    memcpy(pipe_opengpu_resource_map(vb), verts, sizeof(verts));

    write_fragment_shader(fs);
    vs_bytes = write_vertex_shader(vs);
    pipe_opengpu_set_framebuffer(ctx, color, FB_W, FB_H);
    if (pipe_opengpu_bind_fs(ctx, fs, 21u * 4u) ||
        pipe_opengpu_bind_vs(ctx, vs, vs_bytes) ||
        pipe_opengpu_set_vertex_buffer(ctx, vb, sizeof(struct vertex),
                                       sizeof(verts)) ||
        pipe_opengpu_bind_texture(ctx, tex, 4, 4, TEX_WORDS * 4u,
                                  OPENGPU_RESOURCE_TEXTURE_CLAMP |
                                      (2u << OPENGPU_RESOURCE_TEXTURE_MAX_MIP_SHIFT) |
                                      OPENGPU_RESOURCE_UNCACHED))
        goto done;

    if (pipe_opengpu_clear(ctx, 0x000000ffu, &fence) ||
        pipe_opengpu_fence_finish(ctx, fence, 30000))
        goto done;
    pipe_opengpu_fence_reference(&fence, NULL);

    memset(&draw, 0, sizeof(draw));
    draw.vertex_count = 3;
    draw.vertex_stride = sizeof(struct vertex);
    draw.fragment_kernarg_bank_stride = 320;
    draw.state = OPENGPU_DRAW_STATE_OVERRIDE |
        OPENGPU_DRAW_STATE_DEPTH_TEST |
        OPENGPU_DRAW_STATE_DEPTH_WRITE |
        OPENGPU_DRAW_STATE_TEX_ENABLE |
        OPENGPU_DRAW_STATE_TEX_CLAMP |
        (2u << OPENGPU_DRAW_STATE_MAX_MIP_SHIFT);
    draw.blend_config = OPENGPU_DRAW_BLEND_PRESENT |
        (1u << OPENGPU_DRAW_BLEND_SRC_SHIFT);

    if (pipe_opengpu_draw_vertex(ctx, &draw, &fence) ||
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
            fprintf(stderr, "inconsistent vertex-draw pixels\n");
            errno = EIO;
            goto done;
        }
        painted++;
    }
    if (!painted || sample != 0xff0000ffu) {
        fprintf(stderr, "vertex_draw coloured=%u sample=0x%08x\n", painted,
                sample);
        errno = EIO;
        goto done;
    }
    printf("pipe vertex_draw completed; coloured=%u sample=0x%08x\n", painted,
           sample);
    status = 0;
done:
    if (status)
        perror("pipe_vertex_draw");
    pipe_opengpu_fence_reference(&fence, NULL);
    if (vb)
        pipe_opengpu_resource_destroy(screen, vb);
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
