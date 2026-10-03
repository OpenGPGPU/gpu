/* SPDX-License-Identifier: MIT */
#ifndef OPENGPU_GALLIUM_INTERNAL_H
#define OPENGPU_GALLIUM_INTERNAL_H

#include "pipe_opengpu.h"
#include "pipe/p_screen.h"
#include "pipe/p_state.h"

struct opengpu_resource {
    struct pipe_resource base;
    struct pipe_opengpu_resource *gpu;
};

struct opengpu_resource *opengpu_resource(struct pipe_resource *resource);
struct pipe_opengpu_screen *opengpu_screen_gpu(struct pipe_screen *screen);
struct pipe_context *opengpu_pipe_context_create(struct pipe_screen *screen,
                                                 void *priv, unsigned flags);

#endif
