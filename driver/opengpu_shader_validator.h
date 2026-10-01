/* SPDX-License-Identifier: MIT */
#ifndef OPENGPU_SHADER_VALIDATOR_H
#define OPENGPU_SHADER_VALIDATOR_H

#ifdef __KERNEL__
#include <linux/types.h>
typedef u32 opengpu_shader_u32;
typedef s32 opengpu_shader_s32;
typedef u64 opengpu_shader_u64;
typedef s64 opengpu_shader_s64;
#else
#include <stdbool.h>
#include <stdint.h>
typedef uint32_t opengpu_shader_u32;
typedef int32_t opengpu_shader_s32;
typedef uint64_t opengpu_shader_u64;
typedef int64_t opengpu_shader_s64;
#endif

#define OPENGPU_SHADER_MAX_INSTRUCTIONS 256u
#define OPENGPU_SHADER_MAX_FORWARD_BRANCHES 4u
#define OPENGPU_SHADER_CEASE 0x30500073u
/* csrrwi x0, vxrm, mode: shader ABI admits only immediate modes 0..3. */
#define OPENGPU_SHADER_SET_VXRM(mode) (0x00a05073u | ((mode) << 15))

enum opengpu_shader_value_kind {
    OPENGPU_SHADER_VALUE_UNKNOWN,
    OPENGPU_SHADER_VALUE_KERNARG,
    OPENGPU_SHADER_VALUE_LOCAL_INDEX,
    OPENGPU_SHADER_VALUE_LOCAL_BYTES,
    OPENGPU_SHADER_VALUE_KERNARG_LOCAL,
    OPENGPU_SHADER_VALUE_CONSTANT,
};

struct opengpu_shader_value {
    enum opengpu_shader_value_kind kind;
    opengpu_shader_s32 offset;
};

struct opengpu_shader_state {
    struct opengpu_shader_value values[32];
    bool scalar_defined[32];
    bool vector_defined[32];
    bool fp_defined[32];
    opengpu_shader_u32 vector_local_indices;
    opengpu_shader_u32 vector_local_bytes;
    opengpu_shader_u32 vector_length;
};

struct opengpu_shader_branch_state {
    struct opengpu_shader_state state;
    opengpu_shader_u32 target;
    bool used;
};

static inline void opengpu_shader_merge_state(
    struct opengpu_shader_state *state,
    const struct opengpu_shader_state *incoming)
{
    opengpu_shader_u32 reg;

    for (reg = 0; reg < 32; reg++) {
        state->scalar_defined[reg] &= incoming->scalar_defined[reg];
        state->vector_defined[reg] &= incoming->vector_defined[reg];
        state->fp_defined[reg] &= incoming->fp_defined[reg];
        if (!state->scalar_defined[reg] ||
            state->values[reg].kind != incoming->values[reg].kind ||
            state->values[reg].offset != incoming->values[reg].offset) {
            state->values[reg].kind = OPENGPU_SHADER_VALUE_UNKNOWN;
            state->values[reg].offset = 0;
        }
    }
    state->vector_local_indices &= incoming->vector_local_indices;
    state->vector_local_bytes &= incoming->vector_local_bytes;
    if (state->vector_length != incoming->vector_length)
        state->vector_length = 0;
}

static inline bool opengpu_shader_queue_branch(
    struct opengpu_shader_branch_state *branches,
    opengpu_shader_u32 target, const struct opengpu_shader_state *state)
{
    opengpu_shader_u32 slot;

    for (slot = 0; slot < OPENGPU_SHADER_MAX_FORWARD_BRANCHES; slot++) {
        if (branches[slot].used && branches[slot].target == target) {
            opengpu_shader_merge_state(&branches[slot].state, state);
            return true;
        }
    }
    for (slot = 0; slot < OPENGPU_SHADER_MAX_FORWARD_BRANCHES; slot++) {
        if (!branches[slot].used) {
            branches[slot].used = true;
            branches[slot].target = target;
            branches[slot].state = *state;
            return true;
        }
    }
    return false;
}

/* Width field of a vector load or store: 0 is 8 bits, 5 is 16, 6 is 32. */
static inline opengpu_shader_u32 opengpu_shader_element_bytes(
    opengpu_shader_u32 width)
{
    if (width == 0u)
        return 1u;
    if (width == 5u)
        return 2u;
    if (width == 6u)
        return 4u;
    return 0u;
}

/* Unit-stride (lumop 0), constant-stride, or ordered/unordered 32-bit index.
 * Narrow indexed forms are not implemented. */
static inline bool opengpu_shader_vector_memory_form(
    opengpu_shader_u32 insn, opengpu_shader_u32 opcode,
    bool *strided, bool *indexed, opengpu_shader_u32 *elem_bytes)
{
    opengpu_shader_u32 width = (insn >> 12) & 7u;
    opengpu_shader_u32 mop = insn & 0xfc00707fu;
    opengpu_shader_u32 bytes = opengpu_shader_element_bytes(width);
    bool unit = bytes && (insn & 0xfdf0707fu) == ((width << 12) | opcode);

    *elem_bytes = bytes;
    *strided = bytes && mop == (0x08000000u | (width << 12) | opcode);
    *indexed = mop == (0x04006000u | opcode) || mop == (0x0c006000u | opcode);
    return *strided || *indexed || unit;
}

static inline bool opengpu_shader_vector_access_valid(
    const struct opengpu_shader_value *base, opengpu_shader_u32 vl,
    opengpu_shader_u32 elem_bytes, bool store, opengpu_shader_u64 kernarg_size,
    opengpu_shader_u32 batch_capacity, opengpu_shader_u64 output_start,
    opengpu_shader_u64 output_end)
{
    opengpu_shader_u64 start, bytes, end;

    if (!vl || !elem_bytes || base->offset < 0 ||
        (base->offset & (elem_bytes - 1u)))
        return false;
    start = (opengpu_shader_u32)base->offset;
    if (base->kind == OPENGPU_SHADER_VALUE_KERNARG)
        bytes = (opengpu_shader_u64)elem_bytes * vl;
    else if (base->kind == OPENGPU_SHADER_VALUE_KERNARG_LOCAL)
        /* x8 is the trusted localLinearBase.  Active lanes cover logical
         * fragment indices [0, batch_capacity), including a partial warp. */
        bytes = (opengpu_shader_u64)elem_bytes * batch_capacity;
    else
        return false;
    end = start + bytes;
    if (end < start || end > kernarg_size)
        return false;
    return !store || (start >= output_start && end <= output_end);
}

static inline bool opengpu_shader_vector_strided_access_valid(
    const struct opengpu_shader_value *base,
    const struct opengpu_shader_value *stride, opengpu_shader_u32 vl,
    opengpu_shader_u32 elem_bytes, bool store, opengpu_shader_u64 kernarg_size,
    opengpu_shader_u64 output_start, opengpu_shader_u64 output_end)
{
    opengpu_shader_u32 lane;

    if (!vl || !elem_bytes || base->kind != OPENGPU_SHADER_VALUE_KERNARG ||
        stride->kind != OPENGPU_SHADER_VALUE_CONSTANT ||
        base->offset < 0 || (base->offset & (elem_bytes - 1u)) ||
        (stride->offset & (elem_bytes - 1u)))
        return false;
    for (lane = 0; lane < vl; lane++) {
        opengpu_shader_s64 start = (opengpu_shader_s64)base->offset +
            (opengpu_shader_s64)lane * stride->offset;
        opengpu_shader_u64 end;

        if (start < 0)
            return false;
        end = (opengpu_shader_u64)start + elem_bytes;
        if (end < (opengpu_shader_u64)start || end > kernarg_size)
            return false;
        if (store && ((opengpu_shader_u64)start < output_start ||
                      end > output_end))
            return false;
    }
    return true;
}

static inline bool opengpu_shader_vector_indexed_access_valid(
    const struct opengpu_shader_value *base,
    bool trusted_local_bytes,
    bool store, opengpu_shader_u64 kernarg_size,
    opengpu_shader_u32 batch_capacity, opengpu_shader_u64 output_start,
    opengpu_shader_u64 output_end)
{
    opengpu_shader_u64 start, end;

    if (base->kind != OPENGPU_SHADER_VALUE_KERNARG || base->offset < 0 ||
        (base->offset & 3) ||
        !trusted_local_bytes)
        return false;
    start = (opengpu_shader_u32)base->offset;
    end = start + 4ull * batch_capacity;
    if (end < start || end > kernarg_size)
        return false;
    return !store || (start >= output_start && end <= output_end);
}

static inline bool opengpu_shader_vector_alu_valid(opengpu_shader_u32 insn)
{
    opengpu_shader_u32 funct6 = insn >> 26;
    opengpu_shader_u32 form = (insn >> 12) & 7;
    opengpu_shader_u32 vd = (insn >> 7) & 0x1f;
    opengpu_shader_u32 vs2 = (insn >> 20) & 0x1f;

    /* Masked operations require a defined v0 and old vd below. Keep the
     * form allow-list authoritative so reserved encodings stay rejected. */
    if (!(insn & (1u << 25))) {
        bool lane_local =
            ((form == 0 || form == 3 || form == 4) &&
             (funct6 < 0x0c || funct6 >= 0x20)) ||
            ((form == 2 || form == 6) && funct6 >= 0x20) ||
            (form == 2 && funct6 == 0x12);
        bool comparison =
            ((form == 0 || form == 3 || form == 4) &&
             funct6 >= 0x18 && funct6 <= 0x1f) ||
            (form == 1 &&
             (funct6 == 0x18 || funct6 == 0x19 || funct6 == 0x1b ||
              funct6 == 0x1c)) ||
            (form == 5 &&
             (funct6 == 0x18 || funct6 == 0x19 || funct6 == 0x1b ||
              funct6 == 0x1c || funct6 == 0x1d || funct6 == 0x1f));
        bool reduction = form == 2 && funct6 <= 0x07;
        bool gather = (form == 0 || form == 3 || form == 4) &&
                      funct6 == 0x0c;
        bool slide = ((form == 0 || form == 3 || form == 4) &&
                      (funct6 == 0x0e || funct6 == 0x0f)) ||
                     (form == 6 && (funct6 == 0x0e || funct6 == 0x0f));

        /* vfmerge.vfm is masked by definition. vfmv.v.f is its unmasked form. */
        bool fp_merge = form == 5 && funct6 == 0x17;
        /* vmerge.vvm/vxm/vim. vmv is the unmasked form and is handled below. */
        bool integer_merge =
            (form == 0 || form == 3 || form == 4) && funct6 == 0x17;
        /* Other non-compare FP ops keep the old destination where v0 is clear. */
        bool fp_data =
            (form == 1 &&
             (funct6 == 0x00 || funct6 == 0x02 || funct6 == 0x04 ||
              funct6 == 0x06 || funct6 == 0x08 || funct6 == 0x09 ||
              funct6 == 0x0a || funct6 == 0x12 || funct6 == 0x13 ||
              funct6 == 0x20 || funct6 == 0x24 ||
              (funct6 >= 0x28 && funct6 <= 0x2f))) ||
            (form == 5 &&
             (funct6 == 0x00 || funct6 == 0x02 || funct6 == 0x04 ||
              funct6 == 0x06 || funct6 == 0x08 || funct6 == 0x09 ||
              funct6 == 0x0a || funct6 == 0x20 || funct6 == 0x21 ||
              funct6 == 0x24 || funct6 == 0x27 ||
              (funct6 >= 0x28 && funct6 <= 0x2f)));
        if (!(lane_local || comparison || reduction || gather || slide ||
              fp_merge || fp_data || integer_merge) ||
            vd == 0)
            return false;
    }
    if (funct6 == 0x0e && vd == vs2) /* vslideup overlap is reserved */
        return false;
    if (form == 2 && funct6 == 0x12 && vd == vs2)
        return false; /* widening source/destination overlap */
    if ((form == 0 || form == 3 || form == 4) &&
        (funct6 >= 0x2c && funct6 <= 0x2f) &&
        ((vs2 & 1) || vd == vs2 || vd == vs2 + 1))
        return false; /* narrowing needs an even vs2 pair disjoint from vd */
    switch (form) {
    case 0: /* integer vv */
        switch (funct6) {
        case 0x17: /* vmerge; vmv requires vs2 = v0 */
            return !(insn & (1u << 25)) || vs2 == 0;
        case 0x00: /* vadd */
        case 0x02: /* vsub */
        case 0x04: /* vminu */
        case 0x05: /* vmin */
        case 0x06: /* vmaxu */
        case 0x07: /* vmax */
        case 0x09: /* vand */
        case 0x0a: /* vor */
        case 0x0b: /* vxor */
        case 0x0c: /* vrgather */
        case 0x0e: /* vslideup */
        case 0x0f: /* vslidedown */
        case 0x18: /* vmseq */
        case 0x19: /* vmsne */
        case 0x1a: /* vmsltu */
        case 0x1b: /* vmslt */
        case 0x1c: /* vmsleu */
        case 0x1d: /* vmsle */
        case 0x20: /* vsaddu */
        case 0x21: /* vsadd */
        case 0x22: /* vssubu */
        case 0x23: /* vssub */
        case 0x25: /* vsll */
        case 0x27: /* vsmul */
        case 0x28: /* vsrl */
        case 0x29: /* vsra */
        case 0x2a: /* vssrl */
        case 0x2b: /* vssra */
        case 0x2c: /* vnsrl */
        case 0x2d: /* vnsra */
        case 0x2e: /* vnclipu */
        case 0x2f: /* vnclip */
        case 0x30: /* vwadd */
        case 0x31: /* vwsub */
        case 0x32: /* vwmul */
        case 0x33: /* vwmulu */
        case 0x34: /* vwmulsu */
            return true;
        default:
            return false;
        }
    case 2: /* integer reduction, mask logical, vmv.x.s, or multiply/divide vv */
        /* vmand/vmor/vmxor and the negated forms are unmasked. */
        if (funct6 >= 0x18 && funct6 <= 0x1f)
            return (insn & (1u << 25)) != 0;
        /* vmv.x.s: VWXUNARY0, vs1 = 0, unmasked. Other vs1 values stay reserved. */
        if (funct6 == 0x10)
            return (insn & (1u << 25)) && ((insn >> 15) & 0x1f) == 0;
        return funct6 <= 0x07 || funct6 == 0x12 ||
               (funct6 >= 0x20 && funct6 <= 0x27);
    case 6: /* vslide1*.vx, vmv.s.x, or multiply/divide vx */
        /* vslide1up.vx / vslide1down.vx insert the scalar and shift by one. */
        if (funct6 == 0x0e || funct6 == 0x0f)
            return true;
        /* vmv.s.x: VRXUNARY0, vs2 = v0, unmasked. */
        if (funct6 == 0x10)
            return (insn & (1u << 25)) && vs2 == 0;
        return funct6 >= 0x20 && funct6 <= 0x27;
    case 1: { /* OPFVV: FP32 VFUNARY1 plus unmasked binary FVV */
        opengpu_shader_u32 vs1 = (insn >> 15) & 0x1f;

        if (funct6 == 0x12) {
            /* vfcvt.* ; vs1 selects the conversion and is not a VGPR.
             * 0 xu.f, 1 x.f, 2 f.xu, 3 f.x, 6 rtz.xu.f, 7 rtz.x.f. */
            return vs1 <= 3u || vs1 == 6u || vs1 == 7u;
        }
        if (funct6 == 0x13) {
            /* vfsqrt / vfrec7 / vfrsqrt7 / vfclass; vs1 is not a VGPR. */
            return vs1 == 0 || vs1 == 4 || vs1 == 5 || vs1 == 16;
        }
        /* vfmv.f.s: vs1 = 0, unmasked. Other vs1 values stay reserved. */
        if (funct6 == 0x10)
            return (insn & (1u << 25)) && vs1 == 0;
        switch (funct6) {
        case 0x00: /* vfadd */
        case 0x02: /* vfsub */
        case 0x04: /* vfmin */
        case 0x06: /* vfmax */
        case 0x08: /* vfsgnj */
        case 0x09: /* vfsgnjn */
        case 0x0a: /* vfsgnjx */
        case 0x18: /* vmfeq */
        case 0x19: /* vmfle */
        case 0x1b: /* vmflt */
        case 0x1c: /* vmfne */
        case 0x20: /* vfdiv */
        case 0x24: /* vfmul */
        case 0x28: /* vfmadd */
        case 0x29: /* vfnmadd */
        case 0x2a: /* vfmsub */
        case 0x2b: /* vfnmsub */
        case 0x2c: /* vfmacc */
        case 0x2d: /* vfnmacc */
        case 0x2e: /* vfmsac */
        case 0x2f: /* vfnmsac */
            return true;
        default:
            return false;
        }
    }
    case 5: /* OPFVF: rs1 names a scalar FP register */
        /* vfmv.s.f: vs2 = v0, unmasked. */
        if (funct6 == 0x10)
            return (insn & (1u << 25)) && vs2 == 0;
        switch (funct6) {
        case 0x00: /* vfadd */
        case 0x02: /* vfsub */
        case 0x04: /* vfmin */
        case 0x06: /* vfmax */
        case 0x08: /* vfsgnj */
        case 0x09: /* vfsgnjn */
        case 0x0a: /* vfsgnjx */
        case 0x17: /* vfmerge.vfm / vfmv.v.f */
        case 0x18: /* vmfeq */
        case 0x19: /* vmfle */
        case 0x1b: /* vmflt */
        case 0x1c: /* vmfne */
        case 0x1d: /* vmfgt */
        case 0x1f: /* vmfge */
        case 0x20: /* vfdiv */
        case 0x21: /* vfrdiv */
        case 0x24: /* vfmul */
        case 0x27: /* vfrsub */
        case 0x28: /* vfmadd */
        case 0x29: /* vfnmadd */
        case 0x2a: /* vfmsub */
        case 0x2b: /* vfnmsub */
        case 0x2c: /* vfmacc */
        case 0x2d: /* vfnmacc */
        case 0x2e: /* vfmsac */
        case 0x2f: /* vfnmsac */
            return true;
        default:
            return false;
        }
    case 3: /* integer vi */
        switch (funct6) {
        case 0x17: /* vmerge; vmv requires vs2 = v0 */
            return !(insn & (1u << 25)) || vs2 == 0;
        case 0x00: /* vadd */
        case 0x03: /* vrsub */
        case 0x09: /* vand */
        case 0x0a: /* vor */
        case 0x0b: /* vxor */
        case 0x0c: /* vrgather */
        case 0x0e: /* vslideup */
        case 0x0f: /* vslidedown */
        case 0x18: /* vmseq */
        case 0x19: /* vmsne */
        case 0x1c: /* vmsleu */
        case 0x1d: /* vmsle */
        case 0x1e: /* vmsgtu */
        case 0x1f: /* vmsgt */
        case 0x20: /* vsaddu */
        case 0x21: /* vsadd */
        case 0x25: /* vsll */
        case 0x28: /* vsrl */
        case 0x29: /* vsra */
        case 0x2a: /* vssrl */
        case 0x2b: /* vssra */
        case 0x2c: /* vnsrl */
        case 0x2d: /* vnsra */
        case 0x2e: /* vnclipu */
        case 0x2f: /* vnclip */
        case 0x30: /* vwadd.vi */
            return true;
        default:
            return false;
        }
    case 4: /* integer vx */
        switch (funct6) {
        case 0x17: /* vmerge; vmv requires vs2 = v0 */
            return !(insn & (1u << 25)) || vs2 == 0;
        case 0x00: /* vadd */
        case 0x02: /* vsub */
        case 0x03: /* vrsub */
        case 0x04: /* vminu */
        case 0x05: /* vmin */
        case 0x06: /* vmaxu */
        case 0x07: /* vmax */
        case 0x09: /* vand */
        case 0x0a: /* vor */
        case 0x0b: /* vxor */
        case 0x0c: /* vrgather */
        case 0x0e: /* vslideup */
        case 0x0f: /* vslidedown */
        case 0x18: /* vmseq */
        case 0x19: /* vmsne */
        case 0x1a: /* vmsltu */
        case 0x1b: /* vmslt */
        case 0x1c: /* vmsleu */
        case 0x1d: /* vmsle */
        case 0x1e: /* vmsgtu */
        case 0x1f: /* vmsgt */
        case 0x20: /* vsaddu */
        case 0x21: /* vsadd */
        case 0x22: /* vssubu */
        case 0x23: /* vssub */
        case 0x25: /* vsll */
        case 0x27: /* vsmul */
        case 0x28: /* vsrl */
        case 0x29: /* vsra */
        case 0x2a: /* vssrl */
        case 0x2b: /* vssra */
        case 0x2c: /* vnsrl */
        case 0x2d: /* vnsra */
        case 0x2e: /* vnclipu */
        case 0x2f: /* vnclip */
        case 0x30: /* vwadd.vx */
        case 0x31: /* vwsub.vx */
        case 0x32: /* vwmul.vx */
        case 0x33: /* vwmulu.vx */
            return true;
        default:
            return false;
        }
    default:
        return false;
    }
}

/* RNE, RTZ, RDN, RUP, RMM, and dynamic. Reserved modes 5 and 6 are rejected. */
static inline bool opengpu_shader_fp_rounding_valid(opengpu_shader_u32 rm)
{
    return rm <= 4u || rm == 7u;
}

/* An integer result is a plain value. It must not keep a pointer kind that
 * would let a later vector store treat it as kernarg. */
static inline void opengpu_shader_define_integer(
    struct opengpu_shader_state *state, opengpu_shader_u32 rd)
{
    state->values[rd].kind = OPENGPU_SHADER_VALUE_UNKNOWN;
    state->values[rd].offset = 0;
    state->scalar_defined[rd] = true;
}

/* Shared shader sandbox. Unreconverged forward scalar branches are limited
 * to four. Paths may reconverge or terminate independently in CEASE; every
 * reachable path must terminate. x1 remains the immutable kernarg base.
 * Scalar loads and stores are imm(x1) only: lb/lbu/lh/lhu/lw and sb/sh/sw,
 * naturally aligned, inside kernarg for loads and inside the profile output
 * window for stores. When the scalar FPU is enabled, flw is limited to aligned imm(x1)
 * reads inside kernarg and fsw to aligned imm(x1) writes of a defined
 * f-register into that profile's output window. The same capability admits
 * the FP32 operations that retire on the scalar FPU fast path: fadd/fsub/fmul,
 * fsgnj/fsgnjn/fsgnjx, fmin/fmax, and the four fused multiply-add forms.
 * Each source f-register must already be defined, and the destination becomes
 * defined. The same path admits feq/flt/fle, fcvt.w.s/fcvt.wu.s, fmv.x.w and
 * fclass.s, which write an integer other than x1, and fcvt.s.w/fcvt.s.wu and
 * fmv.w.x, which read a defined integer and write an f-register. An integer
 * destination loses any pointer kind it had. fdiv.s reads two defined
 * f-registers. fsqrt.s reads one defined f-register and requires rs2 = 0.
 * Both write a defined f-register.
 * vfmv.v.f broadcasts a defined f-register. vfmerge.vfm is its masked form:
 * v0 and the old destination must be defined, and vs2 supplies the
 * mask-clear lanes.
 * vmerge.vvm/vxm/vim select vs1, a scalar, or a sign-extended immediate on
 * set mask lanes and vs2 on clear mask lanes. v0, vs2, and the old
 * destination must be defined, and the destination cannot be v0. vmv.v.v,
 * vmv.v.x, and vmv.v.i are the unmasked forms: vs2 must be v0 and is not
 * read. Lanes outside VL keep the old destination.
 * vmand, vmor, vmxor and the negated forms vmandn, vmorn, vmnand, vmnor
 * and vmxnor combine two defined mask registers. The encoding is unmasked;
 * a clear vm bit is rejected. The destination may be v0. Bits outside VL
 * keep the old destination.
 * vmv.s.x writes a defined integer into element 0 of vd. vs2 must be v0
 * and is not read. vmv.x.s copies element 0 of a defined vector into an
 * integer register other than x1. vs1 must be 0 and is not a vector source.
 * Both encodings are unmasked.
 * vfmv.s.f writes a defined f-register into element 0 of vd. vs2 must be v0
 * and is not read. vfmv.f.s copies element 0 of a defined vector into an
 * f-register. vs1 must be 0 and is not a vector source. Both need the scalar
 * FPU and are unmasked. An inactive element 0 writes the canonical NaN.
 * vslide1up.vx inserts a defined integer at element 0 and slides vs2 up by
 * one. vslide1down.vx slides vs2 down by one and writes that integer at
 * element vl-1. Both are OPMVX. vslide1up keeps the vslideup rule that vd
 * must already be defined and must not overlap vs2. Masked-off lanes and
 * lanes outside vl keep the old destination.
 * vslideup and vslidedown also take a vector of per-element offsets in a
 * defined vs1, so each lane shifts by its own amount rather than by one
 * shared scalar or immediate. The vslideup rules carry over unchanged: vd
 * must already be defined and must not overlap vs2.
 * The RVV profile admits vsetivli e32,m1, the implemented lane-local
 * integer ALU, comparison, saturating, reduction, gather, slide, multiply,
 * divide and remainder forms, vssrl/vssra rounded scaling shifts,
 * masked lane-local integer arithmetic, comparisons, reductions, gathers,
 * slides and extensions, fixed-profile vnsrl/vnsra narrowing shifts over even/odd
 * register pairs, vnclipu/vnclip rounded saturating narrowing, FP32
 * VFUNARY0 (`vfcvt.xu.f.v`/`vfcvt.x.f.v`/`vfcvt.f.xu.v`/`vfcvt.f.x.v` and the
 * two rtz float-to-integer forms) and VFUNARY1
 * (`vfsqrt`/`vfrec7`/`vfrsqrt7`/`vfclass`), OPFVV
 * `vfadd`/`vfsub`/`vfmul`/`vfdiv`/`vfmin`/`vfmax`/`vfsgnj`/`vfsgnjn`/`vfsgnjx`,
 * OPFVV compares (`vmfeq`/`vmfle`/`vmflt`/`vmfne`), the eight fused FMA
 * forms, the matching OPFVF forms plus `vfrdiv`/`vfrsub`/`vmfgt`/`vmfge`
 * whose scalar operand must be a defined f-register. Masked non-compare
 * forms of those FP ops need a defined v0 and a defined old destination,
 * which supplies the mask-clear lanes. The same profile admits masked or
 * unmasked unit-stride and constant-stride memory at 8, 16, and 32 bits,
 * plus trusted-local-index word accesses. Each lane holds one element,
 * zero-extended to 32 bits. The base and a constant stride must be aligned
 * to that element. Indexed accesses stay 32-bit.
 * Defined-register tracking prevents stale SGPR/VGPR data from being exported.
 * A small abstract interpreter recognizes x1 + 4*x8 + constant, where x8 is
 * the trusted warp localLinearBase, and proves every active vector lane
 * remains in kernarg; each profile selects the writable portion. The fragment
 * profile restricts stores to colour/depth/validity slices, while general
 * compute permits the whole explicitly bound kernarg range. The bounded
 * vector texture sample requires a validated texture binding. Backward
 * branches, jumps, atomics and all other custom instructions remain rejected.
 */
static inline bool opengpu_shader_validate_words_profile(
    const opengpu_shader_u32 *words, opengpu_shader_u32 word_count,
    opengpu_shader_u64 kernarg_size, opengpu_shader_u32 batch_capacity,
    opengpu_shader_u32 output_start_slice,
    opengpu_shader_u32 output_end_slice, bool fragment_ops_enabled,
    bool texture_enabled, bool all_kernarg_writable, bool scalar_fpu_enabled)
{
    opengpu_shader_u64 stride, output_start, output_end;
    struct opengpu_shader_state state = { 0 };
    struct opengpu_shader_branch_state branches[
        OPENGPU_SHADER_MAX_FORWARD_BRANCHES] = { 0 };
    struct opengpu_shader_value *values = state.values;
    bool *scalar_defined = state.scalar_defined;
    bool *vector_defined = state.vector_defined;
    bool reachable = true;
    opengpu_shader_u32 i, branch;

    if (!words || !word_count || !batch_capacity || batch_capacity > 64)
        return false;
    stride = 4ull * batch_capacity;
    if (all_kernarg_writable) {
        output_start = 0;
        output_end = kernarg_size;
    } else {
        output_start = output_start_slice * stride;
        output_end = output_end_slice * stride;
        if (output_start_slice >= output_end_slice ||
            kernarg_size < output_end)
            return false;
    }
    if (word_count > OPENGPU_SHADER_MAX_INSTRUCTIONS)
        word_count = OPENGPU_SHADER_MAX_INSTRUCTIONS;
    for (i = 0; i <= 8; i++)
        scalar_defined[i] = true;
    vector_defined[1] = true; /* launch-time local IDs */
    state.vector_local_indices = 1u << 1;
    values[1].kind = OPENGPU_SHADER_VALUE_KERNARG;
    values[8].kind = OPENGPU_SHADER_VALUE_LOCAL_INDEX;

    for (i = 0; i < word_count; i++) {
        opengpu_shader_u32 insn = words[i];
        opengpu_shader_u32 opcode = insn & 0x7f;
        opengpu_shader_u32 rd = (insn >> 7) & 0x1f;
        opengpu_shader_u32 funct3 = (insn >> 12) & 7;
        opengpu_shader_u32 rs1 = (insn >> 15) & 0x1f;
        opengpu_shader_u32 rs2 = (insn >> 20) & 0x1f;
        opengpu_shader_u32 funct7 = insn >> 25;
        struct opengpu_shader_value lhs, rhs;
        opengpu_shader_s32 imm;
        opengpu_shader_u64 end;
        bool target_reachable = false;

        for (branch = 0; branch < OPENGPU_SHADER_MAX_FORWARD_BRANCHES;
             branch++) {
            if (branches[branch].used && branches[branch].target < i)
                return false;
            if (branches[branch].used && branches[branch].target == i) {
                if (reachable || target_reachable)
                    opengpu_shader_merge_state(
                        &state, &branches[branch].state);
                else
                    state = branches[branch].state;
                target_reachable = true;
                branches[branch].used = false;
            }
        }
        reachable |= target_reachable;
        if (!reachable)
            continue;
        if (insn == OPENGPU_SHADER_CEASE) {
            for (branch = 0;
                 branch < OPENGPU_SHADER_MAX_FORWARD_BRANCHES; branch++) {
                if (branches[branch].used)
                    break;
            }
            if (branch == OPENGPU_SHADER_MAX_FORWARD_BRANCHES)
                return true;
            reachable = false;
            continue;
        }
        lhs = values[rs1];
        rhs = values[rs2];
        switch (opcode) {
        case 0x73: /* shader-profile vxrm immediate write only */
            if ((insn & 0xfff07fffu) != 0x00a05073u ||
                (rs1 & ~3u))
                return false;
            break;
        case 0x13: /* RV32I OP-IMM */
            if (rd == 1 || !scalar_defined[rs1])
                return false;
            if (funct3 == 1 && funct7 != 0)
                return false;
            if (funct3 == 5 && funct7 != 0 && funct7 != 0x20)
                return false;
            values[rd].kind = OPENGPU_SHADER_VALUE_UNKNOWN;
            values[rd].offset = 0;
            imm = (opengpu_shader_s32)insn >> 20;
            if (funct3 == 0 && rs1 == 0) {
                values[rd].kind = OPENGPU_SHADER_VALUE_CONSTANT;
                values[rd].offset = imm;
            } else if (funct3 == 0 &&
                (lhs.kind == OPENGPU_SHADER_VALUE_KERNARG ||
                 lhs.kind == OPENGPU_SHADER_VALUE_LOCAL_BYTES ||
                 lhs.kind == OPENGPU_SHADER_VALUE_KERNARG_LOCAL)) {
                values[rd] = lhs;
                values[rd].offset += imm;
            } else if (funct3 == 1 &&
                       lhs.kind == OPENGPU_SHADER_VALUE_LOCAL_INDEX &&
                       ((insn >> 20) & 0x1f) == 2) {
                values[rd].kind = OPENGPU_SHADER_VALUE_LOCAL_BYTES;
            }
            if (rd == 0) {
                values[0].kind = OPENGPU_SHADER_VALUE_UNKNOWN;
                values[0].offset = 0;
            }
            scalar_defined[rd] = true;
            break;
        case 0x33: /* RV32I/M OP */
            if (rd == 1 || !scalar_defined[rs1] || !scalar_defined[rs2] ||
                (funct7 != 0 && funct7 != 1 && funct7 != 0x20) ||
                (funct7 == 0x20 && funct3 != 0 && funct3 != 5))
                return false;
            values[rd].kind = OPENGPU_SHADER_VALUE_UNKNOWN;
            values[rd].offset = 0;
            if (funct7 == 0 && funct3 == 0 &&
                ((lhs.kind == OPENGPU_SHADER_VALUE_KERNARG &&
                  rhs.kind == OPENGPU_SHADER_VALUE_LOCAL_BYTES) ||
                 (rhs.kind == OPENGPU_SHADER_VALUE_KERNARG &&
                  lhs.kind == OPENGPU_SHADER_VALUE_LOCAL_BYTES))) {
                values[rd].kind = OPENGPU_SHADER_VALUE_KERNARG_LOCAL;
                values[rd].offset = lhs.offset + rhs.offset;
            }
            if (rd == 0) {
                values[0].kind = OPENGPU_SHADER_VALUE_UNKNOWN;
                values[0].offset = 0;
            }
            scalar_defined[rd] = true;
            break;
        case 0x17: /* AUIPC */
        case 0x37: /* LUI */
            if (rd == 1)
                return false;
            values[rd].kind = OPENGPU_SHADER_VALUE_UNKNOWN;
            values[rd].offset = 0;
            scalar_defined[rd] = true;
            break;
        case 0x03: { /* lb/lbu/lh/lhu/lw imm(x1) inside kernarg */
            opengpu_shader_u32 bytes =
                funct3 == 0u || funct3 == 4u ? 1u :
                funct3 == 1u || funct3 == 5u ? 2u :
                funct3 == 2u ? 4u : 0u;

            imm = (opengpu_shader_s32)insn >> 20;
            if (!bytes || rs1 != 1 || rd == 1 || imm < 0 ||
                (imm & (opengpu_shader_s32)(bytes - 1u)))
                return false;
            end = (opengpu_shader_u64)(opengpu_shader_u32)imm + bytes;
            if (end > kernarg_size)
                return false;
            values[rd].kind = OPENGPU_SHADER_VALUE_UNKNOWN;
            values[rd].offset = 0;
            scalar_defined[rd] = true;
            break;
        }
        case 0x23: { /* sb/sh/sw imm(x1) into the output window */
            opengpu_shader_u32 bytes =
                funct3 == 0u ? 1u : funct3 == 1u ? 2u : funct3 == 2u ? 4u : 0u;

            imm = (opengpu_shader_s32)(((insn >> 7) & 0x1f) |
                                       ((insn >> 25) << 5));
            if (imm & 0x800)
                imm |= (opengpu_shader_s32)~0xfff;
            if (!bytes || rs1 != 1 || !scalar_defined[rs2] || imm < 0 ||
                (imm & (opengpu_shader_s32)(bytes - 1u)) ||
                (opengpu_shader_u64)(opengpu_shader_u32)imm < output_start)
                return false;
            end = (opengpu_shader_u64)(opengpu_shader_u32)imm + bytes;
            if (end > output_end)
                return false;
            break;
        }
        case 0x57: /* vsetivli or allow-listed vector ALU (int + OPFVV) */
            if ((insn & 0xfff07fffu) == 0xc1007057u) {
                state.vector_length = rs1;
                if (!state.vector_length ||
                    state.vector_length > batch_capacity)
                    return false;
            } else {
                bool opfvv = funct3 == 1;
                bool opfvf = funct3 == 5;
                bool fp = opfvv || opfvf;
                bool vfunary0 = opfvv && (insn >> 26) == 0x12;
                bool vfunary1 = opfvv && (insn >> 26) == 0x13;
                bool vfmerge = opfvf && (insn >> 26) == 0x17;
                /* vfmv.v.f is vfmerge with the mask bit set; vs2 is unused. */
                bool vfmv = vfmerge && (insn & (1u << 25));
                /* vmv.v.v/x/i encode vs2 as v0 and do not read it. */
                bool integer_mv = (insn >> 26) == 0x17 &&
                    (insn & (1u << 25)) &&
                    (funct3 == 0 || funct3 == 3 || funct3 == 4);
                /* vmv.x.s copies vs2[0] into integer rd. vs1 is the opcode. */
                bool vmv_xs = funct3 == 2 && (insn >> 26) == 0x10 &&
                    (insn & (1u << 25)) && rs1 == 0;
                /* vmv.s.x writes the scalar into vd[0]. vs2 is v0 and unread. */
                bool vmv_sx = funct3 == 6 && (insn >> 26) == 0x10 &&
                    (insn & (1u << 25)) && rs2 == 0;
                /* vfmv.f.s copies vs2[0] into f[rd]. vs1 is the opcode. */
                bool vfmv_fs = funct3 == 1 && (insn >> 26) == 0x10 &&
                    (insn & (1u << 25)) && rs1 == 0;
                /* vfmv.s.f writes f[rs1] into vd[0]. vs2 is v0 and unread. */
                bool vfmv_sf = funct3 == 5 && (insn >> 26) == 0x10 &&
                    (insn & (1u << 25)) && rs2 == 0;

                if (!state.vector_length ||
                    !opengpu_shader_vector_alu_valid(insn) ||
                    (!vfmv && !integer_mv && !vmv_sx && !vfmv_sf &&
                     !vector_defined[rs2]) ||
                    (vfmerge && !scalar_fpu_enabled) ||
                    ((vfmv_fs || vfmv_sf) && !scalar_fpu_enabled) ||
                    (!fp && (insn >> 26) >= 0x2c && (insn >> 26) <= 0x2f &&
                     !vector_defined[rs2 + 1]) ||
                    (!(insn & (1u << 25)) &&
                     (!vector_defined[0] || !vector_defined[rd])) ||
                    /* OPMVV vsext/vzext. OPFVV vfcvt uses the same funct6
                     * with the conversion opcode in vs1. */
                    ((insn >> 26) == 0x12 && !vfunary0 &&
                     !(rs1 == 2 || rs1 == 3 || rs1 == 4 || rs1 == 5 ||
                       rs1 == 6 || rs1 == 7)) ||
                    ((insn >> 26) <= 0x07 && funct3 == 2 &&
                     !vector_defined[rd]) ||
                    ((insn >> 26) == 0x0e && !vector_defined[rd]) ||
                    ((funct3 == 0 || funct3 == 2) && (insn >> 26) != 0x12 &&
                     !vmv_xs &&
                     !vector_defined[rs1]) ||
                    /* Binary OPFVV reads vs1. VFUNARY0/1 encode the op there. */
                    (opfvv && !vfunary0 && !vfunary1 && !vfmv_fs &&
                     !vector_defined[rs1]) ||
                    (opfvf && !state.fp_defined[rs1]) ||
                    /* Fused FMA forms read the old destination. */
                    (fp && (insn >> 26) >= 0x28 && (insn >> 26) <= 0x2f &&
                     !vector_defined[rd]) ||
                    ((funct3 == 4 || funct3 == 6) &&
                     !scalar_defined[rs1]))
                    return false;
                if (vfmv_fs) {
                    state.fp_defined[rd] = true;
                    break;
                }
                if (vmv_xs) {
                    if (rd == 1)
                        return false;
                    values[rd].kind = OPENGPU_SHADER_VALUE_UNKNOWN;
                    values[rd].offset = 0;
                    if (rd == 0) {
                        values[0].kind = OPENGPU_SHADER_VALUE_UNKNOWN;
                        values[0].offset = 0;
                    }
                    scalar_defined[rd] = true;
                    break;
                }
                state.vector_local_indices &= ~(1u << rd);
                state.vector_local_bytes &= ~(1u << rd);
                /* A masked shift can retain arbitrary old destination lanes. */
                if ((insn & (1u << 25)) &&
                    (insn >> 26) == 0x25 && funct3 == 3 && rs1 == 2 &&
                    (state.vector_local_indices & (1u << rs2)))
                    state.vector_local_bytes |= 1u << rd;
                vector_defined[rd] = true;
            }
            break;
        case 0x07: { /* flw imm(x1), or an 8/16/32-bit vector load */
            bool strided, indexed;
            opengpu_shader_u32 elem;

            if (funct3 == 2) {
                imm = (opengpu_shader_s32)insn >> 20;
                end = (opengpu_shader_u64)(opengpu_shader_u32)imm + 4;
                if (!scalar_fpu_enabled || rs1 != 1 || imm < 0 ||
                    (imm & 3) || end > kernarg_size)
                    return false;
                state.fp_defined[rd] = true;
                break;
            }
            if (!opengpu_shader_vector_memory_form(
                    insn, 0x07u, &strided, &indexed, &elem) ||
                !scalar_defined[rs1] ||
                (strided && !scalar_defined[rs2]) ||
                (indexed && !vector_defined[rs2]) ||
                (!(insn & (1u << 25)) &&
                 (!vector_defined[0] || rd == 0 || !vector_defined[rd])) ||
                (indexed ?
                 !opengpu_shader_vector_indexed_access_valid(
                     &values[rs1],
                     !!(state.vector_local_bytes & (1u << rs2)), false,
                     kernarg_size,
                     batch_capacity, output_start, output_end) : strided ?
                 !opengpu_shader_vector_strided_access_valid(
                     &values[rs1], &values[rs2], state.vector_length, elem,
                     false, kernarg_size, output_start, output_end) :
                 !opengpu_shader_vector_access_valid(
                     &values[rs1], state.vector_length, elem, false,
                     kernarg_size, batch_capacity, output_start, output_end)))
                return false;
            state.vector_local_indices &= ~(1u << rd);
            state.vector_local_bytes &= ~(1u << rd);
            vector_defined[rd] = true;
            break;
        }
        case 0x27: { /* fsw imm(x1), or an 8/16/32-bit vector store */
            bool strided, indexed;
            opengpu_shader_u32 elem;

            /* funct3 2 is the scalar FP store. Vector stores use funct3
             * 0, 5, or 6, so the two do not share an encoding. */
            if (funct3 == 2) {
                imm = (opengpu_shader_s32)(((insn >> 7) & 0x1f) |
                                           ((insn >> 25) << 5));
                if (imm & 0x800)
                    imm |= (opengpu_shader_s32)~0xfff;
                end = (opengpu_shader_u64)(opengpu_shader_u32)imm + 4;
                if (!scalar_fpu_enabled || rs1 != 1 ||
                    !state.fp_defined[rs2] || imm < 0 || (imm & 3) ||
                    (opengpu_shader_u64)(opengpu_shader_u32)imm <
                        output_start ||
                    end > output_end)
                    return false;
                break;
            }
            if (!opengpu_shader_vector_memory_form(
                    insn, 0x27u, &strided, &indexed, &elem) ||
                !scalar_defined[rs1] || !vector_defined[rd] ||
                (strided && !scalar_defined[rs2]) ||
                (indexed && !vector_defined[rs2]) ||
                (!(insn & (1u << 25)) && !vector_defined[0]) ||
                (indexed ?
                 !opengpu_shader_vector_indexed_access_valid(
                     &values[rs1],
                     !!(state.vector_local_bytes & (1u << rs2)), true,
                     kernarg_size,
                     batch_capacity, output_start, output_end) : strided ?
                 !opengpu_shader_vector_strided_access_valid(
                     &values[rs1], &values[rs2], state.vector_length, elem,
                     true, kernarg_size, output_start, output_end) :
                 !opengpu_shader_vector_access_valid(
                     &values[rs1], state.vector_length, elem, true,
                     kernarg_size, batch_capacity, output_start, output_end)))
                return false;
            break;
        }
        case 0x63: { /* bounded forward scalar conditional branch */
            opengpu_shader_s32 target;

            imm = ((insn >> 31) & 1) << 12 |
                  ((insn >> 7) & 1) << 11 |
                  ((insn >> 25) & 0x3f) << 5 |
                  ((insn >> 8) & 0xf) << 1;
            if (imm & 0x1000)
                imm |= (opengpu_shader_s32)~0x1fff;
            target = (opengpu_shader_s32)i + imm / 4;
            if (imm <= 0 || (imm & 3) || target <= (opengpu_shader_s32)i ||
                target >= (opengpu_shader_s32)word_count ||
                funct3 == 2 || funct3 == 3 ||
                !scalar_defined[rs1] || !scalar_defined[rs2] ||
                !opengpu_shader_queue_branch(branches,
                    (opengpu_shader_u32)target, &state))
                return false;
            break;
        }
        case 0x2b: /* OpenGPU texture and fragment-quad operations */
            if (!fragment_ops_enabled) {
                return false;
            } else if ((insn & 0xfe00707fu) == 0x0600002bu) {
                if (!texture_enabled || !state.vector_length ||
                    !vector_defined[rs1] || !vector_defined[rs2])
                    return false;
            } else if ((insn & 0xfe0ff07fu) == 0x3200002bu ||
                       (insn & 0xfe0ff07fu) == 0x3600002bu) {
                /* vquad.dfdx/dfdy are unary-vs2, unmasked, rs1=0. */
                if (!state.vector_length || !vector_defined[rs2])
                    return false;
            } else {
                return false;
            }
            vector_defined[rd] = true;
            state.vector_local_indices &= ~(1u << rd);
            state.vector_local_bytes &= ~(1u << rd);
            break;
        case 0x43: /* fmadd.s */
        case 0x47: /* fmsub.s */
        case 0x4b: /* fnmsub.s */
        case 0x4f: { /* fnmadd.s */
            opengpu_shader_u32 rs3 = insn >> 27;
            opengpu_shader_u32 fmt = (insn >> 25) & 3u;

            if (!scalar_fpu_enabled || fmt ||
                !opengpu_shader_fp_rounding_valid(funct3) ||
                !state.fp_defined[rs1] || !state.fp_defined[rs2] ||
                !state.fp_defined[rs3])
                return false;
            state.fp_defined[rd] = true;
            break;
        }
        case 0x53: { /* FP32 ops that retire on the scalar FPU fast path */
            opengpu_shader_u32 funct5 = insn >> 27;
            opengpu_shader_u32 fmt = (insn >> 25) & 3u;
            bool fp_sources =
                state.fp_defined[rs1] && state.fp_defined[rs2];
            bool binop = funct5 <= 2u &&
                opengpu_shader_fp_rounding_valid(funct3);
            bool div = funct5 == 0x03u &&
                opengpu_shader_fp_rounding_valid(funct3);
            bool sqrt = funct5 == 0x0bu && rs2 == 0u &&
                opengpu_shader_fp_rounding_valid(funct3);
            bool sgnj = funct5 == 0x04u && funct3 <= 2u;
            bool minmax = funct5 == 0x05u && funct3 <= 1u;
            bool compare = funct5 == 0x14u && funct3 <= 2u;
            bool fcvt_to_int = funct5 == 0x18u && rs2 <= 1u &&
                opengpu_shader_fp_rounding_valid(funct3);
            bool move_or_class = funct5 == 0x1cu && rs2 == 0u &&
                funct3 <= 1u;
            bool fcvt_to_fp = funct5 == 0x1au && rs2 <= 1u &&
                opengpu_shader_fp_rounding_valid(funct3);
            bool move_to_fp = funct5 == 0x1eu && rs2 == 0u && funct3 == 0u;

            if (!scalar_fpu_enabled || fmt)
                return false;
            if (binop || sgnj || minmax || div) {
                if (!fp_sources)
                    return false;
                state.fp_defined[rd] = true;
                break;
            }
            if (sqrt) {
                if (!state.fp_defined[rs1])
                    return false;
                state.fp_defined[rd] = true;
                break;
            }
            if (compare || fcvt_to_int || move_or_class) {
                if (rd == 1 ||
                    (compare ? !fp_sources : !state.fp_defined[rs1]))
                    return false;
                opengpu_shader_define_integer(&state, rd);
                break;
            }
            if (fcvt_to_fp || move_to_fp) {
                if (!scalar_defined[rs1])
                    return false;
                state.fp_defined[rd] = true;
                break;
            }
            return false;
        }
        default:
            return false;
        }
    }
    return false;
}

/* Fragment/vertex profile. scalar_fpu_enabled admits flw and fsw, which is
 * what a `uniform float` lowers to and how that value is stored. Pass it only
 * when GPU_CAP_COMPUTE_SCALAR_FPU is advertised: the graphics shader CU is
 * built with the FP backend whenever that capability is set, and without it
 * flw never completes and hangs the warp. */
static inline bool opengpu_shader_validate_words_with_texture(
    const opengpu_shader_u32 *words, opengpu_shader_u32 word_count,
    opengpu_shader_u64 kernarg_size, opengpu_shader_u32 batch_capacity,
    bool texture_enabled, bool scalar_fpu_enabled)
{
    return opengpu_shader_validate_words_profile(
        words, word_count, kernarg_size, batch_capacity,
        6, 9, true, texture_enabled, false, scalar_fpu_enabled);
}

static inline bool opengpu_shader_validate_words(
    const opengpu_shader_u32 *words, opengpu_shader_u32 word_count,
    opengpu_shader_u64 kernarg_size, opengpu_shader_u32 batch_capacity,
    bool scalar_fpu_enabled)
{
    return opengpu_shader_validate_words_with_texture(
        words, word_count, kernarg_size, batch_capacity, false,
        scalar_fpu_enabled);
}

/* Vertex profile: the fixed-function stage owns input slices 0..7 and reads
 * transformed attributes from slices 8..15. Fragment-only texture and quad
 * derivative instructions are not meaningful during a vertex launch. */
static inline bool opengpu_vertex_shader_validate_words(
    const opengpu_shader_u32 *words, opengpu_shader_u32 word_count,
    opengpu_shader_u64 kernarg_size, opengpu_shader_u32 batch_capacity,
    bool scalar_fpu_enabled)
{
    return opengpu_shader_validate_words_profile(
        words, word_count, kernarg_size, batch_capacity,
        8, 16, false, false, false, scalar_fpu_enabled);
}

/* General-compute profile: retain the bounded instruction and address
 * analysis, reject fragment-only operations, and permit writes anywhere
 * inside the explicitly bound kernarg range. scalar_fpu_enabled admits flw,
 * fsw and OPFVF; pass it only when GPU_CAP_COMPUTE_SCALAR_FPU is advertised,
 * since flw never completes on compute CUs built without the scalar FPU. */
static inline bool opengpu_compute_shader_validate_words_fpu(
    const opengpu_shader_u32 *words, opengpu_shader_u32 word_count,
    opengpu_shader_u64 kernarg_size, opengpu_shader_u32 local_items,
    bool scalar_fpu_enabled)
{
    return opengpu_shader_validate_words_profile(
        words, word_count, kernarg_size, local_items,
        0, 0, false, false, true, scalar_fpu_enabled);
}

static inline bool opengpu_compute_shader_validate_words(
    const opengpu_shader_u32 *words, opengpu_shader_u32 word_count,
    opengpu_shader_u64 kernarg_size, opengpu_shader_u32 local_items)
{
    return opengpu_compute_shader_validate_words_fpu(
        words, word_count, kernarg_size, local_items, false);
}

#endif /* OPENGPU_SHADER_VALIDATOR_H */
