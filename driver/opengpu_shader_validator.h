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

/* Initial sandbox profile for fragment programs.  Control flow is linear and
 * must terminate in CEASE.  Integer ALU instructions cannot overwrite x1,
 * which remains the immutable kernarg base.  The only memory operations are
 * lw imm(x1) within the binding and sw imm(x1) into the colour-output array.
 * Branches, jumps, atomics, vector memory and custom instructions are rejected
 * until their address/control-flow proofs have dedicated validator support. */
static inline bool opengpu_shader_validate_words(
    const opengpu_shader_u32 *words, opengpu_shader_u32 word_count,
    opengpu_shader_u64 kernarg_size, opengpu_shader_u32 batch_capacity)
{
    opengpu_shader_u64 stride, output_start, output_end;
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

    for (i = 0; i < word_count; i++) {
        opengpu_shader_u32 insn = words[i];
        opengpu_shader_u32 opcode = insn & 0x7f;
        opengpu_shader_u32 rd = (insn >> 7) & 0x1f;
        opengpu_shader_u32 funct3 = (insn >> 12) & 7;
        opengpu_shader_u32 rs1 = (insn >> 15) & 0x1f;
        opengpu_shader_u32 funct7 = insn >> 25;
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
            break;
        case 0x33: /* RV32I/M OP */
            if (rd == 1 || (funct7 != 0 && funct7 != 1 && funct7 != 0x20) ||
                (funct7 == 0x20 && funct3 != 0 && funct3 != 5))
                return false;
            break;
        case 0x17: /* AUIPC */
        case 0x37: /* LUI */
            if (rd == 1)
                return false;
            break;
        case 0x03: /* only lw imm(x1) inside kernarg */
            imm = (opengpu_shader_s32)insn >> 20;
            if (funct3 != 2 || rs1 != 1 || rd == 1 || imm < 0)
                return false;
            end = (opengpu_shader_u64)(opengpu_shader_u32)imm + 4;
            if (end > kernarg_size)
                return false;
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
        default:
            return false;
        }
    }
    return false;
}

#endif /* OPENGPU_SHADER_VALIDATOR_H */
