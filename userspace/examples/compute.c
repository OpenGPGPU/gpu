/* SPDX-License-Identifier: MIT */
/* Launch a minimal validated shader and wait for its completion fence. */
#include "../opengpu.h"
#include <errno.h>
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
    int fd = -1, status = 1;
    fd = opengpu_open(argc > 1 ? argv[1] : NULL);
    if (fd < 0) goto done;
    if (opengpu_context_create(fd, &context) ||
        opengpu_buffer_create(fd, 64, &shader) ||
        opengpu_buffer_create(fd, 64, &kernarg) ||
        opengpu_sync_create(fd, &fence)) goto done;
    ((uint32_t *)shader.map)[0] = 0x30500073u; /* OpenGPU cease */
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
    puts("compute completed");
    status = 0;
done:
    if (status) perror("compute");
    if (fence) opengpu_sync_destroy(fd, fence);
    if (context) opengpu_context_destroy(fd, context);
    if (kernarg.handle) opengpu_buffer_destroy(fd, &kernarg);
    if (shader.handle) opengpu_buffer_destroy(fd, &shader);
    if (fd >= 0) close(fd);
    return status;
}
