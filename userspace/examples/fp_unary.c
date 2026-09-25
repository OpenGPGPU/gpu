/* SPDX-License-Identifier: MIT */
/* Launch the corpus fp_unary shader (vfsqrt/vfrsqrt7/vfrec7/vfclass). */
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

    fd = opengpu_open(argc > 1 ? argv[1] : NULL);
    if (fd < 0)
        goto done;
    if (opengpu_context_create(fd, &context) ||
        opengpu_buffer_create(fd, 128, &shader) ||
        opengpu_buffer_create(fd, 64, &kernarg) ||
        opengpu_sync_create(fd, &fence))
        goto done;
    shader_fd = open(argc > 2 ? argv[2] : "/opengpu_fp_unary.bin", O_RDONLY);
    if (shader_fd < 0)
        goto done;
    shader_bytes = read(shader_fd, shader.map, shader.size);
    if (shader_bytes <= 0 || shader_bytes > 128 || (shader_bytes & 3)) {
        errno = EINVAL;
        goto done;
    }
    words = kernarg.map;
    for (i = 0; i < 4; i++)
        words[i] = 0x40800000u; /* 4.0f — one per CU lane */
    for (i = 4; i < 8; i++)
        words[i] = 0x3f800000u; /* 1.0f */
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
    /* Match host CU lane count so VL=4 is admitted and every FPU lane sees
     * a defined vs2 (VL=1 left inactive lanes on garbage and hung vfsqrt). */
    command.local[0] = 4;
    command.local[1] = command.local[2] = 1;
    command.out_syncobj = fence;
    if (opengpu_compute(fd, &command) ||
        opengpu_sync_wait_success(fd, fence, 300000))
        goto done;
    /* Exact RTL reference values from VectorBackendSpec / VectorFEstimateAluSpec. */
    if (words[8] != 0x40000000u ||  /* vfsqrt(4) */
        words[9] != 0x3eff0000u ||  /* vfrsqrt7(4) */
        words[10] != 0x3f7f0000u || /* vfrec7(1) */
        words[11] != 0x40u) {       /* vfclass(+normal) */
        fprintf(stderr,
                "fp_unary got %08x %08x %08x %08x\n",
                words[8], words[9], words[10], words[11]);
        errno = EIO;
        goto done;
    }
    puts("fp_unary completed; sqrt/rsqrt7/rec7/class ok");
    status = 0;
done:
    if (status)
        perror("fp_unary");
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
