/* SPDX-License-Identifier: MIT */
/* Gallium spike: launch_grid through pipe_opengpu (round_modes corpus). */
#include "../pipe_opengpu.h"
#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <unistd.h>

int main(int argc, char **argv)
{
    struct pipe_opengpu_screen *screen = NULL;
    struct pipe_opengpu_context *ctx = NULL;
    struct pipe_opengpu_fence *fence = NULL;
    uint8_t shader[64];
    uint32_t grid[3] = { 1, 1, 1 }, local[3] = { 1, 1, 1 };
    uint32_t *kernarg;
    int status = 1, shader_fd = -1;
    ssize_t n;

    screen = pipe_opengpu_screen_create(argc > 1 ? argv[1] : NULL);
    if (!screen)
        goto done;
    ctx = pipe_opengpu_context_create(screen);
    if (!ctx)
        goto done;

    shader_fd = open(argc > 2 ? argv[2] : "/opengpu_compute_shader.bin",
                     O_RDONLY);
    if (shader_fd < 0)
        goto done;
    n = read(shader_fd, shader, sizeof(shader));
    if (n <= 0 || n > 64 || (n & 3)) {
        errno = EINVAL;
        goto done;
    }
    if (pipe_opengpu_bind_cs(ctx, shader, (size_t)n))
        goto done;
    kernarg = pipe_opengpu_cs_kernarg_map(ctx);
    if (!kernarg) {
        errno = EINVAL;
        goto done;
    }
    kernarg[0] = 1;
    if (pipe_opengpu_launch_grid(ctx, grid, local, &fence) ||
        pipe_opengpu_fence_finish(ctx, fence, 30000))
        goto done;
    pipe_opengpu_fence_reference(&fence, NULL);
    if (kernarg[1] != 1 || kernarg[2] != 0) {
        errno = EIO;
        goto done;
    }
    puts("pipe launch_grid completed; RNU=1 RDN=0");
    status = 0;
done:
    if (status)
        perror("pipe_compute");
    pipe_opengpu_fence_reference(&fence, NULL);
    if (ctx)
        pipe_opengpu_context_destroy(ctx);
    if (screen)
        pipe_opengpu_screen_destroy(screen);
    if (shader_fd >= 0)
        close(shader_fd);
    return status;
}
