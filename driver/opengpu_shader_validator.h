/* SPDX-License-Identifier: MIT */
#ifndef OPENGPU_SHADER_VALIDATOR_H
#define OPENGPU_SHADER_VALIDATOR_H

#ifdef __KERNEL__
#include <linux/types.h>
typedef u32 opengpu_shader_u32;
typedef s32 opengpu_shader_s32;
typedef u64 opengpu_shader_u64;
#else
#include <stdbool.h>
#include <stdint.h>
typedef uint32_t opengpu_shader_u32;
typedef int32_t opengpu_shader_s32;
typedef uint64_t opengpu_shader_u64;
#endif

#define OPENGPU_SHADER_MAX_INSTRUCTIONS 256u
#define OPENGPU_SHADER_MAX_FORWARD_BRANCHES 4u
#define OPENGPU_SHADER_CEASE 0x30500073u

enum opengpu_shader_value_kind {
    OPENGPU_SHADER_VALUE_UNKNOWN,
    OPENGPU_SHADER_VALUE_KERNARG,
    OPENGPU_SHADER_VALUE_LOCAL_INDEX,
    OPENGPU_SHADER_VALUE_LOCAL_BYTES,
    OPENGPU_SHADER_VALUE_KERNARG_LOCAL,
};

struct opengpu_shader_value {
    enum opengpu_shader_value_kind kind;
    opengpu_shader_s32 offset;
};

struct opengpu_shader_state {
    struct opengpu_shader_value values[32];
    bool scalar_defined[32];
    bool vector_defined[32];
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
        if (!state->scalar_defined[reg] ||
            state->values[reg].kind != incoming->values[reg].kind ||
            state->values[reg].offset != incoming->values[reg].offset) {
            state->values[reg].kind = OPENGPU_SHADER_VALUE_UNKNOWN;
            state->values[reg].offset = 0;
        }
    }
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

static inline bool opengpu_shader_vector_access_valid(
    const struct opengpu_shader_value *base, opengpu_shader_u32 vl,
    bool store, opengpu_shader_u64 kernarg_size,
    opengpu_shader_u32 batch_capacity)
{
    opengpu_shader_u64 stride = 4ull * batch_capacity;
    opengpu_shader_u64 output_start = 4ull * stride;
    opengpu_shader_u64 output_end = 6ull * stride;
    opengpu_shader_u64 start, bytes, end;

    if (!vl || base->offset < 0 || (base->offset & 3))
        return false;
    start = (opengpu_shader_u32)base->offset;
    if (base->kind == OPENGPU_SHADER_VALUE_KERNARG)
        bytes = 4ull * vl;
    else if (base->kind == OPENGPU_SHADER_VALUE_KERNARG_LOCAL)
        /* x8 is the trusted localLinearBase.  Active lanes cover logical
         * fragment indices [0, batch_capacity), including a partial warp. */
        bytes = 4ull * batch_capacity;
    else
        return false;
    end = start + bytes;
    if (end < start || end > kernarg_size)
        return false;
    return !store || (start >= output_start && end <= output_end);
}

static inline bool opengpu_shader_vector_alu_valid(opengpu_shader_u32 insn)
{
    opengpu_shader_u32 funct6 = insn >> 26;
    opengpu_shader_u32 form = (insn >> 12) & 7;

    if (!(insn & (1u << 25))) /* shader vector ALU is deliberately unmasked */
        return false;
    switch (funct6) {
    case 0x00: /* vadd.vv/vi */
    case 0x09: /* vand.vv/vi */
    case 0x0a: /* vor.vv/vi */
    case 0x0b: /* vxor.vv/vi */
        return form == 0 || form == 3;
    case 0x02: /* vsub.vv */
        return form == 0;
    case 0x03: /* vrsub.vi */
        return form == 3;
    default:
        return false;
    }
}

/* Fragment shader sandbox. Unreconverged forward scalar branches are limited
 * to four. Paths may reconverge or terminate independently in CEASE; every
 * reachable path must terminate. x1 remains the immutable kernarg base.
 * Scalar lw/sw retain the v1
 * bounds. The RVV profile admits vsetivli e32,m1, a lane-local integer ALU
 * allow-list, and unmasked unit-stride vle32/vse32. Defined-register tracking
 * prevents stale SGPR/VGPR data from being exported. A small abstract
 * interpreter recognizes x1 +
 * 4*x8 + constant, where x8 is the trusted warp localLinearBase, and proves
 * every active vector lane remains in kernarg (loads) or the colour-output /
 * output-valid slices (stores). Clearing an output-valid word discards that
 * fragment. The bounded vector texture sample requires a validated
 * texture binding. Backward branches, jumps, atomics and all other custom
 * instructions remain rejected. */
static inline bool opengpu_shader_validate_words_with_texture(
    const opengpu_shader_u32 *words, opengpu_shader_u32 word_count,
    opengpu_shader_u64 kernarg_size, opengpu_shader_u32 batch_capacity,
    bool texture_enabled)
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
    output_start = 4ull * stride;
    output_end = 6ull * stride;
    if (kernarg_size < 6ull * stride)
        return false;
    if (word_count > OPENGPU_SHADER_MAX_INSTRUCTIONS)
        word_count = OPENGPU_SHADER_MAX_INSTRUCTIONS;
    for (i = 0; i <= 8; i++)
        scalar_defined[i] = true;
    vector_defined[1] = true; /* launch-time local IDs */
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
            if (funct3 == 0 &&
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
        case 0x03: /* only lw imm(x1) inside kernarg */
            imm = (opengpu_shader_s32)insn >> 20;
            if (funct3 != 2 || rs1 != 1 || rd == 1 || imm < 0)
                return false;
            end = (opengpu_shader_u64)(opengpu_shader_u32)imm + 4;
            if (end > kernarg_size)
                return false;
            values[rd].kind = OPENGPU_SHADER_VALUE_UNKNOWN;
            values[rd].offset = 0;
            scalar_defined[rd] = true;
            break;
        case 0x23: /* only sw imm(x1) into output/valid arrays */
            imm = (opengpu_shader_s32)(((insn >> 7) & 0x1f) |
                                       ((insn >> 25) << 5));
            if (imm & 0x800)
                imm |= (opengpu_shader_s32)~0xfff;
            if (funct3 != 2 || rs1 != 1 || !scalar_defined[rs2] || imm < 0 ||
                (opengpu_shader_u64)(opengpu_shader_u32)imm < output_start)
                return false;
            end = (opengpu_shader_u64)(opengpu_shader_u32)imm + 4;
            if (end > output_end)
                return false;
            break;
        case 0x57: /* vsetivli or allow-listed unmasked vector integer ALU */
            if ((insn & 0xfff07fffu) == 0xc1007057u) {
                state.vector_length = rs1;
                if (!state.vector_length ||
                    state.vector_length > batch_capacity)
                    return false;
            } else {
                if (!state.vector_length ||
                    !opengpu_shader_vector_alu_valid(insn) ||
                    !vector_defined[rs2] ||
                    (funct3 == 0 && !vector_defined[rs1]))
                    return false;
                vector_defined[rd] = true;
            }
            break;
        case 0x07: /* unmasked unit-stride vle32.v */
            if ((insn & 0xfff0707fu) != 0x02006007u ||
                !scalar_defined[rs1] ||
                !opengpu_shader_vector_access_valid(
                    &values[rs1], state.vector_length, false, kernarg_size,
                    batch_capacity))
                return false;
            vector_defined[rd] = true;
            break;
        case 0x27: /* unmasked unit-stride vse32.v */
            if ((insn & 0xfff0707fu) != 0x02006027u ||
                !scalar_defined[rs1] || !vector_defined[rd] ||
                !opengpu_shader_vector_access_valid(
                    &values[rs1], state.vector_length, true, kernarg_size,
                    batch_capacity))
                return false;
            break;
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
        case 0x2b: /* unmasked opengpu.vtex.sample vd,vs1,vs2 */
            if ((insn & 0xfe00707fu) != 0x0600002bu ||
                !texture_enabled || !state.vector_length ||
                !vector_defined[rs1] || !vector_defined[rs2])
                return false;
            vector_defined[rd] = true;
            break;
        default:
            return false;
        }
    }
    return false;
}

static inline bool opengpu_shader_validate_words(
    const opengpu_shader_u32 *words, opengpu_shader_u32 word_count,
    opengpu_shader_u64 kernarg_size, opengpu_shader_u32 batch_capacity)
{
    return opengpu_shader_validate_words_with_texture(
        words, word_count, kernarg_size, batch_capacity, false);
}

#endif /* OPENGPU_SHADER_VALIDATOR_H */
