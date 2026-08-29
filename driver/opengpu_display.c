// SPDX-License-Identifier: GPL-2.0
/* DRM/KMS display client over the host-visible scanout control interface. */
#include <linux/module.h>
#include <linux/overflow.h>

#include <drm/drm_atomic_helper.h>
#include <drm/drm_connector.h>
#include <drm/drm_device.h>
#include <drm/drm_drv.h>
#include <drm/drm_fb_dma_helper.h>
#include <drm/drm_fourcc.h>
#include <drm/drm_framebuffer.h>
#include <drm/drm_gem_dma_helper.h>
#include <drm/drm_gem_framebuffer_helper.h>
#include <drm/drm_managed.h>
#include <drm/drm_modes.h>
#include <drm/drm_probe_helper.h>
#include <drm/drm_simple_kms_helper.h>

#include "opengpu_device.h"

struct opengpu_drm {
    struct drm_device drm;
    struct drm_simple_display_pipe pipe;
    struct drm_connector connector;
    struct opengpu_device *gpu;
};

#define to_opengpu_drm(drm_dev) \
    container_of(drm_dev, struct opengpu_drm, drm)
#define pipe_to_opengpu_drm(display_pipe) \
    container_of(display_pipe, struct opengpu_drm, pipe)

static const u32 opengpu_formats[] = {
    /* DRM [31:0] R:G:B:A, matching renderer word 0xRRGGBBAA. */
    DRM_FORMAT_RGBA8888,
};

static int opengpu_kms_commit(struct opengpu_drm *kms,
                              struct drm_plane_state *plane_state)
{
    struct drm_framebuffer *fb = plane_state->fb;
    struct opengpu_scanout scanout;

    if (!fb || fb->format->format != DRM_FORMAT_RGBA8888)
        return -EINVAL;

    scanout = (struct opengpu_scanout) {
        .base = drm_fb_dma_get_gem_addr(fb, plane_state, 0),
        .stride = fb->pitches[0],
        .width = plane_state->crtc_w,
        .height = plane_state->crtc_h,
        .format = GPU_SCANOUT_FORMAT_RGBA8888,
        .enable = true,
    };
    return opengpu_hw_display_commit(kms->gpu, &scanout);
}

static enum drm_mode_status
opengpu_pipe_mode_valid(struct drm_simple_display_pipe *pipe,
                        const struct drm_display_mode *mode)
{
    struct opengpu_drm *kms = pipe_to_opengpu_drm(pipe);

    if (mode->hdisplay != kms->gpu->width ||
        mode->vdisplay != kms->gpu->height)
        return MODE_BAD;
    return MODE_OK;
}

static void opengpu_pipe_enable(struct drm_simple_display_pipe *pipe,
                                struct drm_crtc_state *crtc_state,
                                struct drm_plane_state *plane_state)
{
    struct opengpu_drm *kms = pipe_to_opengpu_drm(pipe);
    int ret = opengpu_kms_commit(kms, plane_state);

    if (ret)
        dev_err(kms->gpu->dev, "DRM scanout enable failed: %d\n", ret);
}

static void opengpu_pipe_update(struct drm_simple_display_pipe *pipe,
                                struct drm_plane_state *old_plane_state)
{
    struct opengpu_drm *kms = pipe_to_opengpu_drm(pipe);
    int ret;

    if (!pipe->plane.state->fb)
        return;
    ret = opengpu_kms_commit(kms, pipe->plane.state);
    if (ret)
        dev_err(kms->gpu->dev, "DRM scanout update failed: %d\n", ret);
}

static void opengpu_pipe_disable(struct drm_simple_display_pipe *pipe)
{
    struct opengpu_drm *kms = pipe_to_opengpu_drm(pipe);
    const struct opengpu_scanout disabled = { };

    opengpu_hw_display_commit(kms->gpu, &disabled);
}

static const struct drm_simple_display_pipe_funcs opengpu_pipe_funcs = {
    .mode_valid = opengpu_pipe_mode_valid,
    .enable = opengpu_pipe_enable,
    .update = opengpu_pipe_update,
    .disable = opengpu_pipe_disable,
};

static enum drm_connector_status
opengpu_connector_detect(struct drm_connector *connector, bool force)
{
    return connector_status_connected;
}

static int opengpu_connector_get_modes(struct drm_connector *connector)
{
    struct opengpu_drm *kms = to_opengpu_drm(connector->dev);
    struct drm_display_mode *mode;

    mode = drm_mode_create(connector->dev);
    if (!mode)
        return 0;

    mode->clock = max_t(u32, 1,
                        DIV_ROUND_UP(kms->gpu->width * kms->gpu->height * 60,
                                     1000));
    mode->hdisplay = kms->gpu->width;
    mode->hsync_start = mode->hdisplay + 1;
    mode->hsync_end = mode->hsync_start + 1;
    mode->htotal = mode->hsync_end + 2;
    mode->vdisplay = kms->gpu->height;
    mode->vsync_start = mode->vdisplay + 1;
    mode->vsync_end = mode->vsync_start + 1;
    mode->vtotal = mode->vsync_end + 2;
    mode->type = DRM_MODE_TYPE_DRIVER | DRM_MODE_TYPE_PREFERRED;
    drm_mode_set_name(mode);
    drm_mode_probed_add(connector, mode);
    return 1;
}

static const struct drm_connector_helper_funcs opengpu_connector_helpers = {
    .get_modes = opengpu_connector_get_modes,
};

static const struct drm_connector_funcs opengpu_connector_funcs = {
    .reset = drm_atomic_helper_connector_reset,
    .detect = opengpu_connector_detect,
    .fill_modes = drm_helper_probe_single_connector_modes,
    .destroy = drm_connector_cleanup,
    .atomic_duplicate_state = drm_atomic_helper_connector_duplicate_state,
    .atomic_destroy_state = drm_atomic_helper_connector_destroy_state,
};

static const struct drm_mode_config_funcs opengpu_mode_config_funcs = {
    .fb_create = drm_gem_fb_create,
    .atomic_check = drm_atomic_helper_check,
    .atomic_commit = drm_atomic_helper_commit,
};

DEFINE_DRM_GEM_DMA_FOPS(opengpu_drm_fops);

static const struct drm_driver opengpu_drm_driver = {
    .driver_features = DRIVER_MODESET | DRIVER_GEM | DRIVER_ATOMIC,
    .name = "opengpu",
    .desc = "RISC-V SIMT OpenGPU",
    .major = 1,
    .minor = 0,
    .fops = &opengpu_drm_fops,
    DRM_GEM_DMA_DRIVER_OPS,
};

static int opengpu_kms_init(struct opengpu_device *gpu)
{
    struct opengpu_drm *kms;
    int ret;

    kms = devm_drm_dev_alloc(gpu->dev, &opengpu_drm_driver,
                             struct opengpu_drm, drm);
    if (IS_ERR(kms))
        return PTR_ERR(kms);
    kms->gpu = gpu;

    ret = drmm_mode_config_init(&kms->drm);
    if (ret)
        return ret;
    kms->drm.mode_config.min_width = gpu->width;
    kms->drm.mode_config.max_width = gpu->width;
    kms->drm.mode_config.min_height = gpu->height;
    kms->drm.mode_config.max_height = gpu->height;
    kms->drm.mode_config.funcs = &opengpu_mode_config_funcs;

    drm_connector_helper_add(&kms->connector, &opengpu_connector_helpers);
    ret = drm_connector_init(&kms->drm, &kms->connector,
                             &opengpu_connector_funcs,
                             DRM_MODE_CONNECTOR_VIRTUAL);
    if (ret)
        return ret;

    ret = drm_simple_display_pipe_init(&kms->drm, &kms->pipe,
                                       &opengpu_pipe_funcs,
                                       opengpu_formats,
                                       ARRAY_SIZE(opengpu_formats),
                                       NULL, &kms->connector);
    if (ret)
        return ret;

    drm_mode_config_reset(&kms->drm);
    ret = drm_dev_register(&kms->drm, 0);
    if (ret)
        return ret;

    gpu->display.kms = kms;
    dev_info(gpu->dev, "OPENGPU DRM PASS: card registered, format=RGBA8888\n");
    return 0;
}

static void opengpu_kms_fini(struct opengpu_device *gpu)
{
    struct opengpu_drm *kms = gpu->display.kms;

    if (!kms)
        return;
    drm_dev_unregister(&kms->drm);
    drm_atomic_helper_shutdown(&kms->drm);
    gpu->display.kms = NULL;
}

int opengpu_display_init(struct opengpu_device *gpu,
                         const struct opengpu_buffer *boot_fb)
{
    struct opengpu_display *display = &gpu->display;
    struct opengpu_scanout scanout;
    size_t required;
    int ret;

    if (check_mul_overflow((size_t)gpu->stride, (size_t)gpu->height,
                           &required) || required > boot_fb->size)
        return -EINVAL;

    scanout = (struct opengpu_scanout) {
        .base = boot_fb->dma,
        .stride = gpu->stride,
        .width = gpu->width,
        .height = gpu->height,
        .format = GPU_SCANOUT_FORMAT_RGBA8888,
        .enable = true,
    };
    ret = opengpu_hw_display_commit(gpu, &scanout);
    if (ret)
        return ret;

    display->scanout = scanout.base;
    display->stride = scanout.stride;
    display->width = scanout.width;
    display->height = scanout.height;
    display->format = scanout.format;
    display->enabled = true;
    dev_info(gpu->dev, "display scanout: fb=%pad stride=%u mode=%ux%u\n",
             &display->scanout, display->stride,
             display->width, display->height);

    ret = opengpu_kms_init(gpu);
    if (ret) {
        const struct opengpu_scanout disabled = { };

        opengpu_hw_display_commit(gpu, &disabled);
        display->enabled = false;
    }
    return ret;
}

void opengpu_display_fini(struct opengpu_device *gpu)
{
    const struct opengpu_scanout disabled = { };

    opengpu_kms_fini(gpu);
    if (!gpu->display.enabled)
        return;
    opengpu_hw_display_commit(gpu, &disabled);
    gpu->display.enabled = false;
}
