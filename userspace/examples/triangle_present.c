/* SPDX-License-Identifier: MIT */
/* Render one triangle into the native KMS mode buffer and present it.
 *
 * Closes the ARTI display loop: GPU write → GEM → SETCRTC → QEMU scanout.
 * Fixed-function builds draw a solid red triangle; fragment-core builds load
 * the corpus tint shader (default /opengpu_fragment_tint.bin). Pass --hold
 * (or OPENGPU_PRESENT_HOLD=1) to keep the CRTC programmed for cocoa demos.
 */
#include "../opengpu.h"

#include <drm/drm.h>
#include <drm/drm_fourcc.h>
#include <drm/drm_mode.h>

#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <unistd.h>

enum { FS_GEM_BYTES = 128u, FS_KERNARG_BYTES = 640u };

static int want_hold(int argc, char **argv)
{
    const char *env = getenv("OPENGPU_PRESENT_HOLD");

    if (env && env[0] && strcmp(env, "0") != 0)
        return 1;
    for (int i = 1; i < argc; i++) {
        if (strcmp(argv[i], "--hold") == 0)
            return 1;
    }
    return 0;
}

static const char *card_path(int argc, char **argv)
{
    for (int i = 1; i < argc; i++) {
        if (strcmp(argv[i], "--hold") == 0)
            continue;
        if (argv[i][0] == '-')
            continue;
        return argv[i];
    }
    return "/dev/dri/card0";
}

static const char *shader_path(int argc, char **argv)
{
    int seen_card = 0;

    for (int i = 1; i < argc; i++) {
        if (strcmp(argv[i], "--hold") == 0)
            continue;
        if (argv[i][0] == '-')
            continue;
        if (!seen_card) {
            seen_card = 1;
            continue;
        }
        return argv[i];
    }
    return "/opengpu_fragment_tint.bin";
}

static void fill_triangle(struct drm_opengpu_draw *draw, int tint)
{
    memset(draw, 0, sizeof(*draw));
    draw->v0[0] = -0x10000;
    draw->v0[1] = -0x10000;
    draw->v1[0] = 0x10000;
    draw->v1[1] = -0x10000;
    draw->v2[0] = -0x10000;
    draw->v2[1] = 0x10000;
    draw->v0[3] = draw->v1[3] = draw->v2[3] = 0x10000;
    if (tint) {
        draw->c0[0] = draw->c1[0] = draw->c2[0] = 0xfe;
        draw->c0[1] = draw->c1[1] = draw->c2[1] = 0x00;
        draw->c0[2] = draw->c1[2] = draw->c2[2] = 0xff;
    } else {
        draw->c0[0] = draw->c1[0] = draw->c2[0] = 255;
    }
    draw->d0 = draw->d1 = draw->d2 = 0x10;
}

int main(int argc, char **argv)
{
    struct drm_mode_card_res resources = { 0 };
    struct drm_mode_get_connector connector = { 0 };
    struct drm_mode_modeinfo mode = { 0 };
    struct drm_mode_create_dumb dumb = { .bpp = 32 };
    struct drm_mode_fb_cmd2 fb = { .pixel_format = DRM_FORMAT_RGBA8888 };
    struct drm_mode_map_dumb map = { 0 };
    struct drm_mode_crtc crtc = { 0 };
    struct drm_mode_destroy_dumb destroy = { 0 };
    struct opengpu_buffer commands = { 0 }, shader = { 0 }, kernarg = { 0 };
    struct drm_opengpu_resource binding = { 0 };
    struct drm_opengpu_submit submit = { 0 };
    struct drm_opengpu_draw *draw;
    uint32_t connector_id = 0, crtc_id = 0;
    uint32_t encoders[8] = { 0 }, framebuffers[8] = { 0 };
    uint32_t connector_encoders[8] = { 0 }, properties[32] = { 0 };
    uint64_t property_values[32] = { 0 };
    uint32_t context = 0, fence = 0;
    uint64_t caps = 0;
    uint8_t *pixels = MAP_FAILED;
    unsigned painted = 0;
    int fd = -1, shader_fd = -1, status = 1, fragment = 0, hold;
    ssize_t shader_bytes;

    hold = want_hold(argc, argv);
    fd = opengpu_open(card_path(argc, argv));
    if (fd < 0)
        goto done;
    if (opengpu_capabilities(fd, &caps))
        goto done;
    fragment = !!(caps & OPENGPU_CAP_FRAGMENT_CORE);
    if ((caps & OPENGPU_CAP_VERTEX_CORE) && !fragment) {
        fprintf(stderr, "triangle_present: unexpected vertex-only config\n");
        errno = EINVAL;
        goto done;
    }

    if (ioctl(fd, DRM_IOCTL_MODE_GETRESOURCES, &resources) < 0)
        goto done;
    if (resources.count_connectors != 1 || resources.count_crtcs != 1 ||
        resources.count_encoders > 8 || resources.count_fbs > 8) {
        errno = ENODEV;
        goto done;
    }
    resources.connector_id_ptr = (uintptr_t)&connector_id;
    resources.crtc_id_ptr = (uintptr_t)&crtc_id;
    resources.encoder_id_ptr = (uintptr_t)encoders;
    resources.fb_id_ptr = (uintptr_t)framebuffers;
    if (ioctl(fd, DRM_IOCTL_MODE_GETRESOURCES, &resources) < 0)
        goto done;

    connector.connector_id = connector_id;
    if (ioctl(fd, DRM_IOCTL_MODE_GETCONNECTOR, &connector) < 0 ||
        connector.count_modes != 1 || connector.count_encoders > 8 ||
        connector.count_props > 32) {
        errno = ENODEV;
        goto done;
    }
    connector.modes_ptr = (uintptr_t)&mode;
    connector.encoders_ptr = (uintptr_t)connector_encoders;
    connector.props_ptr = (uintptr_t)properties;
    connector.prop_values_ptr = (uintptr_t)property_values;
    if (ioctl(fd, DRM_IOCTL_MODE_GETCONNECTOR, &connector) < 0)
        goto done;

    dumb.width = mode.hdisplay;
    dumb.height = mode.vdisplay;
    if (ioctl(fd, DRM_IOCTL_MODE_CREATE_DUMB, &dumb) < 0)
        goto done;
    map.handle = dumb.handle;
    if (ioctl(fd, DRM_IOCTL_MODE_MAP_DUMB, &map) < 0)
        goto done;
    pixels = mmap(NULL, dumb.size, PROT_READ | PROT_WRITE, MAP_SHARED, fd,
                  map.offset);
    if (pixels == MAP_FAILED)
        goto done;
    for (uint32_t y = 0; y < dumb.height; y++) {
        uint32_t *row = (uint32_t *)(pixels + y * dumb.pitch);

        for (uint32_t x = 0; x < dumb.width; x++)
            row[x] = 0x102040ffu;
    }

    if (opengpu_context_create(fd, &context) ||
        opengpu_buffer_create(fd, sizeof(struct drm_opengpu_draw), &commands) ||
        opengpu_sync_create(fd, &fence))
        goto done;

    if (fragment) {
        if (opengpu_buffer_create(fd, FS_GEM_BYTES, &shader) ||
            opengpu_buffer_create(fd, FS_KERNARG_BYTES, &kernarg))
            goto done;
        shader_fd = open(shader_path(argc, argv), O_RDONLY);
        if (shader_fd < 0)
            shader_fd = open("/root/opengpu_fragment_tint.bin", O_RDONLY);
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
    }

    draw = commands.map;
    fill_triangle(draw, fragment);

    submit.context_id = context;
    submit.command_handle = commands.handle;
    submit.color_handle = dumb.handle;
    submit.stride = dumb.pitch;
    submit.command_count = 1;
    submit.out_syncobj = fence;
    if (fragment) {
        submit.shader_slot = 2;
        submit.kernarg_slot = 3;
    }
    if (opengpu_render(fd, &submit) ||
        opengpu_sync_wait_success(fd, fence, 300000))
        goto done;

    for (uint32_t y = 0; y < dumb.height; y++) {
        uint32_t *row = (uint32_t *)(pixels + y * dumb.pitch);

        for (uint32_t x = 0; x < dumb.width; x++) {
            if (row[x] != 0x102040ffu)
                painted++;
        }
    }
    if (!painted) {
        fprintf(stderr, "triangle_present rendered no coloured pixels\n");
        errno = EIO;
        goto done;
    }

    fb.width = dumb.width;
    fb.height = dumb.height;
    fb.pitches[0] = dumb.pitch;
    fb.handles[0] = dumb.handle;
    if (ioctl(fd, DRM_IOCTL_MODE_ADDFB2, &fb) < 0)
        goto done;
    crtc.crtc_id = crtc_id;
    crtc.fb_id = fb.fb_id;
    crtc.set_connectors_ptr = (uintptr_t)&connector_id;
    crtc.count_connectors = 1;
    crtc.mode_valid = 1;
    crtc.mode = mode;
    if (ioctl(fd, DRM_IOCTL_MODE_SETCRTC, &crtc) < 0)
        goto done;

    printf("OPENGPU TRIANGLE PRESENT PASS: %ux%u painted=%u%s on %s\n",
           dumb.width, dumb.height, painted, fragment ? " tint" : "",
           card_path(argc, argv));
    fflush(stdout);
    status = 0;
    if (hold) {
        for (;;)
            pause();
    }

done:
    if (status)
        perror("triangle_present");
    if (fence)
        opengpu_sync_destroy(fd, fence);
    if (context)
        opengpu_context_destroy(fd, context);
    if (kernarg.handle)
        opengpu_buffer_destroy(fd, &kernarg);
    if (shader.handle)
        opengpu_buffer_destroy(fd, &shader);
    if (commands.handle)
        opengpu_buffer_destroy(fd, &commands);
    if (shader_fd >= 0)
        close(shader_fd);
    if (pixels != MAP_FAILED)
        munmap(pixels, dumb.size);
    if (dumb.handle && status) {
        destroy.handle = dumb.handle;
        ioctl(fd, DRM_IOCTL_MODE_DESTROY_DUMB, &destroy);
    }
    if (fd >= 0)
        close(fd);
    return status;
}
