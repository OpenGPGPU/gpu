/* SPDX-License-Identifier: MIT */
/* Gallium screen for the OpenGPU DRM device. Included from Mesa's
 * drm_helper.h as opengpu/opengpu_public.h after scripts/stage_mesa_opengpu.sh
 * copies this tree into the pinned Mesa checkout. */
#ifndef OPENGPU_GALLIUM_PUBLIC_H
#define OPENGPU_GALLIUM_PUBLIC_H

struct pipe_screen;
struct pipe_screen_config;

struct pipe_screen *opengpu_drm_screen_create(
    int fd, const struct pipe_screen_config *config);

#endif
