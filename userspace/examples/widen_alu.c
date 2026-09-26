/* SPDX-License-Identifier: MIT */
/* Launch the corpus widen_alu shader (vwadd/vwsub/vwmul). */
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
    shader_fd = open(argc > 2 ? argv[2] : "/opengpu_widen_alu.bin", O_RDONLY);
    if (shader_fd < 0)
        goto done;
    shader_bytes = read(shader_fd, shader.map, shader.size);
    if (shader_bytes <= 0 || shader_bytes > 128 || (shader_bytes & 3)) {
        errno = EINVAL;
        goto done;
    }
    words = kernarg.map;
    words[0] = 3; /* vs2 low-16 → +3 */
    words[1] = 5; /* vs1 low-16 → +5 */
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
    command.local[0] = command.local[1] = command.local[2] = 1;
    command.out_syncobj = fence;
    if (opengpu_compute(fd, &command) ||
        opengpu_sync_wait_success(fd, fence, 30000))
        goto done;
    /* Exact RTL reference: sext16 halves, then add/sub/mul (VectorIntegerAlu). */
    if (words[2] != 8u ||           /* vwadd 3+5 */
        words[3] != 0xfffffffeu ||  /* vwsub 3-5 */
        words[4] != 15u) {          /* vwmul 3*5 */
        fprintf(stderr,
                "widen_alu got %08x %08x %08x\n",
                words[2], words[3], words[4]);
        errno = EIO;
        goto done;
    }
    puts("widen_alu completed; add/sub/mul ok");
    status = 0;
done:
    if (status)
        perror("widen_alu");
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
