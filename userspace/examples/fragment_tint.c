/* SPDX-License-Identifier: MIT */
/* Programmable fragment tint: XOR interpolated colour with 0x00ff00ff.
 * Requires OPENGPU_CAP_FRAGMENT_CORE. Shader binary from fragment_tint.c
 * (corpus); default path /opengpu_fragment_tint.bin. */
#include "../opengpu.h"
#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <unistd.h>

int main(int argc, char **argv)
{
    struct opengpu_buffer commands = { 0 }, color = { 0 };
    struct opengpu_buffer shader = { 0 }, kernarg = { 0 };
    struct drm_opengpu_resource binding = { 0 };
    struct drm_opengpu_submit submit = { 0 };
    struct drm_opengpu_draw *draw;
    uint32_t context = 0, fence = 0;
    uint64_t caps;
    int fd = -1, shader_fd = -1, status = 1;
    ssize_t shader_bytes;
    unsigned painted = 0;
    uint32_t sample = 0;

    fd = opengpu_open(argc > 1 ? argv[1] : NULL);
    if (fd < 0)
        goto done;
    if (opengpu_capabilities(fd, &caps))
        goto done;
    if (!(caps & OPENGPU_CAP_FRAGMENT_CORE)) {
        fprintf(stderr, "fragment_tint requires OPENGPU_CAP_FRAGMENT_CORE\n");
        close(fd);
        return 2;
    }
    if (opengpu_context_create(fd, &context) ||
        opengpu_buffer_create(fd, sizeof(struct drm_opengpu_draw), &commands) ||
        opengpu_buffer_create(fd, 16u * 16u * 4u, &color) ||
        opengpu_buffer_create(fd, 128, &shader) ||
        opengpu_buffer_create(fd, 288, &kernarg) ||
        opengpu_sync_create(fd, &fence))
        goto done;

    shader_fd = open(argc > 2 ? argv[2] : "/opengpu_fragment_tint.bin",
                     O_RDONLY);
    if (shader_fd < 0)
        goto done;
    shader_bytes = read(shader_fd, shader.map, shader.size);
    if (shader_bytes <= 0 || shader_bytes > 128 || (shader_bytes & 3)) {
        errno = EINVAL;
        goto done;
    }

    binding.context_id = context;
    binding.slot = 2;
    binding.handle = shader.handle;
    binding.type = OPENGPU_RESOURCE_SHADER;
    binding.size = 128;
    if (opengpu_bind(fd, &binding))
        goto done;
    binding.slot = 3;
    binding.handle = kernarg.handle;
    binding.type = OPENGPU_RESOURCE_KERNARG;
    binding.size = 288;
    binding.flags = OPENGPU_RESOURCE_UNCACHED;
    if (opengpu_bind(fd, &binding))
        goto done;

    draw = commands.map;
    draw->v0[0] = -0x10000;
    draw->v0[1] = -0x10000;
    draw->v1[0] = 0x10000;
    draw->v1[1] = -0x10000;
    draw->v2[0] = -0x10000;
    draw->v2[1] = 0x10000;
    draw->v0[3] = draw->v1[3] = draw->v2[3] = 0x10000;
    draw->c0[0] = 0xfe;
    draw->c0[1] = 0x00;
    draw->c0[2] = 0xff;
    draw->c1[0] = 0xfe;
    draw->c1[1] = 0x00;
    draw->c1[2] = 0xff;
    draw->c2[0] = 0xfe;
    draw->c2[1] = 0x00;
    draw->c2[2] = 0xff;
    draw->d0 = draw->d1 = draw->d2 = 0x10;
    draw->shader_pc = 0;
    draw->kernarg = 0;

    submit.context_id = context;
    submit.command_handle = commands.handle;
    submit.color_handle = color.handle;
    submit.stride = 16u * 4u;
    submit.command_count = 1;
    submit.shader_slot = 2;
    submit.kernarg_slot = 3;
    submit.out_syncobj = fence;
    if (opengpu_render(fd, &submit) ||
        opengpu_sync_wait_success(fd, fence, 30000))
        goto done;

    for (unsigned i = 0; i < 16u * 16u; i++) {
        uint32_t pixel = ((uint32_t *)color.map)[i];

        if (pixel == 0)
            continue;
        if (!painted)
            sample = pixel;
        else if (pixel != sample) {
            fprintf(stderr, "inconsistent tinted pixels\n");
            errno = EIO;
            goto done;
        }
        painted++;
    }
    if (!painted) {
        fprintf(stderr, "fragment_tint rendered no coloured pixels\n");
        errno = EIO;
        goto done;
    }
    printf("fragment tint completed; coloured pixels: %u sample=0x%08x\n",
           painted, sample);
    status = 0;
done:
    if (status)
        perror("fragment_tint");
    if (fence)
        opengpu_sync_destroy(fd, fence);
    if (context)
        opengpu_context_destroy(fd, context);
    if (kernarg.handle)
        opengpu_buffer_destroy(fd, &kernarg);
    if (shader.handle)
        opengpu_buffer_destroy(fd, &shader);
    if (color.handle)
        opengpu_buffer_destroy(fd, &color);
    if (commands.handle)
        opengpu_buffer_destroy(fd, &commands);
    if (shader_fd >= 0)
        close(shader_fd);
    if (fd >= 0)
        close(fd);
    return status;
}
