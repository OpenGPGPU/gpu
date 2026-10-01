/* SPDX-License-Identifier: MIT */
/* Launch the corpus fp_reduce shader (vfredusum.vs, vfredosum.vs). */
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
    /* The sum and the three elements the reduction must leave alone. */
    static const uint32_t expected[4] = {
        0x41200000u, /* 1 + 2 + 3 + 4 */
        0x40000000u, /* 2.0f, the doubled element 0 */
        0x40800000u, /* 4.0f */
        0x40c00000u, /* 6.0f */
    };
    unsigned i;
    int fd = -1, shader_fd = -1, status = 1;
    ssize_t shader_bytes;

    fd = opengpu_open(argc > 1 ? argv[1] : NULL);
    if (fd < 0)
        goto done;
    if (opengpu_context_create(fd, &context) ||
        opengpu_buffer_create(fd, 128, &shader) ||
        opengpu_buffer_create(fd, 64, &kernarg) ||
        opengpu_sync_create(fd, &fence))
        goto done;
    shader_fd = open(argc > 2 ? argv[2] : "/opengpu_fp_reduce.bin", O_RDONLY);
    if (shader_fd < 0)
        goto done;
    shader_bytes = read(shader_fd, shader.map, shader.size);
    if (shader_bytes <= 0 || shader_bytes > 128 || (shader_bytes & 3)) {
        errno = EINVAL;
        goto done;
    }
    words = kernarg.map;
    /* 1, 2, 3, 4 to reduce and a zero seed. */
    words[0] = 0x3f800000u;
    words[1] = 0x40000000u;
    words[2] = 0x40400000u;
    words[3] = 0x40800000u;
    words[4] = 0x00000000u;
    binding.context_id = context;
    binding.slot = 1;
    binding.handle = shader.handle;
    binding.type = OPENGPU_RESOURCE_COMPUTE_SHADER;
    binding.size = 128;
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
    /* Exact RTL reference values from VectorFReduceAluSpec. Both sums are
     * 1+2+3+4 = 10, and elements past 0 keep the doubled values the shader's
     * vadd.vv wrote, because a reduction only rewrites element 0. */
    for (i = 0; i < 4; i++) {
        if (words[8 + i] != expected[i]) {
            fprintf(stderr, "fp_reduce unordered word %u = %08x, want %08x\n",
                    8 + i, words[8 + i], expected[i]);
            errno = EIO;
            goto done;
        }
        if (words[12 + i] != expected[i]) {
            fprintf(stderr, "fp_reduce ordered word %u = %08x, want %08x\n",
                    12 + i, words[12 + i], expected[i]);
            errno = EIO;
            goto done;
        }
    }
    puts("fp_reduce completed; vfredusum/vfredosum ok");
    status = 0;
done:
    if (status)
        perror("fp_reduce");
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
