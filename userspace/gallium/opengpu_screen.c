/* SPDX-License-Identifier: MIT */
/* Gallium screen. Resources are OpenGPU GEMs. Clear, draw, and present are
 * submitted by opengpu_context.c. This file does not rasterize. */
#include "opengpu_public.h"
#include "opengpu_internal.h"

#include "pipe/p_defines.h"
#include "frontend/winsys_handle.h"
#include "util/u_memory.h"

#include <errno.h>
#include <stdio.h>
#include <string.h>

struct opengpu_screen {
    struct pipe_screen base;
    struct pipe_opengpu_screen *gpu;
};

static struct opengpu_screen *oscreen(struct pipe_screen *screen)
{
    return (struct opengpu_screen *)screen;
}

struct opengpu_resource *opengpu_resource(struct pipe_resource *resource)
{
    return (struct opengpu_resource *)resource;
}

struct pipe_opengpu_screen *opengpu_screen_gpu(struct pipe_screen *screen)
{
    return oscreen(screen)->gpu;
}

static void screen_destroy(struct pipe_screen *screen)
{
    struct opengpu_screen *og = oscreen(screen);

    pipe_opengpu_screen_destroy(og->gpu);
    FREE(og);
}

static const char *screen_get_name(struct pipe_screen *screen)
{
    (void)screen;
    return "opengpu";
}

static const char *screen_get_vendor(struct pipe_screen *screen)
{
    (void)screen;
    return "opengpu";
}

static int screen_get_param(struct pipe_screen *screen, enum pipe_cap param)
{
    (void)screen;
    switch (param) {
    case PIPE_CAP_NPOT_TEXTURES:
    case PIPE_CAP_VERTEX_COLOR_UNCLAMPED:
    case PIPE_CAP_VERTEX_COLOR_CLAMPED:
    case PIPE_CAP_TGSI_TEXCOORD:
    case PIPE_CAP_ACCELERATED:
    case PIPE_CAP_UMA:
    case PIPE_CAP_BLEND_EQUATION_SEPARATE:
    case PIPE_CAP_POINT_SIZE_FIXED:
        /* Triangles only. A fixed point size keeps gl_PointSize out of the
         * vertex shader, which the vertex core does not consume. */
        return 1;
    case PIPE_CAP_ESSL_FEATURE_LEVEL:
        /* GLES 2.0 is GLSL ES 1.00. Mesa hides the ES2 API bit without this. */
        return 100;
    case PIPE_CAP_MAX_RENDER_TARGETS:
        return 1;
    case PIPE_CAP_MAX_TEXTURE_2D_SIZE:
        return 2048;
    case PIPE_CAP_MAX_TEXTURE_3D_LEVELS:
    case PIPE_CAP_MAX_TEXTURE_CUBE_LEVELS:
        return 1;
    case PIPE_CAP_GLSL_FEATURE_LEVEL:
    case PIPE_CAP_GLSL_FEATURE_LEVEL_COMPATIBILITY:
        return 120;
    case PIPE_CAP_CONSTANT_BUFFER_OFFSET_ALIGNMENT:
    case PIPE_CAP_MIN_MAP_BUFFER_ALIGNMENT:
        return 64;
    case PIPE_CAP_ENDIANNESS:
        return PIPE_ENDIAN_LITTLE;
    case PIPE_CAP_MAX_VERTEX_BUFFERS:
        return 8;
    case PIPE_CAP_SUPPORTED_PRIM_MODES:
        /* Only triangles are a GPU draw. Advertising the mode keeps u_vbuf
         * from rewriting glDrawArrays through the index generator. */
        return 1u << PIPE_PRIM_TRIANGLES;
    case PIPE_CAP_MAX_VARYINGS:
        /* GLES 2.0 minimum. The passthrough triangle uses one color varying. */
        return 8;
    case PIPE_CAP_VENDOR_ID:
    case PIPE_CAP_DEVICE_ID:
        return 0;
    case PIPE_CAP_VIDEO_MEMORY:
        return 64;
    default:
        return 0;
    }
}

static float screen_get_paramf(struct pipe_screen *screen, enum pipe_capf param)
{
    (void)screen;
    switch (param) {
    case PIPE_CAPF_MIN_LINE_WIDTH:
    case PIPE_CAPF_MIN_LINE_WIDTH_AA:
    case PIPE_CAPF_MIN_POINT_SIZE:
    case PIPE_CAPF_MIN_POINT_SIZE_AA:
    case PIPE_CAPF_MAX_TEXTURE_ANISOTROPY:
        return 1.0f;
    case PIPE_CAPF_MAX_LINE_WIDTH:
    case PIPE_CAPF_MAX_LINE_WIDTH_AA:
    case PIPE_CAPF_MAX_POINT_SIZE:
    case PIPE_CAPF_MAX_POINT_SIZE_AA:
        return 1.0f;
    case PIPE_CAPF_MAX_TEXTURE_LOD_BIAS:
        return 0.0f;
    default:
        return 0.0f;
    }
}

static int screen_get_shader_param(struct pipe_screen *screen,
                                   enum pipe_shader_type shader,
                                   enum pipe_shader_cap param)
{
    (void)screen;
    if (shader != PIPE_SHADER_VERTEX && shader != PIPE_SHADER_FRAGMENT)
        return 0;
    switch (param) {
    case PIPE_SHADER_CAP_MAX_INSTRUCTIONS:
    case PIPE_SHADER_CAP_MAX_ALU_INSTRUCTIONS:
        return 256;
    case PIPE_SHADER_CAP_MAX_TEX_INSTRUCTIONS:
    case PIPE_SHADER_CAP_MAX_TEX_INDIRECTIONS:
    case PIPE_SHADER_CAP_MAX_CONTROL_FLOW_DEPTH:
        return 0;
    case PIPE_SHADER_CAP_MAX_INPUTS:
        return shader == PIPE_SHADER_VERTEX ? 8 : 4;
    case PIPE_SHADER_CAP_MAX_OUTPUTS:
        return shader == PIPE_SHADER_VERTEX ? 4 : 1;
    case PIPE_SHADER_CAP_MAX_CONST_BUFFER0_SIZE:
        return 256;
    case PIPE_SHADER_CAP_MAX_CONST_BUFFERS:
        return 1;
    case PIPE_SHADER_CAP_MAX_TEMPS:
        return 8;
    case PIPE_SHADER_CAP_MAX_TEXTURE_SAMPLERS:
    case PIPE_SHADER_CAP_MAX_SAMPLER_VIEWS:
        /* GLES 2.0 requires a non-zero count to create a context. A TEX
         * opcode still fails the draw; this slice only copies color. */
        return 8;
    case PIPE_SHADER_CAP_PREFERRED_IR:
        return PIPE_SHADER_IR_TGSI;
    case PIPE_SHADER_CAP_SUPPORTED_IRS:
        return 1u << PIPE_SHADER_IR_TGSI;
    case PIPE_SHADER_CAP_CONT_SUPPORTED:
    case PIPE_SHADER_CAP_INDIRECT_INPUT_ADDR:
    case PIPE_SHADER_CAP_INDIRECT_OUTPUT_ADDR:
    case PIPE_SHADER_CAP_INDIRECT_TEMP_ADDR:
    case PIPE_SHADER_CAP_INDIRECT_CONST_ADDR:
    case PIPE_SHADER_CAP_SUBROUTINES:
    case PIPE_SHADER_CAP_INTEGERS:
    case PIPE_SHADER_CAP_FP16:
        return 0;
    default:
        return 0;
    }
}

static bool format_ok(enum pipe_format format, unsigned bindings)
{
    bool color = format == PIPE_FORMAT_R8G8B8A8_UNORM;
    bool depth = format == PIPE_FORMAT_Z24_UNORM_S8_UINT ||
                 format == PIPE_FORMAT_Z24X8_UNORM;
    bool vertex = format == PIPE_FORMAT_R32G32B32A32_FLOAT ||
                  format == PIPE_FORMAT_R32G32B32_FLOAT ||
                  format == PIPE_FORMAT_R32G32_FLOAT ||
                  format == PIPE_FORMAT_R32_FLOAT ||
                  format == PIPE_FORMAT_R8G8B8A8_UNORM;

    if (bindings & PIPE_BIND_DEPTH_STENCIL)
        return depth;
    if (bindings & (PIPE_BIND_RENDER_TARGET | PIPE_BIND_SCANOUT |
                    PIPE_BIND_DISPLAY_TARGET | PIPE_BIND_SAMPLER_VIEW))
        return color;
    if (bindings & PIPE_BIND_VERTEX_BUFFER)
        return vertex;
    return color || depth || vertex;
}

static bool screen_is_format_supported(struct pipe_screen *screen,
                                       enum pipe_format format,
                                       enum pipe_texture_target target,
                                       unsigned sample_count,
                                       unsigned storage_sample_count,
                                       unsigned bindings)
{
    (void)screen;
    (void)storage_sample_count;
    if (sample_count > 1)
        return false;
    if (target != PIPE_BUFFER && target != PIPE_TEXTURE_2D &&
        target != PIPE_TEXTURE_RECT)
        return false;
    return format_ok(format, bindings);
}

static uint32_t align64(uint32_t bytes)
{
    return (bytes + 63u) & ~63u;
}

static void resource_destroy(struct pipe_screen *screen,
                             struct pipe_resource *presource)
{
    struct opengpu_resource *res = opengpu_resource(presource);

    if (res->gpu)
        pipe_opengpu_resource_destroy(opengpu_screen_gpu(screen), res->gpu);
    FREE(res);
}

static struct pipe_resource *resource_create(struct pipe_screen *screen,
                                             const struct pipe_resource *templ)
{
    struct opengpu_resource *res;
    uint32_t bytes;

    if (!templ)
        return NULL;
    if (templ->target == PIPE_BUFFER) {
        if (!templ->width0 || templ->width0 > 0x7fffffffu)
            return NULL;
        bytes = align64((uint32_t)templ->width0);
    } else if (templ->target == PIPE_TEXTURE_2D ||
               templ->target == PIPE_TEXTURE_RECT) {
        if (!templ->width0 || !templ->height0 || templ->nr_samples > 1)
            return NULL;
        if (templ->format != PIPE_FORMAT_R8G8B8A8_UNORM &&
            templ->format != PIPE_FORMAT_Z24_UNORM_S8_UINT &&
            templ->format != PIPE_FORMAT_Z24X8_UNORM)
            return NULL;
        bytes = align64((uint32_t)templ->width0 * (uint32_t)templ->height0 * 4u);
    } else {
        return NULL;
    }

    res = CALLOC_STRUCT(opengpu_resource);
    if (!res)
        return NULL;
    res->base = *templ;
    res->base.screen = screen;
    res->base.reference.count = 1;
    if (templ->target == PIPE_BUFFER ||
        templ->format == PIPE_FORMAT_Z24_UNORM_S8_UINT ||
        templ->format == PIPE_FORMAT_Z24X8_UNORM) {
        res->gpu = pipe_opengpu_resource_create(opengpu_screen_gpu(screen), bytes);
    } else if (templ->bind & (PIPE_BIND_SCANOUT | PIPE_BIND_DISPLAY_TARGET |
                              PIPE_BIND_SHARED)) {
        res->gpu = pipe_opengpu_resource_create_2d(
            opengpu_screen_gpu(screen), (uint32_t)templ->width0,
            (uint32_t)templ->height0);
    } else if (templ->last_level == 0) {
        /* Sampler textures are tightly packed. A mip chain is not a GPU
         * texture yet, so a request for one fails the allocation. */
        res->gpu = pipe_opengpu_resource_create_linear(
            opengpu_screen_gpu(screen), (uint32_t)templ->width0,
            (uint32_t)templ->height0);
    }
    if (!res->gpu) {
        FREE(res);
        return NULL;
    }
    return &res->base;
}

static bool resource_get_handle(struct pipe_screen *screen,
                               struct pipe_context *context,
                               struct pipe_resource *tex,
                               struct winsys_handle *whandle, unsigned usage)
{
    struct opengpu_resource *res = opengpu_resource(tex);
    uint32_t gem;
    uint32_t pitch;

    (void)screen;
    (void)context;
    (void)usage;
    if (!res || !res->gpu || !whandle || whandle->plane)
        return false;
    if (whandle->type != WINSYS_HANDLE_TYPE_KMS)
        return false;
    gem = pipe_opengpu_resource_handle(res->gpu);
    pitch = pipe_opengpu_resource_pitch(res->gpu);
    if (!pitch && tex->target != PIPE_BUFFER)
        pitch = (uint32_t)tex->width0 * 4u;
    whandle->handle = gem;
    whandle->stride = pitch;
    whandle->offset = 0;
    whandle->modifier = 0;
    whandle->format = tex->format;
    return gem != 0;
}

static bool resource_get_param(struct pipe_screen *screen,
                               struct pipe_context *context,
                               struct pipe_resource *tex, unsigned plane,
                               unsigned layer, unsigned level,
                               enum pipe_resource_param param,
                               unsigned handle_usage, uint64_t *value)
{
    struct opengpu_resource *res = opengpu_resource(tex);
    uint32_t pitch;

    (void)screen;
    (void)context;
    (void)layer;
    (void)level;
    (void)handle_usage;
    if (!res || !res->gpu || plane || !value)
        return false;
    pitch = pipe_opengpu_resource_pitch(res->gpu);
    if (!pitch && tex->target != PIPE_BUFFER)
        pitch = (uint32_t)tex->width0 * 4u;
    switch (param) {
    case PIPE_RESOURCE_PARAM_NPLANES:
        *value = 1;
        return true;
    case PIPE_RESOURCE_PARAM_STRIDE:
        *value = pitch;
        return true;
    case PIPE_RESOURCE_PARAM_OFFSET:
        *value = 0;
        return true;
    case PIPE_RESOURCE_PARAM_MODIFIER:
        *value = 0;
        return true;
    case PIPE_RESOURCE_PARAM_HANDLE_TYPE_KMS:
        *value = pipe_opengpu_resource_handle(res->gpu);
        return *value != 0;
    default:
        return false;
    }
}

static void flush_frontbuffer(struct pipe_screen *screen,
                              struct pipe_context *ctx,
                              struct pipe_resource *resource,
                              unsigned level, unsigned layer,
                              void *winsys_drawable_handle,
                              struct pipe_box *subbox)
{
    struct opengpu_resource *res = opengpu_resource(resource);

    (void)ctx;
    (void)level;
    (void)layer;
    (void)winsys_drawable_handle;
    (void)subbox;
    if (!res || !res->gpu)
        return;
    if (pipe_opengpu_present(opengpu_screen_gpu(screen), res->gpu))
        fprintf(stderr, "opengpu: present failed\n");
}

static void fence_reference(struct pipe_screen *screen,
                            struct pipe_fence_handle **ptr,
                            struct pipe_fence_handle *fence)
{
    (void)screen;
    (void)fence;
    /* Draws wait for the GPU fence inside the winsys before they return. */
    if (ptr)
        *ptr = NULL;
}

static bool fence_finish(struct pipe_screen *screen, struct pipe_context *ctx,
                         struct pipe_fence_handle *fence, uint64_t timeout)
{
    (void)screen;
    (void)ctx;
    (void)fence;
    (void)timeout;
    return true;
}

struct pipe_screen *opengpu_drm_screen_create(
    int fd, const struct pipe_screen_config *config)
{
    struct opengpu_screen *screen;

    (void)config;
    screen = CALLOC_STRUCT(opengpu_screen);
    if (!screen)
        return NULL;
    screen->gpu = pipe_opengpu_screen_create_fd(fd);
    if (!screen->gpu) {
        fprintf(stderr, "opengpu: screen init failed: %s\n", strerror(errno));
        FREE(screen);
        return NULL;
    }
    screen->base.destroy = screen_destroy;
    screen->base.get_name = screen_get_name;
    screen->base.get_vendor = screen_get_vendor;
    screen->base.get_device_vendor = screen_get_vendor;
    screen->base.get_param = screen_get_param;
    screen->base.get_paramf = screen_get_paramf;
    screen->base.get_shader_param = screen_get_shader_param;
    screen->base.is_format_supported = screen_is_format_supported;
    screen->base.context_create = opengpu_pipe_context_create;
    screen->base.resource_create = resource_create;
    screen->base.resource_destroy = resource_destroy;
    screen->base.resource_get_handle = resource_get_handle;
    screen->base.resource_get_param = resource_get_param;
    screen->base.flush_frontbuffer = flush_frontbuffer;
    screen->base.fence_reference = fence_reference;
    screen->base.fence_finish = fence_finish;
    return &screen->base;
}
