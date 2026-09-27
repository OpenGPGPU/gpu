/* SPDX-License-Identifier: MIT */
/* Launch the corpus fp_scalar shader (flw + OPFVF vector-scalar FP ops).
 * Requires OPENGPU_CAP_COMPUTE_SCALAR_FPU; skips without it. */
#include "../opengpu.h"
#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <unistd.h>

int main(int argc, char **argv)
{
    static const uint32_t expected[6] = {
        0x40a00000u, /* vfadd.vf  3 + 2 = 5.0 */
        0xbf800000u, /* vfrsub.vf 2 - 3 = -1.0 */
        0x3f2aaaabu, /* vfrdiv.vf 2 / 3 */
        0x40c00000u, /* vfmul.vf  3 * 2 = 6.0 */
        0x40e00000u, /* vfmacc.vf 2 * 3 + 1 = 7.0 */
        8u,          /* vmfgt.vf 3 > 2 masks vadd.vi 7 + 1 */
    };
    struct opengpu_buffer shader = { 0 }, kernarg = { 0 };
    struct drm_opengpu_resource binding = { 0 };
    struct drm_opengpu_compute command = { 0 };
    uint32_t context = 0, fence = 0;
    uint64_t caps = 0;
    uint32_t *words;
    unsigned i;
    int fd = -1, shader_fd = -1, status = 1;
    ssize_t shader_bytes;

    fd = opengpu_open(argc > 1 ? argv[1] : NULL);
    if (fd < 0)
        goto done;
    if (opengpu_capabilities(fd, &caps))
        goto done;
    if (!(caps & OPENGPU_CAP_COMPUTE_SCALAR_FPU)) {
        puts("fp_scalar skipped; needs OPENGPU_CAP_COMPUTE_SCALAR_FPU");
        close(fd);
        return 0;
    }
    if (opengpu_context_create(fd, &context) ||
        opengpu_buffer_create(fd, 128, &shader) ||
        opengpu_buffer_create(fd, 128, &kernarg) ||
        opengpu_sync_create(fd, &fence))
        goto done;
    shader_fd = open(argc > 2 ? argv[2] : "/opengpu_fp_scalar.bin", O_RDONLY);
    if (shader_fd < 0)
        goto done;
    shader_bytes = read(shader_fd, shader.map, shader.size);
    if (shader_bytes <= 0 || shader_bytes > 128 || (shader_bytes & 3)) {
        errno = EINVAL;
        goto done;
    }
    words = kernarg.map;
    for (i = 0; i < 4; i++)
        words[i] = 0x40000000u; /* 2.0f scalar operand at kernarg[0] */
    for (i = 4; i < 8; i++)
        words[i] = 0x40400000u; /* 3.0f */
    for (i = 8; i < 12; i++)
        words[i] = 0x3f800000u; /* 1.0f vfmacc accumulator */
    for (i = 12; i < 16; i++)
        words[i] = 7u;
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
    for (i = 0; i < 6; i++) {
        if (words[16 + i] != expected[i]) {
            fprintf(stderr, "fp_scalar result %u got %08x want %08x\n",
                    i, words[16 + i], expected[i]);
            errno = EIO;
            goto done;
        }
    }
    puts("fp_scalar completed; flw + vfadd/vfrsub/vfrdiv/vfmul/vfmacc/vmfgt.vf ok");
    status = 0;
done:
    if (status)
        perror("fp_scalar");
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
