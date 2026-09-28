/* SPDX-License-Identifier: MIT */
/* Programmable fragment uniform float: the shader reads a per-draw uniform with
 * flw and broadcasts it with vfadd.vf, then XORs it into the interpolated
 * colour. This is the shape a `uniform float` lowers to.
 * Requires OPENGPU_CAP_FRAGMENT_CORE and OPENGPU_CAP_COMPUTE_SCALAR_FPU.
 * Shader binary from fragment_fp_scalar.S (corpus); default path
 * /opengpu_fragment_fp_scalar.bin. */
#include "../opengpu.h"
#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

/* Shader BO must be a multiple of 64 (instruction window); kernarg BO must be
 * a multiple of GPU_KERNARG_BANK_ALIGN (64). The uniform lives at word 72,
 * which is the first per-draw uniform slot (9 * stride, stride 32). */
enum { FS_GEM_BYTES = 128u, FS_KERNARG_BYTES = 640u };
enum { FS_UNIFORM_WORD = 72 };

/* 5.0f in kernarg[72], broadcast to every lane. */
#define FRAGMENT_FP_SCALAR_UNIFORM 0x40a00000u
/* Interpolated colour 0xfe00ffff XOR the uniform. */
#define FRAGMENT_FP_SCALAR_EXPECTED 0xbea0ffffu

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
        printf("fragment_fp_scalar skipped; needs "
               "OPENGPU_CAP_FRAGMENT_CORE\n");
        close(fd);
        return 0;
    }
    if (!(caps & OPENGPU_CAP_COMPUTE_SCALAR_FPU)) {
        /* Mirror fp_scalar: a skip is a pass, because the guest harness treats
         * any non-zero exit as a failure. */
        printf("fragment_fp_scalar skipped; needs "
               "OPENGPU_CAP_COMPUTE_SCALAR_FPU\n");
        close(fd);
        return 0;
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

    shader_fd = open(argc > 2 ? argv[2] : "/opengpu_fragment_fp_scalar.bin",
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

    memset(kernarg.map, 0, kernarg.size);
    ((uint32_t *)kernarg.map)[FS_UNIFORM_WORD] =
        FRAGMENT_FP_SCALAR_UNIFORM;

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
        if (pixel != FRAGMENT_FP_SCALAR_EXPECTED) {
            fprintf(stderr, "fragment_fp_scalar pixel %u got 0x%08x "
                    "want 0x%08x\n", i, pixel,
                    FRAGMENT_FP_SCALAR_EXPECTED);
            errno = EIO;
            goto done;
        }
        painted++;
    }
    if (!painted) {
        fprintf(stderr, "fragment_fp_scalar rendered no coloured pixels\n");
        errno = EIO;
        goto done;
    }
    printf("fragment_fp_scalar completed; flw uniform 0x%08x through "
           "vfadd.vf, pixels: %u sample=0x%08x\n",
           FRAGMENT_FP_SCALAR_UNIFORM, painted,
           FRAGMENT_FP_SCALAR_EXPECTED);
    status = 0;

done:
    if (shader_fd >= 0)
        close(shader_fd);
    if (fd >= 0) {
        if (fence)
            opengpu_sync_destroy(fd, fence);
        if (kernarg.handle)
            opengpu_buffer_destroy(fd, &kernarg);
        if (shader.handle)
            opengpu_buffer_destroy(fd, &shader);
        if (color.handle)
            opengpu_buffer_destroy(fd, &color);
        if (commands.handle)
            opengpu_buffer_destroy(fd, &commands);
        if (context)
            opengpu_context_destroy(fd, context);
        close(fd);
    }
    return status;
}
