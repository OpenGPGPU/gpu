/* SPDX-License-Identifier: MIT */
/* Launch the corpus fp_fma shader (eight unmasked OPFVV fused FMA forms). */
#include "../opengpu.h"
#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <unistd.h>

int main(int argc, char **argv)
{
    struct opengpu_buffer shader = { 0 }, kernarg = { 0 };
    struct drm_opengpu_resource binding = { 0 };
    struct drm_opengpu_compute command = { 0 };
    uint32_t context = 0, fence = 0;
    uint32_t *words;
    unsigned i;
    int fd = -1, shader_fd = -1, status = 1;
    ssize_t shader_bytes;
    static const uint32_t expect[8] = {
        0x40e00000u, /* vfmadd  3*2+1 = 7 */
        0xc0a00000u, /* vfnmadd -(3*2)+1 = -5 */
        0x40a00000u, /* vfmsub  3*2-1 = 5 */
        0xc0e00000u, /* vfnmsub -(3*2)-1 = -7 */
        0x40e00000u, /* vfmacc  2*3+1 = 7 */
        0xc0a00000u, /* vfnmacc -(2*3)+1 = -5 */
        0x40a00000u, /* vfmsac  2*3-1 = 5 */
        0xc0e00000u, /* vfnmsac -(2*3)-1 = -7 */
    };

    fd = opengpu_open(argc > 1 ? argv[1] : NULL);
    if (fd < 0)
        goto done;
    if (opengpu_context_create(fd, &context) ||
        opengpu_buffer_create(fd, 192, &shader) ||
        opengpu_buffer_create(fd, 128, &kernarg) ||
        opengpu_sync_create(fd, &fence))
        goto done;
    shader_fd = open(argc > 2 ? argv[2] : "/opengpu_fp_fma.bin", O_RDONLY);
    if (shader_fd < 0)
        goto done;
    shader_bytes = read(shader_fd, shader.map, shader.size);
    if (shader_bytes <= 0 || shader_bytes > 192 || (shader_bytes & 3)) {
        errno = EINVAL;
        goto done;
    }
    words = kernarg.map;
    for (i = 0; i < 4; i++)
        words[i] = 0x40400000u; /* 3.0f */
    for (i = 4; i < 8; i++)
        words[i] = 0x40000000u; /* 2.0f */
    for (i = 8; i < 12; i++)
        words[i] = 0x3f800000u; /* 1.0f old vd */
    binding.context_id = context;
    binding.slot = 1;
    binding.handle = shader.handle;
    binding.type = OPENGPU_RESOURCE_COMPUTE_SHADER;
    binding.size = 192;
    if (opengpu_bind(fd, &binding))
        goto done;
    binding.slot = 2;
    binding.handle = kernarg.handle;
    binding.type = OPENGPU_RESOURCE_COMPUTE_KERNARG;
    binding.flags = OPENGPU_RESOURCE_UNCACHED;
    if (opengpu_bind(fd, &binding))
        goto done;
    command.context_id = context;
    command.shader_slot = 1;
    command.kernarg_slot = 2;
    command.grid[0] = command.grid[1] = command.grid[2] = 1;
    command.local[0] = 4;
    command.local[1] = command.local[2] = 1;
    command.out_syncobj = fence;
    if (opengpu_compute(fd, &command) ||
        opengpu_sync_wait_success(fd, fence, 300000))
        goto done;
    for (i = 0; i < 8; i++) {
        if (words[12 + i] != expect[i]) {
            fprintf(stderr, "fp_fma[%u] got %08x want %08x\n",
                    i, words[12 + i], expect[i]);
            errno = EIO;
            goto done;
        }
    }
    puts("fp_fma completed; eight fused forms ok");
    status = 0;
done:
    if (status)
        perror("fp_fma");
    if (fence)
        opengpu_sync_destroy(fd, fence);
    if (context)
        opengpu_context_destroy(fd, context);
    if (kernarg.handle)
        opengpu_buffer_destroy(fd, &kernarg);
    if (shader.handle)
        opengpu_buffer_destroy(fd, &shader);
    if (shader_fd >= 0)
        close(shader_fd);
    if (fd >= 0)
        close(fd);
    return status;
}
