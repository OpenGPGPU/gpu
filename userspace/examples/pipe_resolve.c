/* SPDX-License-Identifier: MIT */
/* Gallium spike: CPU-fill a 2x MSAA GEM, resolve, verify channel average. */
#include "../pipe_opengpu.h"
#include <errno.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

enum { WIDTH = 4, HEIGHT = 2, MODE = 1 }; /* 2x */

static uint32_t resolve_average(const uint32_t *samples, uint32_t count)
{
    uint32_t word = 0, channel, index;

    for (channel = 0; channel < 4; channel++) {
        uint32_t sum = 0;

        for (index = 0; index < count; index++)
            sum += (samples[index] >> (8 * channel)) & 0xffu;
        word |= (((sum + count / 2) / count) & 0xffu) << (8 * channel);
    }
    return word;
}

int main(int argc, char **argv)
{
    struct pipe_opengpu_screen *screen = NULL;
    struct pipe_opengpu_context *ctx = NULL;
    struct pipe_opengpu_resource *src = NULL, *dst = NULL;
    struct pipe_opengpu_fence *fence = NULL;
    uint32_t sample_words[2] = { 0x00000000u, 0xffffffffu };
    uint32_t samples = 1u << MODE;
    uint32_t src_stride = WIDTH * samples * 4u;
    uint32_t dst_stride = WIDTH * 4u;
    uint32_t expected;
    uint32_t max_mode;
    uint64_t caps;
    unsigned x, y;
    int status = 1;

    screen = pipe_opengpu_screen_create(argc > 1 ? argv[1] : NULL);
    if (!screen)
        goto done;
    caps = pipe_opengpu_screen_capabilities(screen);
    if (!(caps & OPENGPU_CAP_MSAA)) {
        puts("pipe resolve skipped; MSAA not advertised");
        status = 0;
        goto done;
    }
    max_mode = (uint32_t)((caps & OPENGPU_CAP_MSAA_MAX_MODE_MASK) >>
                          OPENGPU_CAP_MSAA_MAX_MODE_SHIFT);
    if (MODE > max_mode) {
        puts("pipe resolve skipped; 2x above device max");
        status = 0;
        goto done;
    }

    ctx = pipe_opengpu_context_create(screen);
    src = pipe_opengpu_resource_create(screen, src_stride * HEIGHT);
    dst = pipe_opengpu_resource_create(screen, dst_stride * HEIGHT);
    if (!ctx || !src || !dst)
        goto done;

    expected = resolve_average(sample_words, samples);
    for (y = 0; y < HEIGHT; y++) {
        uint32_t *row =
            (uint32_t *)((uint8_t *)pipe_opengpu_resource_map(src) +
                         y * src_stride);

        for (x = 0; x < WIDTH; x++) {
            unsigned s;

            for (s = 0; s < samples; s++)
                row[x * samples + s] = sample_words[s];
        }
    }
    memset(pipe_opengpu_resource_map(dst), 0x5a, dst_stride * HEIGHT);

    if (pipe_opengpu_resolve(ctx, dst, src, WIDTH, HEIGHT, src_stride,
                             dst_stride, MODE, &fence) ||
        pipe_opengpu_fence_finish(ctx, fence, 300000))
        goto done;
    pipe_opengpu_fence_reference(&fence, NULL);

    for (y = 0; y < HEIGHT; y++) {
        uint32_t *row =
            (uint32_t *)((uint8_t *)pipe_opengpu_resource_map(dst) +
                         y * dst_stride);

        for (x = 0; x < WIDTH; x++) {
            if (row[x] != expected) {
                fprintf(stderr,
                        "resolve (%u,%u)=0x%08x expected 0x%08x\n", x, y,
                        row[x], expected);
                errno = EIO;
                goto done;
            }
        }
    }
    printf("pipe resolve completed; mode=2x expected=0x%08x\n", expected);
    status = 0;
done:
    if (status)
        perror("pipe_resolve");
    pipe_opengpu_fence_reference(&fence, NULL);
    if (dst)
        pipe_opengpu_resource_destroy(screen, dst);
    if (src)
        pipe_opengpu_resource_destroy(screen, src);
    if (ctx)
        pipe_opengpu_context_destroy(ctx);
    if (screen)
        pipe_opengpu_screen_destroy(screen);
    return status;
}
