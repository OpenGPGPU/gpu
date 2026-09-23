/* SPDX-License-Identifier: MIT */
/* Launch a minimal validated shader and wait for its completion fence. */
#include "../opengpu.h"
#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

int main(int argc, char **argv)
{
    struct opengpu_buffer shader = { 0 }, kernarg = { 0 };
    struct drm_opengpu_resource binding = { 0 };
    struct drm_opengpu_compute command = { 0 };
    uint32_t context = 0, fence = 0;
    int fd = -1, shader_fd = -1, status = 1;
    ssize_t shader_bytes;
    fd = opengpu_open(argc > 1 ? argv[1] : NULL);
    if (fd < 0) goto done;
    if (opengpu_context_create(fd, &context) ||
        opengpu_buffer_create(fd, 64, &shader) ||
        opengpu_buffer_create(fd, 64, &kernarg) ||
        opengpu_sync_create(fd, &fence)) goto done;
    shader_fd = open(argc > 2 ? argv[2] : "/opengpu_compute_shader.bin", O_RDONLY);
    if (shader_fd < 0) goto done;
    shader_bytes = read(shader_fd, shader.map, shader.size);
    if (shader_bytes <= 0 || shader_bytes > 64 || (shader_bytes & 3)) {
        errno = EINVAL;
        goto done;
    }
    ((uint32_t *)kernarg.map)[0] = 42;
    binding.context_id = context;
    binding.slot = 1;
    binding.handle = shader.handle;
    binding.type = OPENGPU_RESOURCE_COMPUTE_SHADER;
    binding.size = 64;
    if (opengpu_bind(fd, &binding)) goto done;
    binding.slot = 2;
    binding.handle = kernarg.handle;
    binding.type = OPENGPU_RESOURCE_COMPUTE_KERNARG;
    binding.flags = OPENGPU_RESOURCE_UNCACHED;
    if (opengpu_bind(fd, &binding)) goto done;
    command.context_id = context;
    command.shader_slot = 1;
    command.kernarg_slot = 2;
    command.grid[0] = command.grid[1] = command.grid[2] = 1;
    command.local[0] = command.local[1] = command.local[2] = 1;
    command.out_syncobj = fence;
    if (opengpu_compute(fd, &command) || opengpu_sync_wait_success(fd, fence, 30000))
        goto done;
    if (((uint32_t *)kernarg.map)[1] != 42) {
        errno = EIO;
        goto done;
    }
    puts("compute completed; output=42");
    status = 0;
done:
    if (status) perror("compute");
    if (fence) opengpu_sync_destroy(fd, fence);
    if (context) opengpu_context_destroy(fd, context);
    if (kernarg.handle) opengpu_buffer_destroy(fd, &kernarg);
    if (shader.handle) opengpu_buffer_destroy(fd, &shader);
    if (shader_fd >= 0) close(shader_fd);
    if (fd >= 0) close(fd);
    return status;
}
