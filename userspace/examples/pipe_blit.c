/* SPDX-License-Identifier: MIT */
/* Gallium spike: clear a source GEM, blit to destination, verify. */
#include "../pipe_opengpu.h"
#include <errno.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

enum { BYTES = 1024 }; /* 16x16 RGBA, 64-byte aligned */

int main(int argc, char **argv)
{
    struct pipe_opengpu_screen *screen = NULL;
    struct pipe_opengpu_context *ctx = NULL;
    struct pipe_opengpu_resource *src = NULL, *dst = NULL;
    struct pipe_opengpu_fence *fence = NULL;
    uint32_t *dst_map;
    unsigned i;
    int status = 1;

    screen = pipe_opengpu_screen_create(argc > 1 ? argv[1] : NULL);
    if (!screen)
        goto done;
    ctx = pipe_opengpu_context_create(screen);
    src = pipe_opengpu_resource_create(screen, BYTES);
    dst = pipe_opengpu_resource_create(screen, BYTES);
    if (!ctx || !src || !dst)
        goto done;

    /* Reuse clear against an explicit resource by temporarily setting FB. */
    pipe_opengpu_set_framebuffer(ctx, src, 16, 16);
    if (pipe_opengpu_clear(ctx, 0xaabbccddu, &fence) ||
        pipe_opengpu_fence_finish(ctx, fence, 30000))
        goto done;
    pipe_opengpu_fence_reference(&fence, NULL);

    memset(pipe_opengpu_resource_map(dst), 0, BYTES);
    if (pipe_opengpu_blit(ctx, dst, src, 0, 0, BYTES, &fence) ||
        pipe_opengpu_fence_finish(ctx, fence, 30000))
        goto done;
    pipe_opengpu_fence_reference(&fence, NULL);

    dst_map = pipe_opengpu_resource_map(dst);
    for (i = 0; i < BYTES / 4u; i++) {
        if (dst_map[i] != 0xaabbccddu) {
            fprintf(stderr, "blit mismatch at word %u: 0x%08x\n", i,
                    dst_map[i]);
            errno = EIO;
            goto done;
        }
    }
    puts("pipe blit completed; 1024 bytes matched");
    status = 0;
done:
    if (status)
        perror("pipe_blit");
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
