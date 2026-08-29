// SPDX-License-Identifier: MIT
/* Minimal no-libdrm KMS test for the ARTI initramfs. */
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
#include <time.h>
#include <unistd.h>

#include "opengpu_drm.h"

#define ARRAY_SIZE(a) (sizeof(a) / sizeof((a)[0]))
#define TEST_WIDTH 16
#define TEST_HEIGHT 16
#define MIN_FENCE_WAIT_MS 30

struct kms_ids {
    uint32_t connector;
    uint32_t crtc;
    uint32_t plane;
    struct drm_mode_modeinfo mode;
};

struct dumb_fb {
    uint32_t handle;
    uint32_t fb_id;
    uint32_t pitch;
    uint64_t size;
    void *map;
};

static int set_client_cap(int fd, uint64_t capability)
{
    struct drm_set_client_cap cap = {
        .capability = capability,
        .value = 1,
    };

    return ioctl(fd, DRM_IOCTL_SET_CLIENT_CAP, &cap);
}

static uint32_t find_property(int fd, uint32_t object_id,
                              uint32_t object_type, const char *name)
{
    struct drm_mode_obj_get_properties object = {
        .obj_id = object_id,
        .obj_type = object_type,
    };
    uint32_t ids[32] = { 0 };
    uint64_t values[32] = { 0 };
    uint32_t i;

    object.count_props = ARRAY_SIZE(ids);
    object.props_ptr = (uintptr_t)ids;
    object.prop_values_ptr = (uintptr_t)values;
    if (ioctl(fd, DRM_IOCTL_MODE_OBJ_GETPROPERTIES, &object) < 0 ||
        object.count_props > ARRAY_SIZE(ids))
        return 0;

    for (i = 0; i < object.count_props; i++) {
        struct drm_mode_get_property property = { .prop_id = ids[i] };

        if (ioctl(fd, DRM_IOCTL_MODE_GETPROPERTY, &property) == 0 &&
            strcmp(property.name, name) == 0)
            return ids[i];
    }
    return 0;
}

static int find_kms_objects(int fd, struct kms_ids *ids)
{
    struct drm_mode_card_res resources = { 0 };
    struct drm_mode_get_plane_res plane_resources = { 0 };
    uint32_t connectors[8] = { 0 };
    uint32_t crtcs[8] = { 0 };
    uint32_t encoders[8] = { 0 };
    uint32_t framebuffers[8] = { 0 };
    uint32_t planes[8] = { 0 };
    uint32_t i;

    if (ioctl(fd, DRM_IOCTL_MODE_GETRESOURCES, &resources) < 0)
        return -1;
    if (!resources.count_connectors || !resources.count_crtcs ||
        resources.count_connectors > ARRAY_SIZE(connectors) ||
        resources.count_crtcs > ARRAY_SIZE(crtcs) ||
        resources.count_encoders > ARRAY_SIZE(encoders) ||
        resources.count_fbs > ARRAY_SIZE(framebuffers)) {
        errno = ENODEV;
        return -1;
    }

    resources.connector_id_ptr = (uintptr_t)connectors;
    resources.crtc_id_ptr = (uintptr_t)crtcs;
    resources.encoder_id_ptr = (uintptr_t)encoders;
    resources.fb_id_ptr = (uintptr_t)framebuffers;
    if (ioctl(fd, DRM_IOCTL_MODE_GETRESOURCES, &resources) < 0)
        return -1;
    ids->connector = connectors[0];
    ids->crtc = crtcs[0];

    {
        struct drm_mode_get_connector connector = {
            .connector_id = ids->connector,
        };
        struct drm_mode_modeinfo modes[8] = { 0 };
        uint32_t connector_encoders[8] = { 0 };
        uint32_t properties[32] = { 0 };
        uint64_t property_values[32] = { 0 };

        if (ioctl(fd, DRM_IOCTL_MODE_GETCONNECTOR, &connector) < 0 ||
            !connector.count_modes ||
            connector.count_modes > ARRAY_SIZE(modes) ||
            connector.count_encoders > ARRAY_SIZE(connector_encoders) ||
            connector.count_props > ARRAY_SIZE(properties)) {
            errno = ENODEV;
            return -1;
        }
        connector.modes_ptr = (uintptr_t)modes;
        connector.encoders_ptr = (uintptr_t)connector_encoders;
        connector.props_ptr = (uintptr_t)properties;
        connector.prop_values_ptr = (uintptr_t)property_values;
        if (ioctl(fd, DRM_IOCTL_MODE_GETCONNECTOR, &connector) < 0)
            return -1;
        ids->mode = modes[0];
    }

    if (ioctl(fd, DRM_IOCTL_MODE_GETPLANERESOURCES, &plane_resources) < 0 ||
        !plane_resources.count_planes ||
        plane_resources.count_planes > ARRAY_SIZE(planes)) {
        errno = ENODEV;
        return -1;
    }
    plane_resources.plane_id_ptr = (uintptr_t)planes;
    if (ioctl(fd, DRM_IOCTL_MODE_GETPLANERESOURCES, &plane_resources) < 0)
        return -1;

    for (i = 0; i < plane_resources.count_planes; i++) {
        struct drm_mode_get_plane plane = { .plane_id = planes[i] };
        uint32_t formats[16] = { 0 };
        uint32_t j;

        plane.count_format_types = ARRAY_SIZE(formats);
        plane.format_type_ptr = (uintptr_t)formats;
        if (ioctl(fd, DRM_IOCTL_MODE_GETPLANE, &plane) < 0)
            continue;
        if (!(plane.possible_crtcs & 1))
            continue;
        for (j = 0; j < plane.count_format_types; j++) {
            if (formats[j] == DRM_FORMAT_RGBA8888) {
                ids->plane = planes[i];
                return 0;
            }
        }
    }

    errno = ENODEV;
    return -1;
}

static int create_fb(int fd, uint32_t color, struct dumb_fb *fb)
{
    struct drm_mode_create_dumb create = {
        .width = TEST_WIDTH,
        .height = TEST_HEIGHT,
        .bpp = 32,
    };
    struct drm_mode_fb_cmd2 add = {
        .width = TEST_WIDTH,
        .height = TEST_HEIGHT,
        .pixel_format = DRM_FORMAT_RGBA8888,
    };
    struct drm_mode_map_dumb map = { 0 };
    uint32_t x, y;

    if (ioctl(fd, DRM_IOCTL_MODE_CREATE_DUMB, &create) < 0)
        return -1;
    fb->handle = create.handle;
    fb->pitch = create.pitch;
    fb->size = create.size;

    add.handles[0] = fb->handle;
    add.pitches[0] = fb->pitch;
    if (ioctl(fd, DRM_IOCTL_MODE_ADDFB2, &add) < 0)
        return -1;
    fb->fb_id = add.fb_id;

    map.handle = fb->handle;
    if (ioctl(fd, DRM_IOCTL_MODE_MAP_DUMB, &map) < 0)
        return -1;
    fb->map = mmap(NULL, fb->size, PROT_READ | PROT_WRITE, MAP_SHARED,
                   fd, map.offset);
    if (fb->map == MAP_FAILED)
        return -1;

    for (y = 0; y < TEST_HEIGHT; y++) {
        uint32_t *row = (uint32_t *)((uint8_t *)fb->map + y * fb->pitch);

        for (x = 0; x < TEST_WIDTH; x++)
            row[x] = color;
    }
    return 0;
}

static int submit_render(int fd, const struct dumb_fb *fb)
{
    struct drm_opengpu_submit submit = {
        .color_handle = fb->handle,
        .stride = fb->pitch,
        .flags = OPENGPU_SUBMIT_TEST_FENCE_DELAY,
    };

    return ioctl(fd, DRM_IOCTL_OPENGPU_SUBMIT, &submit);
}

static int atomic_modeset(int fd, const struct kms_ids *ids,
                          uint32_t fb_id)
{
    const char *connector_names[] = { "CRTC_ID" };
    const char *crtc_names[] = { "MODE_ID", "ACTIVE" };
    const char *plane_names[] = {
        "FB_ID", "CRTC_ID", "SRC_X", "SRC_Y", "SRC_W", "SRC_H",
        "CRTC_X", "CRTC_Y", "CRTC_W", "CRTC_H",
    };
    uint32_t objects[] = { ids->connector, ids->crtc, ids->plane };
    uint32_t counts[] = { ARRAY_SIZE(connector_names),
                          ARRAY_SIZE(crtc_names), ARRAY_SIZE(plane_names) };
    uint32_t properties[ARRAY_SIZE(connector_names) + ARRAY_SIZE(crtc_names) +
                        ARRAY_SIZE(plane_names)] = { 0 };
    uint64_t values[ARRAY_SIZE(properties)] = { 0 };
    struct drm_mode_create_blob blob = {
        .length = sizeof(ids->mode),
        .data = (uintptr_t)&ids->mode,
    };
    struct drm_mode_atomic atomic = {
        .flags = DRM_MODE_ATOMIC_ALLOW_MODESET,
        .count_objs = ARRAY_SIZE(objects),
        .objs_ptr = (uintptr_t)objects,
        .count_props_ptr = (uintptr_t)counts,
        .props_ptr = (uintptr_t)properties,
        .prop_values_ptr = (uintptr_t)values,
    };
    uint32_t offset = 0;
    uint32_t i;

    for (i = 0; i < ARRAY_SIZE(connector_names); i++)
        properties[offset + i] = find_property(fd, ids->connector,
                                                DRM_MODE_OBJECT_CONNECTOR,
                                                connector_names[i]);
    values[offset++] = ids->crtc;

    if (ioctl(fd, DRM_IOCTL_MODE_CREATEPROPBLOB, &blob) < 0)
        return -1;
    for (i = 0; i < ARRAY_SIZE(crtc_names); i++)
        properties[offset + i] = find_property(fd, ids->crtc,
                                                DRM_MODE_OBJECT_CRTC,
                                                crtc_names[i]);
    values[offset++] = blob.blob_id;
    values[offset++] = 1;

    for (i = 0; i < ARRAY_SIZE(plane_names); i++)
        properties[offset + i] = find_property(fd, ids->plane,
                                                DRM_MODE_OBJECT_PLANE,
                                                plane_names[i]);
    values[offset++] = fb_id;
    values[offset++] = ids->crtc;
    values[offset++] = 0;
    values[offset++] = 0;
    values[offset++] = (uint64_t)TEST_WIDTH << 16;
    values[offset++] = (uint64_t)TEST_HEIGHT << 16;
    values[offset++] = 0;
    values[offset++] = 0;
    values[offset++] = TEST_WIDTH;
    values[offset++] = TEST_HEIGHT;

    for (i = 0; i < ARRAY_SIZE(properties); i++) {
        if (!properties[i]) {
            errno = ENOENT;
            return -1;
        }
    }
    return ioctl(fd, DRM_IOCTL_MODE_ATOMIC, &atomic);
}

static int atomic_page_flip(int fd, const struct kms_ids *ids, uint32_t fb_id)
{
    uint32_t object = ids->plane;
    uint32_t count = 1;
    uint32_t property = find_property(fd, ids->plane, DRM_MODE_OBJECT_PLANE,
                                      "FB_ID");
    uint64_t value = fb_id;
    struct drm_mode_atomic atomic = {
        .count_objs = 1,
        .objs_ptr = (uintptr_t)&object,
        .count_props_ptr = (uintptr_t)&count,
        .props_ptr = (uintptr_t)&property,
        .prop_values_ptr = (uintptr_t)&value,
    };

    if (!property) {
        errno = ENOENT;
        return -1;
    }
    return ioctl(fd, DRM_IOCTL_MODE_ATOMIC, &atomic);
}

static uint64_t monotonic_ms(void)
{
    struct timespec time;

    if (clock_gettime(CLOCK_MONOTONIC, &time) < 0)
        return 0;
    return (uint64_t)time.tv_sec * 1000 + time.tv_nsec / 1000000;
}

int main(void)
{
    struct kms_ids ids = { 0 };
    struct dumb_fb first = { 0 }, second = { 0 };
    uint64_t start;
    int fd;

    fd = open("/dev/dri/card0", O_RDWR | O_CLOEXEC);
    if (fd < 0) {
        perror("OPENGPU USERSPACE DRM FAIL open card0");
        return 1;
    }
#define CHECK(call, stage) do { \
    if ((call) < 0) { \
        perror("OPENGPU USERSPACE DRM FAIL " stage); \
        return 1; \
    } \
} while (0)
    CHECK(set_client_cap(fd, DRM_CLIENT_CAP_UNIVERSAL_PLANES),
          "universal planes");
    CHECK(set_client_cap(fd, DRM_CLIENT_CAP_ATOMIC), "atomic capability");
    CHECK(find_kms_objects(fd, &ids), "resource discovery");
    CHECK(create_fb(fd, 0xff0000ffu, &first), "first dumb buffer");
    CHECK(create_fb(fd, 0x00ff00ffu, &second), "second dumb buffer");
    CHECK(submit_render(fd, &first), "render first buffer");
    start = monotonic_ms();
    CHECK(atomic_modeset(fd, &ids, first.fb_id), "atomic modeset");
    if (monotonic_ms() - start < MIN_FENCE_WAIT_MS) {
        errno = ETIME;
        perror("OPENGPU USERSPACE DRM FAIL modeset skipped fence");
        return 1;
    }
    CHECK(submit_render(fd, &second), "render second buffer");
    start = monotonic_ms();
    CHECK(atomic_page_flip(fd, &ids, second.fb_id), "atomic page flip");
    if (monotonic_ms() - start < MIN_FENCE_WAIT_MS) {
        errno = ETIME;
        perror("OPENGPU USERSPACE DRM FAIL page flip skipped fence");
        return 1;
    }
#undef CHECK

    printf("OPENGPU USERSPACE DRM PASS: fenced render + waited atomic flip\n");
    return 0;
}
