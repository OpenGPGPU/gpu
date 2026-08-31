// SPDX-License-Identifier: MIT
/* Minimal no-libdrm KMS test for the ARTI initramfs. */
#include <drm/drm.h>
#include <drm/drm_fourcc.h>
#include <drm/drm_mode.h>

#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <stdbool.h>
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
#define FLIP_EVENT_COOKIE UINT64_C(0x4f50454e475055)

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

struct command_buffer {
    uint32_t handle;
    uint64_t size;
    struct drm_opengpu_draw *map;
};

struct resource_buffer {
    uint32_t handle;
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

static int get_capabilities(int fd, uint64_t *capabilities)
{
    struct drm_opengpu_param param = {
        .param = OPENGPU_PARAM_CAPABILITIES,
    };

    if (ioctl(fd, DRM_IOCTL_OPENGPU_GET_PARAM, &param) < 0)
        return -1;
    *capabilities = param.value;
    return 0;
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

static int create_command_buffer(int fd, struct command_buffer *commands)
{
    struct drm_mode_create_dumb create = {
        .width = sizeof(struct drm_opengpu_draw) / 4,
        .height = 1,
        .bpp = 32,
    };
    struct drm_mode_map_dumb map = { 0 };
    struct drm_opengpu_draw *draw;

    if (ioctl(fd, DRM_IOCTL_MODE_CREATE_DUMB, &create) < 0)
        return -1;
    commands->handle = create.handle;
    commands->size = create.size;
    map.handle = commands->handle;
    if (ioctl(fd, DRM_IOCTL_MODE_MAP_DUMB, &map) < 0)
        return -1;
    commands->map = mmap(NULL, commands->size, PROT_READ | PROT_WRITE,
                         MAP_SHARED, fd, map.offset);
    if (commands->map == MAP_FAILED)
        return -1;

    draw = commands->map;
    memset(draw, 0, sizeof(*draw));
    draw->v0[0] = -0x10000; draw->v0[1] = -0x10000;
    draw->v0[3] = 0x10000;
    draw->v1[0] =  0x10000; draw->v1[1] = -0x10000;
    draw->v1[3] = 0x10000;
    draw->v2[0] = -0x10000; draw->v2[1] =  0x10000;
    draw->v2[3] = 0x10000;
    draw->c0[0] = 255; draw->c0[1] = 255; draw->c0[2] = 255;
    draw->c1[0] = 255; draw->c1[1] = 255; draw->c1[2] = 255;
    draw->c2[0] = 255; draw->c2[1] = 255; draw->c2[2] = 255;
    draw->d0 = 0x10;
    draw->d1 = 0x10;
    draw->d2 = 0x10;
    return 0;
}

static int create_texture_buffer(int fd, struct resource_buffer *texture)
{
    struct drm_mode_create_dumb create = {
        .width = 1,
        .height = 1,
        .bpp = 32,
    };
    struct drm_mode_map_dumb map = { 0 };

    if (ioctl(fd, DRM_IOCTL_MODE_CREATE_DUMB, &create) < 0)
        return -1;
    texture->handle = create.handle;
    texture->size = create.size;
    map.handle = texture->handle;
    if (ioctl(fd, DRM_IOCTL_MODE_MAP_DUMB, &map) < 0)
        return -1;
    texture->map = mmap(NULL, texture->size, PROT_READ | PROT_WRITE,
                        MAP_SHARED, fd, map.offset);
    if (texture->map == MAP_FAILED)
        return -1;
    *(uint32_t *)texture->map = 0xff0000ffu;
    return 0;
}

static int create_resource_buffer(int fd, uint32_t bytes,
                                  struct resource_buffer *buffer)
{
    struct drm_mode_create_dumb create = {
        .width = (bytes + 3) / 4,
        .height = 1,
        .bpp = 32,
    };
    struct drm_mode_map_dumb map = { 0 };

    if (ioctl(fd, DRM_IOCTL_MODE_CREATE_DUMB, &create) < 0)
        return -1;
    buffer->handle = create.handle;
    buffer->size = create.size;
    map.handle = buffer->handle;
    if (ioctl(fd, DRM_IOCTL_MODE_MAP_DUMB, &map) < 0)
        return -1;
    buffer->map = mmap(NULL, buffer->size, PROT_READ | PROT_WRITE,
                       MAP_SHARED, fd, map.offset);
    return buffer->map == MAP_FAILED ? -1 : 0;
}

static int create_context(int fd, uint32_t *context_id)
{
    struct drm_opengpu_context context = { 0 };

    if (ioctl(fd, DRM_IOCTL_OPENGPU_CONTEXT_CREATE, &context) < 0)
        return -1;
    *context_id = context.id;
    return context.id ? 0 : -1;
}

static int destroy_context(int fd, uint32_t context_id)
{
    struct drm_opengpu_context context = { .id = context_id };

    return ioctl(fd, DRM_IOCTL_OPENGPU_CONTEXT_DESTROY, &context);
}

static int bind_texture(int fd, uint32_t context_id, uint32_t slot,
                        const struct resource_buffer *texture)
{
    struct drm_opengpu_resource resource = {
        .context_id = context_id,
        .slot = slot,
        .handle = texture->handle,
        .type = OPENGPU_RESOURCE_TEXTURE,
        .size = 4,
        .width = 1,
        .height = 1,
        .flags = OPENGPU_RESOURCE_TEXTURE_CLAMP,
    };

    return ioctl(fd, DRM_IOCTL_OPENGPU_RESOURCE_BIND, &resource);
}

static int bind_resource(int fd, uint32_t context_id, uint32_t slot,
                         const struct resource_buffer *buffer, uint32_t type,
                         uint64_t size)
{
    struct drm_opengpu_resource resource = {
        .context_id = context_id,
        .slot = slot,
        .handle = buffer->handle,
        .type = type,
        .size = size,
    };

    return ioctl(fd, DRM_IOCTL_OPENGPU_RESOURCE_BIND, &resource);
}

static int unbind_resource(int fd, uint32_t context_id, uint32_t slot)
{
    struct drm_opengpu_resource resource = {
        .context_id = context_id,
        .slot = slot,
    };

    return ioctl(fd, DRM_IOCTL_OPENGPU_RESOURCE_UNBIND, &resource);
}

static int submit_render(int fd, uint32_t context_id,
                         const struct command_buffer *commands,
                         const struct dumb_fb *fb, uint32_t texture_slot,
                         uint32_t shader_slot, uint32_t kernarg_slot,
                         uint32_t in_syncobj, uint32_t out_syncobj)
{
    struct drm_opengpu_submit submit = {
        .context_id = context_id,
        .command_handle = commands->handle,
        .color_handle = fb->handle,
        .stride = fb->pitch,
        .command_count = 1,
        .flags = OPENGPU_SUBMIT_TEST_FENCE_DELAY,
        .texture_slot = texture_slot,
        .shader_slot = shader_slot,
        .kernarg_slot = kernarg_slot,
        .in_syncobj = in_syncobj,
        .out_syncobj = out_syncobj,
    };

    return ioctl(fd, DRM_IOCTL_OPENGPU_SUBMIT, &submit);
}

static int reject_unsafe_command(int fd, uint32_t context_id,
                                 struct command_buffer *commands,
                                 const struct dumb_fb *fb)
{
    int ret;

    commands->map->shader_pc = 4;
    errno = 0;
    ret = submit_render(fd, context_id, commands, fb, 1, 0, 0, 0, 0);
    commands->map->shader_pc = 0;
    if (ret == -1 && errno == EINVAL)
        return 0;
    errno = EPROTO;
    return -1;
}

static int reject_shader_submit(int fd, uint32_t context_id,
                                const struct command_buffer *commands,
                                const struct dumb_fb *fb, uint32_t texture_slot,
                                int expected_errno)
{
    struct drm_opengpu_submit submit = {
        .context_id = context_id,
        .command_handle = commands->handle,
        .color_handle = fb->handle,
        .stride = fb->pitch,
        .command_count = 1,
        .texture_slot = texture_slot,
        .shader_slot = 2,
        .kernarg_slot = 3,
    };

    errno = 0;
    if (ioctl(fd, DRM_IOCTL_OPENGPU_SUBMIT, &submit) == -1 &&
        errno == expected_errno)
        return 0;
    errno = EPROTO;
    return -1;
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
    uint32_t objects[] = { ids->crtc, ids->plane };
    uint32_t counts[] = { 1, 1 };
    uint32_t properties[] = {
        find_property(fd, ids->crtc, DRM_MODE_OBJECT_CRTC, "ACTIVE"),
        find_property(fd, ids->plane, DRM_MODE_OBJECT_PLANE, "FB_ID"),
    };
    uint64_t values[] = { 1, fb_id };
    struct drm_mode_atomic atomic = {
        .flags = DRM_MODE_ATOMIC_NONBLOCK | DRM_MODE_PAGE_FLIP_EVENT,
        .count_objs = ARRAY_SIZE(objects),
        .objs_ptr = (uintptr_t)objects,
        .count_props_ptr = (uintptr_t)counts,
        .props_ptr = (uintptr_t)properties,
        .prop_values_ptr = (uintptr_t)values,
        .user_data = FLIP_EVENT_COOKIE,
    };

    if (!properties[0] || !properties[1]) {
        errno = ENOENT;
        return -1;
    }
    return ioctl(fd, DRM_IOCTL_MODE_ATOMIC, &atomic);
}

static int wait_flip_event(int fd, struct drm_event_vblank *event)
{
    struct pollfd pollfd = {
        .fd = fd,
        .events = POLLIN,
    };
    ssize_t length;
    int ret;

    ret = poll(&pollfd, 1, 1000);
    if (ret <= 0) {
        if (!ret)
            errno = ETIME;
        return -1;
    }
    length = read(fd, event, sizeof(*event));
    if (length != sizeof(*event)) {
        if (length >= 0)
            errno = EPROTO;
        return -1;
    }
    if (event->base.type != DRM_EVENT_FLIP_COMPLETE ||
        event->base.length != sizeof(*event) ||
        event->user_data != FLIP_EVENT_COOKIE) {
        errno = EPROTO;
        return -1;
    }
    return 0;
}

static uint64_t monotonic_ms(void)
{
    struct timespec time;

    if (clock_gettime(CLOCK_MONOTONIC, &time) < 0)
        return 0;
    return (uint64_t)time.tv_sec * 1000 + time.tv_nsec / 1000000;
}

static uint32_t framebuffer_count(const struct dumb_fb *fb, uint32_t pixel)
{
    uint32_t count = 0;
    uint32_t x, y;

    for (y = 0; y < TEST_HEIGHT; y++) {
        const uint32_t *row = (const uint32_t *)
            ((const uint8_t *)fb->map + y * fb->pitch);

        for (x = 0; x < TEST_WIDTH; x++)
            count += row[x] == pixel;
    }
    return count;
}

static void write_vector_shader(void *mapping)
{
    uint32_t *program = mapping;

    /* Sample one texture per lane; warp zero keeps red and warp one adds one. */
    program[0] = 0x00241293u; /* slli x5,x8,2 */
    program[1] = 0x005082b3u; /* add x5,x1,x5 */
    program[2] = 0xc1027057u; /* vsetivli x0,4,e32,m1,ta,ma */
    program[3] = 0x0610812bu; /* vtex.sample v2,v1,v1 */
    program[4] = 0x00040463u; /* beq x8,x0,+8 */
    program[5] = 0x0220b157u; /* vadd.vi v2,v2,1 */
    program[6] = 0x08028313u; /* addi x6,x5,128 */
    program[7] = 0x02036127u; /* vse32.v v2,(x6) */
    program[8] = 0x30500073u; /* cease */
}

static int create_syncobj(int fd, uint32_t *handle)
{
    struct drm_syncobj_create create = { 0 };

    if (ioctl(fd, DRM_IOCTL_SYNCOBJ_CREATE, &create) < 0)
        return -1;
    *handle = create.handle;
    return 0;
}

static int wait_syncobjs_timeout(int fd, uint32_t *handles, uint32_t count,
                                 int64_t timeout_nsec)
{
    struct drm_syncobj_wait wait = {
        .handles = (uintptr_t)handles,
        .count_handles = count,
        .flags = DRM_SYNCOBJ_WAIT_FLAGS_WAIT_ALL |
                 DRM_SYNCOBJ_WAIT_FLAGS_WAIT_FOR_SUBMIT,
        .timeout_nsec = timeout_nsec,
    };

    return ioctl(fd, DRM_IOCTL_SYNCOBJ_WAIT, &wait);
}

static int wait_syncobjs(int fd, uint32_t *handles, uint32_t count)
{
    struct timespec now;

    if (clock_gettime(CLOCK_MONOTONIC, &now) < 0)
        return -1;
    return wait_syncobjs_timeout(fd, handles, count,
                                 (int64_t)now.tv_sec * 1000000000ll +
                                 now.tv_nsec + 10000000000ll);
}

int main(void)
{
    struct kms_ids ids = { 0 };
    struct dumb_fb first = { 0 }, second = { 0 };
    struct command_buffer commands = { 0 };
    struct resource_buffer texture = { 0 };
    struct resource_buffer shader = { 0 }, kernarg = { 0 };
    struct drm_event_vblank event = { 0 };
    uint32_t syncobjs[3] = { 0 };
    uint32_t output_syncobjs[2];
    uint64_t capabilities;
    uint32_t batch_capacity;
    uint32_t texture_slot, shader_slot, kernarg_slot;
    uint32_t expected_pixel, alternate_pixel = 0;
    bool frag_core;
    uint64_t start;
    uint32_t context_id;
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
    CHECK(get_capabilities(fd, &capabilities), "query GPU capabilities");
    frag_core = capabilities & OPENGPU_CAP_FRAGMENT_CORE;
    batch_capacity = (capabilities & OPENGPU_CAP_FRAGMENT_BATCH_MASK) >>
                     OPENGPU_CAP_FRAGMENT_BATCH_SHIFT;
    if (frag_core && batch_capacity != 8) {
        errno = EPROTO;
        perror("OPENGPU USERSPACE DRM FAIL fragment batch capacity");
        return 1;
    }
    CHECK(find_kms_objects(fd, &ids), "resource discovery");
    CHECK(create_fb(fd, 0x000000ffu, &first), "first dumb buffer");
    CHECK(create_fb(fd, 0x000000ffu, &second), "second dumb buffer");
    CHECK(create_command_buffer(fd, &commands), "command buffer");
    if (frag_core) {
        uint32_t i;

        for (i = 0; i < 3; i++) {
            commands.map->c0[i] = (i + 1) * 0x10;
            commands.map->c1[i] = (i + 1) * 0x10;
            commands.map->c2[i] = (i + 1) * 0x10;
        }
    }
    CHECK(create_texture_buffer(fd, &texture), "texture buffer");
    CHECK(create_resource_buffer(fd, 64, &shader), "shader buffer");
    CHECK(create_resource_buffer(fd, 192, &kernarg), "kernarg buffer");
    write_vector_shader(shader.map);
    CHECK(create_context(fd, &context_id), "create render context");
    CHECK(bind_texture(fd, context_id, 1, &texture), "bind texture");
    CHECK(bind_resource(fd, context_id, 2, &shader,
                        OPENGPU_RESOURCE_SHADER, 64), "bind shader");
    CHECK(bind_resource(fd, context_id, 3, &kernarg,
                        OPENGPU_RESOURCE_KERNARG, 192), "bind kernarg");
    if (frag_core) {
        CHECK(reject_shader_submit(fd, context_id, &commands, &first, 0,
                                   EINVAL),
              "require texture for vtex.sample");
        ((uint32_t *)shader.map)[7] = 0x0200e127u; /* vse32.v v2,(x1) */
        CHECK(reject_shader_submit(fd, context_id, &commands, &first, 1,
                                   EINVAL),
              "reject unsafe vector fragment shader");
        write_vector_shader(shader.map);
        texture_slot = 1;
        shader_slot = 2;
        kernarg_slot = 3;
        expected_pixel = 0xff0001ffu;
        alternate_pixel = 0xff0000ffu;
    } else {
        CHECK(reject_shader_submit(fd, context_id, &commands, &first, 1,
                                   EOPNOTSUPP),
              "gate fragment shader capability");
        CHECK(reject_unsafe_command(fd, context_id, &commands, &first),
              "reject unsafe command");
        texture_slot = 1;
        shader_slot = 0;
        kernarg_slot = 0;
        expected_pixel = 0xfe0000ffu;
    }
    CHECK(create_syncobj(fd, &syncobjs[1]), "create first output syncobj");
    CHECK(create_syncobj(fd, &syncobjs[2]), "create second output syncobj");
    CHECK(submit_render(fd, context_id, &commands, &first, texture_slot,
                        shader_slot, kernarg_slot, 0, syncobjs[1]),
          "queue first buffer");
    CHECK(submit_render(fd, context_id, &commands, &second, texture_slot,
                        shader_slot, kernarg_slot, syncobjs[1], syncobjs[2]),
          "queue second buffer");
    output_syncobjs[0] = syncobjs[1];
    output_syncobjs[1] = syncobjs[2];
    errno = 0;
    if (wait_syncobjs_timeout(fd, output_syncobjs,
                              ARRAY_SIZE(output_syncobjs), 0) != -1 ||
        errno != ETIME) {
        errno = EPROTO;
        perror("OPENGPU USERSPACE DRM FAIL queued jobs already completed");
        return 1;
    }
    start = monotonic_ms();
    CHECK(atomic_modeset(fd, &ids, first.fb_id), "atomic modeset");
    if (monotonic_ms() - start < MIN_FENCE_WAIT_MS) {
        errno = ETIME;
        perror("OPENGPU USERSPACE DRM FAIL modeset skipped fence");
        return 1;
    }
    if ((frag_core &&
         (framebuffer_count(&first, expected_pixel) != 60 ||
          framebuffer_count(&first, alternate_pixel) != 60)) ||
        (!frag_core &&
         *(uint32_t *)((uint8_t *)first.map + first.pitch + 4) !=
             expected_pixel)) {
        errno = EIO;
        perror("OPENGPU USERSPACE DRM FAIL texture result");
        return 1;
    }
    CHECK(atomic_page_flip(fd, &ids, second.fb_id), "atomic page flip");
    CHECK(wait_flip_event(fd, &event), "wait flip event");
    CHECK(wait_syncobjs(fd, output_syncobjs, ARRAY_SIZE(output_syncobjs)),
          "wait output syncobjs");
    if ((frag_core &&
         (framebuffer_count(&second, expected_pixel) != 60 ||
          framebuffer_count(&second, alternate_pixel) != 60)) ||
        (!frag_core &&
         *(uint32_t *)((uint8_t *)second.map + second.pitch + 4) !=
             expected_pixel)) {
        errno = EIO;
        perror("OPENGPU USERSPACE DRM FAIL queued texture result");
        return 1;
    }
    if (frag_core)
        CHECK(unbind_resource(fd, context_id, 2), "unbind shader");
    else
        CHECK(unbind_resource(fd, context_id, 1), "unbind texture");
    errno = 0;
    if (submit_render(fd, context_id, &commands, &second, texture_slot,
                      shader_slot, kernarg_slot, 0, 0) != -1 ||
        errno != EINVAL) {
        errno = EPROTO;
        perror("OPENGPU USERSPACE DRM FAIL unbound texture accepted");
        return 1;
    }
    CHECK(destroy_context(fd, context_id), "destroy render context");
    errno = 0;
    if (submit_render(fd, context_id, &commands, &second, texture_slot,
                      shader_slot, kernarg_slot, 0, 0) != -1 ||
        errno != ENOENT) {
        errno = EPROTO;
        perror("OPENGPU USERSPACE DRM FAIL destroyed context accepted");
        return 1;
    }
#undef CHECK

    printf("OPENGPU USERSPACE DRM PASS: queued %s render + explicit "
           "syncobj + validated vtex/forward-branch sandbox + "
           "validated context + "
           "vblank flip event sequence=%u\n",
           frag_core ? "core-backed" : "texture", event.sequence);
    return 0;
}
