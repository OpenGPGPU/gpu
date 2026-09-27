/* SPDX-License-Identifier: MIT */
/* Programmable fragment vector FP: the shader computes 6.0f per lane with
 * vfmul/vfdiv/vfmacc and XORs it into the interpolated colour.
 * Requires OPENGPU_CAP_FRAGMENT_CORE. Shader binary from fragment_fp.S
 * (corpus); default path /opengpu_fragment_fp.bin. */
#include "../opengpu.h"
#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

/* Shader BO must be a multiple of 64 (instruction window); kernarg BO must be
 * a multiple of GPU_KERNARG_BANK_ALIGN (64). */
enum { FS_GEM_BYTES = 128u, FS_KERNARG_BYTES = 640u };

/* Interpolated colour 0xfe00ffff XOR 6.0f (0x40c00000). */
#define FRAGMENT_FP_EXPECTED 0xbec0ffffu

int main(int argc, char **argv)
{
    struct opengpu_buffer commands = { 0 }, color = { 0 };
    struct opengpu_buffer shader = { 0 }, kernarg = { 0 };
    struct drm_opengpu_resource binding = { 0 };
    struct drm_opengpu_submit submit = { 0 };
    struct drm_opengpu_draw *draw;
    uint32_t context = 0, fence = 0;
    uint32_t width = 0, height = 0;
    uint64_t caps;
    int fd = -1, shader_fd = -1, status = 1;
    ssize_t shader_bytes;
    unsigned painted = 0;

    fd = opengpu_open(argc > 1 ? argv[1] : NULL);
    if (fd < 0)
        goto done;
    if (opengpu_capabilities(fd, &caps))
        goto done;
    if (!(caps & OPENGPU_CAP_FRAGMENT_CORE)) {
        fprintf(stderr, "fragment_fp requires OPENGPU_CAP_FRAGMENT_CORE\n");
        close(fd);
        return 2;
    }
    if (opengpu_display_size(fd, &width, &height) || !width || !height ||
        width > UINT32_MAX / 4u / height) {
        errno = EINVAL;
        goto done;
    }
    if (opengpu_context_create(fd, &context) ||
        opengpu_buffer_create(fd, sizeof(struct drm_opengpu_draw), &commands) ||
        opengpu_buffer_create(fd, width * height * 4u, &color) ||
        opengpu_buffer_create(fd, FS_GEM_BYTES, &shader) ||
        opengpu_buffer_create(fd, FS_KERNARG_BYTES, &kernarg) ||
        opengpu_sync_create(fd, &fence))
        goto done;

    shader_fd = open(argc > 2 ? argv[2] : "/opengpu_fragment_fp.bin",
                     O_RDONLY);
    if (shader_fd < 0)
        goto done;
    memset(shader.map, 0, shader.size);
    shader_bytes = read(shader_fd, shader.map, FS_GEM_BYTES);
    if (shader_bytes <= 0 || shader_bytes > FS_GEM_BYTES ||
        (shader_bytes & 3)) {
        errno = EINVAL;
        goto done;
    }

    binding.context_id = context;
    binding.slot = 2;
    binding.handle = shader.handle;
    binding.type = OPENGPU_RESOURCE_SHADER;
    binding.size = FS_GEM_BYTES;
    if (opengpu_bind(fd, &binding))
        goto done;
    binding.slot = 3;
    binding.handle = kernarg.handle;
    binding.type = OPENGPU_RESOURCE_KERNARG;
    binding.size = FS_KERNARG_BYTES;
    binding.flags = OPENGPU_RESOURCE_UNCACHED;
    if (opengpu_bind(fd, &binding))
        goto done;

    draw = commands.map;
    memset(draw, 0, sizeof(*draw));
    draw->v0[0] = -0x10000;
    draw->v0[1] = -0x10000;
    draw->v1[0] = 0x10000;
    draw->v1[1] = -0x10000;
    draw->v2[0] = -0x10000;
    draw->v2[1] = 0x10000;
    draw->v0[3] = draw->v1[3] = draw->v2[3] = 0x10000;
    draw->c0[0] = draw->c1[0] = draw->c2[0] = 0xfe;
    draw->c0[1] = draw->c1[1] = draw->c2[1] = 0x00;
    draw->c0[2] = draw->c1[2] = draw->c2[2] = 0xff;
    draw->d0 = draw->d1 = draw->d2 = 0x10;

    submit.context_id = context;
    submit.command_handle = commands.handle;
    submit.color_handle = color.handle;
    submit.stride = width * 4u;
    submit.command_count = 1;
    submit.shader_slot = 2;
    submit.kernarg_slot = 3;
    submit.out_syncobj = fence;
    if (opengpu_render(fd, &submit) ||
        opengpu_sync_wait_success(fd, fence, 300000))
        goto done;

    for (uint32_t i = 0; i < width * height; i++) {
        uint32_t pixel = ((uint32_t *)color.map)[i];

        if (pixel == 0)
            continue;
        if (pixel != FRAGMENT_FP_EXPECTED) {
            fprintf(stderr, "fragment_fp pixel %u got 0x%08x want 0x%08x\n",
                    i, pixel, FRAGMENT_FP_EXPECTED);
            errno = EIO;
            goto done;
        }
        painted++;
    }
    if (!painted) {
        fprintf(stderr, "fragment_fp rendered no coloured pixels\n");
        errno = EIO;
        goto done;
    }
    printf("fragment_fp completed; vfmul/vfdiv/vfmacc on the shader CU, "
           "pixels: %u sample=0x%08x\n", painted, FRAGMENT_FP_EXPECTED);
    status = 0;
done:
    if (status)
        perror("fragment_fp");
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
