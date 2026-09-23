/* SPDX-License-Identifier: MIT */
/* Gallium spike: invalidate after CPU fill, then strided 2D blit. */
#include "../pipe_opengpu.h"
#include <errno.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

enum {
    WIDTH = 64, /* bytes per row copied */
    HEIGHT = 4,
    SRC_STRIDE = 128,
    DST_STRIDE = 192,
};

int main(int argc, char **argv)
{
    struct pipe_opengpu_screen *screen = NULL;
    struct pipe_opengpu_context *ctx = NULL;
    struct pipe_opengpu_resource *src = NULL, *dst = NULL;
    struct pipe_opengpu_fence *fence = NULL;
    uint32_t row, word;
    int status = 1;

    screen = pipe_opengpu_screen_create(argc > 1 ? argv[1] : NULL);
    if (!screen)
        goto done;
    if (!(pipe_opengpu_screen_capabilities(screen) &
          OPENGPU_CAP_STRIDED_ENGINE)) {
        puts("pipe strided_blit skipped; STRIDED_ENGINE not advertised");
        status = 0;
        goto done;
    }

    ctx = pipe_opengpu_context_create(screen);
    src = pipe_opengpu_resource_create(screen, SRC_STRIDE * HEIGHT);
    dst = pipe_opengpu_resource_create(screen, DST_STRIDE * HEIGHT);
    if (!ctx || !src || !dst)
        goto done;

    memset(pipe_opengpu_resource_map(dst), 0x5a, DST_STRIDE * HEIGHT);
    for (row = 0; row < HEIGHT; row++) {
        uint32_t *words =
            (uint32_t *)((uint8_t *)pipe_opengpu_resource_map(src) +
                         row * SRC_STRIDE);

        for (word = 0; word < WIDTH / 4u; word++)
            words[word] = 0x10203040u + row * 0x100u + word;
    }

    if (pipe_opengpu_invalidate(ctx, src, 0, SRC_STRIDE * HEIGHT, &fence) ||
        pipe_opengpu_fence_finish(ctx, fence, 30000))
        goto done;
    pipe_opengpu_fence_reference(&fence, NULL);

    if (pipe_opengpu_strided_blit(ctx, dst, src, 0, 0, WIDTH, HEIGHT,
                                  DST_STRIDE, SRC_STRIDE, &fence) ||
        pipe_opengpu_fence_finish(ctx, fence, 30000))
        goto done;
    pipe_opengpu_fence_reference(&fence, NULL);

    for (row = 0; row < HEIGHT; row++) {
        uint32_t *dst_words =
            (uint32_t *)((uint8_t *)pipe_opengpu_resource_map(dst) +
                         row * DST_STRIDE);

        for (word = 0; word < WIDTH / 4u; word++) {
            uint32_t want = 0x10203040u + row * 0x100u + word;

            if (dst_words[word] != want) {
                fprintf(stderr,
                        "strided mismatch row=%u word=%u got=0x%08x "
                        "want=0x%08x\n",
                        row, word, dst_words[word], want);
                errno = EIO;
                goto done;
            }
        }
        for (word = WIDTH / 4u; word < DST_STRIDE / 4u; word++) {
            if (dst_words[word] != 0x5a5a5a5au) {
                fprintf(stderr, "strided padding clobber row=%u word=%u\n",
                        row, word);
                errno = EIO;
                goto done;
            }
        }
    }
    puts("pipe strided_blit completed; 4x64 bytes matched");
    status = 0;
done:
    if (status)
        perror("pipe_strided_blit");
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
