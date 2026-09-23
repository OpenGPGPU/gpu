/* SPDX-License-Identifier: MIT */
/* Fixed-function triangle into a caller-owned 16x16 colour buffer. */
#include "../opengpu.h"
#include <stdint.h>
#include <stdio.h>
#include <unistd.h>

int main(int argc, char **argv)
{
    struct opengpu_buffer commands = { 0 }, color = { 0 };
    struct drm_opengpu_submit submit = { 0 };
    struct drm_opengpu_draw *draw;
    uint32_t context = 0, fence = 0;
    uint64_t caps;
    int fd = -1, status = 1;
    unsigned painted = 0;
    fd = opengpu_open(argc > 1 ? argv[1] : NULL);
    if (fd < 0) goto done;
    if (opengpu_capabilities(fd, &caps)) goto done;
    if (caps & (OPENGPU_CAP_FRAGMENT_CORE | OPENGPU_CAP_VERTEX_CORE)) {
        fprintf(stderr, "triangle requires a fixed-function build\n");
        close(fd);
        return 2;
    }
    if (opengpu_context_create(fd, &context) ||
        opengpu_buffer_create(fd, sizeof(struct drm_opengpu_draw), &commands) ||
        opengpu_buffer_create(fd, 16u * 16u * 4u, &color) ||
        opengpu_sync_create(fd, &fence)) goto done;
    draw = commands.map;
    draw->v0[0] = -0x10000; draw->v0[1] = -0x10000;
    draw->v1[0] =  0x10000; draw->v1[1] = -0x10000;
    draw->v2[0] = -0x10000; draw->v2[1] =  0x10000;
    draw->v0[3] = draw->v1[3] = draw->v2[3] = 0x10000;
    draw->c0[0] = draw->c1[0] = draw->c2[0] = 255;
    draw->d0 = draw->d1 = draw->d2 = 0x10;
    submit.context_id = context;
    submit.command_handle = commands.handle;
    submit.color_handle = color.handle;
    submit.stride = 16u * 4u;
    submit.command_count = 1;
    submit.out_syncobj = fence;
    if (opengpu_render(fd, &submit) || opengpu_sync_wait_success(fd, fence, 30000))
        goto done;
    for (unsigned i = 0; i < 16u * 16u; i++)
        if (((uint32_t *)color.map)[i] != 0) painted++;
    if (!painted) {
        fprintf(stderr, "triangle rendered no coloured pixels\n");
        goto done;
    }
    printf("render completed; coloured pixels: %u\n", painted);
    status = 0;
done:
    if (status) perror("triangle");
    if (fence) opengpu_sync_destroy(fd, fence);
    if (context) opengpu_context_destroy(fd, context);
    if (color.handle) opengpu_buffer_destroy(fd, &color);
    if (commands.handle) opengpu_buffer_destroy(fd, &commands);
    if (fd >= 0) close(fd);
    return status;
}
