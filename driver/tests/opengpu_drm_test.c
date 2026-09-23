// SPDX-License-Identifier: MIT
/* Minimal no-libdrm KMS test for the ARTI initramfs. */
#include <drm/drm.h>
#include <drm/drm_fourcc.h>
#include <drm/drm_mode.h>
#include <linux/dma-buf.h>
#include <linux/sync_file.h>

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

/* Stencil/blend encodings (GL order), mirroring gpu_abi.h.  The UAPI masks
 * above carry the field layout; these name the values. */
#define TEST_STENCIL_FUNC_ALWAYS 6u
#define TEST_STENCIL_FUNC_EQUAL  4u
#define TEST_STENCIL_OP_REPLACE  2u
#define TEST_DEPTH_FUNC_LEQUAL    1u
#define TEST_DEPTH_FUNC_GREATER    2u
#define TEST_BLEND_FACTOR_ONE    1u
#define TEST_BLEND_EQ_REV_SUB    2u

/* The state word and the per-draw blend config share word indices across the
 * fixed-function and vertex-core draw-record forms. */
#define DRAW_RECORD_STATE_WORD   32u
#define DRAW_RECORD_BLEND_WORD   35u

#define ARRAY_SIZE(a) (sizeof(a) / sizeof((a)[0]))
/* Overridable so the ARTI runner can compile the guest test for the same
 * resolution the RTL was elaborated with (-DTEST_WIDTH/-DTEST_HEIGHT). */
#ifndef TEST_WIDTH
#define TEST_WIDTH 16
#endif
#ifndef TEST_HEIGHT
#define TEST_HEIGHT 16
#endif
#if (TEST_WIDTH & (TEST_WIDTH - 1)) != 0 || (TEST_HEIGHT & (TEST_HEIGHT - 1)) != 0 || \
    TEST_WIDTH < 16 || TEST_HEIGHT < 16
#error "TEST_WIDTH/TEST_HEIGHT must be powers of two and at least 16"
#endif
#define MIN_FENCE_WAIT_MS 30
#define FLIP_EVENT_COOKIE UINT64_C(0x4f50454e475055)

/* Recovery-contract guard: the safe-reset fault bits the driver records must
 * fit the mask GET_FAULT validates against, or a real recovery would be
 * reported to userspace as a malformed snapshot. */
#if defined(__STDC_VERSION__) && __STDC_VERSION__ >= 201112L
_Static_assert((OPENGPU_FAULT_RESET_ISSUED | OPENGPU_FAULT_RESET_TIMEOUT) ==
               ((OPENGPU_FAULT_RESET_ISSUED | OPENGPU_FAULT_RESET_TIMEOUT) &
                OPENGPU_FAULT_FLAGS_MASK),
               "safe-reset fault flags must fit OPENGPU_FAULT_FLAGS_MASK");
#endif

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

struct vertex_data {
    int32_t x, y, z, w;
    uint32_t color;
    int32_t depth;
    uint32_t u, v;
};

struct resource_buffer {
    uint32_t handle;
    uint64_t size;
    void *map;
};

static int create_resource_buffer(int fd, uint32_t bytes,
                                  struct resource_buffer *buffer);

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

static int get_last_fault(int fd, struct drm_opengpu_fault *fault)
{
    return ioctl(fd, DRM_IOCTL_OPENGPU_GET_FAULT, fault);
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

/* Allocate a raw dumb buffer of width_px x height_px words and fill it with
 * `color`.  Unlike create_fb this does not register a framebuffer: it backs an
 * intermediate surface, such as a multisample render target whose row pitch is
 * wider than the scanned-out width. */
static int create_dumb_buffer(int fd, uint32_t width_px, uint32_t height_px,
                              uint32_t color, struct dumb_fb *fb)
{
    struct drm_mode_create_dumb create = {
        .width = width_px,
        .height = height_px,
        .bpp = 32,
    };
    struct drm_mode_map_dumb map = { 0 };
    uint32_t i;

    if (ioctl(fd, DRM_IOCTL_MODE_CREATE_DUMB, &create) < 0)
        return -1;
    fb->handle = create.handle;
    fb->pitch = create.pitch;
    fb->size = create.size;
    map.handle = fb->handle;
    if (ioctl(fd, DRM_IOCTL_MODE_MAP_DUMB, &map) < 0)
        return -1;
    fb->map = mmap(NULL, fb->size, PROT_READ | PROT_WRITE, MAP_SHARED,
                   fd, map.offset);
    if (fb->map == MAP_FAILED)
        return -1;
    for (i = 0; i < fb->size / sizeof(uint32_t); i++)
        ((uint32_t *)fb->map)[i] = color;
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
    /* Eight repeats over 16 pixels => two base texels/pixel on a 4x4
     * texture, selecting mip 1 in the quad-backed fragment path. */
    draw->uv0[0] = 0;       draw->uv0[1] = 0;
    draw->uv1[0] = 0x80000; draw->uv1[1] = 0;
    draw->uv2[0] = 0;       draw->uv2[1] = 0x80000;
    draw->state = OPENGPU_DRAW_STATE_OVERRIDE |
        OPENGPU_DRAW_STATE_DEPTH_TEST |
        (0u << OPENGPU_DRAW_STATE_DEPTH_FUNC_SHIFT) |
        OPENGPU_DRAW_STATE_DEPTH_WRITE |
        OPENGPU_DRAW_STATE_TEX_ENABLE |
        OPENGPU_DRAW_STATE_TEX_CLAMP |
        (2u << OPENGPU_DRAW_STATE_MAX_MIP_SHIFT);
    /* Pass-through blend config (src ONE, dst ZERO, ADD): the new word 35
     * must decode without changing the expected texel colours. */
    draw->blend_config = OPENGPU_DRAW_BLEND_PRESENT |
        (TEST_BLEND_FACTOR_ONE << OPENGPU_DRAW_BLEND_SRC_SHIFT);
    return 0;
}

static void convert_to_vertex_command(struct command_buffer *commands)
{
    struct drm_opengpu_vertex_draw *draw =
        (struct drm_opengpu_vertex_draw *)commands->map;

    memset(draw, 0, sizeof(*draw));
    draw->vertex_count = 3;
    draw->vertex_stride = sizeof(struct vertex_data);
    draw->fragment_kernarg_bank_stride = 320;
    draw->state = OPENGPU_DRAW_STATE_OVERRIDE |
        OPENGPU_DRAW_STATE_DEPTH_TEST |
        (0u << OPENGPU_DRAW_STATE_DEPTH_FUNC_SHIFT) |
        OPENGPU_DRAW_STATE_DEPTH_WRITE |
        OPENGPU_DRAW_STATE_TEX_ENABLE |
        OPENGPU_DRAW_STATE_TEX_CLAMP |
        (2u << OPENGPU_DRAW_STATE_MAX_MIP_SHIFT);
    /* Match the fixed-function record: pass-through blend must not alter the
     * sampled texel colours the DRM checks assert on. */
    draw->blend_config = OPENGPU_DRAW_BLEND_PRESENT |
        (TEST_BLEND_FACTOR_ONE << OPENGPU_DRAW_BLEND_SRC_SHIFT);
}

/* Three-record command buffer that proves the D24S8 stencil path: record 0
 * stamps stencil 0x5a over the triangle, record 1 passes EQUAL 0x5a and
 * blends the covered pixels to zero (REV_SUB of identical src/dst), and
 * record 2 (EQUAL 0x33) must be blocked by the stored stencil byte.
 * Both 40-word record forms share state/blend/stencil word indices. */
static int create_stencil_command_buffer(
    int fd, const struct command_buffer *base, struct command_buffer *commands)
{
    struct drm_mode_create_dumb create = {
        .width = (uint32_t)(3 * sizeof(struct drm_opengpu_draw) / 4),
        .height = 1,
        .bpp = 32,
    };
    struct drm_mode_map_dumb map = { 0 };
    const uint32_t *src = (const uint32_t *)base->map;
    uint32_t *draws;
    uint32_t base_state = src[DRAW_RECORD_STATE_WORD];
    uint32_t stencil_state =
        (base_state & ~OPENGPU_DRAW_STATE_DEPTH_FUNC_MASK) |
        (TEST_DEPTH_FUNC_LEQUAL << OPENGPU_DRAW_STATE_DEPTH_FUNC_SHIFT) |
        OPENGPU_DRAW_STATE_STENCIL_TEST;
    uint32_t masks = OPENGPU_DRAW_STENCIL_RMASK_MASK |
                     OPENGPU_DRAW_STENCIL_WMASK_MASK;
    uint32_t record_words = (uint32_t)(sizeof(struct drm_opengpu_draw) / 4);
    uint32_t i;

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

    draws = (uint32_t *)commands->map;
    for (i = 0; i < 3; i++)
        memcpy(draws + i * record_words, src,
               sizeof(struct drm_opengpu_draw));
    /* Stamp: func ALWAYS, z-pass REPLACE 0x5a. */
    draws[0 * record_words + DRAW_RECORD_STATE_WORD] = stencil_state;
    draws[0 * record_words + 36] = TEST_STENCIL_FUNC_ALWAYS |
        (TEST_STENCIL_OP_REPLACE << OPENGPU_DRAW_STENCIL_ZPASS_SHIFT);
    draws[0 * record_words + 37] = 0x5au | masks;
    /* Pass: func EQUAL 0x5a; the blend subtracts the destination from
     * itself, so a passing fragment visibly zeroes the covered pixels. */
    draws[1 * record_words + DRAW_RECORD_STATE_WORD] = stencil_state;
    draws[1 * record_words + 36] = TEST_STENCIL_FUNC_EQUAL;
    draws[1 * record_words + 37] = 0x5au | masks;
    draws[1 * record_words + DRAW_RECORD_BLEND_WORD] =
        OPENGPU_DRAW_BLEND_PRESENT |
        (TEST_BLEND_FACTOR_ONE << OPENGPU_DRAW_BLEND_SRC_SHIFT) |
        (TEST_BLEND_FACTOR_ONE << OPENGPU_DRAW_BLEND_DST_SHIFT) |
        (TEST_BLEND_EQ_REV_SUB << OPENGPU_DRAW_BLEND_EQ_SHIFT);
    /* Block: func EQUAL 0x33 never matches the stamped byte. */
    draws[2 * record_words + DRAW_RECORD_STATE_WORD] = stencil_state;
    draws[2 * record_words + 36] = TEST_STENCIL_FUNC_EQUAL;
    draws[2 * record_words + 37] = 0x33u | masks;
    return 0;
}

/* Copy a draw command record and apply a state patch.  Both 40-word record
 * forms (fixed-function and vertex-core) keep `state` at the same word index,
 * so a continuation record can be derived without decoding the form. */
static int create_continuation_buffer(
    int fd, const struct command_buffer *base, struct command_buffer *commands,
    uint32_t state_mask, uint32_t state_set, uint32_t blend_config)
{
    struct drm_mode_create_dumb create = {
        .width = sizeof(struct drm_opengpu_draw) / 4,
        .height = 1,
        .bpp = 32,
    };
    struct drm_mode_map_dumb map = { 0 };
    const uint32_t *base_words = (const uint32_t *)base->map;
    uint32_t *words;
    size_t copy_bytes;

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

    copy_bytes = commands->size < base->size ? commands->size : base->size;
    memcpy(commands->map, base->map, copy_bytes);
    words = (uint32_t *)commands->map;
    words[DRAW_RECORD_STATE_WORD] =
        (base_words[DRAW_RECORD_STATE_WORD] & ~state_mask) | state_set;
    words[DRAW_RECORD_BLEND_WORD] = blend_config;
    return 0;
}

static int create_vertex_buffer(int fd, struct resource_buffer *buffer)
{
    static const struct vertex_data vertices[] = {
        { -0x10000, -0x10000, 0, 0x10000,
          0x101010ffu, 0x10, 0,       0 },
        {  0x10000, -0x10000, 0, 0x10000,
          0x202020ffu, 0x10, 0x80000, 0 },
        { -0x10000,  0x10000, 0, 0x10000,
          0x303030ffu, 0x10, 0,       0x80000 },
    };

    if (create_resource_buffer(fd, sizeof(vertices), buffer) < 0)
        return -1;
    memcpy(buffer->map, vertices, sizeof(vertices));
    return 0;
}

static int create_texture_buffer(int fd, struct resource_buffer *texture)
{
    struct drm_mode_create_dumb create = {
        .width = 21,
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
    /* Packed chain: 4x4 green, 2x2 red, 1x1 blue. */
    {
        uint32_t *texels = texture->map;
        uint32_t i;

        for (i = 0; i < 16; i++)
            texels[i] = 0x00ff00ffu;
        for (i = 16; i < 20; i++)
            texels[i] = 0xff0000ffu;
        texels[20] = 0x0000ffffu;
    }
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
        .size = 21 * sizeof(uint32_t),
        .width = 4,
        .height = 4,
        .flags = OPENGPU_RESOURCE_TEXTURE_CLAMP |
            (2u << OPENGPU_RESOURCE_TEXTURE_MAX_MIP_SHIFT) |
            OPENGPU_RESOURCE_UNCACHED,
    };

    return ioctl(fd, DRM_IOCTL_OPENGPU_RESOURCE_BIND, &resource);
}

static int reject_truncated_mip_chain(int fd, uint32_t context_id,
                                      uint32_t slot,
                                      const struct resource_buffer *texture)
{
    struct drm_opengpu_resource resource = {
        .context_id = context_id,
        .slot = slot,
        .handle = texture->handle,
        .type = OPENGPU_RESOURCE_TEXTURE,
        .size = 16 * sizeof(uint32_t), /* base only; advertised chain is 21 */
        .width = 4,
        .height = 4,
        .flags = OPENGPU_RESOURCE_TEXTURE_CLAMP |
            (2u << OPENGPU_RESOURCE_TEXTURE_MAX_MIP_SHIFT),
    };

    errno = 0;
    if (ioctl(fd, DRM_IOCTL_OPENGPU_RESOURCE_BIND, &resource) != -1 ||
        errno != EINVAL) {
        errno = EPROTO;
        return -1;
    }
    return 0;
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

static int submit_render_count(int fd, uint32_t context_id,
                               const struct command_buffer *commands,
                               const struct dumb_fb *fb, uint32_t count,
                               uint32_t texture_slot, uint32_t shader_slot,
                               uint32_t kernarg_slot, uint32_t sample_mode,
                               uint32_t in_syncobj, uint32_t out_syncobj)
{
    struct drm_opengpu_submit submit = {
        .context_id = context_id,
        .command_handle = commands->handle,
        .color_handle = fb->handle,
        .stride = fb->pitch,
        .command_count = count,
        .flags = OPENGPU_SUBMIT_TEST_FENCE_DELAY,
        .texture_slot = texture_slot,
        .shader_slot = shader_slot,
        .kernarg_slot = kernarg_slot,
        .sample_mode = sample_mode,
        .in_syncobj = in_syncobj,
        .out_syncobj = out_syncobj,
    };

    return ioctl(fd, DRM_IOCTL_OPENGPU_SUBMIT, &submit);
}

static int submit_render(int fd, uint32_t context_id,
                         const struct command_buffer *commands,
                         const struct dumb_fb *fb, uint32_t texture_slot,
                         uint32_t shader_slot, uint32_t kernarg_slot,
                         uint32_t sample_mode, uint32_t in_syncobj,
                         uint32_t out_syncobj)
{
    return submit_render_count(fd, context_id, commands, fb, 1, texture_slot,
                               shader_slot, kernarg_slot, sample_mode,
                               in_syncobj, out_syncobj);
}

static int submit_vertex_render(
    int fd, uint32_t context_id, const struct command_buffer *commands,
    const struct dumb_fb *fb, uint32_t texture_slot,
    uint32_t fragment_shader_slot, uint32_t fragment_kernarg_slot,
    uint32_t vertex_buffer_slot, uint32_t vertex_shader_slot,
    uint32_t vertex_kernarg_slot, uint32_t sample_mode, uint32_t in_syncobj,
    uint32_t out_syncobj)
{
    struct drm_opengpu_submit submit = {
        .context_id = context_id,
        .command_handle = commands->handle,
        .color_handle = fb->handle,
        .stride = fb->pitch,
        .command_count = 1,
        .flags = OPENGPU_SUBMIT_TEST_FENCE_DELAY |
                 OPENGPU_SUBMIT_VERTEX_CORE,
        .texture_slot = texture_slot,
        .shader_slot = fragment_shader_slot,
        .kernarg_slot = fragment_kernarg_slot,
        .sample_mode = sample_mode,
        .in_syncobj = in_syncobj,
        .out_syncobj = out_syncobj,
        .vertex_buffer_slot = vertex_buffer_slot,
        .vertex_shader_slot = vertex_shader_slot,
        .vertex_kernarg_slot = vertex_kernarg_slot,
    };

    return ioctl(fd, DRM_IOCTL_OPENGPU_SUBMIT, &submit);
}

static int submit_selected_render(
    int fd, bool vertex_core, uint32_t context_id,
    const struct command_buffer *commands, const struct dumb_fb *fb,
    uint32_t texture_slot, uint32_t fragment_shader_slot,
    uint32_t fragment_kernarg_slot, uint32_t vertex_buffer_slot,
    uint32_t vertex_shader_slot, uint32_t vertex_kernarg_slot,
    uint32_t sample_mode, uint32_t in_syncobj, uint32_t out_syncobj)
{
    if (vertex_core)
        return submit_vertex_render(
            fd, context_id, commands, fb, texture_slot,
            fragment_shader_slot, fragment_kernarg_slot,
            vertex_buffer_slot, vertex_shader_slot, vertex_kernarg_slot,
            sample_mode, in_syncobj, out_syncobj);
    return submit_render(fd, context_id, commands, fb, texture_slot,
                         fragment_shader_slot, fragment_kernarg_slot,
                         sample_mode, in_syncobj, out_syncobj);
}

/* Render submission that binds a caller-owned persistent depth/stencil
 * attachment.  The vertex-core slots mirror submit_selected_render; `flags`
 * carries OPENGPU_SUBMIT_DEPTH_LOAD (or zero to clear the bound plane) and is
 * combined with the fence-delay and vertex-core selector bits. */
static int submit_depth_render(
    int fd, bool vertex_core, uint32_t context_id,
    const struct command_buffer *commands, const struct dumb_fb *fb,
    uint32_t count, uint32_t texture_slot, uint32_t shader_slot,
    uint32_t kernarg_slot, uint32_t vertex_buffer_slot,
    uint32_t vertex_shader_slot, uint32_t vertex_kernarg_slot,
    uint32_t sample_mode, uint32_t depth_handle, uint64_t depth_offset,
    uint32_t flags, uint32_t in_syncobj, uint32_t out_syncobj)
{
    struct drm_opengpu_submit submit = {
        .context_id = context_id,
        .command_handle = commands->handle,
        .color_handle = fb->handle,
        .stride = fb->pitch,
        .command_count = count,
        .flags = OPENGPU_SUBMIT_TEST_FENCE_DELAY | flags |
                 (vertex_core ? OPENGPU_SUBMIT_VERTEX_CORE : 0),
        .texture_slot = texture_slot,
        .shader_slot = shader_slot,
        .kernarg_slot = kernarg_slot,
        .sample_mode = sample_mode,
        .depth_handle = depth_handle,
        .depth_offset = depth_offset,
        .in_syncobj = in_syncobj,
        .out_syncobj = out_syncobj,
        .vertex_buffer_slot = vertex_buffer_slot,
        .vertex_shader_slot = vertex_shader_slot,
        .vertex_kernarg_slot = vertex_kernarg_slot,
    };

    return ioctl(fd, DRM_IOCTL_OPENGPU_SUBMIT, &submit);
}

static int submit_blit(int fd, uint32_t context_id,
                       const struct dumb_fb *source,
                       const struct dumb_fb *destination,
                       uint64_t source_offset, uint64_t destination_offset,
                       uint64_t bytes, uint32_t in_syncobj,
                       uint32_t out_syncobj)
{
    struct drm_opengpu_blit blit = {
        .context_id = context_id,
        .source_handle = source->handle,
        .destination_handle = destination->handle,
        .source_offset = source_offset,
        .destination_offset = destination_offset,
        .bytes = bytes,
        .in_syncobj = in_syncobj,
        .out_syncobj = out_syncobj,
    };

    return ioctl(fd, DRM_IOCTL_OPENGPU_BLIT, &blit);
}

static int submit_fill(int fd, uint32_t context_id,
                       const struct dumb_fb *destination,
                       uint64_t destination_offset, uint64_t bytes,
                       uint32_t pattern, uint32_t in_syncobj,
                       uint32_t out_syncobj)
{
    struct drm_opengpu_fill fill = {
        .context_id = context_id,
        .destination_handle = destination->handle,
        .pattern = pattern,
        .destination_offset = destination_offset,
        .bytes = bytes,
        .in_syncobj = in_syncobj,
        .out_syncobj = out_syncobj,
    };

    return ioctl(fd, DRM_IOCTL_OPENGPU_FILL, &fill);
}

static int submit_strided_blit(int fd, uint32_t context_id,
                               const struct dumb_fb *source,
                               const struct dumb_fb *destination,
                               uint64_t source_offset,
                               uint64_t destination_offset,
                               uint32_t width_bytes, uint32_t height,
                               uint32_t source_stride,
                               uint32_t destination_stride,
                               uint32_t in_syncobj,
                               uint32_t out_syncobj, uint32_t flags,
                               uint32_t wait_event, uint32_t signal_event)
{
    struct drm_opengpu_strided_blit blit = {
        .context_id = context_id,
        .source_handle = source->handle,
        .destination_handle = destination->handle,
        .source_offset = source_offset,
        .destination_offset = destination_offset,
        .width_bytes = width_bytes,
        .height = height,
        .source_stride = source_stride,
        .destination_stride = destination_stride,
        .in_syncobj = in_syncobj,
        .out_syncobj = out_syncobj,
        .flags = flags,
        .wait_event = wait_event,
        .signal_event = signal_event,
    };

    return ioctl(fd, DRM_IOCTL_OPENGPU_STRIDED_BLIT, &blit);
}

static int submit_resolve(int fd, uint32_t context_id,
                          const struct dumb_fb *source,
                          const struct dumb_fb *destination,
                          uint64_t source_offset, uint64_t destination_offset,
                          uint32_t width, uint32_t height,
                          uint32_t source_stride, uint32_t destination_stride,
                          uint32_t sample_mode, uint32_t in_syncobj,
                          uint32_t out_syncobj, uint32_t flags)
{
    struct drm_opengpu_resolve resolve = {
        .context_id = context_id,
        .source_handle = source->handle,
        .destination_handle = destination->handle,
        .source_offset = source_offset,
        .destination_offset = destination_offset,
        .width = width,
        .height = height,
        .source_stride = source_stride,
        .destination_stride = destination_stride,
        .sample_mode = sample_mode,
        .in_syncobj = in_syncobj,
        .out_syncobj = out_syncobj,
        .flags = flags,
    };

    return ioctl(fd, DRM_IOCTL_OPENGPU_RESOLVE, &resolve);
}

static int submit_invalidate(int fd, uint32_t context_id, uint32_t handle,
                             uint64_t offset, uint64_t bytes,
                             uint32_t in_syncobj, uint32_t out_syncobj)
{
    struct drm_opengpu_invalidate invalidate = {
        .context_id = context_id,
        .handle = handle,
        .offset = offset,
        .bytes = bytes,
        .in_syncobj = in_syncobj,
        .out_syncobj = out_syncobj,
    };

    return ioctl(fd, DRM_IOCTL_OPENGPU_INVALIDATE, &invalidate);
}

static int submit_compute(int fd, uint32_t context_id, uint32_t shader_slot,
                          uint32_t kernarg_slot, uint32_t local_x,
                          uint32_t in_syncobj, uint32_t out_syncobj,
                          uint32_t flags, uint32_t wait_event,
                          uint32_t signal_event)
{    struct drm_opengpu_compute compute = {
        .context_id = context_id,
        .shader_slot = shader_slot,
        .kernarg_slot = kernarg_slot,
        .grid = { 1, 1, 1 },
        .local = { local_x, 1, 1 },
        .in_syncobj = in_syncobj,
        .out_syncobj = out_syncobj,
        .flags = flags,
        .wait_event = wait_event,
        .signal_event = signal_event,
    };

    return ioctl(fd, DRM_IOCTL_OPENGPU_COMPUTE, &compute);
}

static int fill_resource(int fd, uint32_t context_id,
                         const struct resource_buffer *destination,
                         uint32_t pattern, uint32_t out_syncobj,
                         uint32_t signal_event)
{
    struct drm_opengpu_fill fill = {
        .context_id = context_id,
        .destination_handle = destination->handle,
        .pattern = pattern,
        .bytes = 64,
        .out_syncobj = out_syncobj,
        .flags = OPENGPU_COMMAND_SIGNAL_EVENT,
        .signal_event = signal_event,
    };

    return ioctl(fd, DRM_IOCTL_OPENGPU_FILL, &fill);
}

static void write_compute_shader(void *mapping)
{
    uint32_t *program = mapping;

    program[0] = 0xc1027057u; /* vsetivli x0,4,e32,m1,ta,ma */
    program[1] = 0x02103157u; /* vadd.vi v2,v1,0: define old vd */
    program[2] = 0x3a10b157u; /* vslideup.vi v2,v1,1 */
    program[3] = 0x3e20b1d7u; /* vslidedown.vi v3,v2,1 */
    program[4] = 0x02103257u; /* vadd.vi v4,v1,0: define reduction vd */
    program[5] = 0x0230a257u; /* vredsum.vs v4,v3,v1: result 3 */
    program[6] = 0x324032d7u; /* vrgather.vi v5,v4,0: broadcast 3 */
    program[7] = 0x00200313u; /* addi x6,x0,2 */
    program[8] = 0x96536357u; /* vmul.vx v6,v5,x6 */
    program[9] = 0x7210b057u; /* vmsleu.vi v0,v1,1: enable lanes 0-1 */
    program[10] = 0x0000e327u; /* vse32.v v6,(x1),v0.t */
    program[11] = 0x021033d7u; /* vadd.vi v7,v1,0: define masked-load vd */
    program[12] = 0x0000e387u; /* vle32.v v7,(x1),v0.t */
    program[13] = 0x00800393u; /* addi x7,x0,8: signed byte stride */
    program[14] = 0x0a70e3a7u; /* vsse32.v v7,(x1),x7 */
    program[15] = 0x0a70e407u; /* vlse32.v v8,(x1),x7 */
    program[16] = 0x0200e427u; /* vse32.v v8,(x1) */
    program[17] = 0x961134d7u; /* vsll.vi v9,v1,2: trusted byte indices */
    program[18] = 0x0690e507u; /* vluxei32.v v10,(x1),v9 */
    program[19] = 0x0200e527u; /* vse32.v v10,(x1) */
    program[20] = 0x30500073u; /* cease */
}

static int reject_unsafe_command(int fd, uint32_t context_id,
                                 struct command_buffer *commands,
                                 const struct dumb_fb *fb)
{
    int ret;

    commands->map->shader_pc = 4;
    errno = 0;
    ret = submit_render(fd, context_id, commands, fb, 1, 0, 0, 0, 0, 0);
    commands->map->shader_pc = 0;
    if (ret != -1 || errno != EINVAL)
        goto fail;

    commands->map->state &= ~OPENGPU_DRAW_STATE_MAX_MIP_MASK;
    commands->map->state |= 3u << OPENGPU_DRAW_STATE_MAX_MIP_SHIFT;
    errno = 0;
    ret = submit_render(fd, context_id, commands, fb, 1, 0, 0, 0, 0, 0);
    commands->map->state &= ~OPENGPU_DRAW_STATE_MAX_MIP_MASK;
    commands->map->state |= 2u << OPENGPU_DRAW_STATE_MAX_MIP_SHIFT;
    if (ret != -1 || errno != EINVAL)
        goto fail;

    commands->map->reserved[0] = 1;
    errno = 0;
    ret = submit_render(fd, context_id, commands, fb, 1, 0, 0, 0, 0, 0);
    commands->map->reserved[0] = 0;
    if (ret != -1 || errno != EINVAL)
        goto fail;

    commands->map->sampler = 3u << OPENGPU_DRAW_SAMPLER_MIN_LOD_SHIFT;
    errno = 0;
    ret = submit_render(fd, context_id, commands, fb, 1, 0, 0, 0, 0, 0);
    commands->map->sampler = 0;
    if (ret != -1 || errno != EINVAL)
        goto fail;

    /* Reserved blend source factor (11-15 are rejected). */
    commands->map->blend_config = OPENGPU_DRAW_BLEND_PRESENT |
        (11u << OPENGPU_DRAW_BLEND_SRC_SHIFT);
    errno = 0;
    ret = submit_render(fd, context_id, commands, fb, 1, 0, 0, 0, 0, 0);
    commands->map->blend_config = 0;
    if (ret != -1 || errno != EINVAL)
        goto fail;

    /* Reserved blend equation (5-7 are rejected). */
    commands->map->blend_config = OPENGPU_DRAW_BLEND_PRESENT |
        (5u << OPENGPU_DRAW_BLEND_EQ_SHIFT);
    errno = 0;
    ret = submit_render(fd, context_id, commands, fb, 1, 0, 0, 0, 0, 0);
    commands->map->blend_config = 0;
    if (ret != -1 || errno != EINVAL)
        goto fail;

    /* Stencil words without the stencil-test enable bit. */
    commands->map->stencil_ref = 1u;
    errno = 0;
    ret = submit_render(fd, context_id, commands, fb, 1, 0, 0, 0, 0, 0);
    commands->map->stencil_ref = 0;
    if (ret == -1 && errno == EINVAL)
        return 0;
fail:
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

    /* A nonblocking atomic commit waits for the preceding render fence in its
     * worker. Under instruction-level RTL simulation that draw takes roughly
     * 21 host seconds, while guest virtual time advances much more slowly. */
    ret = poll(&pollfd, 1, 5000);
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

/* Rounded half-up channel average of packed RGBA8888 samples, mirroring the
 * resolve datapath (MsaaResolveEngine): each 8-bit channel is summed over the
 * samples and divided by the power-of-two count after adding count/2. */
static uint32_t resolve_average(const uint32_t *samples, uint32_t count)
{
    uint32_t word = 0;
    uint32_t channel, index;

    for (channel = 0; channel < 4; channel++) {
        uint32_t sum = 0;

        for (index = 0; index < count; index++)
            sum += (samples[index] >> (8 * channel)) & 0xffu;
        word |= (((sum + count / 2) / count) & 0xffu) << (8 * channel);
    }
    return word;
}

static void write_vector_shader(void *mapping)
{
    uint32_t *program = mapping;

    /* Load quad UVs, sample per lane, write dFdx(u); warp zero discards. */
    program[0] = 0x00241293u; /* slli x5,x8,2 */
    program[1] = 0x005082b3u; /* add x5,x1,x5 */
    program[2] = 0xc1027057u; /* vsetivli x0,4,e32,m1,ta,ma */
    program[3] = 0x08028313u; /* addi x6,x5,128: u */
    program[4] = 0x02036087u; /* vle32.v v1,(x6) */
    program[5] = 0x0a028313u; /* addi x6,x5,160: v */
    program[6] = 0x02036107u; /* vle32.v v2,(x6) */
    program[7] = 0x0620812bu; /* vtex.sample v2,v1,v2 */
    program[8] = 0x00041a63u; /* bne x8,x0,+20 */
    program[9] = 0x2e1081d7u; /* vxor.vv v3,v1,v1 */
    program[10] = 0x10028313u; /* addi x6,x5,256: valid */
    program[11] = 0x020361a7u; /* vse32.v v3,(x6): discard warp zero */
    program[12] = 0x30500073u; /* cease discarded warp early */
    program[13] = 0x04028313u; /* addi x6,x5,64: input depth */
    program[14] = 0x02036207u; /* vle32.v v4,(x6) */
    program[15] = 0x3210022bu; /* vquad.dfdx v4,v1 */
    program[16] = 0x0e028313u; /* addi x6,x5,224: output depth */
    program[17] = 0x02036227u; /* vse32.v v4,(x6) */
    program[18] = 0x0c028313u; /* addi x6,x5,192: output colour */
    program[19] = 0x02036127u; /* vse32.v v2,(x6) */
    program[20] = 0x30500073u; /* cease live warp */
}

static void write_vertex_shader(void *mapping)
{
    uint32_t *program = mapping;
    uint32_t pc = 0;
    uint32_t field;

    /* x8 is the first local invocation represented by this four-lane warp.
     * Copy all eight input SoA slices to their transformed-output slices. */
    program[pc++] = 0x00241293u; /* slli x5,x8,2 */
    program[pc++] = 0x005082b3u; /* add x5,x1,x5 */
    program[pc++] = 0xc1027057u; /* vsetivli x0,4,e32,m1,ta,ma */
    for (field = 0; field < 8; field++) {
        uint32_t input_offset = field * 32;
        uint32_t output_offset = (8 + field) * 32;

        program[pc++] = (input_offset << 20) | 0x00028313u;
        program[pc++] = 0x02036087u; /* vle32.v v1,(x6) */
        program[pc++] = (output_offset << 20) | 0x00028313u;
        program[pc++] = 0x020360a7u; /* vse32.v v1,(x6) */
    }
    program[pc] = 0x30500073u; /* cease */
}

static int create_syncobj(int fd, uint32_t *handle)
{
    struct drm_syncobj_create create = { 0 };

    if (ioctl(fd, DRM_IOCTL_SYNCOBJ_CREATE, &create) < 0)
        return -1;
    *handle = create.handle;
    return 0;
}

static int set_mmu_fail_private_maps(unsigned int count)
{
    char value[16];
    int fd = open("/sys/module/gpu_drv/parameters/mmu_fail_private_maps",
                  O_WRONLY | O_CLOEXEC);
    int length = snprintf(value, sizeof(value), "%u\n", count);
    ssize_t written;

    if (fd < 0)
        return -1;
    written = write(fd, value, length);
    close(fd);
    return written == length ? 0 : -1;
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
                                 now.tv_nsec + 300000000000ll);
}

static int expect_syncobj_status(int fd, uint32_t handle, int expected)
{
    struct drm_syncobj_handle export = {
        .handle = handle,
        .flags = DRM_SYNCOBJ_HANDLE_TO_FD_FLAGS_EXPORT_SYNC_FILE,
    };
    struct sync_file_info info = { 0 };
    int ret;

    if (wait_syncobjs(fd, &handle, 1) < 0)
        return -1;
    if (ioctl(fd, DRM_IOCTL_SYNCOBJ_HANDLE_TO_FD, &export) < 0)
        return -1;
    ret = ioctl(export.fd, SYNC_IOC_FILE_INFO, &info);
    close(export.fd);
    if (ret < 0)
        return -1;
    if (info.status != expected) {
        fprintf(stderr, "syncobj %u status=%d expected=%d\n",
                handle, info.status, expected);
        errno = EPROTO;
        return -1;
    }
    return 0;
}

int main(void)
{
    struct kms_ids ids = { 0 };
    struct dumb_fb first = { 0 }, second = { 0 };
    struct dumb_fb strided_source = { 0 }, strided_destination = { 0 };
    struct command_buffer commands = { 0 };
    struct command_buffer stencil_commands = { 0 };
    struct resource_buffer texture = { 0 };
    struct resource_buffer shader = { 0 }, kernarg = { 0 };
    struct resource_buffer vertex_buffer = { 0 };
    struct resource_buffer vertex_shader = { 0 }, vertex_kernarg = { 0 };
    struct resource_buffer compute_shader = { 0 }, compute_kernarg = { 0 };
    struct drm_event_vblank event = { 0 };
    struct drm_opengpu_fault initial_fault = { 0 }, final_fault = { 0 };
    uint32_t syncobjs[8] = { 0 };
    uint32_t failure_syncobjs[2] = { 0 };
    uint32_t output_syncobjs[2];
    uint64_t capabilities;
    uint32_t batch_capacity;
    uint32_t msaa_max_mode;
    uint32_t texture_slot, shader_slot, kernarg_slot;
    uint32_t vertex_buffer_slot = 0, vertex_shader_slot = 0;
    uint32_t vertex_kernarg_slot = 0;
    uint32_t expected_pixel, alternate_pixel = 0;
    bool frag_core, vert_core, msaa_capable;
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
    CHECK(get_last_fault(fd, &initial_fault), "query initial GPU fault");
    if (!!initial_fault.sequence !=
        !!(initial_fault.flags & OPENGPU_FAULT_VALID) ||
        (initial_fault.flags & ~OPENGPU_FAULT_FLAGS_MASK)) {
        errno = EPROTO;
        perror("OPENGPU USERSPACE DRM FAIL malformed fault snapshot");
        return 1;
    }
    {
        struct drm_opengpu_fault invalid_fault = { .pad = { 1, 0 } };

        errno = 0;
        if (get_last_fault(fd, &invalid_fault) != -1 || errno != EINVAL) {
            errno = EPROTO;
            perror("OPENGPU USERSPACE DRM FAIL fault reserved fields");
            return 1;
        }
    }
    frag_core = capabilities & OPENGPU_CAP_FRAGMENT_CORE;
    vert_core = capabilities & OPENGPU_CAP_VERTEX_CORE;
    batch_capacity = (capabilities & OPENGPU_CAP_FRAGMENT_BATCH_MASK) >>
                     OPENGPU_CAP_FRAGMENT_BATCH_SHIFT;
    msaa_capable = !!(capabilities & OPENGPU_CAP_MSAA);
    msaa_max_mode = (capabilities & OPENGPU_CAP_MSAA_MAX_MODE_MASK) >>
                    OPENGPU_CAP_MSAA_MAX_MODE_SHIFT;
    /* MSAA is advertised on both backends - the backend is selected by
     * OPENGPU_CAP_FRAGMENT_CORE.  A capable build must report a non-zero max
     * sample mode and an incapable build must report a zero one. */
    if ((msaa_capable && msaa_max_mode < 1) ||
        (!msaa_capable && msaa_max_mode != 0)) {
        errno = EPROTO;
        perror("OPENGPU USERSPACE DRM FAIL MSAA capabilities");
        return 1;
    }
    if (frag_core && batch_capacity != 8) {
        errno = EPROTO;
        perror("OPENGPU USERSPACE DRM FAIL fragment batch capacity");
        return 1;
    }
    if (vert_core && !frag_core) {
        errno = EPROTO;
        perror("OPENGPU USERSPACE DRM FAIL vertex core without shared CU");
        return 1;
    }
    if (!(capabilities & OPENGPU_CAP_PERSISTENT_DEPTH)) {
        errno = EPROTO;
        perror("OPENGPU USERSPACE DRM FAIL persistent depth capability");
        return 1;
    }
    CHECK(find_kms_objects(fd, &ids), "resource discovery");
    CHECK(create_fb(fd, 0x000000ffu, &first), "first dumb buffer");
    CHECK(create_fb(fd, 0x000000ffu, &second), "second dumb buffer");
    CHECK(create_fb(fd, 0, &strided_source), "strided source buffer");
    CHECK(create_fb(fd, 0, &strided_destination),
          "strided destination buffer");
    CHECK(create_command_buffer(fd, &commands), "command buffer");
    if (vert_core)
        convert_to_vertex_command(&commands);
    if (frag_core && !vert_core) {
        uint32_t i;

        commands.map->kernarg_bank_stride = 320;

        for (i = 0; i < 3; i++) {
            commands.map->c0[i] = (i + 1) * 0x10;
            commands.map->c1[i] = (i + 1) * 0x10;
            commands.map->c2[i] = (i + 1) * 0x10;
        }
    }
    CHECK(create_texture_buffer(fd, &texture), "texture buffer");
    CHECK(create_resource_buffer(fd, 128, &shader), "shader buffer");
    CHECK(create_resource_buffer(fd, 640, &kernarg), "kernarg buffer");
    CHECK(create_resource_buffer(fd, 128, &compute_shader),
          "compute shader buffer");
    CHECK(create_resource_buffer(fd, 64, &compute_kernarg),
          "compute kernarg buffer");
    write_vector_shader(shader.map);
    write_compute_shader(compute_shader.map);
    ((uint32_t *)compute_kernarg.map)[0] = 0xcafe0001u;
    if (vert_core) {
        CHECK(create_vertex_buffer(fd, &vertex_buffer), "vertex buffer");
        CHECK(create_resource_buffer(fd, 256, &vertex_shader),
              "vertex shader buffer");
        CHECK(create_resource_buffer(fd, 512, &vertex_kernarg),
              "vertex kernarg buffer");
        write_vertex_shader(vertex_shader.map);
    }
    CHECK(create_context(fd, &context_id), "create render context");
    CHECK(reject_truncated_mip_chain(fd, context_id, 1, &texture),
          "reject truncated mip chain");
    CHECK(bind_texture(fd, context_id, 1, &texture), "bind texture");
    CHECK(bind_resource(fd, context_id, 2, &shader,
                        OPENGPU_RESOURCE_SHADER, 128), "bind shader");
    CHECK(bind_resource(fd, context_id, 3, &kernarg,
                        OPENGPU_RESOURCE_KERNARG, 640), "bind kernarg");
    CHECK(bind_resource(fd, context_id, 7, &compute_shader,
                        OPENGPU_RESOURCE_COMPUTE_SHADER, 128),
          "bind compute shader");
    CHECK(bind_resource(fd, context_id, 8, &compute_kernarg,
                        OPENGPU_RESOURCE_COMPUTE_KERNARG, 64),
          "bind compute kernarg");
    if (vert_core) {
        CHECK(bind_resource(fd, context_id, 4, &vertex_buffer,
                            OPENGPU_RESOURCE_VERTEX_BUFFER,
                            3 * sizeof(struct vertex_data)),
              "bind vertex buffer");
        CHECK(bind_resource(fd, context_id, 5, &vertex_shader,
                            OPENGPU_RESOURCE_VERTEX_SHADER, 256),
              "bind vertex shader");
        CHECK(bind_resource(fd, context_id, 6, &vertex_kernarg,
                            OPENGPU_RESOURCE_VERTEX_KERNARG, 512),
              "bind vertex kernarg");
        vertex_buffer_slot = 4;
        vertex_shader_slot = 5;
        vertex_kernarg_slot = 6;
    }
    if (frag_core) {
        if (!vert_core) {
            CHECK(reject_shader_submit(fd, context_id, &commands, &first, 0,
                                       EINVAL),
                  "require texture for vtex.sample");
            ((uint32_t *)shader.map)[19] = 0x0200e127u; /* vse32.v v2,(x1) */
            CHECK(reject_shader_submit(fd, context_id, &commands, &first, 1,
                                       EINVAL),
                  "reject unsafe vector fragment shader");
            write_vector_shader(shader.map);
        }
        texture_slot = 1;
        shader_slot = 2;
        kernarg_slot = 3;
        expected_pixel = 0xff0000ffu;
        alternate_pixel = 0xff0001ffu;
    } else {
        CHECK(reject_shader_submit(fd, context_id, &commands, &first, 1,
                                   EOPNOTSUPP),
              "gate fragment shader capability");
        CHECK(reject_unsafe_command(fd, context_id, &commands, &first),
              "reject unsafe command");
        texture_slot = 1;
        shader_slot = 0;
        kernarg_slot = 0;
        expected_pixel = 0x00fe00ffu;
    }
    CHECK(create_syncobj(fd, &syncobjs[1]), "create first output syncobj");
    CHECK(create_syncobj(fd, &syncobjs[2]), "create second output syncobj");
    CHECK(create_syncobj(fd, &syncobjs[3]), "create blit output syncobj");
    CHECK(create_syncobj(fd, &syncobjs[4]), "create fill output syncobj");
    CHECK(create_syncobj(fd, &syncobjs[5]),
          "create strided blit output syncobj");
    CHECK(create_syncobj(fd, &syncobjs[6]),
          "create compute output syncobj");
    CHECK(create_syncobj(fd, &syncobjs[7]),
          "create stencil output syncobj");
    CHECK(create_syncobj(fd, &failure_syncobjs[0]),
          "create compute mapping-failure syncobj");
    CHECK(create_syncobj(fd, &failure_syncobjs[1]),
          "create render mapping-failure syncobj");
    CHECK(fill_resource(fd, context_id, &compute_kernarg, 0xcafe0001u,
                        syncobjs[4], OPENGPU_COMMAND_EVENT(7, 1)),
          "initialize compute kernarg and signal hardware event");
    errno = 0;
    if (submit_compute(fd, context_id, 7, 8, 1, 0, syncobjs[6], 0,
                       OPENGPU_COMMAND_EVENT(7, 1), 0) != -1 ||
        errno != EINVAL) {
        errno = EPROTO;
        perror("OPENGPU USERSPACE DRM FAIL unflagged event accepted");
        return 1;
    }
    errno = 0;
    if (submit_compute(fd, context_id, 7, 8, 33, 0, syncobjs[6], 0, 0,
                       0) != -1 ||
        errno != EINVAL) {
        errno = EPROTO;
        perror("OPENGPU USERSPACE DRM FAIL oversized workgroup accepted");
        return 1;
    }
    /* The ioctl queues successfully; run_job then fails to map its private
     * code snapshot. The finished fence must carry ENOMEM, and the failed
     * kernel must leave the kernarg untouched for the next submission. */
    CHECK(set_mmu_fail_private_maps(1),
          "arm compute submission mapping failure");
    CHECK(submit_compute(fd, context_id, 7, 8, 4, 0,
                         failure_syncobjs[0], OPENGPU_COMMAND_WAIT_EVENT,
                         OPENGPU_COMMAND_EVENT(7, 1), 0),
          "queue compute with mapping failure");
    CHECK(expect_syncobj_status(fd, failure_syncobjs[0], -ENOMEM),
          "compute mapping-failure fence status");
    CHECK(set_mmu_fail_private_maps(0),
          "clear compute submission mapping failure");
    if (((uint32_t *)compute_kernarg.map)[0] != 0xcafe0001u) {
        errno = EIO;
        perror("OPENGPU USERSPACE DRM FAIL failed compute wrote kernarg");
        return 1;
    }
    CHECK(submit_compute(
              fd, context_id, 7, 8, 4, 0, syncobjs[6],
              OPENGPU_COMMAND_WAIT_EVENT | OPENGPU_COMMAND_SIGNAL_EVENT,
              OPENGPU_COMMAND_EVENT(7, 1),
              OPENGPU_COMMAND_EVENT(8, 1)),
          "queue event-dependent general compute");
    CHECK(wait_syncobjs(fd, &syncobjs[6], 1), "wait compute syncobj");
    for (uint32_t lane = 0; lane < 4; lane++) {
        uint32_t expected = lane < 2 ? 6u : lane;

        if (((uint32_t *)compute_kernarg.map)[lane] != expected) {
            fprintf(stderr,
                    "compute kernarg lane=%u output=0x%08x expected=%u\n",
                    lane, ((uint32_t *)compute_kernarg.map)[lane], expected);
            errno = EIO;
            perror("OPENGPU USERSPACE DRM FAIL reduction compute result");
            return 1;
        }
    }
    /* A second kernel before the first job-ring draw used to leave stale GPU
     * L2 lines over reused coherent snapshot pages and drop coverage. */
    CHECK(fill_resource(fd, context_id, &compute_kernarg, 0xcafe0001u,
                        syncobjs[4], OPENGPU_COMMAND_EVENT(7, 1)),
          "reinitialize compute kernarg for second kernel");
    CHECK(submit_compute(
              fd, context_id, 7, 8, 4, 0, syncobjs[6],
              OPENGPU_COMMAND_WAIT_EVENT | OPENGPU_COMMAND_SIGNAL_EVENT,
              OPENGPU_COMMAND_EVENT(7, 1),
              OPENGPU_COMMAND_EVENT(8, 1)),
          "queue second general compute before render");
    CHECK(wait_syncobjs(fd, &syncobjs[6], 1), "wait second compute syncobj");
    for (uint32_t lane = 0; lane < 4; lane++) {
        uint32_t expected = lane < 2 ? 6u : lane;

        if (((uint32_t *)compute_kernarg.map)[lane] != expected) {
            fprintf(stderr,
                    "second compute kernarg lane=%u output=0x%08x expected=%u\n",
                    lane, ((uint32_t *)compute_kernarg.map)[lane], expected);
            errno = EIO;
            perror("OPENGPU USERSPACE DRM FAIL second reduction compute");
            return 1;
        }
    }
    /* Fail the first private command-snapshot mapping. The next render uses
     * the same context and buffers, proving the failed job was retired. */
    {
        uint32_t before = *(uint32_t *)((uint8_t *)first.map +
                                        first.pitch + 4);

        CHECK(set_mmu_fail_private_maps(1),
              "arm render submission mapping failure");
        CHECK(submit_selected_render(
                  fd, vert_core, context_id, &commands, &first, texture_slot,
                  shader_slot, kernarg_slot, vertex_buffer_slot,
                  vertex_shader_slot, vertex_kernarg_slot, 0, 0,
                  failure_syncobjs[1]),
              "queue render with mapping failure");
        CHECK(expect_syncobj_status(fd, failure_syncobjs[1], -ENOMEM),
              "render mapping-failure fence status");
        CHECK(set_mmu_fail_private_maps(0),
              "clear render submission mapping failure");
        if (*(uint32_t *)((uint8_t *)first.map + first.pitch + 4) != before) {
            errno = EIO;
            perror("OPENGPU USERSPACE DRM FAIL failed render wrote colour");
            return 1;
        }
    }
    CHECK(submit_selected_render(
              fd, vert_core, context_id, &commands, &first, texture_slot,
              shader_slot, kernarg_slot, vertex_buffer_slot,
              vertex_shader_slot, vertex_kernarg_slot, 0, 0, syncobjs[1]),
          "queue first buffer");
    CHECK(submit_selected_render(
              fd, vert_core, context_id, &commands, &second, texture_slot,
              shader_slot, kernarg_slot, vertex_buffer_slot,
              vertex_shader_slot, vertex_kernarg_slot, 0,
              syncobjs[1], syncobjs[2]),
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
          framebuffer_count(&first, alternate_pixel) != 0)) ||
        (!frag_core &&
         *(uint32_t *)((uint8_t *)first.map + first.pitch + 4) !=
             expected_pixel)) {
        if (frag_core)
            fprintf(stderr,
                    "texture counts expected=0x%08x:%u alternate=0x%08x:%u clear=0x000000ff:%u\n",
                    expected_pixel, framebuffer_count(&first, expected_pixel),
                    alternate_pixel, framebuffer_count(&first, alternate_pixel),
                    framebuffer_count(&first, 0x000000ffu));
        else
            fprintf(stderr, "texture pixel got=0x%08x expected=0x%08x\n",
                    *(uint32_t *)((uint8_t *)first.map + first.pitch + 4),
                    expected_pixel);
        errno = EIO;
        perror("OPENGPU USERSPACE DRM FAIL texture result");
        return 1;
    }
    if (!(capabilities & OPENGPU_CAP_BLIT_ENGINE)) {
        errno = EOPNOTSUPP;
        perror("OPENGPU USERSPACE DRM FAIL blit capability");
        return 1;
    }
    /* Isolate a second-draw hang from a blit/coherence failure: the ordered
     * blit waits on syncobjs[2], but a timed-out render still signals that
     * fence with an error and the blit wait would succeed vacuously. */
    CHECK(wait_syncobjs(fd, &syncobjs[2], 1), "wait second render syncobj");
    if ((frag_core &&
         (framebuffer_count(&second, expected_pixel) != 60 ||
          framebuffer_count(&second, alternate_pixel) != 0)) ||
        (!frag_core &&
         *(uint32_t *)((uint8_t *)second.map + second.pitch + 4) !=
             expected_pixel)) {
        errno = EIO;
        perror("OPENGPU USERSPACE DRM FAIL second texture result");
        return 1;
    }
    memset(second.map, 0x5a, second.size);
    errno = 0;
    if (submit_blit(fd, context_id, &first, &second, 4, 0, 64,
                    syncobjs[2], syncobjs[3]) != -1 || errno != EINVAL) {
        errno = EPROTO;
        perror("OPENGPU USERSPACE DRM FAIL unaligned blit accepted");
        return 1;
    }
    CHECK(submit_blit(fd, context_id, &first, &second, 0, 0,
                      first.size, syncobjs[2], syncobjs[3]),
          "queue ordered colour blit");
    CHECK(wait_syncobjs(fd, &syncobjs[3], 1), "wait colour blit syncobj");
    if (memcmp(first.map, second.map, first.size)) {
        errno = EIO;
        perror("OPENGPU USERSPACE DRM FAIL colour blit result");
        return 1;
    }
    CHECK(atomic_page_flip(fd, &ids, second.fb_id), "atomic page flip");
    CHECK(wait_syncobjs(fd, output_syncobjs, ARRAY_SIZE(output_syncobjs)),
          "wait output syncobjs");
    CHECK(wait_flip_event(fd, &event), "wait flip event");
    if ((frag_core &&
         (framebuffer_count(&second, expected_pixel) != 60 ||
          framebuffer_count(&second, alternate_pixel) != 0)) ||
        (!frag_core &&
         *(uint32_t *)((uint8_t *)second.map + second.pitch + 4) !=
             expected_pixel)) {
        errno = EIO;
        perror("OPENGPU USERSPACE DRM FAIL queued texture result");
        return 1;
    }
    if (!(capabilities & OPENGPU_CAP_CLEAR_ENGINE)) {
        errno = EOPNOTSUPP;
        perror("OPENGPU USERSPACE DRM FAIL fill capability");
        return 1;
    }
    errno = 0;
    if (submit_fill(fd, context_id, &second, 4, 64, 0x12345678u,
                    syncobjs[3], syncobjs[4]) != -1 || errno != EINVAL) {
        errno = EPROTO;
        perror("OPENGPU USERSPACE DRM FAIL unaligned fill accepted");
        return 1;
    }
    CHECK(submit_fill(fd, context_id, &second, 0, 64, 0x12345678u,
                      syncobjs[3], syncobjs[4]),
          "queue ordered colour fill");
    CHECK(wait_syncobjs(fd, &syncobjs[4], 1), "wait colour fill syncobj");
    for (uint32_t i = 0; i < 16; i++) {
        if (((uint32_t *)second.map)[i] != 0x12345678u) {
            errno = EIO;
            perror("OPENGPU USERSPACE DRM FAIL colour fill result");
            return 1;
        }
    }
    if (!(capabilities & OPENGPU_CAP_STRIDED_ENGINE)) {
        errno = EOPNOTSUPP;
        perror("OPENGPU USERSPACE DRM FAIL strided blit capability");
        return 1;
    }
    memset(strided_destination.map, 0x5a, strided_destination.size);
    for (uint32_t row = 0; row < 4; row++)
        for (uint32_t word = 0; word < 16; word++)
            ((uint32_t *)((uint8_t *)strided_source.map + row * 128))[word] =
                0x10203040u + row * 0x100u + word;
    errno = 0;
    if (submit_strided_blit(fd, context_id, &strided_source,
                            &strided_destination, 0, 0,
                            32, 4, 128, 192, syncobjs[4], syncobjs[5],
                            0, 0, 0) != -1 ||
        errno != EINVAL) {
        errno = EPROTO;
        perror("OPENGPU USERSPACE DRM FAIL unaligned strided blit accepted");
        return 1;
    }
    errno = 0;
    if (submit_strided_blit(fd, context_id, &strided_source,
                            &strided_destination, 0, 0,
                            64, 4, 0, 192, syncobjs[4], syncobjs[5],
                            0, 0, 0) != -1 ||
        errno != EINVAL) {
        errno = EPROTO;
        perror("OPENGPU USERSPACE DRM FAIL short strided pitch accepted");
        return 1;
    }
    errno = 0;
    if (submit_strided_blit(fd, context_id, &strided_source,
                            &strided_destination, 0,
                            strided_destination.size,
                            64, 1, 64, 64,
                            syncobjs[4], syncobjs[5], 0, 0, 0) != -1 ||
        errno != EINVAL) {
        errno = EPROTO;
        perror("OPENGPU USERSPACE DRM FAIL out-of-range strided blit accepted");
        return 1;
    }
    CHECK(submit_strided_blit(fd, context_id, &strided_source,
                              &strided_destination, 0, 0,
                              64, 4, 128, 192,
                              syncobjs[4], syncobjs[5],
                              OPENGPU_COMMAND_WAIT_EVENT,
                              OPENGPU_COMMAND_EVENT(8, 1), 0),
          "queue event-dependent ordered strided blit");
    CHECK(wait_syncobjs(fd, &syncobjs[5], 1),
          "wait strided blit syncobj");
    for (uint32_t row = 0; row < 4; row++) {
        if (memcmp((uint8_t *)strided_source.map + row * 128,
                   (uint8_t *)strided_destination.map + row * 192, 64) ||
            ((uint8_t *)strided_destination.map)[row * 192 + 64] != 0x5a) {
            errno = EIO;
            perror("OPENGPU USERSPACE DRM FAIL strided blit result");
            return 1;
        }
    }
    /* L2 line invalidate: a validated 64-byte-aligned GEM range completes and
     * malformed ranges are rejected.  It carries no memory traffic and lets a
     * driver make CPU-written memory visible to a later GPU read. */
    if (capabilities & OPENGPU_CAP_UNIFIED_COMMANDS) {
        uint32_t invalidate_sync = 0;

        CHECK(create_syncobj(fd, &invalidate_sync),
              "create invalidate syncobj");
        errno = 0;
        if (submit_invalidate(fd, context_id, first.handle, 4, 64, 0,
                              0) != -1 || errno != EINVAL) {
            errno = EPROTO;
            perror("OPENGPU USERSPACE DRM FAIL unaligned invalidate accepted");
            return 1;
        }
        errno = 0;
        if (submit_invalidate(fd, context_id, first.handle, 0, 60, 0,
                              0) != -1 || errno != EINVAL) {
            errno = EPROTO;
            perror("OPENGPU USERSPACE DRM FAIL short invalidate accepted");
            return 1;
        }
        errno = 0;
        if (submit_invalidate(fd, context_id, first.handle, 0,
                              first.size + 64, 0, 0) != -1 ||
            errno != EINVAL) {
            errno = EPROTO;
            perror("OPENGPU USERSPACE DRM FAIL oversized invalidate accepted");
            return 1;
        }
        CHECK(submit_invalidate(fd, context_id, first.handle, 0, 64, 0,
                                invalidate_sync),
              "queue L2 line invalidate");
        CHECK(wait_syncobjs(fd, &invalidate_sync, 1),
              "wait invalidate syncobj");
    }
    /* dma-buf CPU-access sync: after a CPU write, end_cpu_access drops the
     * shared-L2 lines for the object so the next GPU read sees the new data. */
    {
        struct drm_prime_handle prime = {
            .handle = first.handle,
            .flags = O_RDWR | O_CLOEXEC,
        };
        struct dma_buf_sync sync = { 0 };
        void *mapping;
        int dbuf_fd;

        if (ioctl(fd, DRM_IOCTL_PRIME_HANDLE_TO_FD, &prime) < 0 ||
            prime.fd < 0) {
            errno = EPROTO;
            perror("OPENGPU USERSPACE DRM FAIL prime export");
            return 1;
        }
        dbuf_fd = prime.fd;
        mapping = mmap(NULL, first.size, PROT_READ | PROT_WRITE, MAP_SHARED,
                       dbuf_fd, 0);
        if (mapping == MAP_FAILED) {
            errno = EPROTO;
            perror("OPENGPU USERSPACE DRM FAIL prime mmap");
            return 1;
        }
        ((uint32_t *)mapping)[0] = 0xdeadbeefu;
        sync.flags = DMA_BUF_SYNC_START | DMA_BUF_SYNC_WRITE;
        if (ioctl(dbuf_fd, DMA_BUF_IOCTL_SYNC, &sync) < 0) {
            errno = EPROTO;
            perror("OPENGPU USERSPACE DRM FAIL dma-buf sync start");
            return 1;
        }
        sync.flags = DMA_BUF_SYNC_END | DMA_BUF_SYNC_WRITE;
        if (ioctl(dbuf_fd, DMA_BUF_IOCTL_SYNC, &sync) < 0) {
            errno = EPROTO;
            perror("OPENGPU USERSPACE DRM FAIL dma-buf sync end");
            return 1;
        }
        munmap(mapping, first.size);
        close(dbuf_fd);
    }
    /* Typed MSAA resolve: average the interleaved colour samples of a small
     * region into a single-sample destination.  The driver validates both
     * ranges; a mode above the advertised maximum is rejected.  The region is
     * one physical row here; the multi-row crossing is covered by the RTL
     * integration tests (GpuSystemSpec / GpuHostSystemAxiSpec). */
    if (msaa_capable) {
        struct dumb_fb resolve_source = { 0 }, resolve_destination = { 0 };
        uint32_t resolve_sync = 0;
        uint32_t width = 4;
        uint32_t height = 1;
        uint32_t samples = 1u << msaa_max_mode;
        uint32_t src_stride, dst_stride;

        /* Fresh buffers: the source is CPU-initialized and then read by the
         * GPU, so it must not carry data from an earlier GPU access. */
        CHECK(create_fb(fd, 0, &resolve_source), "resolve source buffer");
        CHECK(create_fb(fd, 0, &resolve_destination),
              "resolve destination buffer");
        src_stride = resolve_source.pitch;
        dst_stride = resolve_destination.pitch;
        CHECK(create_syncobj(fd, &resolve_sync), "create resolve syncobj");

        errno = 0;
        if (submit_resolve(fd, context_id, &resolve_source,
                           &resolve_destination, 0, 0, width, height,
                           src_stride, dst_stride, msaa_max_mode + 1,
                           0, resolve_sync, 0) != -1 || errno != EINVAL) {
            errno = EPROTO;
            perror("OPENGPU USERSPACE DRM FAIL above-max resolve accepted");
            return 1;
        }
        errno = 0;
        if (submit_resolve(fd, context_id, &resolve_source,
                           &resolve_destination, 0, 0, width, height,
                           src_stride / 2, dst_stride, msaa_max_mode,
                           0, resolve_sync, 0) != -1 || errno != EINVAL) {
            errno = EPROTO;
            perror("OPENGPU USERSPACE DRM FAIL short resolve stride accepted");
            return 1;
        }

        memset(resolve_destination.map, 0x5a, resolve_destination.size);
        for (uint32_t y = 0; y < height; y++) {
            uint32_t *row = (uint32_t *)((uint8_t *)resolve_source.map +
                                         y * src_stride);

            for (uint32_t x = 0; x < width; x++) {
                row[x * samples + 0] = 0x00000000u;
                row[x * samples + 1] = 0xffffffffu;
                if (samples == 4) {
                    row[x * samples + 2] = 0xff00ff00u;
                    row[x * samples + 3] = 0x00ff00ffu;
                }
            }
        }
        CHECK(submit_resolve(fd, context_id, &resolve_source,
                             &resolve_destination, 0, 0, width, height,
                             src_stride, dst_stride, msaa_max_mode,
                             0, resolve_sync, 0),
              "queue ordered MSAA resolve");
        CHECK(wait_syncobjs(fd, &resolve_sync, 1), "wait resolve syncobj");
        for (uint32_t y = 0; y < height; y++) {
            uint32_t *row = (uint32_t *)((uint8_t *)resolve_destination.map +
                                         y * dst_stride);

            for (uint32_t x = 0; x < width; x++) {
                if (row[x] != 0x80808080u) {
                    fprintf(stderr,
                            "resolve (%u,%u)=0x%08x expected 0x80808080 "
                            "src_stride=%u dst_stride=%u\n",
                            x, y, row[x], src_stride, dst_stride);
                    errno = EIO;
                    perror("OPENGPU USERSPACE DRM FAIL resolve result");
                    return 1;
                }
            }
            if (row[width] != 0x5a5a5a5au) {
                errno = EIO;
                perror("OPENGPU USERSPACE DRM FAIL resolve padding");
                return 1;
            }
        }
    }
    /* MSAA render -> resolve -> scanout through the backend under test.  The
     * render writes one colour per pixel to that pixel's covered samples of a
     * multisample target and the resolve averages them back down, so a fully
     * covered pixel resolves to its render colour while an uncovered one keeps
     * the initialised zero: that distinction is what separates a real
     * multisample render from a zeroed buffer.  The resolved buffer is then
     * scanned out through the KMS flip path. */
    if (msaa_capable && msaa_max_mode >= 1) {
        struct dumb_fb msaa_target = { 0 }, resolved = { 0 };
        uint32_t render_sync = 0, resolve_sync = 0;
        uint32_t samples = 1u << msaa_max_mode;
        uint32_t covered_pixels = 0, empty_pixels = 0;

        /* One row of the target holds TEST_WIDTH pixels of `samples`
         * interleaved words, so its pitch is exactly the width * samples * 4
         * stride the render and resolve paths require. */
        CHECK(create_dumb_buffer(fd, TEST_WIDTH * samples, TEST_HEIGHT, 0,
                                 &msaa_target),
              "multisample render target");
        CHECK(create_fb(fd, 0, &resolved), "resolved scanout buffer");
        CHECK(create_syncobj(fd, &render_sync),
              "create MSAA render syncobj");
        CHECK(create_syncobj(fd, &resolve_sync),
              "create MSAA resolve syncobj");

        CHECK(submit_selected_render(
                  fd, vert_core, context_id, &commands, &msaa_target,
                  texture_slot, shader_slot, kernarg_slot, vertex_buffer_slot,
                  vertex_shader_slot, vertex_kernarg_slot, msaa_max_mode,
                  0, render_sync),
              "queue multisample render");
        CHECK(wait_syncobjs(fd, &render_sync, 1),
              "wait MSAA render syncobj");
        CHECK(submit_resolve(fd, context_id, &msaa_target, &resolved, 0, 0,
                             TEST_WIDTH, TEST_HEIGHT, msaa_target.pitch,
                             resolved.pitch, msaa_max_mode, 0, resolve_sync, 0),
              "queue MSAA resolve");
        CHECK(wait_syncobjs(fd, &resolve_sync, 1),
              "wait MSAA resolve syncobj");

        for (uint32_t y = 0; y < TEST_HEIGHT; y++) {
            const uint32_t *src = (const uint32_t *)
                ((const uint8_t *)msaa_target.map + y * msaa_target.pitch);
            const uint32_t *dst = (const uint32_t *)
                ((const uint8_t *)resolved.map + y * resolved.pitch);

            for (uint32_t x = 0; x < TEST_WIDTH; x++) {
                uint32_t expected =
                    resolve_average(&src[x * samples], samples);

                if (dst[x] != expected) {
                    fprintf(stderr,
                            "MSAA resolve (%u,%u)=0x%08x expected 0x%08x "
                            "samples=%u\n",
                            x, y, dst[x], expected, samples);
                    errno = EIO;
                    perror("OPENGPU USERSPACE DRM FAIL MSAA resolve result");
                    return 1;
                }
                if (expected == expected_pixel)
                    covered_pixels++;
                else if (expected == 0)
                    empty_pixels++;
            }
        }
        if (!covered_pixels || !empty_pixels) {
            fprintf(stderr,
                    "MSAA render covered=%u empty=%u: multisample samples "
                    "were not written as expected\n",
                    covered_pixels, empty_pixels);
            errno = EIO;
            perror("OPENGPU USERSPACE DRM FAIL MSAA render result");
            return 1;
        }
        CHECK(atomic_page_flip(fd, &ids, resolved.fb_id),
              "scan out resolved buffer");
        CHECK(wait_flip_event(fd, &event), "wait MSAA flip event");
    }
    /* Stencil-gated render: the second draw passes EQUAL 0x5a and blends the
     * covered pixels to black (REV_SUB of identical src/dst), while the third
     * (EQUAL 0x33) is blocked by the stamped byte.  The colour output proves
     * both the stencil pass and fail paths. */
    CHECK(create_stencil_command_buffer(fd, &commands, &stencil_commands),
          "stencil command buffer");
    /* Vertex-core builds reject fixed-function submits; use the same depth
     * helper that already carries OPENGPU_SUBMIT_VERTEX_CORE when needed. */
    CHECK(submit_depth_render(fd, vert_core, context_id, &stencil_commands,
                              &first, 3, texture_slot, shader_slot,
                              kernarg_slot, vertex_buffer_slot,
                              vertex_shader_slot, vertex_kernarg_slot, 0,
                              0, 0, 0, 0, syncobjs[7]),
          "queue stencil-gated render");
    CHECK(wait_syncobjs(fd, &syncobjs[7], 1), "wait stencil render syncobj");
    if ((frag_core &&
         (framebuffer_count(&first, 0x00000000u) != 60 ||
          framebuffer_count(&first, expected_pixel) != 0)) ||
        (!frag_core &&
         *(uint32_t *)((uint8_t *)first.map + first.pitch + 4) !=
             0x00000000u)) {
        if (!frag_core)
            fprintf(stderr, "stencil pixel got=0x%08x expected=0x00000000\n",
                    *(uint32_t *)((uint8_t *)first.map + first.pitch + 4));
        errno = EIO;
        perror("OPENGPU USERSPACE DRM FAIL stencil result");
        return 1;
    }
    /* Cross-submission render pass over a caller-owned persistent depth
     * attachment.  Submission 1 clears the bound plane (no DEPTH_LOAD) and
     * writes depth plus colour; submission 2 re-binds it with DEPTH_LOAD and
     * draws farther geometry with a GREATER test, which passes only against
     * the nearer depth the first submission stored.  Its reverse-subtract
     * blend then zeroes the pixels the first pass wrote, so a plane that was
     * cleared instead of loaded would leave them untouched.  Malformed
     * bindings are rejected alongside.  This drives depth_handle/depth_offset
     * binding, the OPENGPU_SUBMIT_DEPTH_LOAD contract and the driver range
     * checks end to end under ARTI/QEMU (verified on the Verilator backend). */
    if (capabilities & OPENGPU_CAP_PERSISTENT_DEPTH) {
        struct dumb_fb depth_attachment = { 0 }, pass_colour = { 0 };
        struct command_buffer continuation = { 0 };
        uint32_t pass_sync[2] = { 0 };
        uint32_t total_pixels = TEST_WIDTH * TEST_HEIGHT;
        uint32_t written;

        CHECK(create_dumb_buffer(fd, TEST_WIDTH, TEST_HEIGHT, 0,
                                 &depth_attachment),
              "persistent depth attachment");
        CHECK(create_dumb_buffer(fd, TEST_WIDTH, TEST_HEIGHT, 0x5a5a5a5au,
                                 &pass_colour),
              "persistent depth colour buffer");
        /* Continuation record: the same draw at a greater depth with a
         * GREATER test, so it passes only against the nearer depth the first
         * submission stored.  A cleared plane holds the far value and rejects
         * it.  Its reverse-subtract blend zeroes the pixels the first
         * submission wrote. */
        CHECK(create_continuation_buffer(
                  fd, &commands, &continuation,
                  OPENGPU_DRAW_STATE_DEPTH_FUNC_MASK |
                      OPENGPU_DRAW_STATE_STENCIL_TEST,
                  (TEST_DEPTH_FUNC_GREATER
                   << OPENGPU_DRAW_STATE_DEPTH_FUNC_SHIFT),
                  OPENGPU_DRAW_BLEND_PRESENT |
                      (TEST_BLEND_FACTOR_ONE
                       << OPENGPU_DRAW_BLEND_SRC_SHIFT) |
                      (TEST_BLEND_FACTOR_ONE
                       << OPENGPU_DRAW_BLEND_DST_SHIFT) |
                      (TEST_BLEND_EQ_REV_SUB
                       << OPENGPU_DRAW_BLEND_EQ_SHIFT)),
              "persistent depth continuation command buffer");
        {
            uint32_t *words = (uint32_t *)continuation.map;

            /* Move the continuation triangle to depth 0x20, farther than the
             * 0x10 the first pass stores. */
            words[21] = 0x20;
            words[22] = 0x20;
            words[23] = 0x20;
        }
        CHECK(create_syncobj(fd, &pass_sync[0]),
              "persistent depth first syncobj");
        CHECK(create_syncobj(fd, &pass_sync[1]),
              "persistent depth second syncobj");

        /* Validation rejects: DEPTH_LOAD without a handle, the colour object
         * bound as the depth plane, a misaligned binding and a binding whose
         * range runs past the backing allocation. */
        errno = 0;
        if (submit_depth_render(fd, vert_core, context_id, &commands,
                                &pass_colour, 1, texture_slot, shader_slot,
                                kernarg_slot, vertex_buffer_slot,
                                vertex_shader_slot, vertex_kernarg_slot, 0, 0,
                                0, OPENGPU_SUBMIT_DEPTH_LOAD, 0, 0) != -1 ||
            errno != EINVAL) {
            errno = EPROTO;
            perror("OPENGPU USERSPACE DRM FAIL depth load without handle");
            return 1;
        }
        errno = 0;
        if (submit_depth_render(fd, vert_core, context_id, &commands,
                                &pass_colour, 1, texture_slot, shader_slot,
                                kernarg_slot, vertex_buffer_slot,
                                vertex_shader_slot, vertex_kernarg_slot, 0,
                                pass_colour.handle, 0, 0, 0, 0) != -1 ||
            errno != EINVAL) {
            errno = EPROTO;
            perror("OPENGPU USERSPACE DRM FAIL colour bound as depth");
            return 1;
        }
        errno = 0;
        if (submit_depth_render(fd, vert_core, context_id, &commands,
                                &pass_colour, 1, texture_slot, shader_slot,
                                kernarg_slot, vertex_buffer_slot,
                                vertex_shader_slot, vertex_kernarg_slot, 0,
                                depth_attachment.handle, 2, 0, 0, 0) != -1 ||
            errno != EINVAL) {
            errno = EPROTO;
            perror("OPENGPU USERSPACE DRM FAIL misaligned depth binding");
            return 1;
        }
        errno = 0;
        if (submit_depth_render(fd, vert_core, context_id, &commands,
                                &pass_colour, 1, texture_slot, shader_slot,
                                kernarg_slot, vertex_buffer_slot,
                                vertex_shader_slot, vertex_kernarg_slot, 0,
                                depth_attachment.handle, 0x100000, 0, 0,
                                0) != -1 ||
            errno != ERANGE) {
            errno = EPROTO;
            perror("OPENGPU USERSPACE DRM FAIL out-of-range depth binding");
            return 1;
        }

        CHECK(submit_depth_render(fd, vert_core, context_id, &commands,
                                  &pass_colour, 1, texture_slot, shader_slot,
                                  kernarg_slot, vertex_buffer_slot,
                                  vertex_shader_slot, vertex_kernarg_slot, 0,
                                  depth_attachment.handle, 0, 0, 0,
                                  pass_sync[0]),
              "queue persistent depth first pass");
        CHECK(wait_syncobjs(fd, &pass_sync[0], 1),
              "wait persistent depth first pass");
        written = total_pixels -
                  framebuffer_count(&pass_colour, 0x5a5a5a5au);
        if (!written) {
            errno = EIO;
            perror("OPENGPU USERSPACE DRM FAIL persistent depth first pass");
            return 1;
        }
        /* The fragment-core backend writes shader-defined depth, so the
         * greater-depth comparison is only meaningful on the fixed-function
         * path; the binding, clear/load and validation contract above still
         * runs on both backends. */
        if (!frag_core) {
            CHECK(submit_depth_render(fd, vert_core, context_id, &continuation,
                                      &pass_colour, 1, texture_slot,
                                      shader_slot, kernarg_slot,
                                      vertex_buffer_slot, vertex_shader_slot,
                                      vertex_kernarg_slot, 0,
                                      depth_attachment.handle, 0,
                                      OPENGPU_SUBMIT_DEPTH_LOAD, 0,
                                      pass_sync[1]),
                  "queue persistent depth continuation");
            CHECK(wait_syncobjs(fd, &pass_sync[1], 1),
                  "wait persistent depth continuation");
            if (framebuffer_count(&pass_colour, 0x00000000u) != written) {
                fprintf(stderr,
                        "persistent depth continuation zero=%u written=%u\n",
                        framebuffer_count(&pass_colour, 0x00000000u),
                        written);
                errno = EIO;
                perror("OPENGPU USERSPACE DRM FAIL persistent depth "
                       "continuation");
                return 1;
            }
        }

        /* Repeat the clear/load continuation with interleaved depth samples.
         * Verify each sample rather than only resolved pixels: a partial
         * coverage edge must retain far depth in its untouched samples. */
        if (!frag_core && msaa_capable) {
            for (uint32_t mode = 1; mode <= msaa_max_mode; mode++) {
                struct dumb_fb sample_depth = { 0 }, sample_colour = { 0 };
                uint32_t sample_sync[2] = { 0 };
                uint32_t samples = 1u << mode;
                uint8_t first_covered[TEST_WIDTH * TEST_HEIGHT * samples];
                uint32_t covered_count = 0, empty_count = 0;
                uint32_t partial_pixels = 0;

                CHECK(create_dumb_buffer(fd, TEST_WIDTH * samples,
                                         TEST_HEIGHT, 0, &sample_depth),
                      "multisample persistent depth attachment");
                CHECK(create_dumb_buffer(fd, TEST_WIDTH * samples,
                                         TEST_HEIGHT, 0x5a5a5a5au,
                                         &sample_colour),
                      "multisample persistent colour buffer");
                CHECK(create_syncobj(fd, &sample_sync[0]),
                      "multisample persistent first syncobj");
                CHECK(create_syncobj(fd, &sample_sync[1]),
                      "multisample persistent continuation syncobj");
                CHECK(submit_depth_render(fd, vert_core, context_id,
                                          &commands, &sample_colour, 1,
                                          texture_slot, shader_slot,
                                          kernarg_slot, vertex_buffer_slot,
                                          vertex_shader_slot,
                                          vertex_kernarg_slot, mode,
                                          sample_depth.handle, 0, 0, 0,
                                          sample_sync[0]),
                      "queue multisample persistent first pass");
                CHECK(wait_syncobjs(fd, &sample_sync[0], 1),
                      "wait multisample persistent first pass");
                for (uint32_t y = 0; y < TEST_HEIGHT; y++) {
                    const uint32_t *colour = (const uint32_t *)
                        ((const uint8_t *)sample_colour.map +
                         y * sample_colour.pitch);
                    const uint32_t *depth = (const uint32_t *)
                        ((const uint8_t *)sample_depth.map +
                         y * sample_depth.pitch);

                    for (uint32_t x = 0; x < TEST_WIDTH; x++) {
                        uint32_t pixel_covered = 0;

                        for (uint32_t s = 0; s < samples; s++) {
                            uint32_t at = x * samples + s;
                            uint32_t index = (y * TEST_WIDTH + x) * samples + s;
                            bool covered = colour[at] != 0x5a5a5a5au;
                            uint32_t expected_depth =
                                covered ? 0x10u : 0x00ffffffu;

                            first_covered[index] = covered;
                            pixel_covered += covered;
                            covered_count += covered;
                            empty_count += !covered;
                            if (depth[at] != expected_depth) {
                                fprintf(stderr,
                                        "MSAA depth first mode=%u (%u,%u,%u) "
                                        "got=0x%08x expected=0x%08x\n",
                                        mode, x, y, s, depth[at],
                                        expected_depth);
                                errno = EIO;
                                perror("OPENGPU USERSPACE DRM FAIL MSAA "
                                       "persistent first pass");
                                return 1;
                            }
                        }
                        partial_pixels += pixel_covered > 0 &&
                                          pixel_covered < samples;
                    }
                }
                if (!covered_count || !empty_count || !partial_pixels) {
                    fprintf(stderr,
                            "MSAA depth mode=%u covered=%u empty=%u "
                            "partial=%u\n", mode, covered_count,
                            empty_count, partial_pixels);
                    errno = EIO;
                    perror("OPENGPU USERSPACE DRM FAIL MSAA first "
                           "coverage");
                    return 1;
                }
                CHECK(submit_depth_render(fd, vert_core, context_id,
                                          &continuation, &sample_colour, 1,
                                          texture_slot, shader_slot,
                                          kernarg_slot, vertex_buffer_slot,
                                          vertex_shader_slot,
                                          vertex_kernarg_slot, mode,
                                          sample_depth.handle, 0,
                                          OPENGPU_SUBMIT_DEPTH_LOAD, 0,
                                          sample_sync[1]),
                      "queue multisample persistent continuation");
                CHECK(wait_syncobjs(fd, &sample_sync[1], 1),
                      "wait multisample persistent continuation");
                for (uint32_t y = 0; y < TEST_HEIGHT; y++) {
                    const uint32_t *colour = (const uint32_t *)
                        ((const uint8_t *)sample_colour.map +
                         y * sample_colour.pitch);
                    const uint32_t *depth = (const uint32_t *)
                        ((const uint8_t *)sample_depth.map +
                         y * sample_depth.pitch);

                    for (uint32_t x = 0; x < TEST_WIDTH; x++) {
                        for (uint32_t s = 0; s < samples; s++) {
                            uint32_t at = x * samples + s;
                            uint32_t index = (y * TEST_WIDTH + x) * samples + s;
                            uint32_t expected_colour = first_covered[index] ?
                                0u : 0x5a5a5a5au;
                            uint32_t expected_depth = first_covered[index] ?
                                0x20u : 0x00ffffffu;

                            if (colour[at] != expected_colour ||
                                depth[at] != expected_depth) {
                                fprintf(stderr,
                                        "MSAA depth load mode=%u (%u,%u,%u) "
                                        "colour=0x%08x/0x%08x "
                                        "depth=0x%08x/0x%08x\n",
                                        mode, x, y, s, colour[at],
                                        expected_colour, depth[at],
                                        expected_depth);
                                errno = EIO;
                                perror("OPENGPU USERSPACE DRM FAIL MSAA "
                                       "persistent continuation");
                                return 1;
                            }
                        }
                    }
                }
            }
        }
    }
    if (vert_core)
        CHECK(unbind_resource(fd, context_id, 5), "unbind vertex shader");
    else if (frag_core)
        CHECK(unbind_resource(fd, context_id, 2), "unbind shader");
    else
        CHECK(unbind_resource(fd, context_id, 1), "unbind texture");
    errno = 0;
    if (submit_selected_render(
            fd, vert_core, context_id, &commands, &second, texture_slot,
            shader_slot, kernarg_slot, vertex_buffer_slot,
            vertex_shader_slot, vertex_kernarg_slot, 0, 0, 0) != -1 ||
        errno != EINVAL) {
        errno = EPROTO;
        perror("OPENGPU USERSPACE DRM FAIL unbound resource accepted");
        return 1;
    }
    CHECK(destroy_context(fd, context_id), "destroy render context");
    errno = 0;
    if (submit_selected_render(
            fd, vert_core, context_id, &commands, &second, texture_slot,
            shader_slot, kernarg_slot, vertex_buffer_slot,
            vertex_shader_slot, vertex_kernarg_slot, 0, 0, 0) != -1 ||
        errno != ENOENT) {
        errno = EPROTO;
        perror("OPENGPU USERSPACE DRM FAIL destroyed context accepted");
        return 1;
    }
    /* VM isolation through the DRM ioctl path: the driver maps each context's
     * fill, texture, kernarg and DMA buffers at the same fixed virtual
     * windows, so this exercises same-VA/different-PA separation, cross-window
     * rebind revocation, and context teardown with queued work followed by an
     * ASID recycle. */
    {
        struct resource_buffer iso_fill_a, iso_fill_b, iso_kernarg;
        struct resource_buffer iso_fail_kernarg;
        uint32_t iso_context_a = 0, iso_context_b = 0;
        uint32_t iso_recycled = 0;
        uint32_t iso_sync[7] = { 0 };
        uint32_t iso_fail_sync[2] = { 0 };
        uint32_t lane;

        CHECK(create_resource_buffer(fd, 64, &iso_fill_a),
              "VM isolation fill A buffer");
        CHECK(create_resource_buffer(fd, 64, &iso_fill_b),
              "VM isolation fill B buffer");
        CHECK(create_resource_buffer(fd, 64, &iso_kernarg),
              "VM isolation kernarg buffer");
        CHECK(create_context(fd, &iso_context_a), "VM isolation context A");
        CHECK(create_context(fd, &iso_context_b), "VM isolation context B");
        CHECK(create_syncobj(fd, &iso_sync[0]),
              "VM isolation fill A syncobj");
        CHECK(create_syncobj(fd, &iso_sync[1]),
              "VM isolation fill B syncobj");
        CHECK(create_syncobj(fd, &iso_sync[2]),
              "VM isolation rebind fill syncobj");
        CHECK(create_syncobj(fd, &iso_sync[3]),
              "VM isolation rebind compute syncobj");
        CHECK(create_syncobj(fd, &iso_sync[4]),
              "VM isolation teardown fill syncobj");
        CHECK(create_syncobj(fd, &iso_sync[5]),
              "VM isolation recycled fill syncobj");
        CHECK(create_syncobj(fd, &iso_sync[6]),
              "VM isolation recycled compute syncobj");
        CHECK(create_resource_buffer(fd, 64, &iso_fail_kernarg),
              "VM isolation map-failure kernarg buffer");
        CHECK(create_syncobj(fd, &iso_fail_sync[0]),
              "VM isolation map-failure fill syncobj");
        CHECK(create_syncobj(fd, &iso_fail_sync[1]),
              "VM isolation map-failure compute syncobj");

        /* Both contexts use the same private DMA destination VA window. If
         * the two ASIDs were not isolated, the second fill would corrupt the
         * first buffer (or vice versa). */
        CHECK(fill_resource(fd, iso_context_a, &iso_fill_a, 0xaaaaaaaau,
                            iso_sync[0], OPENGPU_COMMAND_EVENT(9, 1)),
              "VM isolation fill A");
        CHECK(wait_syncobjs(fd, &iso_sync[0], 1), "wait VM isolation fill A");
        CHECK(fill_resource(fd, iso_context_b, &iso_fill_b, 0xbbbbbbbbu,
                            iso_sync[1], OPENGPU_COMMAND_EVENT(10, 1)),
              "VM isolation fill B");
        CHECK(wait_syncobjs(fd, &iso_sync[1], 1), "wait VM isolation fill B");
        if (((uint32_t *)iso_fill_a.map)[0] != 0xaaaaaaaau ||
            ((uint32_t *)iso_fill_b.map)[0] != 0xbbbbbbbbu) {
            fprintf(stderr, "VM fill isolation a=0x%08x b=0x%08x\n",
                    ((uint32_t *)iso_fill_a.map)[0],
                    ((uint32_t *)iso_fill_b.map)[0]);
            errno = EIO;
            perror("OPENGPU USERSPACE DRM FAIL VM fill isolation");
            return 1;
        }

        /* Cross-window rebind: slot 1 moves from a texture private window to
         * a compute-kernarg private window. The old texture window must be
         * revoked before the new mapping is published, or bind fails. */
        CHECK(bind_texture(fd, iso_context_b, 1, &texture),
              "VM isolation bind texture slot 1");
        CHECK(bind_resource(fd, iso_context_b, 1, &iso_kernarg,
                            OPENGPU_RESOURCE_COMPUTE_KERNARG, 64),
              "VM isolation rebind slot 1 to compute kernarg");
        CHECK(bind_resource(fd, iso_context_b, 7, &compute_shader,
                            OPENGPU_RESOURCE_COMPUTE_SHADER, 128),
              "VM isolation bind compute shader slot 7");
        CHECK(fill_resource(fd, iso_context_b, &iso_kernarg, 0xcafe0001u,
                            iso_sync[2], OPENGPU_COMMAND_EVENT(11, 1)),
              "VM isolation initialize rebound kernarg");
        CHECK(wait_syncobjs(fd, &iso_sync[2], 1),
              "wait VM isolation rebound kernarg fill");
        CHECK(submit_compute(
                  fd, iso_context_b, 7, 1, 4, 0, iso_sync[3],
                  OPENGPU_COMMAND_WAIT_EVENT | OPENGPU_COMMAND_SIGNAL_EVENT,
                  OPENGPU_COMMAND_EVENT(11, 1),
                  OPENGPU_COMMAND_EVENT(12, 1)),
              "VM isolation compute through rebound slot");
        CHECK(wait_syncobjs(fd, &iso_sync[3], 1),
              "wait VM isolation rebound compute");
        for (lane = 0; lane < 4; lane++) {
            uint32_t expected = lane < 2 ? 6u : lane;

            if (((uint32_t *)iso_kernarg.map)[lane] != expected) {
                fprintf(stderr,
                        "rebound compute kernarg lane=%u output=0x%08x "
                        "expected=%u\n",
                        lane, ((uint32_t *)iso_kernarg.map)[lane], expected);
                errno = EIO;
                perror("OPENGPU USERSPACE DRM FAIL VM rebind compute result");
                return 1;
            }
        }

        /* Bind-time private-map allocation failure: the ioctl must reject the
         * bind, leave no mapping behind, and a follow-up bind/submission must
         * succeed through the same window. */
        CHECK(set_mmu_fail_private_maps(1), "arm VM private-map failure");
        errno = 0;
        if (bind_resource(fd, iso_context_a, 8, &iso_fail_kernarg,
                          OPENGPU_RESOURCE_COMPUTE_KERNARG, 64) != -1 ||
            errno != ENOMEM) {
            errno = EPROTO;
            perror("OPENGPU USERSPACE DRM FAIL VM private-map failure");
            return 1;
        }
        CHECK(set_mmu_fail_private_maps(0), "clear VM private-map failure");
        CHECK(bind_resource(fd, iso_context_a, 7, &compute_shader,
                            OPENGPU_RESOURCE_COMPUTE_SHADER, 128),
              "VM isolation bind shader after map failure");
        CHECK(bind_resource(fd, iso_context_a, 8, &iso_fail_kernarg,
                            OPENGPU_RESOURCE_COMPUTE_KERNARG, 64),
              "VM isolation bind kernarg after map failure");
        CHECK(fill_resource(fd, iso_context_a, &iso_fail_kernarg, 0xcafe0001u,
                            iso_fail_sync[0], OPENGPU_COMMAND_EVENT(16, 1)),
              "VM isolation initialize map-failure kernarg");
        CHECK(wait_syncobjs(fd, &iso_fail_sync[0], 1),
              "wait VM isolation map-failure fill");
        CHECK(submit_compute(
                  fd, iso_context_a, 7, 8, 4, 0, iso_fail_sync[1],
                  OPENGPU_COMMAND_WAIT_EVENT | OPENGPU_COMMAND_SIGNAL_EVENT,
                  OPENGPU_COMMAND_EVENT(16, 1),
                  OPENGPU_COMMAND_EVENT(17, 1)),
              "VM isolation compute after map failure");
        CHECK(wait_syncobjs(fd, &iso_fail_sync[1], 1),
              "wait VM isolation map-failure compute");
        for (lane = 0; lane < 4; lane++) {
            uint32_t expected = lane < 2 ? 6u : lane;

            if (((uint32_t *)iso_fail_kernarg.map)[lane] != expected) {
                fprintf(stderr,
                        "map-failure follow-up kernarg lane=%u output=0x%08x "
                        "expected=%u\n",
                        lane, ((uint32_t *)iso_fail_kernarg.map)[lane],
                        expected);
                errno = EIO;
                perror("OPENGPU USERSPACE DRM FAIL VM map-failure follow-up "
                       "compute result");
                return 1;
            }
        }

        /* Context teardown with queued work, then ASID recycle. A recycled
         * ASID must start from a fresh root and compute in the new context
         * without observing the destroyed context's private leaves. */
        CHECK(fill_resource(fd, iso_context_b, &iso_fill_b, 0x5a5a5a5au,
                            iso_sync[4], OPENGPU_COMMAND_EVENT(13, 1)),
              "VM isolation queue teardown fill");
        CHECK(destroy_context(fd, iso_context_b),
              "VM isolation destroy context with queued work");
        CHECK(wait_syncobjs(fd, &iso_sync[4], 1),
              "wait VM isolation teardown fill");
        CHECK(create_context(fd, &iso_recycled),
              "VM isolation recycle context");
        CHECK(bind_resource(fd, iso_recycled, 7, &compute_shader,
                            OPENGPU_RESOURCE_COMPUTE_SHADER, 128),
              "VM isolation bind recycled shader");
        CHECK(bind_resource(fd, iso_recycled, 8, &iso_kernarg,
                            OPENGPU_RESOURCE_COMPUTE_KERNARG, 64),
              "VM isolation bind recycled kernarg");
        CHECK(fill_resource(fd, iso_recycled, &iso_kernarg, 0xcafe0001u,
                            iso_sync[5], OPENGPU_COMMAND_EVENT(14, 1)),
              "VM isolation initialize recycled kernarg");
        CHECK(wait_syncobjs(fd, &iso_sync[5], 1),
              "wait VM isolation recycled kernarg fill");
        CHECK(submit_compute(
                  fd, iso_recycled, 7, 8, 4, 0, iso_sync[6],
                  OPENGPU_COMMAND_WAIT_EVENT | OPENGPU_COMMAND_SIGNAL_EVENT,
                  OPENGPU_COMMAND_EVENT(14, 1),
                  OPENGPU_COMMAND_EVENT(15, 1)),
              "VM isolation recycled compute");
        CHECK(wait_syncobjs(fd, &iso_sync[6], 1),
              "wait VM isolation recycled compute");
        for (lane = 0; lane < 4; lane++) {
            uint32_t expected = lane < 2 ? 6u : lane;

            if (((uint32_t *)iso_kernarg.map)[lane] != expected) {
                fprintf(stderr,
                        "recycled compute kernarg lane=%u output=0x%08x "
                        "expected=%u\n",
                        lane, ((uint32_t *)iso_kernarg.map)[lane], expected);
                errno = EIO;
                perror("OPENGPU USERSPACE DRM FAIL VM recycled compute "
                       "result");
                return 1;
            }
        }

        CHECK(destroy_context(fd, iso_recycled),
              "VM isolation destroy recycled context");
        CHECK(destroy_context(fd, iso_context_a),
              "VM isolation destroy context A");
    }
    /* Context destruction must wait for an active render's delayed writes and
     * finished fence before freeing its VM. Reuse the same resource bindings
     * through a fresh context so the test covers the selected shader backend. */
    {
        struct dumb_fb teardown_fb = { 0 };
        uint32_t teardown_context = 0, teardown_sync = 0;

        CHECK(create_fb(fd, 0x5a5a5a5au, &teardown_fb),
              "create teardown framebuffer");
        CHECK(create_context(fd, &teardown_context),
              "create delayed-render teardown context");
        CHECK(bind_texture(fd, teardown_context, 1, &texture),
              "bind teardown texture");
        CHECK(bind_resource(fd, teardown_context, 2, &shader,
                            OPENGPU_RESOURCE_SHADER, 128),
              "bind teardown fragment shader");
        CHECK(bind_resource(fd, teardown_context, 3, &kernarg,
                            OPENGPU_RESOURCE_KERNARG, 640),
              "bind teardown fragment kernarg");
        if (vert_core) {
            CHECK(bind_resource(fd, teardown_context, 4, &vertex_buffer,
                                OPENGPU_RESOURCE_VERTEX_BUFFER,
                                3 * sizeof(struct vertex_data)),
                  "bind teardown vertex buffer");
            CHECK(bind_resource(fd, teardown_context, 5, &vertex_shader,
                                OPENGPU_RESOURCE_VERTEX_SHADER, 256),
                  "bind teardown vertex shader");
            CHECK(bind_resource(fd, teardown_context, 6, &vertex_kernarg,
                                OPENGPU_RESOURCE_VERTEX_KERNARG, 512),
                  "bind teardown vertex kernarg");
        }
        CHECK(create_syncobj(fd, &teardown_sync),
              "create delayed-render teardown syncobj");
        CHECK(submit_selected_render(
                  fd, vert_core, teardown_context, &commands, &teardown_fb,
                  texture_slot, shader_slot, kernarg_slot,
                  vertex_buffer_slot, vertex_shader_slot,
                  vertex_kernarg_slot, 0, 0, teardown_sync),
              "queue delayed render before context destroy");
        errno = 0;
        if (wait_syncobjs_timeout(fd, &teardown_sync, 1, 0) != -1 ||
            errno != ETIME) {
            errno = EPROTO;
            perror("OPENGPU USERSPACE DRM FAIL teardown render already done");
            return 1;
        }
        CHECK(destroy_context(fd, teardown_context),
              "destroy context during delayed render");
        CHECK(expect_syncobj_status(fd, teardown_sync, 1),
              "teardown render finished fence");
        if ((frag_core &&
             framebuffer_count(&teardown_fb, expected_pixel) != 60) ||
            (!frag_core &&
             *(uint32_t *)((uint8_t *)teardown_fb.map +
                           teardown_fb.pitch + 4) != expected_pixel)) {
            errno = EIO;
            perror("OPENGPU USERSPACE DRM FAIL teardown render result");
            return 1;
        }
    }
    CHECK(get_last_fault(fd, &final_fault), "query final GPU fault");
    if (final_fault.sequence != initial_fault.sequence) {
        fprintf(stderr,
                "unexpected GPU fault sequence before=%llu after=%llu "
                "flags=0x%x error=%d\n",
                (unsigned long long)initial_fault.sequence,
                (unsigned long long)final_fault.sequence,
                final_fault.flags, final_fault.error);
        errno = EIO;
        perror("OPENGPU USERSPACE DRM FAIL successful jobs recorded fault");
        return 1;
    }
#undef CHECK

    printf("OPENGPU USERSPACE DRM PASS: queued %s render + explicit "
           "syncobj + %s sandbox + "
           "validated context + event-chained RVV "
           "slide/reduction/gather/masked/strided/indexed-memory compute + "
           "ordered colour "
           "blit/fill/strided blit + "
           "persistent-depth cross-submission pass + "
           "VM fill/rebind/map-failure/recycle isolation + "
           "render/compute mapping-failure recovery + "
           "delayed-render context teardown + "
           "fault-query ABI + vblank flip event sequence=%u\n",
           vert_core ? "vertex+fragment-core-backed" :
           frag_core ? "core-backed" : "texture",
           vert_core ? "validated vertex passthrough + vtex/quad-derivative/discard" :
           frag_core ? "validated vtex/quad-derivative/discard" :
                       "fixed-function texture",
           event.sequence);
    return 0;
}
