/* SPDX-License-Identifier: GPL-2.0 */
#ifndef OPENGPU_DRM_DEVICE_H
#define OPENGPU_DRM_DEVICE_H
#include <drm/drm_device.h>
#include <drm/drm_connector.h>
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

int opengpu_drm_init(struct opengpu_device *gpu, bool render_only);
int opengpu_drm_register(struct opengpu_device *gpu);
void opengpu_drm_unregister(struct opengpu_device *gpu);
#endif
