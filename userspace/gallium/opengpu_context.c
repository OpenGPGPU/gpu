/* SPDX-License-Identifier: MIT */
/* Gallium context. A draw is a GPU vertex-core submit: the vertex shader and
 * the fragment shader binaries in opengpu_shaders.h run on the shader cores,
 * and the RTL rasterizes the triangle. A TGSI program that is not a colour
 * copy, a mat4 position multiply, a 2D texture sample, or that sample
 * multiplied by an interpolated colour, a fragment vec4, or a replicated
 * float uniform fails the draw. A vec2 sprite position may be padded to
 * (x, y, 0, 1) when it is packed; the vertex core still multiplies it.
 * A fragment vec4 uniform may be copied into that colour interpolant.
 * The fragment core copies it. One extra multiply of a sampled product
 * by CONST[0][0].xxxx, or by the whole CONST[0][0] vec4, runs on the
 * fragment core, as does a multiply of an interpolated colour by that
 * same .xxxx swizzle when there is no sample. Qt's vertex-colour
 * material multiplies the attribute by CONST[0][4].xxxx in the vertex
 * shader. The vertex program has no bytes left for that scale, and the
 * factor is constant, so the same fragment multiply runs it. The CPU
 * only packs the float or the vec4 into the fragment kernarg uniform.
 * The Gallium draw module is not used. */
#include "opengpu_internal.h"
#include "opengpu_fs_emit.h"
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

/* A vertex output the fragment shader can match by semantic. src is the
 * attribute slot; imm means the output is a constant, not an attribute. */
struct opengpu_link {
    int semantic;
    int index;
    int src;
    int imm;
    float value[4];
};

struct opengpu_shader {
    int is_fs;
    int pos_src;
    int color_src;
    int color_sem;
    int color_sem_index;
    int color_imm;
    float color[4];
    int samples;
    int modulate;
    /* The modulate colour is fragment CONST[0][0], packed into the
     * interpolant. The fragment core still does the multiply.
     * color_const_chan is -1 for the whole vec4, or 0..3 when one
     * channel is replicated (a float opacity). */
    int color_const;
    int color_const_chan;
    /* After sample * colour, multiply by CONST[0][0]. scale_const_chan
     * is 0 when that factor is .xxxx, and -1 when it is the whole vec4.
     * The packed word is written to both fragment kernarg banks at byte 288. */
    int scale_const;
    int scale_const_chan;
    int sample_sem;
    int sample_sem_index;
    int mvp;
    /* A second mat4 in CONST[0][4..7]. The vertex core multiplies that
     * block first, then the matrix in CONST[0][0..3]. */
    int mvp2;
    /* OUT colour = IN colour * CONST[0][4].xxxx. The fragment core scales
     * the copied colour; the vertex program only copies it. */
    int vs_opacity;
    struct opengpu_link link[4];
    int nlink;
    /* Straight-line ALU fragment program, when the fixed matchers do not
     * accept it. alu_code runs on the fragment core. */
    int alu;
    uint32_t alu_code[256];
    unsigned alu_words;
    unsigned alu_nconst;
    float alu_imm[8];
    unsigned alu_nimm;
};

/* temp_file value: the temporary holds the vtex.sample result. */
#define TEMP_SAMPLE (-2)
/* temp_file value: the temporary holds sample * colour. */
#define TEMP_MODULATE (-4)
/* temp_file value: the temporary holds mat4 * position. */
#define TEMP_MVP (-3)
/* temp_file value: the temporary holds fragment CONST[0][0]. */
#define TEMP_CONST (-5)

struct opengpu_context {
    struct pipe_context base;
    struct pipe_opengpu_context *gpu;
    struct pipe_surface *cbuf;
    struct pipe_surface *zsbuf;
    uint32_t width;
    uint32_t height;
    struct opengpu_shader *vs;
    struct opengpu_shader *fs;
    struct pipe_sampler_view *fs_view;
    struct pipe_vertex_buffer vb[OPENGPU_VB_SLOTS];
    struct pipe_vertex_element ve[OPENGPU_ATTRIBS];
    unsigned nr_ve;
    struct pipe_rasterizer_state rast;
    struct pipe_depth_stencil_alpha_state dsa;
    struct pipe_blend_state blend;
    struct pipe_scissor_state scissor;
    int scissor_set;
    float mvp[16];
    int mvp_set;
    /* CONST[0][4..7], the matrix applied before mvp. */
    float mvp_b[16];
    int mvp_b_set;
    /* CONST[0][4].x from the vertex constant buffer. */
    float vs_opacity;
    int vs_opacity_set;
    float fs_const[4];
    int fs_const_set;
    float fs_block[32];
    unsigned fs_block_n;
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

/* X and Y must be the coordinate. Z and W are unused by the sampler. */
static int xy_swizzle(const struct tgsi_src_register *src)
{
    return !src->Indirect && !src->Absolute && !src->Negate &&
           src->SwizzleX == TGSI_SWIZZLE_X && src->SwizzleY == TGSI_SWIZZLE_Y;
}

static int add_link(struct opengpu_shader *shader, int semantic, int index,
                    int src, int imm, const float *value)
{
    struct opengpu_link *link;

    if (shader->nlink >= 4)
        return -1;
    link = &shader->link[shader->nlink++];
    link->semantic = semantic;
    link->index = index;
    link->src = src;
    link->imm = imm;
    if (imm && value)
        memcpy(link->value, value, sizeof(link->value));
    return 0;
}

static int plain_swizzle(const struct tgsi_src_register *src)
{
    return !src->Indirect && !src->Absolute && !src->Negate &&
           src->SwizzleX == TGSI_SWIZZLE_X && src->SwizzleY == TGSI_SWIZZLE_Y &&
           src->SwizzleZ == TGSI_SWIZZLE_Z && src->SwizzleW == TGSI_SWIZZLE_W;
}

static int broadcast_swizzle(const struct tgsi_src_register *src,
                             unsigned channel)
{
    return !src->Indirect && !src->Dimension && !src->Absolute && !src->Negate &&
           src->SwizzleX == channel && src->SwizzleY == channel &&
           src->SwizzleZ == channel && src->SwizzleW == channel;
}

/* CONST[0][index], the column Mesa uploads for a column-major mat4. */
static int const_column(const struct tgsi_full_src_register *src, int index)
{
    if (src->Register.File != TGSI_FILE_CONSTANT || src->Register.Indirect ||
        src->Register.Index != index || src->Register.Negate ||
        src->Register.Absolute || !plain_swizzle(&src->Register))
        return 0;
    if (src->Register.Dimension &&
        (src->Dimension.Indirect || src->Dimension.Index != 0))
        return 0;
    return 1;
}

/* CONST[0][index].cccc, one float replicated across the colour. */
static int const_scalar(const struct tgsi_full_src_register *src, int index,
                        unsigned channel)
{
    const struct tgsi_src_register *reg = &src->Register;

    if (reg->File != TGSI_FILE_CONSTANT || reg->Indirect ||
        reg->Index != index || reg->Negate || reg->Absolute ||
        reg->SwizzleX != channel || reg->SwizzleY != channel ||
        reg->SwizzleZ != channel || reg->SwizzleW != channel)
        return 0;
    if (reg->Dimension &&
        (src->Dimension.Indirect || src->Dimension.Index != 0))
        return 0;
    return 1;
}

/* Column of CONST[0][base + column]. base is 0 or 4. src_temp < 0 means
 * the vector is an INPUT; otherwise it is that temporary. The broadcast
 * channel is the column within the mat4, not the constant index. */
static int mvp_column(const struct tgsi_full_src_register *a,
                      const struct tgsi_full_src_register *b,
                      int src_temp, unsigned *src_in, int *block)
{
    const struct tgsi_full_src_register *col = NULL;
    const struct tgsi_full_src_register *in = NULL;
    int index, column;
    unsigned vector_file;

    vector_file = src_temp < 0 ? TGSI_FILE_INPUT : TGSI_FILE_TEMPORARY;
    if (a->Register.File == TGSI_FILE_CONSTANT &&
        b->Register.File == vector_file) {
        col = a;
        in = b;
    } else if (b->Register.File == TGSI_FILE_CONSTANT &&
               a->Register.File == vector_file) {
        col = b;
        in = a;
    } else {
        return -1;
    }
    index = col->Register.Index;
    if (index < 0 || index > 7)
        return -1;
    *block = index & ~3;
    column = index & 3;
    if ((*block != 0 && *block != 4) || !const_column(col, index) ||
        !broadcast_swizzle(&in->Register, (unsigned)column) ||
        in->Register.Index < 0 || in->Register.Index >= (int)OPENGPU_ATTRIBS)
        return -1;
    if (src_temp >= 0 && in->Register.Index != src_temp)
        return -1;
    *src_in = (unsigned)in->Register.Index;
    return column;
}

/* gl_Position = u_mvp * vec4(pos.xy, 0, 1). Mesa drops the zero column and
 * adds column 3, the w=1 term, as a vector. The packed vertex is still
 * (x, y, 0, 1), so the full matrix program computes the same product.
 * One MAD is "column * component + column 3". The other adds that
 * temporary. Returns 0 when this MAD is part of that product. */
static int match_sprite_mad(const struct tgsi_full_instruction *insn,
                            int *sprite_temp, int *sprite_in, int *sprite_xy,
                            int *sprite_w, struct opengpu_shader *shader,
                            const int *sem_out)
{
    const struct tgsi_full_src_register *col, *in, *add;
    const struct tgsi_dst_register *dst = &insn->Dst[0].Register;
    int column, input;

    if (insn->Instruction.Opcode != TGSI_OPCODE_MAD ||
        insn->Instruction.Saturate || insn->Instruction.NumDstRegs != 1 ||
        insn->Instruction.NumSrcRegs != 3 || dst->Indirect ||
        dst->WriteMask != TGSI_WRITEMASK_XYZW)
        return -1;
    add = &insn->Src[2];
    if (insn->Src[0].Register.File == TGSI_FILE_CONSTANT) {
        col = &insn->Src[0];
        in = &insn->Src[1];
    } else if (insn->Src[1].Register.File == TGSI_FILE_CONSTANT) {
        col = &insn->Src[1];
        in = &insn->Src[0];
    } else {
        return -1;
    }
    column = col->Register.Index;
    if ((column != 0 && column != 1) || !const_column(col, column) ||
        in->Register.File != TGSI_FILE_INPUT ||
        !broadcast_swizzle(&in->Register, (unsigned)column))
        return -1;
    input = in->Register.Index;
    if (input < 0 || input >= (int)OPENGPU_ATTRIBS ||
        (*sprite_in >= 0 && *sprite_in != input) ||
        (*sprite_xy & (1 << column)))
        return -1;
    if (add->Register.File == TGSI_FILE_CONSTANT) {
        if (*sprite_w || !const_column(add, 3) ||
            dst->File != TGSI_FILE_TEMPORARY ||
            dst->Index >= OPENGPU_ATTRIBS)
            return -1;
        *sprite_w = 1;
        *sprite_temp = dst->Index;
    } else if (add->Register.File == TGSI_FILE_TEMPORARY) {
        if (*sprite_temp < 0 || add->Register.Index != *sprite_temp ||
            !plain_swizzle(&add->Register))
            return -1;
        if (dst->File == TGSI_FILE_TEMPORARY) {
            if (dst->Index != *sprite_temp)
                return -1;
        } else if (dst->File == TGSI_FILE_OUTPUT) {
            if (dst->Index >= OPENGPU_ATTRIBS ||
                sem_out[dst->Index] != TGSI_SEMANTIC_POSITION ||
                shader->mvp)
                return -1;
            shader->mvp = 1;
            shader->pos_src = input;
        } else {
            return -1;
        }
    } else {
        return -1;
    }
    *sprite_in = input;
    *sprite_xy |= 1 << column;
    return 0;
}

/* gl_Position = u_mvp * pos. Mesa may emit the four columns in any order.
 * The first term is a MUL into a temporary. Each later term is a MAD that
 * adds the same temporary. The last term may write that temporary or
 * OUT.POSITION directly. */
/* gl_Position = u_mvp * pos, or one mat4 of a two-matrix product.
 * base is 0 or 4 once the first column is seen. src_temp < 0 reads an
 * INPUT; otherwise the vector is that temporary (the other matrix's
 * result). pos_attr >= 0 records that attribute as the position source
 * and marks the second matrix. A finished product may stay in *temp. */
static int match_mvp(const struct tgsi_full_instruction *insn, int *used,
                     int *temp, int *input, int *base, int src_temp,
                     int pos_attr, struct opengpu_shader *shader,
                     const int *sem_out)
{
    const struct tgsi_dst_register *dst = &insn->Dst[0].Register;
    unsigned opcode = insn->Instruction.Opcode;
    unsigned src_in = 0;
    int column, block = -1, to_position;

    if (insn->Instruction.Saturate || insn->Instruction.NumDstRegs != 1 ||
        dst->Indirect || dst->WriteMask != TGSI_WRITEMASK_XYZW)
        return -1;
    if (*used == 0) {
        if (opcode != TGSI_OPCODE_MUL || insn->Instruction.NumSrcRegs != 2)
            return -1;
    } else if (*used != 0xf) {
        if (opcode != TGSI_OPCODE_MAD || insn->Instruction.NumSrcRegs != 3 ||
            insn->Src[2].Register.File != TGSI_FILE_TEMPORARY ||
            insn->Src[2].Register.Index != *temp ||
            !plain_swizzle(&insn->Src[2].Register))
            return -1;
    } else {
        return -1;
    }
    column = mvp_column(&insn->Src[0], &insn->Src[1], src_temp, &src_in,
                        &block);
    if (column < 0 || (*used & (1 << column)))
        return -1;
    if (*used == 0) {
        if (*base >= 0 && block != *base)
            return -1;
        if (dst->File != TGSI_FILE_TEMPORARY || dst->Index >= OPENGPU_ATTRIBS)
            return -1;
        *base = block;
        *temp = dst->Index;
        *input = (int)src_in;
    } else if (block != *base || (int)src_in != *input) {
        return -1;
    }
    *used |= 1 << column;
    to_position = dst->File == TGSI_FILE_OUTPUT &&
                  dst->Index < OPENGPU_ATTRIBS &&
                  sem_out[dst->Index] == TGSI_SEMANTIC_POSITION;
    if (*used != 0xf) {
        if (dst->File != TGSI_FILE_TEMPORARY || dst->Index != *temp)
            return -1;
        return 0;
    }
    if (dst->File == TGSI_FILE_TEMPORARY && dst->Index == *temp)
        return 0;
    if (!to_position || shader->pos_src >= 0)
        return -1;
    /* One matrix lives in CONST[0][0..3]. The second matrix of a pair
     * may be either block; the other block was the first product. */
    if (pos_attr < 0 && *base != 0)
        return -1;
    shader->mvp = 1;
    shader->mvp2 = pos_attr >= 0;
    shader->pos_src = pos_attr >= 0 ? pos_attr : *input;
    return 0;
}

/* gl_FragColor = (texture * v_color) * u_opacity, the second MUL only.
 * The first MUL left sample * colour in a temporary. This one writes the
 * colour output and multiplies by CONST[0][0].xxxx. Returns 0 on that
 * program. */
static int match_shade_mul(const struct tgsi_full_instruction *insn,
                           const int *temp_file, struct opengpu_shader *shader,
                           const int *sem_out)
{
    const struct tgsi_full_src_register *temp = &insn->Src[0];
    const struct tgsi_full_src_register *factor = &insn->Src[1];
    int index;

    if (shader->modulate || shader->color_const || shader->scale_const ||
        insn->Instruction.Saturate ||
        insn->Instruction.NumDstRegs != 1 ||
        insn->Instruction.NumSrcRegs != 2 ||
        insn->Dst[0].Register.Indirect ||
        insn->Dst[0].Register.File != TGSI_FILE_OUTPUT ||
        insn->Dst[0].Register.WriteMask != TGSI_WRITEMASK_XYZW)
        return -1;
    index = insn->Dst[0].Register.Index;
    if (index < 0 || index >= (int)OPENGPU_ATTRIBS || sem_out[index] < 0 ||
        !color_semantic((unsigned)sem_out[index]))
        return -1;
    if (temp->Register.File != TGSI_FILE_TEMPORARY ||
        temp->Register.Indirect || !plain_swizzle(&temp->Register) ||
        temp->Register.Index < 0 ||
        temp->Register.Index >= (int)OPENGPU_ATTRIBS ||
        temp_file[temp->Register.Index] != TEMP_MODULATE)
        return -1;
    if (!const_scalar(factor, 0, 0))
        return -1;
    shader->scale_const = 1;
    shader->scale_const_chan = 0;
    shader->modulate = 1;
    return 0;
}

/* gl_FragColor = (texture * v_color) * u_color, the second MUL only.
 * Same shape as match_shade_mul, except the factor is the whole
 * CONST[0][0] rather than .xxxx. Returns 0 on that program. */
static int match_wash_mul(const struct tgsi_full_instruction *insn,
                          const int *temp_file, struct opengpu_shader *shader,
                          const int *sem_out)
{
    const struct tgsi_full_src_register *temp = &insn->Src[0];
    const struct tgsi_full_src_register *factor = &insn->Src[1];
    int index;

    if (shader->modulate || shader->color_const || shader->scale_const ||
        insn->Instruction.Saturate ||
        insn->Instruction.NumDstRegs != 1 ||
        insn->Instruction.NumSrcRegs != 2 ||
        insn->Dst[0].Register.Indirect ||
        insn->Dst[0].Register.File != TGSI_FILE_OUTPUT ||
        insn->Dst[0].Register.WriteMask != TGSI_WRITEMASK_XYZW)
        return -1;
    index = insn->Dst[0].Register.Index;
    if (index < 0 || index >= (int)OPENGPU_ATTRIBS || sem_out[index] < 0 ||
        !color_semantic((unsigned)sem_out[index]))
        return -1;
    if (temp->Register.File != TGSI_FILE_TEMPORARY ||
        temp->Register.Indirect || !plain_swizzle(&temp->Register) ||
        temp->Register.Index < 0 ||
        temp->Register.Index >= (int)OPENGPU_ATTRIBS ||
        temp_file[temp->Register.Index] != TEMP_MODULATE)
        return -1;
    if (!const_column(factor, 0))
        return -1;
    shader->scale_const = 1;
    shader->scale_const_chan = -1;
    shader->modulate = 1;
    return 0;
}

/* OUT colour = IN colour * CONST[0][4].xxxx, once the mat4 has been
 * matched. Returns 0 on that instruction. The caller only tries this
 * after the matrix is complete, so a matrix column cannot land here. */
static int match_vs_opacity(const struct tgsi_full_instruction *insn,
                            struct opengpu_shader *shader,
                            const int *sem_in, const int *sem_out,
                            const int *sem_out_index)
{
    const struct tgsi_dst_register *dst = &insn->Dst[0].Register;
    const struct tgsi_src_register *in;
    int out_sem;

    if (shader->vs_opacity || insn->Instruction.Opcode != TGSI_OPCODE_MUL ||
        insn->Instruction.Saturate || insn->Instruction.NumDstRegs != 1 ||
        insn->Instruction.NumSrcRegs != 2 || dst->Indirect ||
        dst->WriteMask != TGSI_WRITEMASK_XYZW ||
        dst->File != TGSI_FILE_OUTPUT || dst->Index >= OPENGPU_ATTRIBS)
        return -1;
    out_sem = sem_out[dst->Index];
    if (out_sem < 0 || !color_semantic((unsigned)out_sem))
        return -1;
    in = &insn->Src[0].Register;
    if (in->File != TGSI_FILE_INPUT || in->Indirect || !plain_swizzle(in) ||
        in->Index < 0 || in->Index >= (int)OPENGPU_ATTRIBS ||
        sem_in[in->Index] < 0 ||
        !color_semantic((unsigned)sem_in[in->Index]))
        return -1;
    if (!const_scalar(&insn->Src[1], 4, 0))
        return -1;
    if (add_link(shader, out_sem, sem_out_index[dst->Index], (int)in->Index,
                 0, NULL))
        return -1;
    shader->vs_opacity = 1;
    return 0;
}

/* gl_FragColor = v_color * u_opacity, with no texture. The colour is the
 * interpolated varying and the factor is CONST[0][0].xxxx. Returns 0 on
 * that program. */
static int match_fade_mul(const struct tgsi_full_instruction *insn,
                          struct opengpu_shader *shader, const int *sem_in,
                          const int *sem_in_index, const int *sem_out)
{
    const struct tgsi_full_src_register *color = &insn->Src[0];
    const struct tgsi_full_src_register *factor = &insn->Src[1];
    int index, src;

    if (shader->samples || shader->modulate || shader->scale_const ||
        shader->color_src >= 0 || shader->color_const || shader->color_imm ||
        insn->Instruction.Saturate ||
        insn->Instruction.NumDstRegs != 1 ||
        insn->Instruction.NumSrcRegs != 2 ||
        insn->Dst[0].Register.Indirect ||
        insn->Dst[0].Register.File != TGSI_FILE_OUTPUT ||
        insn->Dst[0].Register.WriteMask != TGSI_WRITEMASK_XYZW)
        return -1;
    index = insn->Dst[0].Register.Index;
    if (index < 0 || index >= (int)OPENGPU_ATTRIBS || sem_out[index] < 0 ||
        !color_semantic((unsigned)sem_out[index]))
        return -1;
    if (color->Register.File != TGSI_FILE_INPUT ||
        color->Register.Indirect || !plain_swizzle(&color->Register))
        return -1;
    src = color->Register.Index;
    if (src < 0 || src >= (int)OPENGPU_ATTRIBS || sem_in[src] < 0 ||
        !color_semantic((unsigned)sem_in[src]))
        return -1;
    if (!const_scalar(factor, 0, 0))
        return -1;
    shader->color_src = src;
    shader->color_sem = sem_in[src];
    shader->color_sem_index = sem_in_index[src];
    shader->scale_const = 1;
    shader->scale_const_chan = 0;
    return 0;
}

/* gl_FragColor = u_color. The uniform is packed into the colour interpolant
 * and the fragment core copies it. Returns 0 when this MOV is that copy.
 * A temporary may hold the uniform first. */
static int match_fill_mov(const struct tgsi_full_instruction *insn,
                          int *temp_file, int *temp_index,
                          struct opengpu_shader *shader, const int *sem_out)
{
    const struct tgsi_dst_register *dst = &insn->Dst[0].Register;
    const struct tgsi_src_register *src = &insn->Src[0].Register;
    int from_const;

    if (insn->Instruction.Opcode != TGSI_OPCODE_MOV ||
        insn->Instruction.Saturate || insn->Instruction.NumDstRegs != 1 ||
        insn->Instruction.NumSrcRegs != 1 || dst->Indirect ||
        dst->WriteMask != TGSI_WRITEMASK_XYZW)
        return -1;
    from_const = const_column(&insn->Src[0], 0);
    if (!from_const) {
        if (src->File != TGSI_FILE_TEMPORARY || src->Indirect ||
            src->Index < 0 || src->Index >= (int)OPENGPU_ATTRIBS ||
            temp_file[src->Index] != TEMP_CONST || !plain_swizzle(src))
            return -1;
    }
    if (dst->File == TGSI_FILE_TEMPORARY) {
        if (!from_const || dst->Index >= OPENGPU_ATTRIBS)
            return -1;
        temp_file[dst->Index] = TEMP_CONST;
        temp_index[dst->Index] = 0;
        return 0;
    }
    if (dst->File != TGSI_FILE_OUTPUT || dst->Index >= OPENGPU_ATTRIBS ||
        sem_out[dst->Index] < 0 ||
        !color_semantic((unsigned)sem_out[dst->Index]) ||
        shader->color_const || shader->color_src >= 0 || shader->color_imm ||
        shader->samples || shader->modulate)
        return -1;
    shader->color_const = 1;
    shader->color_const_chan = -1;
    return 0;
}

static struct opengpu_shader *lower_fragment_alu(
    const struct pipe_shader_state *state)
{
    struct opengpu_fs_alu alu;
    struct opengpu_shader *shader;

    if (!state || state->type != PIPE_SHADER_IR_TGSI || !state->tokens)
        return NULL;
    if (opengpu_fs_emit(state->tokens, &alu))
        return NULL;
    shader = CALLOC_STRUCT(opengpu_shader);
    if (!shader)
        return NULL;
    shader->is_fs = 1;
    shader->alu = 1;
    shader->color_src = -1;
    shader->color_sem = -1;
    shader->sample_sem = -1;
    shader->pos_src = -1;
    shader->alu_words = alu.words;
    shader->alu_nconst = alu.nconst;
    shader->alu_nimm = alu.nimm;
    memcpy(shader->alu_code, alu.code, alu.words * sizeof(uint32_t));
    memcpy(shader->alu_imm, alu.imm, alu.nimm * sizeof(float));
    return shader;
}

/* Record which input feeds gl_Position and which feeds the colour. Anything
 * other than MOV/END, a mat4 multiply, or a write the fixed vertex format
 * cannot carry, fails. */
static struct opengpu_shader *lower_shader(const struct pipe_shader_state *state,
                                           int want_fs)
{
    struct tgsi_parse_context parse;
    struct opengpu_shader *shader;
    int sem_in[OPENGPU_ATTRIBS];
    int sem_out[OPENGPU_ATTRIBS];
    int sem_in_index[OPENGPU_ATTRIBS];
    int sem_out_index[OPENGPU_ATTRIBS];
    int temp_file[OPENGPU_ATTRIBS];
    int temp_index[OPENGPU_ATTRIBS];
    float imm[4];
    int nimm = 0, saw_end = 0, i;
    int mvp_used = 0, mvp_temp = -1, mvp_in = -1, mvp_base = -1;
    int chain_temp = -1, chain_attr = -1;
    int saw_modulate = 0;
    int sprite_temp = -1, sprite_in = -1, sprite_xy = 0, sprite_w = 0;

    if (!state || state->type != PIPE_SHADER_IR_TGSI || !state->tokens)
        return NULL;
    if (tgsi_get_processor_type(state->tokens) !=
        (want_fs ? PIPE_SHADER_FRAGMENT : PIPE_SHADER_VERTEX))
        return NULL;
    if (tgsi_parse_init(&parse, state->tokens) != TGSI_PARSE_OK)
        return NULL;

    for (i = 0; i < (int)OPENGPU_ATTRIBS; i++) {
        sem_in[i] = sem_out[i] = -1;
        sem_in_index[i] = sem_out_index[i] = 0;
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
    shader->color_sem = -1;
    shader->sample_sem = -1;
    shader->color_const_chan = -1;

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
                file == TGSI_FILE_ADDRESS || file == TGSI_FILE_NULL ||
                file == TGSI_FILE_SAMPLER || file == TGSI_FILE_SAMPLER_VIEW)
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
            if (file == TGSI_FILE_INPUT) {
                sem_in[index] = (int)sem;
                sem_in_index[index] = (int)decl->Semantic.Index;
            } else {
                sem_out[index] = (int)sem;
                sem_out_index[index] = (int)decl->Semantic.Index;
            }
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
            if (!want_fs &&
                (insn->Instruction.Opcode == TGSI_OPCODE_MUL ||
                 insn->Instruction.Opcode == TGSI_OPCODE_MAD)) {
                if (shader->mvp && mvp_used == 0xf &&
                    !match_vs_opacity(insn, shader, sem_in, sem_out,
                                      sem_out_index))
                    break;
                if (!match_mvp(insn, &mvp_used, &mvp_temp, &mvp_in, &mvp_base,
                               chain_temp, chain_attr, shader, sem_out)) {
                    temp_file[mvp_temp] = TEMP_MVP;
                    temp_index[mvp_temp] = mvp_in;
                    /* The first mat4 finished in a temporary. The next
                     * one multiplies that result and uses the other
                     * constant block. */
                    if (!shader->mvp && mvp_used == 0xf && chain_temp < 0) {
                        chain_attr = mvp_in;
                        chain_temp = mvp_temp;
                        mvp_base = mvp_base == 0 ? 4 : 0;
                        mvp_used = 0;
                        mvp_temp = -1;
                        mvp_in = -1;
                    }
                    break;
                }
                if (!match_sprite_mad(insn, &sprite_temp, &sprite_in,
                                      &sprite_xy, &sprite_w, shader, sem_out))
                    break;
                goto fail;
            }
            if (insn->Instruction.Opcode == TGSI_OPCODE_TEX) {
                unsigned src_file, src;
                int src_index;

                if (!want_fs || shader->samples || insn->Instruction.Saturate ||
                    insn->Instruction.NumDstRegs != 1 ||
                    insn->Instruction.NumSrcRegs < 1 ||
                    insn->Texture.Texture != TGSI_TEXTURE_2D)
                    goto fail;
                if (insn->Instruction.NumSrcRegs >= 2 &&
                    (insn->Src[1].Register.File != TGSI_FILE_SAMPLER ||
                     insn->Src[1].Register.Index != 0))
                    goto fail;
                if (insn->Dst[0].Register.WriteMask != TGSI_WRITEMASK_XYZW ||
                    insn->Dst[0].Register.Indirect || !xy_swizzle(&insn->Src[0].Register))
                    goto fail;
                src_file = insn->Src[0].Register.File;
                src_index = insn->Src[0].Register.Index;
                if (src_file == TGSI_FILE_TEMPORARY) {
                    if (src_index < 0 || src_index >= (int)OPENGPU_ATTRIBS ||
                        temp_file[src_index] < 0)
                        goto fail;
                    src_file = (unsigned)temp_file[src_index];
                    src_index = temp_index[src_index];
                }
                if (src_file != TGSI_FILE_INPUT)
                    goto fail;
                src = (unsigned)src_index;
                if (src >= OPENGPU_ATTRIBS || sem_in[src] < 0)
                    goto fail;
                index = (unsigned)insn->Dst[0].Register.Index;
                if (index >= OPENGPU_ATTRIBS)
                    goto fail;
                if (insn->Dst[0].Register.File == TGSI_FILE_TEMPORARY) {
                    temp_file[index] = TEMP_SAMPLE;
                    temp_index[index] = 0;
                } else if (insn->Dst[0].Register.File != TGSI_FILE_OUTPUT ||
                           sem_out[index] < 0 ||
                           !color_semantic((unsigned)sem_out[index])) {
                    goto fail;
                }
                shader->samples = 1;
                shader->sample_sem = sem_in[src];
                shader->sample_sem_index = sem_in_index[src];
                break;
            }
            /* gl_FragColor = texture2D(tex, uv) * colour. One source is the
             * sample temporary. The other is a colour varying, CONST[0][0],
             * or one channel of that constant replicated across RGBA. */
            if (want_fs && insn->Instruction.Opcode == TGSI_OPCODE_MUL) {
                int sample_src = -1, color_src = -1, color_const = 0, which;
                int const_chan = -1;

                if (saw_modulate && !shader->modulate) {
                    if (!match_shade_mul(insn, temp_file, shader, sem_out) ||
                        !match_wash_mul(insn, temp_file, shader, sem_out))
                        break;
                    goto fail;
                }
                if (!match_fade_mul(insn, shader, sem_in, sem_in_index, sem_out))
                    break;
                if (shader->modulate || saw_modulate ||
                    insn->Instruction.Saturate ||
                    insn->Instruction.NumDstRegs != 1 ||
                    insn->Instruction.NumSrcRegs != 2 ||
                    insn->Dst[0].Register.Indirect ||
                    insn->Dst[0].Register.WriteMask != TGSI_WRITEMASK_XYZW)
                    goto fail;
                for (which = 0; which < 2; which++) {
                    unsigned src_file = insn->Src[which].Register.File;
                    int src_index = insn->Src[which].Register.Index;
                    unsigned chan;

                    if (src_file == TGSI_FILE_CONSTANT) {
                        if (color_const || color_src >= 0)
                            goto fail;
                        if (const_column(&insn->Src[which], 0)) {
                            color_const = 1;
                            const_chan = -1;
                            continue;
                        }
                        for (chan = 0; chan < 4; chan++) {
                            if (const_scalar(&insn->Src[which], 0, chan)) {
                                color_const = 1;
                                const_chan = (int)chan;
                                break;
                            }
                        }
                        if (!color_const)
                            goto fail;
                        continue;
                    }
                    if (!plain_swizzle(&insn->Src[which].Register))
                        goto fail;
                    if (src_file == TGSI_FILE_TEMPORARY) {
                        if (src_index < 0 || src_index >= (int)OPENGPU_ATTRIBS)
                            goto fail;
                        if (temp_file[src_index] == TEMP_SAMPLE) {
                            if (sample_src >= 0)
                                goto fail;
                            sample_src = src_index;
                            continue;
                        }
                        if (temp_file[src_index] < 0)
                            goto fail;
                        src_file = (unsigned)temp_file[src_index];
                        src_index = temp_index[src_index];
                    }
                    if (src_file != TGSI_FILE_INPUT || src_index < 0 ||
                        src_index >= (int)OPENGPU_ATTRIBS ||
                        sem_in[src_index] < 0 ||
                        !color_semantic((unsigned)sem_in[src_index]))
                        goto fail;
                    if (color_src >= 0 || color_const)
                        goto fail;
                    color_src = src_index;
                }
                if (sample_src < 0 || (color_src < 0 && !color_const))
                    goto fail;
                index = (unsigned)insn->Dst[0].Register.Index;
                if (index >= OPENGPU_ATTRIBS)
                    goto fail;
                if (color_const) {
                    shader->color_const = 1;
                    shader->color_const_chan = const_chan;
                } else {
                    shader->color_sem = sem_in[color_src];
                    shader->color_sem_index = sem_in_index[color_src];
                }
                saw_modulate = 1;
                if (insn->Dst[0].Register.File == TGSI_FILE_TEMPORARY) {
                    temp_file[index] = TEMP_MODULATE;
                    temp_index[index] = color_src;
                    break;
                }
                if (insn->Dst[0].Register.File != TGSI_FILE_OUTPUT ||
                    sem_out[index] < 0 ||
                    !color_semantic((unsigned)sem_out[index]))
                    goto fail;
                shader->modulate = 1;
                break;
            }
            if (want_fs && insn->Instruction.Opcode == TGSI_OPCODE_MOV &&
                insn->Instruction.NumSrcRegs == 1 &&
                (insn->Src[0].Register.File == TGSI_FILE_CONSTANT ||
                 (insn->Src[0].Register.File == TGSI_FILE_TEMPORARY &&
                  insn->Src[0].Register.Index >= 0 &&
                  insn->Src[0].Register.Index < (int)OPENGPU_ATTRIBS &&
                  temp_file[insn->Src[0].Register.Index] == TEMP_CONST))) {
                if (match_fill_mov(insn, temp_file, temp_index, shader, sem_out))
                    goto fail;
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
            /* A vec2 varying is MOV OUT.xy, IN.xyxx. X and Y still come from
             * the attribute; the rasterizer only interpolates those two. */
            if (insn->Dst[0].Register.WriteMask != TGSI_WRITEMASK_XYZW ||
                !identity_swizzle(&insn->Src[0].Register)) {
                int sem = -1;

                if (insn->Dst[0].Register.File == TGSI_FILE_OUTPUT &&
                    insn->Dst[0].Register.Index < OPENGPU_ATTRIBS)
                    sem = sem_out[insn->Dst[0].Register.Index];
                if (sem < 0 || sem == TGSI_SEMANTIC_POSITION ||
                    (insn->Dst[0].Register.WriteMask & TGSI_WRITEMASK_XY) !=
                        TGSI_WRITEMASK_XY ||
                    !xy_swizzle(&insn->Src[0].Register))
                    goto fail;
            }
            index = (unsigned)insn->Dst[0].Register.Index;
            if (index >= OPENGPU_ATTRIBS)
                goto fail;
            if (insn->Dst[0].Register.File == TGSI_FILE_TEMPORARY) {
                unsigned src_file = insn->Src[0].Register.File;
                int src_index = insn->Src[0].Register.Index;

                if (src_file == TGSI_FILE_TEMPORARY) {
                    if (src_index < 0 || src_index >= (int)OPENGPU_ATTRIBS)
                        goto fail;
                    if (temp_file[src_index] == TEMP_SAMPLE) {
                        temp_file[index] = TEMP_SAMPLE;
                        break;
                    }
                    if (temp_file[src_index] < 0)
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
                    if (src_index < 0 || src_index >= (int)OPENGPU_ATTRIBS)
                        goto fail;
                    if (temp_file[src_index] == TEMP_MVP) {
                        if (want_fs || sem != TGSI_SEMANTIC_POSITION ||
                            shader->mvp || mvp_used != 0xf)
                            goto fail;
                        shader->mvp = 1;
                        shader->pos_src = mvp_in;
                        break;
                    }
                    if (temp_file[src_index] == TEMP_SAMPLE) {
                        if (!want_fs || !color_semantic(sem))
                            goto fail;
                        break;
                    }
                    if (temp_file[src_index] == TEMP_MODULATE) {
                        if (!want_fs || !color_semantic(sem))
                            goto fail;
                        shader->modulate = 1;
                        break;
                    }
                    if (temp_file[src_index] < 0)
                        goto fail;
                    src_file = (unsigned)temp_file[src_index];
                    src_index = temp_index[src_index];
                }
                if (src_file == TGSI_FILE_IMMEDIATE) {
                    if (!nimm || src_index != 0)
                        goto fail;
                    if (!want_fs) {
                        if (add_link(shader, (int)sem, sem_out_index[index],
                                     -1, 1, imm))
                            goto fail;
                    } else {
                        if (!color_semantic(sem))
                            goto fail;
                        shader->color_imm = 1;
                        memcpy(shader->color, imm, sizeof(shader->color));
                    }
                } else if (src_file == TGSI_FILE_INPUT) {
                    unsigned src = (unsigned)src_index;

                    if (src >= OPENGPU_ATTRIBS || sem_in[src] < 0)
                        goto fail;
                    if (!want_fs && sem == TGSI_SEMANTIC_POSITION) {
                        if (shader->pos_src >= 0)
                            goto fail;
                        shader->pos_src = (int)src;
                    } else if (!want_fs) {
                        if (add_link(shader, (int)sem, sem_out_index[index],
                                     (int)src, 0, NULL))
                            goto fail;
                    } else if (color_semantic(sem)) {
                        if (shader->color_src >= 0 || shader->color_imm)
                            goto fail;
                        shader->color_src = (int)src;
                        shader->color_sem = sem_in[src];
                        shader->color_sem_index = sem_in_index[src];
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
    if (!saw_end ||
        (want_fs && !shader->samples && shader->color_src < 0 &&
         !shader->color_imm && !shader->color_const) ||
        (want_fs && shader->samples && shader->sample_sem < 0) ||
        (!want_fs && shader->pos_src < 0) ||
        (mvp_used != 0 && !shader->mvp) ||
        (saw_modulate && !shader->modulate) ||
        ((sprite_xy || sprite_w) &&
         (!shader->mvp || sprite_xy != 0x3 || !sprite_w))) {
        FREE(shader);
        return want_fs ? lower_fragment_alu(state) : NULL;
    }
    return shader;
fail:
    tgsi_parse_free(&parse);
    FREE(shader);
    return want_fs ? lower_fragment_alu(state) : NULL;
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
    if (!hw)
        return;
    if (ctx->fs->alu) {
        if (pipe_opengpu_bind_fs(ctx->gpu, ctx->fs->alu_code,
                                 ctx->fs->alu_words * sizeof(uint32_t)))
            reject("fragment shader bind failed");
        return;
    }
    if (ctx->fs->scale_const && ctx->fs->modulate &&
        ctx->fs->scale_const_chan < 0) {
        if (pipe_opengpu_bind_fs(ctx->gpu, opengpu_fragment_wash,
                                 OPENGPU_FRAGMENT_WASH_BYTES))
            reject("fragment shader bind failed");
    } else if (ctx->fs->scale_const && ctx->fs->modulate) {
        if (pipe_opengpu_bind_fs(ctx->gpu, opengpu_fragment_shade,
                                 OPENGPU_FRAGMENT_SHADE_BYTES))
            reject("fragment shader bind failed");
    } else if (ctx->fs->modulate) {
        if (pipe_opengpu_bind_fs(ctx->gpu, opengpu_fragment_modulate,
                                 OPENGPU_FRAGMENT_MODULATE_BYTES))
            reject("fragment shader bind failed");
    } else if (ctx->fs->scale_const) {
        if (pipe_opengpu_bind_fs(ctx->gpu, opengpu_fragment_fade,
                                 OPENGPU_FRAGMENT_FADE_BYTES))
            reject("fragment shader bind failed");
    } else if (ctx->fs->samples) {
        if (pipe_opengpu_bind_fs(ctx->gpu, opengpu_fragment_sample,
                                 OPENGPU_FRAGMENT_SAMPLE_BYTES))
            reject("fragment shader bind failed");
    } else if (ctx->vs && ctx->vs->vs_opacity && ctx->fs->color_src >= 0 &&
               !ctx->fs->color_const && !ctx->fs->color_imm) {
        if (pipe_opengpu_bind_fs(ctx->gpu, opengpu_fragment_fade,
                                 OPENGPU_FRAGMENT_FADE_BYTES))
            reject("fragment shader bind failed");
    } else if (pipe_opengpu_bind_fs(ctx->gpu, opengpu_fragment_color,
                                    OPENGPU_FRAGMENT_COLOR_BYTES)) {
        reject("fragment shader bind failed");
    }
}

static void bind_vs_state(struct pipe_context *pipe, void *hw)
{
    struct opengpu_context *ctx = octx(pipe);

    ctx->vs = hw;
    if (!hw)
        return;
    if (ctx->vs->mvp2) {
        if (pipe_opengpu_bind_vs(ctx->gpu, opengpu_vertex_mvp2,
                                 OPENGPU_VERTEX_MVP2_BYTES))
            reject("vertex shader bind failed");
    } else if (ctx->vs->mvp) {
        if (pipe_opengpu_bind_vs(ctx->gpu, opengpu_vertex_mvp,
                                 OPENGPU_VERTEX_MVP_BYTES))
            reject("vertex shader bind failed");
    } else if (pipe_opengpu_bind_vs(ctx->gpu, opengpu_vertex_passthrough,
                                    OPENGPU_VERTEX_PASSTHROUGH_BYTES)) {
        reject("vertex shader bind failed");
    }
    /* The fragment shader may already have been bound as a colour copy.
     * This multiply is only visible once the vertex shader is bound. */
    if (ctx->vs->vs_opacity && ctx->fs && ctx->fs->color_src >= 0 &&
        !ctx->fs->alu &&
        !ctx->fs->samples && !ctx->fs->modulate && !ctx->fs->scale_const &&
        !ctx->fs->color_const && !ctx->fs->color_imm) {
        if (pipe_opengpu_bind_fs(ctx->gpu, opengpu_fragment_fade,
                                 OPENGPU_FRAGMENT_FADE_BYTES))
            reject("fragment shader bind failed");
    }
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
    struct opengpu_context *ctx = octx(pipe);

    if (hw)
        ctx->blend = *(const struct pipe_blend_state *)hw;
    else
        memset(&ctx->blend, 0, sizeof(ctx->blend));
}

/* Gallium's factor enum is not GL order. The RTL word is GL order, 0..10. */
static int hw_blend_factor(unsigned factor, unsigned *out)
{
    switch (factor) {
    case PIPE_BLENDFACTOR_ZERO:
        *out = 0;
        return 0;
    case PIPE_BLENDFACTOR_ONE:
        *out = 1;
        return 0;
    case PIPE_BLENDFACTOR_SRC_COLOR:
        *out = 2;
        return 0;
    case PIPE_BLENDFACTOR_INV_SRC_COLOR:
        *out = 3;
        return 0;
    case PIPE_BLENDFACTOR_SRC_ALPHA:
        *out = 4;
        return 0;
    case PIPE_BLENDFACTOR_INV_SRC_ALPHA:
        *out = 5;
        return 0;
    case PIPE_BLENDFACTOR_DST_COLOR:
        *out = 6;
        return 0;
    case PIPE_BLENDFACTOR_INV_DST_COLOR:
        *out = 7;
        return 0;
    case PIPE_BLENDFACTOR_DST_ALPHA:
        *out = 8;
        return 0;
    case PIPE_BLENDFACTOR_INV_DST_ALPHA:
        *out = 9;
        return 0;
    case PIPE_BLENDFACTOR_SRC_ALPHA_SATURATE:
        *out = 10;
        return 0;
    default:
        return -1;
    }
}

static int hw_blend(const struct pipe_blend_state *blend, uint32_t *config)
{
    const struct pipe_rt_blend_state *rt = &blend->rt[0];
    unsigned src, dst;

    *config = 0;
    if (!rt->blend_enable)
        return 0;
    if (blend->independent_blend_enable || blend->logicop_enable ||
        blend->alpha_to_coverage || blend->alpha_to_one ||
        blend->advanced_blend_func || rt->colormask != PIPE_MASK_RGBA ||
        rt->rgb_func != rt->alpha_func ||
        rt->rgb_src_factor != rt->alpha_src_factor ||
        rt->rgb_dst_factor != rt->alpha_dst_factor ||
        rt->rgb_func > PIPE_BLEND_MAX)
        return -1;
    if (hw_blend_factor(rt->rgb_src_factor, &src) ||
        hw_blend_factor(rt->rgb_dst_factor, &dst))
        return -1;
    *config = OPENGPU_DRAW_BLEND_PRESENT |
              (src << OPENGPU_DRAW_BLEND_SRC_SHIFT) |
              (dst << OPENGPU_DRAW_BLEND_DST_SHIFT) |
              (rt->rgb_func << OPENGPU_DRAW_BLEND_EQ_SHIFT);
    return 0;
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

static const struct opengpu_link *find_link(const struct opengpu_shader *vs,
                                            int semantic, int index)
{
    int i;

    for (i = 0; i < vs->nlink; i++) {
        if (vs->link[i].semantic == semantic && vs->link[i].index == index)
            return &vs->link[i];
    }
    return NULL;
}

static int pack_one(struct opengpu_context *ctx, unsigned vertex,
                    const struct opengpu_shader *vs,
                    const struct opengpu_shader *fs, struct hw_vertex *out)
{
    const struct pipe_vertex_element *ve;
    const struct opengpu_link *link = NULL;
    const uint8_t *ptr;
    unsigned comps = 0;
    float p[4] = { 0, 0, 0, 1 };
    float c[4] = { 1, 1, 1, 1 };
    float uv[2] = { 0, 0 };

    ptr = attrib_ptr(ctx, (unsigned)vs->pos_src, vertex, &comps);
    if (!ptr)
        return -1;
    ve = &ctx->ve[vs->pos_src];
    if (ve->src_format == PIPE_FORMAT_R8G8B8A8_UNORM)
        return -1;
    memcpy(p, ptr, sizeof(float) * (comps < 4 ? comps : 4));
    if (fs->samples)
        link = find_link(vs, fs->sample_sem, fs->sample_sem_index);
    else if (!fs->color_imm && fs->color_sem >= 0)
        link = find_link(vs, fs->color_sem, fs->color_sem_index);
    if (fs->color_imm)
        memcpy(c, fs->color, sizeof(c));
    else if (link && link->imm)
        memcpy(c, link->value, sizeof(c));
    else if (!fs->samples && link && link->src >= 0) {
        unsigned cc = 0;
        const uint8_t *cp = attrib_ptr(ctx, (unsigned)link->src, vertex, &cc);

        if (!cp || load_color(cp, ctx->ve[link->src].src_format, cc, c))
            return -1;
    } else if (!fs->samples && !fs->color_imm && !fs->color_const && !fs->alu) {
        return -1;
    }
    if (fs->color_const && !fs->modulate) {
        if (!ctx->fs_const_set)
            return -1;
        if (fs->color_const_chan >= 0) {
            float s = ctx->fs_const[fs->color_const_chan];

            c[0] = c[1] = c[2] = c[3] = s;
        } else {
            memcpy(c, ctx->fs_const, sizeof(c));
        }
    }
    if (fs->samples) {
        unsigned uc = 0;
        const uint8_t *up;

        if (!link || link->src < 0 || link->imm)
            return -1;
        up = attrib_ptr(ctx, (unsigned)link->src, vertex, &uc);
        if (!up || ctx->ve[link->src].src_format == PIPE_FORMAT_R8G8B8A8_UNORM ||
            uc < 2)
            return -1;
        memcpy(uv, up, sizeof(uv));
    }
    if (fs->modulate) {
        if (fs->color_const) {
            if (!ctx->fs_const_set)
                return -1;
            if (fs->color_const_chan >= 0) {
                float s = ctx->fs_const[fs->color_const_chan];

                c[0] = c[1] = c[2] = c[3] = s;
            } else {
                memcpy(c, ctx->fs_const, sizeof(c));
            }
        } else {
            const struct opengpu_link *clink =
                find_link(vs, fs->color_sem, fs->color_sem_index);
            unsigned cc = 0;
            const uint8_t *cp;

            if (!clink || clink->imm || clink->src < 0)
                return -1;
            cp = attrib_ptr(ctx, (unsigned)clink->src, vertex, &cc);
            if (!cp || load_color(cp, ctx->ve[clink->src].src_format, cc, c))
                return -1;
        }
    }
    if (vs->mvp) {
        /* The vertex core multiplies these floats. Q16.16 is its output. */
        memcpy(&out->x, &p[0], sizeof(out->x));
        memcpy(&out->y, &p[1], sizeof(out->y));
        memcpy(&out->z, &p[2], sizeof(out->z));
        memcpy(&out->w, &p[3], sizeof(out->w));
    } else {
        out->x = q16(p[0]);
        out->y = q16(p[1]);
        out->z = q16(p[2]);
        out->w = q16(p[3]);
    }
    out->color = pack_color(c);
    out->depth = 0x10;
    out->u = (uint32_t)q16(uv[0]);
    out->v = (uint32_t)q16(uv[1]);
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
    if (ctx->dsa.alpha_enabled) {
        reject("alpha test is not lowered onto the GPU yet");
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
        if (ctx->vs->mvp2) {
            if (!ctx->mvp_set || !ctx->mvp_b_set ||
                pipe_opengpu_write_vs_matrices(ctx->gpu, ctx->mvp,
                                               ctx->mvp_b)) {
                reject("vertex matrices did not reach the vertex core");
                return;
            }
            fprintf(stderr,
                    "opengpu: mvp m00=%g m03=%g m33=%g b00=%g b03=%g\n",
                    ctx->mvp[0], ctx->mvp[12], ctx->mvp[15],
                    ctx->mvp_b[0], ctx->mvp_b[12]);
        } else if (ctx->vs->mvp) {
            if (!ctx->mvp_set ||
                pipe_opengpu_write_vs_matrix(ctx->gpu, ctx->mvp)) {
                reject("vertex matrix did not reach the vertex core");
                return;
            }
            fprintf(stderr, "opengpu: mvp m00=%g m03=%g m33=%g\n",
                    ctx->mvp[0], ctx->mvp[12], ctx->mvp[15]);
        }
        if (ctx->vs->vs_opacity) {
            float s;
            uint32_t byte, word;
            unsigned chan;

            if (!ctx->vs_opacity_set) {
                reject("vertex opacity did not reach the fragment core");
                return;
            }
            s = ctx->vs_opacity;
            if (s < 0.0f)
                s = 0.0f;
            if (s > 1.0f)
                s = 1.0f;
            byte = (uint32_t)(s * 255.0f + 0.5f);
            word = 0;
            for (chan = 0; chan < 4; chan++)
                word |= byte << (8 * (3 - chan));
            if (pipe_opengpu_write_fs_uniform(ctx->gpu, word)) {
                reject("vertex opacity did not reach the fragment core");
                return;
            }
            fprintf(stderr, "opengpu: vertex opacity 0x%08x\n", word);
        }
        if (ctx->fs->scale_const) {
            float s;
            uint32_t byte, word;
            unsigned chan;

            if (!ctx->fs_const_set || ctx->fs->scale_const_chan > 0) {
                reject("fragment opacity did not reach the fragment core");
                return;
            }
            word = 0;
            for (chan = 0; chan < 4; chan++) {
                if (ctx->fs->scale_const_chan == 0)
                    s = ctx->fs_const[0];
                else
                    s = ctx->fs_const[chan];
                if (s < 0.0f)
                    s = 0.0f;
                if (s > 1.0f)
                    s = 1.0f;
                byte = (uint32_t)(s * 255.0f + 0.5f);
                word |= byte << (8 * (3 - chan));
            }
            if (pipe_opengpu_write_fs_uniform(ctx->gpu, word)) {
                reject("fragment opacity did not reach the fragment core");
                return;
            }
            fprintf(stderr, "opengpu: shade 0x%08x\n", word);
        }
        if (ctx->fs->alu) {
            float block[40];
            unsigned nconst = ctx->fs->alu_nconst * 4u;
            unsigned n = nconst + ctx->fs->alu_nimm;

            if (!n || nconst > ctx->fs_block_n || n > 40) {
                reject("fragment constants did not reach the fragment core");
                return;
            }
            memcpy(block, ctx->fs_block, nconst * sizeof(float));
            if (ctx->fs->alu_nimm)
                memcpy(block + nconst, ctx->fs->alu_imm,
                       ctx->fs->alu_nimm * sizeof(float));
            if (pipe_opengpu_write_fs_block(ctx->gpu, block, n)) {
                reject("fragment constants did not reach the fragment core");
                return;
            }
        }
        for (tri = 0; tri < count; tri += 3) {
            struct hw_vertex verts[3];
            struct drm_opengpu_vertex_draw vdraw;
            struct pipe_opengpu_fence *fence = NULL;
            uint32_t cull = 0, depth_bits = 0, blend_config = 0;
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
            if (hw_blend(&ctx->blend, &blend_config)) {
                reject("blend state does not map onto the GPU");
                return;
            }
            fprintf(stderr, "opengpu: blend 0x%x color 0x%08x\n",
                    blend_config, verts[0].color);
            if (ctx->rast.scissor) {
                if (!ctx->scissor_set ||
                    ctx->scissor.minx > 0xffffu || ctx->scissor.miny > 0xffffu ||
                    ctx->scissor.maxx > 0xffffu || ctx->scissor.maxy > 0xffffu) {
                    reject("scissor does not map onto the GPU");
                    return;
                }
            }
            memset(&vdraw, 0, sizeof(vdraw));
            vdraw.vertex_count = 3;
            vdraw.vertex_stride = sizeof(verts[0]);
            vdraw.fragment_kernarg_bank_stride = PIPE_OPENGPU_FS_BANK_STRIDE;
            vdraw.blend_config = blend_config;
            vdraw.state = OPENGPU_DRAW_STATE_OVERRIDE | depth_bits |
                          (cull << OPENGPU_DRAW_STATE_CULL_SHIFT);
            if (ctx->rast.scissor) {
                vdraw.state |= OPENGPU_DRAW_STATE_SCISSOR;
                vdraw.scissor_min = ctx->scissor.minx |
                                    (ctx->scissor.miny << 16);
                vdraw.scissor_max = ctx->scissor.maxx |
                                    (ctx->scissor.maxy << 16);
                fprintf(stderr, "opengpu: scissor [%u,%u) [%u,%u)\n",
                        ctx->scissor.minx, ctx->scissor.maxx,
                        ctx->scissor.miny, ctx->scissor.maxy);
            }
            if (ctx->fs->samples) {
                struct pipe_resource *tex;
                struct opengpu_resource *gpu;
                uint32_t tw, th;

                if (!ctx->fs_view || !ctx->fs_view->texture) {
                    reject("texture sample without a bound texture");
                    return;
                }
                tex = ctx->fs_view->texture;
                gpu = opengpu_resource(tex);
                tw = tex->width0;
                th = tex->height0;
                if (!gpu || !tw || !th ||
                    pipe_opengpu_bind_texture(
                        ctx->gpu, gpu->gpu, tw, th, tw * th * 4u,
                        OPENGPU_RESOURCE_TEXTURE_CLAMP |
                            OPENGPU_RESOURCE_UNCACHED)) {
                    reject("texture bind failed");
                    return;
                }
                /* Base level only. Wrap is clamp; the sampler is bilinear. */
                vdraw.state |= OPENGPU_DRAW_STATE_TEX_ENABLE |
                               OPENGPU_DRAW_STATE_TEX_CLAMP;
            }
            if (pipe_opengpu_draw_vertex(ctx->gpu, &vdraw, &fence) ||
                pipe_opengpu_fence_finish(ctx->gpu, fence, OPENGPU_FENCE_TIMEOUT_MS))
                gpu_failed("GPU draw failed");
            else if (ctx->vs->mvp)
                pipe_opengpu_log_vs_kernarg(ctx->gpu);
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
 * (GeometryStage maps clip ±w onto the full framebuffer), so a GL viewport
 * or clip plane that is not that target is not a GPU job yet. The scissor
 * rectangle is already in scanout pixels when the window is FlipY.
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
    struct opengpu_context *ctx = octx(pipe);

    if (start == 0 && num >= 1 && state) {
        ctx->scissor = state[0];
        ctx->scissor_set = 1;
    }
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
    struct opengpu_context *ctx = octx(pipe);
    const uint8_t *src = NULL;

    if (shader == PIPE_SHADER_VERTEX && index == 0) {
        ctx->mvp_set = 0;
        ctx->mvp_b_set = 0;
        ctx->vs_opacity_set = 0;
    }
    if (shader == PIPE_SHADER_VERTEX && index == 0 && buf &&
        buf->buffer_size >= sizeof(ctx->mvp)) {
        if (buf->user_buffer) {
            src = buf->user_buffer;
        } else if (buf->buffer) {
            uint8_t *map = pipe_opengpu_resource_map(
                opengpu_resource(buf->buffer)->gpu);

            if (map)
                src = map + buf->buffer_offset;
        }
        if (src) {
            memcpy(ctx->mvp, src, sizeof(ctx->mvp));
            ctx->mvp_set = 1;
            if (buf->buffer_size >= 32u * sizeof(float)) {
                memcpy(ctx->mvp_b, src + 16 * sizeof(float), sizeof(ctx->mvp_b));
                ctx->mvp_b_set = 1;
            }
            if (buf->buffer_size >= 17u * sizeof(float)) {
                memcpy(&ctx->vs_opacity, src + 16 * sizeof(float),
                       sizeof(ctx->vs_opacity));
                ctx->vs_opacity_set = 1;
            }
        }
    }
    if (shader == PIPE_SHADER_FRAGMENT && index == 0) {
        ctx->fs_const_set = 0;
        ctx->fs_block_n = 0;
    }
    if (shader == PIPE_SHADER_FRAGMENT && index == 0 && buf &&
        buf->buffer_size >= sizeof(float)) {
        const uint8_t *fsrc = NULL;

        if (buf->user_buffer) {
            fsrc = buf->user_buffer;
        } else if (buf->buffer) {
            uint8_t *map = pipe_opengpu_resource_map(
                opengpu_resource(buf->buffer)->gpu);

            if (map)
                fsrc = map + buf->buffer_offset;
        }
        if (fsrc) {
            unsigned nfloat = buf->buffer_size / sizeof(float);

            if (nfloat > 32)
                nfloat = 32;
            memcpy(ctx->fs_block, fsrc, nfloat * sizeof(float));
            ctx->fs_block_n = nfloat;
            if (nfloat >= 4) {
                memcpy(ctx->fs_const, fsrc, sizeof(ctx->fs_const));
                ctx->fs_const_set = 1;
            }
        }
    }
    if (take_ownership && buf && buf->buffer) {
        struct pipe_resource *owned = buf->buffer;

        pipe_resource_reference(&owned, NULL);
    }
}

static struct pipe_sampler_view *create_sampler_view(
    struct pipe_context *pipe, struct pipe_resource *texture,
    const struct pipe_sampler_view *templ)
{
    struct pipe_sampler_view *view;

    if (!texture || !templ || texture->target != PIPE_TEXTURE_2D ||
        templ->format != PIPE_FORMAT_R8G8B8A8_UNORM ||
        templ->u.tex.first_level != 0 || templ->u.tex.last_level != 0)
        return NULL;
    view = CALLOC_STRUCT(pipe_sampler_view);
    if (!view)
        return NULL;
    *view = *templ;
    view->reference.count = 1;
    view->texture = NULL;
    view->context = pipe;
    pipe_resource_reference(&view->texture, texture);
    return view;
}

static void sampler_view_destroy(struct pipe_context *pipe,
                                 struct pipe_sampler_view *view)
{
    (void)pipe;
    pipe_resource_reference(&view->texture, NULL);
    FREE(view);
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
    struct opengpu_context *ctx = octx(pipe);
    unsigned i;

    if (shader != PIPE_SHADER_FRAGMENT) {
        if (take_ownership && views) {
            for (i = 0; i < num; i++)
                pipe_sampler_view_reference(&views[i], NULL);
        }
        return;
    }
    for (i = 0; i < num; i++) {
        unsigned slot = start + i;
        struct pipe_sampler_view *view = views ? views[i] : NULL;

        if (slot != 0)
            continue;
        if (take_ownership) {
            pipe_sampler_view_reference(&ctx->fs_view, NULL);
            ctx->fs_view = view;
        } else {
            pipe_sampler_view_reference(&ctx->fs_view, view);
        }
    }
    if (start <= 0 && start + num + unbind > 0 && (num == 0 || !views))
        pipe_sampler_view_reference(&ctx->fs_view, NULL);
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
    pipe_sampler_view_reference(&ctx->fs_view, NULL);
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
    ctx->base.create_sampler_view = create_sampler_view;
    ctx->base.sampler_view_destroy = sampler_view_destroy;
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
