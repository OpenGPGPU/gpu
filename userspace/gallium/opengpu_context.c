/* SPDX-License-Identifier: MIT */
/* Gallium context. A draw is a GPU vertex-core submit: the vertex shader and
 * the fragment shader binaries in opengpu_shaders.h run on the shader cores,
 * and the RTL rasterizes the triangle. A TGSI program that is not a
 * passthrough MOV fails the draw. The Gallium draw module is not used. */
#include "opengpu_internal.h"
#include "opengpu_shaders.h"

#include "pipe/p_context.h"
#include "pipe/p_defines.h"
#include "tgsi/tgsi_dump.h"
#include "tgsi/tgsi_parse.h"
#include "util/u_inlines.h"
#include "util/u_memory.h"
#include "util/u_transfer.h"
#include "util/u_upload_mgr.h"

#include <errno.h>
#include <stdio.h>
#include <string.h>

#define OPENGPU_VB_SLOTS 8u
#define OPENGPU_ATTRIBS 8u

struct opengpu_shader {
    int is_fs;
    int pos_src;
    int color_src;
    int color_imm;
    float color[4];
};

struct opengpu_context {
    struct pipe_context base;
    struct pipe_opengpu_context *gpu;
    struct pipe_surface *cbuf;
    struct pipe_surface *zsbuf;
    uint32_t width;
    uint32_t height;
    struct opengpu_shader *vs;
    struct opengpu_shader *fs;
    struct pipe_vertex_buffer vb[OPENGPU_VB_SLOTS];
    struct pipe_vertex_element ve[OPENGPU_ATTRIBS];
    unsigned nr_ve;
    struct pipe_rasterizer_state rast;
    struct pipe_depth_stencil_alpha_state dsa;
    int blend_enabled;
    struct pipe_opengpu_resource *packed;
    uint32_t packed_bytes;
};

struct hw_vertex {
    int32_t x, y, z, w;
    uint32_t color;
    int32_t depth;
    uint32_t u, v;
};

static struct opengpu_context *octx(struct pipe_context *pipe)
{
    return (struct opengpu_context *)pipe;
}

/* A FlashSim draw can take minutes of wall clock; match pipe_desktop. */
#define OPENGPU_FENCE_TIMEOUT_MS 300000

static void reject(const char *why)
{
    fprintf(stderr, "opengpu: %s\n", why);
}

static void gpu_failed(const char *why)
{
    fprintf(stderr, "opengpu: %s (%s)\n", why, strerror(errno));
}

static int identity_swizzle(const struct tgsi_src_register *src)
{
    return !src->Indirect && !src->Dimension && !src->Absolute && !src->Negate &&
           src->SwizzleX == TGSI_SWIZZLE_X && src->SwizzleY == TGSI_SWIZZLE_Y &&
           src->SwizzleZ == TGSI_SWIZZLE_Z && src->SwizzleW == TGSI_SWIZZLE_W;
}

/* The GPU vertex format carries position and one colour. Mesa may still
 * emit point size or clip outputs; those do not change the triangle. */
static int ignored_output(unsigned sem)
{
    return sem == TGSI_SEMANTIC_PSIZE || sem == TGSI_SEMANTIC_EDGEFLAG ||
           sem == TGSI_SEMANTIC_CLIPDIST || sem == TGSI_SEMANTIC_CLIPVERTEX ||
           sem == TGSI_SEMANTIC_FOG;
}

static int color_semantic(unsigned sem)
{
    return sem == TGSI_SEMANTIC_COLOR || sem == TGSI_SEMANTIC_GENERIC ||
           sem == TGSI_SEMANTIC_TEXCOORD;
}

/* Record which input feeds gl_Position and which feeds the colour. Anything
 * other than MOV/END, or a write the fixed vertex format cannot carry, fails. */
static struct opengpu_shader *lower_shader(const struct pipe_shader_state *state,
                                           int want_fs)
{
    struct tgsi_parse_context parse;
    struct opengpu_shader *shader;
    int sem_in[OPENGPU_ATTRIBS];
    int sem_out[OPENGPU_ATTRIBS];
    int temp_file[OPENGPU_ATTRIBS];
    int temp_index[OPENGPU_ATTRIBS];
    float imm[4];
    int nimm = 0, saw_end = 0, i;

    if (!state || state->type != PIPE_SHADER_IR_TGSI || !state->tokens)
        return NULL;
    if (tgsi_get_processor_type(state->tokens) !=
        (want_fs ? PIPE_SHADER_FRAGMENT : PIPE_SHADER_VERTEX))
        return NULL;
    if (tgsi_parse_init(&parse, state->tokens) != TGSI_PARSE_OK)
        return NULL;

    for (i = 0; i < (int)OPENGPU_ATTRIBS; i++) {
        sem_in[i] = sem_out[i] = -1;
        temp_file[i] = -1;
        temp_index[i] = -1;
    }
    shader = CALLOC_STRUCT(opengpu_shader);
    if (!shader) {
        tgsi_parse_free(&parse);
        return NULL;
    }
    shader->is_fs = want_fs;
    shader->pos_src = -1;
    shader->color_src = -1;

    while (!tgsi_parse_end_of_tokens(&parse)) {
        const struct tgsi_full_instruction *insn;
        const struct tgsi_full_declaration *decl;
        unsigned file, index, sem;

        tgsi_parse_token(&parse);
        switch (parse.FullToken.Token.Type) {
        case TGSI_TOKEN_TYPE_DECLARATION:
            decl = &parse.FullToken.FullDeclaration;
            file = decl->Declaration.File;
            if (file == TGSI_FILE_TEMPORARY || file == TGSI_FILE_CONSTANT ||
                file == TGSI_FILE_CONSTBUF || file == TGSI_FILE_SYSTEM_VALUE ||
                file == TGSI_FILE_ADDRESS || file == TGSI_FILE_NULL)
                break;
            if (decl->Range.First != decl->Range.Last ||
                decl->Range.First >= OPENGPU_ATTRIBS)
                goto fail;
            index = decl->Range.First;
            if (file != TGSI_FILE_INPUT && file != TGSI_FILE_OUTPUT)
                goto fail;
            /* nir_to_tgsi emits DCL IN[n] with no semantic. n is the
             * attribute slot from glBindAttribLocation. */
            if (file == TGSI_FILE_INPUT && !decl->Declaration.Semantic) {
                if (want_fs)
                    goto fail;
                sem_in[index] = TGSI_SEMANTIC_GENERIC;
                break;
            }
            if (!decl->Declaration.Semantic)
                goto fail;
            sem = decl->Semantic.Name;
            if (file == TGSI_FILE_INPUT)
                sem_in[index] = (int)sem;
            else
                sem_out[index] = (int)sem;
            break;
        case TGSI_TOKEN_TYPE_IMMEDIATE:
            if (nimm || parse.FullToken.FullImmediate.Immediate.NrTokens < 5)
                goto fail;
            for (i = 0; i < 4; i++)
                imm[i] = parse.FullToken.FullImmediate.u[i].Float;
            nimm = 1;
            break;
        case TGSI_TOKEN_TYPE_INSTRUCTION:
            insn = &parse.FullToken.FullInstruction;
            if (insn->Instruction.Opcode == TGSI_OPCODE_END) {
                saw_end = 1;
                break;
            }
            if (insn->Instruction.Opcode != TGSI_OPCODE_MOV ||
                insn->Instruction.Saturate || insn->Instruction.NumDstRegs != 1 ||
                insn->Instruction.NumSrcRegs != 1)
                goto fail;
            if ((insn->Dst[0].Register.File != TGSI_FILE_OUTPUT &&
                 insn->Dst[0].Register.File != TGSI_FILE_TEMPORARY) ||
                insn->Dst[0].Register.Indirect)
                goto fail;
            if (insn->Dst[0].Register.File == TGSI_FILE_OUTPUT &&
                insn->Dst[0].Register.Index < OPENGPU_ATTRIBS &&
                sem_out[insn->Dst[0].Register.Index] >= 0 &&
                ignored_output((unsigned)sem_out[insn->Dst[0].Register.Index]))
                break;
            if (insn->Dst[0].Register.WriteMask != TGSI_WRITEMASK_XYZW)
                goto fail;
            if (!identity_swizzle(&insn->Src[0].Register))
                goto fail;
            index = (unsigned)insn->Dst[0].Register.Index;
            if (index >= OPENGPU_ATTRIBS)
                goto fail;
            if (insn->Dst[0].Register.File == TGSI_FILE_TEMPORARY) {
                unsigned src_file = insn->Src[0].Register.File;
                int src_index = insn->Src[0].Register.Index;

                if (src_file == TGSI_FILE_TEMPORARY) {
                    if (src_index < 0 || src_index >= (int)OPENGPU_ATTRIBS ||
                        temp_file[src_index] < 0)
                        goto fail;
                    src_file = (unsigned)temp_file[src_index];
                    src_index = temp_index[src_index];
                }
                if (src_file != TGSI_FILE_INPUT && src_file != TGSI_FILE_IMMEDIATE)
                    goto fail;
                temp_file[index] = (int)src_file;
                temp_index[index] = src_index;
                break;
            }
            if (sem_out[index] < 0)
                goto fail;
            sem = (unsigned)sem_out[index];
            {
                unsigned src_file = insn->Src[0].Register.File;
                int src_index = insn->Src[0].Register.Index;

                if (src_file == TGSI_FILE_TEMPORARY) {
                    if (src_index < 0 || src_index >= (int)OPENGPU_ATTRIBS ||
                        temp_file[src_index] < 0)
                        goto fail;
                    src_file = (unsigned)temp_file[src_index];
                    src_index = temp_index[src_index];
                }
                if (src_file == TGSI_FILE_IMMEDIATE) {
                    if (!nimm || src_index != 0)
                        goto fail;
                    if (!color_semantic(sem))
                        goto fail;
                    shader->color_imm = 1;
                    memcpy(shader->color, imm, sizeof(shader->color));
                } else if (src_file == TGSI_FILE_INPUT) {
                    unsigned src = (unsigned)src_index;

                    if (src >= OPENGPU_ATTRIBS || sem_in[src] < 0)
                        goto fail;
                    if (!want_fs && sem == TGSI_SEMANTIC_POSITION) {
                        if (shader->pos_src >= 0)
                            goto fail;
                        shader->pos_src = (int)src;
                    } else if (color_semantic(sem)) {
                        if (shader->color_src >= 0 || shader->color_imm)
                            goto fail;
                        shader->color_src = (int)src;
                    } else {
                        goto fail;
                    }
                } else {
                    goto fail;
                }
            }
            break;
        case TGSI_TOKEN_TYPE_PROPERTY:
            break;
        default:
            goto fail;
        }
    }
    tgsi_parse_free(&parse);
    if (!saw_end || (want_fs && shader->color_src < 0 && !shader->color_imm) ||
        (!want_fs && shader->pos_src < 0)) {
        FREE(shader);
        return NULL;
    }
    return shader;
fail:
    tgsi_parse_free(&parse);
    FREE(shader);
    return NULL;
}

static void *create_shader(struct pipe_context *pipe,
                           const struct pipe_shader_state *state, int want_fs)
{
    struct opengpu_shader *shader = lower_shader(state, want_fs);

    (void)pipe;
    if (!shader) {
        reject(want_fs ? "fragment shader did not lower onto the fragment core"
                       : "vertex shader did not lower onto the vertex core");
        if (state && state->type == PIPE_SHADER_IR_TGSI && state->tokens)
            tgsi_dump(state->tokens, 0);
    }
    return shader;
}

static void *create_fs_state(struct pipe_context *pipe,
                             const struct pipe_shader_state *state)
{
    return create_shader(pipe, state, 1);
}

static void *create_vs_state(struct pipe_context *pipe,
                             const struct pipe_shader_state *state)
{
    return create_shader(pipe, state, 0);
}

static void bind_fs_state(struct pipe_context *pipe, void *hw)
{
    struct opengpu_context *ctx = octx(pipe);

    ctx->fs = hw;
    if (hw && pipe_opengpu_bind_fs(ctx->gpu, opengpu_fragment_color,
                                   OPENGPU_FRAGMENT_COLOR_BYTES))
        reject("fragment shader bind failed");
}

static void bind_vs_state(struct pipe_context *pipe, void *hw)
{
    struct opengpu_context *ctx = octx(pipe);

    ctx->vs = hw;
    if (hw && pipe_opengpu_bind_vs(ctx->gpu, opengpu_vertex_passthrough,
                                   OPENGPU_VERTEX_PASSTHROUGH_BYTES))
        reject("vertex shader bind failed");
}

static void delete_shader(struct pipe_context *pipe, void *hw)
{
    (void)pipe;
    FREE(hw);
}

static void *create_state(struct pipe_context *pipe, const void *templ,
                          size_t bytes)
{
    void *copy;

    (void)pipe;
    copy = MALLOC(bytes);
    if (copy)
        memcpy(copy, templ, bytes);
    return copy;
}

static void *create_blend(struct pipe_context *pipe,
                          const struct pipe_blend_state *templ)
{
    return create_state(pipe, templ, sizeof(*templ));
}

static void bind_blend(struct pipe_context *pipe, void *hw)
{
    const struct pipe_blend_state *blend = hw;

    octx(pipe)->blend_enabled = blend && blend->rt[0].blend_enable;
}

static void *create_rast(struct pipe_context *pipe,
                         const struct pipe_rasterizer_state *templ)
{
    return create_state(pipe, templ, sizeof(*templ));
}

static void bind_rast(struct pipe_context *pipe, void *hw)
{
    if (hw)
        octx(pipe)->rast = *(struct pipe_rasterizer_state *)hw;
}

static void *create_dsa(struct pipe_context *pipe,
                        const struct pipe_depth_stencil_alpha_state *templ)
{
    return create_state(pipe, templ, sizeof(*templ));
}

static void bind_dsa(struct pipe_context *pipe, void *hw)
{
    if (hw)
        octx(pipe)->dsa = *(struct pipe_depth_stencil_alpha_state *)hw;
}

static void delete_state(struct pipe_context *pipe, void *hw)
{
    (void)pipe;
    FREE(hw);
}

struct opengpu_ve {
    unsigned nr;
    struct pipe_vertex_element ve[OPENGPU_ATTRIBS];
};

static void *create_ve(struct pipe_context *pipe, unsigned num,
                       const struct pipe_vertex_element *elems)
{
    struct opengpu_ve *box;

    (void)pipe;
    if (num > OPENGPU_ATTRIBS)
        return NULL;
    box = CALLOC_STRUCT(opengpu_ve);
    if (!box)
        return NULL;
    box->nr = num;
    memcpy(box->ve, elems, sizeof(*elems) * num);
    return box;
}

static void bind_ve(struct pipe_context *pipe, void *hw)
{
    struct opengpu_context *ctx = octx(pipe);
    struct opengpu_ve *box = hw;

    ctx->nr_ve = 0;
    if (!box)
        return;
    ctx->nr_ve = box->nr;
    memcpy(ctx->ve, box->ve, sizeof(box->ve));
}

static void set_vertex_buffers(struct pipe_context *pipe, unsigned start,
                               unsigned num, unsigned unbind, bool take,
                               const struct pipe_vertex_buffer *buffers)
{
    struct opengpu_context *ctx = octx(pipe);
    unsigned i;

    if (start + num + unbind > OPENGPU_VB_SLOTS) {
        reject("too many vertex buffers");
        return;
    }
    for (i = 0; i < num; i++) {
        unsigned slot = start + i;
        struct pipe_vertex_buffer *dst = &ctx->vb[slot];

        if (!dst->is_user_buffer)
            pipe_resource_reference(&dst->buffer.resource, NULL);
        *dst = buffers[i];
        dst->buffer.resource = NULL;
        if (buffers[i].is_user_buffer) {
            dst->buffer.user = buffers[i].buffer.user;
        } else if (take) {
            dst->buffer.resource = buffers[i].buffer.resource;
        } else {
            pipe_resource_reference(&dst->buffer.resource,
                                    buffers[i].buffer.resource);
        }
    }
    for (i = 0; i < unbind; i++) {
        struct pipe_vertex_buffer *dst = &ctx->vb[start + num + i];

        if (!dst->is_user_buffer)
            pipe_resource_reference(&dst->buffer.resource, NULL);
        memset(dst, 0, sizeof(*dst));
    }
}

static struct pipe_surface *create_surface(struct pipe_context *ctx,
                            struct pipe_resource *resource,
                            const struct pipe_surface *templ)
{
    struct pipe_surface *surf = CALLOC_STRUCT(pipe_surface);

    if (!surf)
        return NULL;
    *surf = *templ;
    surf->context = ctx;
    surf->reference.count = 1;
    surf->texture = NULL;
    /* Mesa sizes the window framebuffer from the surface, not the texture. */
    surf->width = resource->width0;
    surf->height = resource->height0;
    pipe_resource_reference(&surf->texture, resource);
    return surf;
}

static void surface_destroy(struct pipe_context *pipe, struct pipe_surface *surf)
{
    (void)pipe;
    pipe_resource_reference(&surf->texture, NULL);
    FREE(surf);
}

static void set_framebuffer(struct pipe_context *pipe,
                            const struct pipe_framebuffer_state *state)
{
    struct opengpu_context *ctx = octx(pipe);
    struct pipe_surface *color = state->nr_cbufs ? state->cbufs[0] : NULL;
    struct opengpu_resource *cres;
    struct opengpu_resource *dres = NULL;

    pipe_surface_reference(&ctx->cbuf, color);
    pipe_surface_reference(&ctx->zsbuf, state->zsbuf);
    ctx->width = state->width;
    ctx->height = state->height;
    if (!ctx->cbuf || !ctx->cbuf->texture)
        return;
    cres = opengpu_resource(ctx->cbuf->texture);
    if (ctx->zsbuf && ctx->zsbuf->texture)
        dres = opengpu_resource(ctx->zsbuf->texture);
    pipe_opengpu_set_framebuffer(ctx->gpu, cres->gpu, state->width, state->height);
    pipe_opengpu_set_depth(ctx->gpu, dres ? dres->gpu : NULL, 0);
}

static uint32_t pack_color(const float c[4])
{
    uint32_t ch[4];
    unsigned i;

    for (i = 0; i < 4; i++) {
        float v = c[i];

        if (v < 0.0f)
            v = 0.0f;
        if (v > 1.0f)
            v = 1.0f;
        ch[i] = (uint32_t)(v * 255.0f + 0.5f);
    }
    return (ch[0] << 24) | (ch[1] << 16) | (ch[2] << 8) | ch[3];
}

static int32_t q16(float x)
{
    double s = (double)x * 65536.0;

    if (s >= 2147483647.0)
        return 2147483647;
    if (s <= -2147483648.0)
        return (int32_t)(-2147483647 - 1);
    return (int32_t)(s >= 0.0 ? s + 0.5 : s - 0.5);
}

static void clear(struct pipe_context *pipe, unsigned buffers,
                  const struct pipe_scissor_state *scissor,
                  const union pipe_color_union *color, double depth,
                  unsigned stencil)
{
    struct opengpu_context *ctx = octx(pipe);
    struct pipe_opengpu_fence *fence = NULL;

    (void)scissor;
    if ((buffers & PIPE_CLEAR_COLOR0) && color) {
        float rgba[4] = { color->f[0], color->f[1], color->f[2], color->f[3] };

        if (pipe_opengpu_clear(ctx->gpu, pack_color(rgba), &fence) ||
            pipe_opengpu_fence_finish(ctx->gpu, fence, OPENGPU_FENCE_TIMEOUT_MS))
            gpu_failed("colour clear failed");
        pipe_opengpu_fence_reference(&fence, NULL);
    }
    if ((buffers & (PIPE_CLEAR_DEPTH | PIPE_CLEAR_STENCIL)) && ctx->zsbuf &&
        ctx->zsbuf->texture) {
        struct opengpu_resource *depth_res = opengpu_resource(ctx->zsbuf->texture);
        uint32_t d = (uint32_t)(depth * 16777215.0 + 0.5);
        uint32_t pattern;
        uint64_t bytes;

        if (d > 0x00ffffffu)
            d = 0x00ffffffu;
        if (!(buffers & PIPE_CLEAR_STENCIL))
            stencil = 0;
        pattern = (stencil << 24) | d;
        bytes = pipe_opengpu_resource_size(depth_res->gpu);
        if (pipe_opengpu_fill(ctx->gpu, depth_res->gpu, 0, bytes, pattern,
                              &fence) ||
            pipe_opengpu_fence_finish(ctx->gpu, fence, OPENGPU_FENCE_TIMEOUT_MS))
            gpu_failed("depth clear failed");
        pipe_opengpu_fence_reference(&fence, NULL);
    }
}

static const uint8_t *attrib_ptr(struct opengpu_context *ctx, unsigned index,
                                 unsigned vertex, unsigned *comps)
{
    const struct pipe_vertex_element *ve;
    const struct pipe_vertex_buffer *vb;
    const uint8_t *base;
    unsigned format;

    if (index >= ctx->nr_ve)
        return NULL;
    ve = &ctx->ve[index];
    if (ve->vertex_buffer_index >= OPENGPU_VB_SLOTS || ve->instance_divisor)
        return NULL;
    vb = &ctx->vb[ve->vertex_buffer_index];
    format = ve->src_format;
    if (format == PIPE_FORMAT_R32G32B32A32_FLOAT)
        *comps = 4;
    else if (format == PIPE_FORMAT_R32G32B32_FLOAT)
        *comps = 3;
    else if (format == PIPE_FORMAT_R32G32_FLOAT)
        *comps = 2;
    else if (format == PIPE_FORMAT_R32_FLOAT ||
             format == PIPE_FORMAT_R8G8B8A8_UNORM)
        *comps = 1;
    else
        return NULL;
    if (vb->is_user_buffer)
        base = vb->buffer.user;
    else if (vb->buffer.resource)
        base = pipe_opengpu_resource_map(
            opengpu_resource(vb->buffer.resource)->gpu);
    else
        return NULL;
    if (!base)
        return NULL;
    {
        uint64_t off = (uint64_t)vb->buffer_offset + ve->src_offset +
                       (uint64_t)vertex * vb->stride;
        uint64_t bytes = off + (uint64_t)(*comps) * 4u;

        if (!vb->is_user_buffer &&
            bytes > pipe_opengpu_resource_size(
                        opengpu_resource(vb->buffer.resource)->gpu))
            return NULL;
        return base + off;
    }
}

static int load_color(const uint8_t *ptr, unsigned format, unsigned comps,
                      float out[4])
{
    if (format == PIPE_FORMAT_R8G8B8A8_UNORM) {
        out[0] = ptr[0] / 255.0f;
        out[1] = ptr[1] / 255.0f;
        out[2] = ptr[2] / 255.0f;
        out[3] = ptr[3] / 255.0f;
        return 0;
    }
    if (comps == 0 || comps > 4)
        return -1;
    memset(out, 0, sizeof(float) * 4);
    out[3] = 1.0f;
    memcpy(out, ptr, sizeof(float) * comps);
    return 0;
}

static int pack_one(struct opengpu_context *ctx, unsigned vertex,
                    const struct opengpu_shader *vs,
                    const struct opengpu_shader *fs, struct hw_vertex *out)
{
    const struct pipe_vertex_element *ve;
    const uint8_t *ptr;
    unsigned comps = 0;
    float p[4] = { 0, 0, 0, 1 };
    float c[4] = { 1, 1, 1, 1 };

    ptr = attrib_ptr(ctx, (unsigned)vs->pos_src, vertex, &comps);
    if (!ptr)
        return -1;
    ve = &ctx->ve[vs->pos_src];
    if (ve->src_format == PIPE_FORMAT_R8G8B8A8_UNORM)
        return -1;
    memcpy(p, ptr, sizeof(float) * (comps < 4 ? comps : 4));
    if (fs->color_imm)
        memcpy(c, fs->color, sizeof(c));
    else if (vs->color_imm)
        memcpy(c, vs->color, sizeof(c));
    else if (vs->color_src >= 0) {
        unsigned cc = 0;
        const uint8_t *cp =
            attrib_ptr(ctx, (unsigned)vs->color_src, vertex, &cc);

        if (!cp || load_color(cp, ctx->ve[vs->color_src].src_format, cc, c))
            return -1;
    }
    out->x = q16(p[0]);
    out->y = q16(p[1]);
    out->z = q16(p[2]);
    out->w = q16(p[3]);
    out->color = pack_color(c);
    out->depth = 0x10;
    out->u = 0;
    out->v = 0;
    return 0;
}

static int hw_cull(const struct pipe_rasterizer_state *rast, uint32_t *cull)
{
    unsigned face = rast->cull_face;

    if (!rast->front_ccw) {
        if (face == PIPE_FACE_FRONT)
            face = PIPE_FACE_BACK;
        else if (face == PIPE_FACE_BACK)
            face = PIPE_FACE_FRONT;
    }
    if (face == PIPE_FACE_NONE) {
        *cull = 0;
        return 0;
    }
    if (face == PIPE_FACE_BACK) {
        *cull = 1;
        return 0;
    }
    if (face == PIPE_FACE_FRONT) {
        *cull = 2;
        return 0;
    }
    return -1;
}

static int hw_depth(const struct pipe_depth_stencil_alpha_state *dsa,
                    uint32_t *bits)
{
    unsigned func;

    *bits = 0;
    if (!dsa->depth_enabled)
        return 0;
    switch (dsa->depth_func) {
    case PIPE_FUNC_LESS:
        func = 0;
        break;
    case PIPE_FUNC_LEQUAL:
        func = 1;
        break;
    case PIPE_FUNC_GREATER:
        func = 2;
        break;
    case PIPE_FUNC_ALWAYS:
        func = 3;
        break;
    case PIPE_FUNC_NEVER:
        func = 7;
        break;
    default:
        return -1;
    }
    *bits = OPENGPU_DRAW_STATE_DEPTH_TEST |
            (func << OPENGPU_DRAW_STATE_DEPTH_FUNC_SHIFT);
    if (dsa->depth_writemask)
        *bits |= OPENGPU_DRAW_STATE_DEPTH_WRITE;
    return 0;
}

static void draw_vbo(struct pipe_context *pipe,
                     const struct pipe_draw_info *info,
                     unsigned drawid_offset,
                     const struct pipe_draw_indirect_info *indirect,
                     const struct pipe_draw_start_count_bias *draws,
                     unsigned num_draws)
{
    struct opengpu_context *ctx = octx(pipe);
    unsigned draw_i;

    (void)drawid_offset;
    fprintf(stderr, "opengpu: draw\n");
    if (!ctx->vs || !ctx->fs || !ctx->cbuf) {
        reject("draw without a lowered shader or a colour target");
        return;
    }
    if (indirect || info->index_size || info->primitive_restart ||
        info->mode != PIPE_PRIM_TRIANGLES ||
        (info->instance_count > 1)) {
        reject("draw needs a non-indexed triangle list on the GPU");
        return;
    }
    if (ctx->blend_enabled || ctx->dsa.alpha_enabled) {
        reject("blend and alpha test are not lowered onto the GPU yet");
        return;
    }
    for (draw_i = 0; draw_i < num_draws; draw_i++) {
        unsigned start = draws[draw_i].start;
        unsigned count = draws[draw_i].count;
        unsigned tri;

        if (!count)
            continue;
        if (count % 3u) {
            reject("triangle list count is not a multiple of 3");
            return;
        }
        for (tri = 0; tri < count; tri += 3) {
            struct hw_vertex verts[3];
            struct drm_opengpu_vertex_draw vdraw;
            struct pipe_opengpu_fence *fence = NULL;
            uint32_t cull = 0, depth_bits = 0;
            unsigned v;

            for (v = 0; v < 3; v++) {
                if (pack_one(ctx, start + tri + v, ctx->vs, ctx->fs, &verts[v])) {
                    reject("vertex attribute did not pack into the GPU format");
                    return;
                }
            }
            if (!ctx->packed || ctx->packed_bytes < sizeof(verts)) {
                if (ctx->packed)
                    pipe_opengpu_resource_destroy(
                        opengpu_screen_gpu(ctx->base.screen), ctx->packed);
                ctx->packed_bytes = 64;
                ctx->packed = pipe_opengpu_resource_create(
                    opengpu_screen_gpu(ctx->base.screen), ctx->packed_bytes);
                if (!ctx->packed) {
                    reject("vertex buffer allocation failed");
                    return;
                }
            }
            {
                void *mapped = pipe_opengpu_resource_map(ctx->packed);

                if (!mapped) {
                    reject("vertex buffer map failed");
                    return;
                }
                memcpy(mapped, verts, sizeof(verts));
            }
            if (pipe_opengpu_invalidate(ctx->gpu, ctx->packed, 0, ctx->packed_bytes,
                                        &fence) ||
                pipe_opengpu_fence_finish(ctx->gpu, fence, OPENGPU_FENCE_TIMEOUT_MS)) {
                pipe_opengpu_fence_reference(&fence, NULL);
                gpu_failed("vertex buffer invalidate failed");
                return;
            }
            pipe_opengpu_fence_reference(&fence, NULL);
            if (pipe_opengpu_set_vertex_buffer(ctx->gpu, ctx->packed,
                                               sizeof(verts[0]), sizeof(verts))) {
                reject("vertex buffer bind failed");
                return;
            }
            if (hw_cull(&ctx->rast, &cull) || hw_depth(&ctx->dsa, &depth_bits)) {
                reject("cull or depth state does not map onto the GPU");
                return;
            }
            memset(&vdraw, 0, sizeof(vdraw));
            vdraw.vertex_count = 3;
            vdraw.vertex_stride = sizeof(verts[0]);
            vdraw.fragment_kernarg_bank_stride = 320;
            vdraw.state = OPENGPU_DRAW_STATE_OVERRIDE | depth_bits |
                          (cull << OPENGPU_DRAW_STATE_CULL_SHIFT);
            if (pipe_opengpu_draw_vertex(ctx->gpu, &vdraw, &fence) ||
                pipe_opengpu_fence_finish(ctx->gpu, fence, OPENGPU_FENCE_TIMEOUT_MS))
                gpu_failed("GPU draw failed");
            pipe_opengpu_fence_reference(&fence, NULL);
        }
    }
}

static void *buffer_map(struct pipe_context *pipe, struct pipe_resource *resource,
                        unsigned level, unsigned usage, const struct pipe_box *box,
                        struct pipe_transfer **out)
{
    struct opengpu_resource *res = opengpu_resource(resource);
    struct pipe_transfer *xfer;
    uint8_t *map;

    (void)pipe;
    (void)level;
    (void)usage;
    map = pipe_opengpu_resource_map(res->gpu);
    if (!map)
        return NULL;
    xfer = CALLOC_STRUCT(pipe_transfer);
    if (!xfer)
        return NULL;
    xfer->resource = resource;
    xfer->level = level;
    xfer->usage = usage;
    xfer->box = *box;
    xfer->stride = resource->target == PIPE_BUFFER
                       ? 0
                       : pipe_opengpu_resource_pitch(res->gpu);
    xfer->layer_stride = 0;
    *out = xfer;
    if (resource->target == PIPE_BUFFER)
        return map + box->x;
    /* Colour and depth targets are one 32-bit texel. box->x is in texels. */
    return map + (size_t)box->y * xfer->stride + (size_t)box->x * 4u;
}

static void buffer_unmap(struct pipe_context *pipe, struct pipe_transfer *xfer)
{
    struct opengpu_context *ctx = octx(pipe);
    struct opengpu_resource *res = opengpu_resource(xfer->resource);
    struct pipe_opengpu_fence *fence = NULL;

    if (xfer->usage & PIPE_MAP_WRITE) {
        uint64_t bytes = pipe_opengpu_resource_size(res->gpu);

        if ((bytes & 63u) == 0 &&
            (pipe_opengpu_invalidate(ctx->gpu, res->gpu, 0, bytes, &fence) ||
             pipe_opengpu_fence_finish(ctx->gpu, fence, OPENGPU_FENCE_TIMEOUT_MS)))
            gpu_failed("buffer invalidate failed");
        pipe_opengpu_fence_reference(&fence, NULL);
    }
    FREE(xfer);
}

/* Mesa sets these on every draw. The RTL viewport is the colour target
 * (GeometryStage maps clip ±w onto the full framebuffer), so a GL viewport,
 * scissor, or clip plane that is not that target is not a GPU job yet.
 * Constant buffers and samplers are unused by the passthrough shaders. */
static void set_viewport_states(struct pipe_context *pipe, unsigned start,
                                unsigned num, const struct pipe_viewport_state *state)
{
    (void)pipe;
    (void)start;
    (void)num;
    (void)state;
}

static void set_scissor_states(struct pipe_context *pipe, unsigned start,
                               unsigned num, const struct pipe_scissor_state *state)
{
    (void)pipe;
    (void)start;
    (void)num;
    (void)state;
}

static void set_blend_color(struct pipe_context *pipe,
                            const struct pipe_blend_color *color)
{
    (void)pipe;
    (void)color;
}

static void set_stencil_ref(struct pipe_context *pipe,
                            const struct pipe_stencil_ref ref)
{
    (void)pipe;
    (void)ref;
}

static void set_sample_mask(struct pipe_context *pipe, unsigned mask)
{
    (void)pipe;
    (void)mask;
}

static void set_clip_state(struct pipe_context *pipe,
                           const struct pipe_clip_state *state)
{
    (void)pipe;
    (void)state;
}

static void set_polygon_stipple(struct pipe_context *pipe,
                                const struct pipe_poly_stipple *stipple)
{
    (void)pipe;
    (void)stipple;
}

static void set_inlinable_constants(struct pipe_context *pipe,
                                    enum pipe_shader_type shader,
                                    uint num_values, uint32_t *values)
{
    (void)pipe;
    (void)shader;
    (void)num_values;
    (void)values;
}

static void set_constant_buffer(struct pipe_context *pipe,
                                enum pipe_shader_type shader, unsigned index,
                                bool take_ownership,
                                const struct pipe_constant_buffer *buf)
{
    struct pipe_resource *owned;

    (void)pipe;
    (void)shader;
    (void)index;
    if (!take_ownership || !buf || !buf->buffer)
        return;
    owned = buf->buffer;
    pipe_resource_reference(&owned, NULL);
}

static void *create_sampler_state(struct pipe_context *pipe,
                                  const struct pipe_sampler_state *state)
{
    (void)pipe;
    (void)state;
    return MALLOC(1);
}

static void bind_sampler_states(struct pipe_context *pipe,
                                enum pipe_shader_type shader, unsigned start,
                                unsigned num, void **samplers)
{
    (void)pipe;
    (void)shader;
    (void)start;
    (void)num;
    (void)samplers;
}

static void set_sampler_views(struct pipe_context *pipe,
                              enum pipe_shader_type shader, unsigned start,
                              unsigned num, unsigned unbind, bool take_ownership,
                              struct pipe_sampler_view **views)
{
    unsigned i;

    (void)pipe;
    (void)shader;
    (void)start;
    (void)unbind;
    if (!take_ownership || !views)
        return;
    for (i = 0; i < num; i++)
        pipe_sampler_view_reference(&views[i], NULL);
}

static void flush(struct pipe_context *pipe, struct pipe_fence_handle **fence,
                  unsigned flags)
{
    (void)flags;
    if (fence)
        pipe->screen->fence_reference(pipe->screen, fence, NULL);
}

/* Draws, clears and copies wait on their fence inside the winsys, so the
 * GEM already holds the GPU pixels when Mesa hands it to scanout. */
static void flush_resource(struct pipe_context *pipe,
                           struct pipe_resource *resource)
{
    (void)pipe;
    (void)resource;
}

static void memory_barrier(struct pipe_context *pipe, unsigned flags)
{
    (void)pipe;
    (void)flags;
}

static void context_destroy(struct pipe_context *pipe)
{
    struct opengpu_context *ctx = octx(pipe);
    unsigned i;

    if (ctx->base.stream_uploader)
        u_upload_destroy(ctx->base.stream_uploader);
    ctx->base.stream_uploader = NULL;
    ctx->base.const_uploader = NULL;
    pipe_surface_reference(&ctx->cbuf, NULL);
    pipe_surface_reference(&ctx->zsbuf, NULL);
    for (i = 0; i < OPENGPU_VB_SLOTS; i++) {
        if (!ctx->vb[i].is_user_buffer)
            pipe_resource_reference(&ctx->vb[i].buffer.resource, NULL);
    }
    if (ctx->packed)
        pipe_opengpu_resource_destroy(opengpu_screen_gpu(pipe->screen),
                                      ctx->packed);
    pipe_opengpu_context_destroy(ctx->gpu);
    FREE(ctx);
}

struct pipe_context *opengpu_pipe_context_create(struct pipe_screen *screen,
                                            void *priv, unsigned flags)
{
    struct opengpu_context *ctx;

    (void)flags;
    ctx = CALLOC_STRUCT(opengpu_context);
    if (!ctx)
        return NULL;
    ctx->gpu = pipe_opengpu_context_create(opengpu_screen_gpu(screen));
    if (!ctx->gpu) {
        FREE(ctx);
        return NULL;
    }
    ctx->base.screen = screen;
    ctx->base.priv = priv;
    ctx->base.destroy = context_destroy;
    ctx->base.draw_vbo = draw_vbo;
    ctx->base.clear = clear;
    ctx->base.flush = flush;
    ctx->base.create_blend_state = (void *(*)(struct pipe_context *,
                                              const struct pipe_blend_state *))
        create_blend;
    ctx->base.bind_blend_state = bind_blend;
    ctx->base.delete_blend_state = delete_state;
    ctx->base.create_rasterizer_state = create_rast;
    ctx->base.bind_rasterizer_state = bind_rast;
    ctx->base.delete_rasterizer_state = delete_state;
    ctx->base.create_depth_stencil_alpha_state = create_dsa;
    ctx->base.bind_depth_stencil_alpha_state = bind_dsa;
    ctx->base.delete_depth_stencil_alpha_state = delete_state;
    ctx->base.create_fs_state = create_fs_state;
    ctx->base.bind_fs_state = bind_fs_state;
    ctx->base.delete_fs_state = delete_shader;
    ctx->base.create_vs_state = create_vs_state;
    ctx->base.bind_vs_state = bind_vs_state;
    ctx->base.delete_vs_state = delete_shader;
    ctx->base.create_vertex_elements_state = create_ve;
    ctx->base.bind_vertex_elements_state = bind_ve;
    ctx->base.delete_vertex_elements_state = delete_state;
    ctx->base.set_vertex_buffers = set_vertex_buffers;
    ctx->base.create_surface = create_surface;
    ctx->base.surface_destroy = surface_destroy;
    ctx->base.set_framebuffer_state = set_framebuffer;
    ctx->base.set_viewport_states = set_viewport_states;
    ctx->base.set_scissor_states = set_scissor_states;
    ctx->base.set_blend_color = set_blend_color;
    ctx->base.set_stencil_ref = set_stencil_ref;
    ctx->base.set_sample_mask = set_sample_mask;
    ctx->base.set_clip_state = set_clip_state;
    ctx->base.set_polygon_stipple = set_polygon_stipple;
    ctx->base.set_constant_buffer = set_constant_buffer;
    ctx->base.set_inlinable_constants = set_inlinable_constants;
    ctx->base.create_sampler_state = create_sampler_state;
    ctx->base.bind_sampler_states = bind_sampler_states;
    ctx->base.delete_sampler_state = delete_state;
    ctx->base.set_sampler_views = set_sampler_views;
    ctx->base.buffer_map = buffer_map;
    ctx->base.buffer_unmap = buffer_unmap;
    ctx->base.texture_map = buffer_map;
    ctx->base.texture_unmap = buffer_unmap;
    ctx->base.buffer_subdata = u_default_buffer_subdata;
    ctx->base.texture_subdata = u_default_texture_subdata;
    ctx->base.transfer_flush_region = u_default_transfer_flush_region;
    ctx->base.flush_resource = flush_resource;
    ctx->base.memory_barrier = memory_barrier;
    ctx->base.stream_uploader = u_upload_create_default(&ctx->base);
    ctx->base.const_uploader = ctx->base.stream_uploader;
    ctx->rast.front_ccw = 1;
    return &ctx->base;
}
