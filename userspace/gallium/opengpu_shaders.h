/* SPDX-License-Identifier: MIT */
/* GPU shader binaries for the Gallium driver. These words are the corpus
 * shaders fragment_color.S and vertex_passthrough.S. The vertex core runs
 * the copy, and the fragment core runs the colour copy. */
#ifndef OPENGPU_GALLIUM_SHADERS_H
#define OPENGPU_GALLIUM_SHADERS_H

#include <stddef.h>
#include <stdint.h>

static const uint32_t opengpu_fragment_color[] = {
    0x00241293u, 0x005082b3u, 0xc1027057u, 0x06028313u,
    0x02036087u, 0x0c028313u, 0x020360a7u, 0x30500073u,
};

static const uint32_t opengpu_vertex_passthrough[] = {
    0x00241293u, 0x005082b3u, 0xc1027057u, 0x00028313u, 0x02036087u,
    0x10028313u, 0x020360a7u, 0x02028313u, 0x02036087u, 0x12028313u,
    0x020360a7u, 0x04028313u, 0x02036087u, 0x14028313u, 0x020360a7u,
    0x06028313u, 0x02036087u, 0x16028313u, 0x020360a7u, 0x08028313u,
    0x02036087u, 0x18028313u, 0x020360a7u, 0x0a028313u, 0x02036087u,
    0x1a028313u, 0x020360a7u, 0x0c028313u, 0x02036087u, 0x1c028313u,
    0x020360a7u, 0x0e028313u, 0x02036087u, 0x1e028313u, 0x020360a7u,
    0x30500073u,
};

#define OPENGPU_FRAGMENT_COLOR_BYTES (sizeof(opengpu_fragment_color))
#define OPENGPU_VERTEX_PASSTHROUGH_BYTES (sizeof(opengpu_vertex_passthrough))

#endif
