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

static inline bool opengpu_shader_vector_access_valid(
    const struct opengpu_shader_value *base, opengpu_shader_u32 vl,
    bool store, opengpu_shader_u64 kernarg_size,
    opengpu_shader_u32 batch_capacity)
{
    opengpu_shader_u64 stride = 4ull * batch_capacity;
    opengpu_shader_u64 output_start = 4ull * stride;
    opengpu_shader_u64 output_end = 5ull * stride;
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

/* Fragment shader sandbox.  Control flow is linear and must terminate in
 * CEASE. x1 remains the immutable kernarg base. Scalar lw/sw retain the v1
 * bounds. The RVV profile admits only vsetivli e32,m1 plus unmasked,
 * unit-stride vle32/vse32. A small abstract interpreter recognizes x1 +
 * 4*x8 + constant, where x8 is the trusted warp localLinearBase, and proves
 * every active vector lane remains in kernarg (loads) or the colour-output
 * slice (stores). Branches, jumps, atomics and custom instructions remain
 * rejected. */
static inline bool opengpu_shader_validate_words(
    const opengpu_shader_u32 *words, opengpu_shader_u32 word_count,
    opengpu_shader_u64 kernarg_size, opengpu_shader_u32 batch_capacity)
{
    opengpu_shader_u64 stride, output_start, output_end;
    struct opengpu_shader_value values[32] = { 0 };
    opengpu_shader_u32 vector_length = 0;
    opengpu_shader_u32 i;

    if (!words || !word_count || !batch_capacity || batch_capacity > 64)
        return false;
    stride = 4ull * batch_capacity;
    output_start = 4ull * stride;
    output_end = 5ull * stride;
    if (kernarg_size < 6ull * stride)
        return false;
    if (word_count > OPENGPU_SHADER_MAX_INSTRUCTIONS)
        word_count = OPENGPU_SHADER_MAX_INSTRUCTIONS;
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
        struct opengpu_shader_value lhs = values[rs1];
        struct opengpu_shader_value rhs = values[rs2];
        opengpu_shader_s32 imm;
        opengpu_shader_u64 end;

        if (insn == OPENGPU_SHADER_CEASE)
            return true;
        switch (opcode) {
        case 0x13: /* RV32I OP-IMM */
            if (rd == 1)
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
            break;
        case 0x33: /* RV32I/M OP */
            if (rd == 1 || (funct7 != 0 && funct7 != 1 && funct7 != 0x20) ||
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
            break;
        case 0x17: /* AUIPC */
        case 0x37: /* LUI */
            if (rd == 1)
                return false;
            values[rd].kind = OPENGPU_SHADER_VALUE_UNKNOWN;
            values[rd].offset = 0;
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
            break;
        case 0x23: /* only sw imm(x1) into output array */
            imm = (opengpu_shader_s32)(((insn >> 7) & 0x1f) |
                                       ((insn >> 25) << 5));
            if (imm & 0x800)
                imm |= (opengpu_shader_s32)~0xfff;
            if (funct3 != 2 || rs1 != 1 || imm < 0 ||
                (opengpu_shader_u64)(opengpu_shader_u32)imm < output_start)
                return false;
            end = (opengpu_shader_u64)(opengpu_shader_u32)imm + 4;
            if (end > output_end)
                return false;
            break;
        case 0x57: /* vsetivli x0, uimm, e32,m1,ta,ma */
            if ((insn & 0xfff07fffu) != 0xc1007057u)
                return false;
            vector_length = rs1;
            if (!vector_length || vector_length > batch_capacity)
                return false;
            break;
        case 0x07: /* unmasked unit-stride vle32.v */
            if ((insn & 0xfff0707fu) != 0x02006007u ||
                !opengpu_shader_vector_access_valid(
                    &values[rs1], vector_length, false, kernarg_size,
                    batch_capacity))
                return false;
            break;
        case 0x27: /* unmasked unit-stride vse32.v */
            if ((insn & 0xfff0707fu) != 0x02006027u ||
                !opengpu_shader_vector_access_valid(
                    &values[rs1], vector_length, true, kernarg_size,
                    batch_capacity))
                return false;
            break;
        default:
            return false;
        }
    }
    return false;
}

#endif /* OPENGPU_SHADER_VALIDATOR_H */
