// SPDX-License-Identifier: MIT
/* Keep a small DRM dumb framebuffer on the OpenGPU scanout. */
#include <drm/drm.h>
#include <drm/drm_fourcc.h>
#include <drm/drm_mode.h>

#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <unistd.h>

static void fail(const char *what)
{
    perror(what);
    exit(1);
}

int main(int argc, char **argv)
{
    const char *card = argc > 1 ? argv[1] : "/dev/dri/card0";
    struct drm_mode_card_res resources = { 0 };
    struct drm_mode_get_connector connector = { 0 };
    struct drm_mode_modeinfo mode = { 0 };
    struct drm_mode_create_dumb dumb = { .bpp = 32 };
    struct drm_mode_fb_cmd2 fb = { .pixel_format = DRM_FORMAT_RGBA8888 };
    struct drm_mode_map_dumb map = { 0 };
    struct drm_mode_crtc crtc = { 0 };
    uint32_t connector_id, crtc_id;
    uint32_t encoders[8] = { 0 }, framebuffers[8] = { 0 };
    uint32_t connector_encoders[8] = { 0 }, properties[32] = { 0 };
    uint64_t property_values[32] = { 0 };
    uint8_t *pixels;
    int fd;

    fd = open(card, O_RDWR);
    if (fd < 0)
        fail("open DRM card");
    if (ioctl(fd, DRM_IOCTL_MODE_GETRESOURCES, &resources) < 0)
        fail("get KMS resources");
    if (resources.count_connectors != 1 || resources.count_crtcs != 1 ||
        resources.count_encoders > 8 || resources.count_fbs > 8) {
        errno = ENODEV;
        fail("expected one OpenGPU connector and CRTC");
    }
    resources.connector_id_ptr = (uintptr_t)&connector_id;
    resources.crtc_id_ptr = (uintptr_t)&crtc_id;
    resources.encoder_id_ptr = (uintptr_t)encoders;
    resources.fb_id_ptr = (uintptr_t)framebuffers;
    if (ioctl(fd, DRM_IOCTL_MODE_GETRESOURCES, &resources) < 0)
        fail("get KMS IDs");
    connector.connector_id = connector_id;
    if (ioctl(fd, DRM_IOCTL_MODE_GETCONNECTOR, &connector) < 0 ||
        connector.count_modes != 1 || connector.count_encoders > 8 ||
        connector.count_props > 32) {
        errno = ENODEV;
        fail("get OpenGPU display mode");
    }
    connector.modes_ptr = (uintptr_t)&mode;
    connector.encoders_ptr = (uintptr_t)connector_encoders;
    connector.props_ptr = (uintptr_t)properties;
    connector.prop_values_ptr = (uintptr_t)property_values;
    if (ioctl(fd, DRM_IOCTL_MODE_GETCONNECTOR, &connector) < 0)
        fail("read OpenGPU display mode");

    dumb.width = mode.hdisplay;
    dumb.height = mode.vdisplay;
    if (ioctl(fd, DRM_IOCTL_MODE_CREATE_DUMB, &dumb) < 0)
        fail("create scanout buffer");
    map.handle = dumb.handle;
    if (ioctl(fd, DRM_IOCTL_MODE_MAP_DUMB, &map) < 0)
        fail("map scanout buffer");
    pixels = mmap(NULL, dumb.size, PROT_READ | PROT_WRITE, MAP_SHARED,
                  fd, map.offset);
    if (pixels == MAP_FAILED)
        fail("mmap scanout buffer");
    for (uint32_t y = 0; y < dumb.height; y++) {
        uint32_t *row = (uint32_t *)(pixels + y * dumb.pitch);
        for (uint32_t x = 0; x < dumb.width; x++) {
            uint8_t red = (uint8_t)(255u * x / (dumb.width - 1));
            uint8_t green = (uint8_t)(255u * y / (dumb.height - 1));
            row[x] = ((uint32_t)red << 24) | ((uint32_t)green << 16) |
                     0x40ffu;
        }
    }
    fb.width = dumb.width;
    fb.height = dumb.height;
    fb.pitches[0] = dumb.pitch;
    fb.handles[0] = dumb.handle;
    if (ioctl(fd, DRM_IOCTL_MODE_ADDFB2, &fb) < 0)
        fail("register scanout framebuffer");
    crtc.crtc_id = crtc_id;
    crtc.fb_id = fb.fb_id;
    crtc.set_connectors_ptr = (uintptr_t)&connector_id;
    crtc.count_connectors = 1;
    crtc.mode_valid = 1;
    crtc.mode = mode;
    if (ioctl(fd, DRM_IOCTL_MODE_SETCRTC, &crtc) < 0)
        fail("set OpenGPU display mode");
    printf("OPENGPU KMS PRESENT PASS: %ux%u on %s\n", dumb.width,
           dumb.height, card);
    fflush(stdout);
    for (;;)
        pause();
}
