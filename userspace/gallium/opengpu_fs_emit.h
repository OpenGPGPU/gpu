/* SPDX-License-Identifier: MIT */
#ifndef OPENGPU_FS_EMIT_H
#define OPENGPU_FS_EMIT_H

#include <stdint.h>

/* A fragment program lowered onto the shader core. Constants occupy
 * kernarg bytes 288 onward, one vec4 at a time; immediates follow them. */
struct opengpu_fs_alu {
    uint32_t code[256];
    unsigned words;
    unsigned nconst;
    float imm[8];
    unsigned nimm;
};

/* tokens is a TGSI token stream. Returns 0 when the program fits in the
 * 256-instruction sandbox. */
int opengpu_fs_emit(const void *tokens, struct opengpu_fs_alu *out);

#endif
