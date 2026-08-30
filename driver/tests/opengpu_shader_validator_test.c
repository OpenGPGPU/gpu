// SPDX-License-Identifier: MIT
#include <assert.h>
#include <stdint.h>

#include "../opengpu_shader_validator.h"

static uint32_t lw(unsigned int rd, int imm)
{
    return ((uint32_t)imm & 0xfff) << 20 | 1u << 15 | 2u << 12 |
           (rd & 0x1f) << 7 | 0x03;
}

static uint32_t sw(unsigned int rs2, int imm)
{
    uint32_t value = (uint32_t)imm & 0xfff;

    return (value >> 5) << 25 | (rs2 & 0x1f) << 20 | 1u << 15 |
           2u << 12 | (value & 0x1f) << 7 | 0x23;
}

static uint32_t addi(unsigned int rd, unsigned int rs1, int imm)
{
    return ((uint32_t)imm & 0xfff) << 20 | (rs1 & 0x1f) << 15 |
           (rd & 0x1f) << 7 | 0x13;
}

static uint32_t slli(unsigned int rd, unsigned int rs1, unsigned int shamt)
{
    return (shamt & 0x1f) << 20 | (rs1 & 0x1f) << 15 | 1u << 12 |
           (rd & 0x1f) << 7 | 0x13;
}

static uint32_t add(unsigned int rd, unsigned int rs1, unsigned int rs2)
{
    return (rs2 & 0x1f) << 20 | (rs1 & 0x1f) << 15 |
           (rd & 0x1f) << 7 | 0x33;
}

static uint32_t vsetivli(unsigned int length)
{
    return 0xc1007057u | (length & 0x1f) << 15;
}

static uint32_t vle32(unsigned int vd, unsigned int rs1)
{
    return 0x02006007u | (rs1 & 0x1f) << 15 | (vd & 0x1f) << 7;
}

static uint32_t vse32(unsigned int vs3, unsigned int rs1)
{
    return 0x02006027u | (rs1 & 0x1f) << 15 | (vs3 & 0x1f) << 7;
}

static uint32_t vector_alu(unsigned int funct6, unsigned int form,
                           unsigned int vd, unsigned int vs2,
                           unsigned int operand)
{
    return (funct6 & 0x3f) << 26 | 1u << 25 | (vs2 & 0x1f) << 20 |
           (operand & 0x1f) << 15 | (form & 7) << 12 |
           (vd & 0x1f) << 7 | 0x57;
}

static uint32_t branch(unsigned int funct3, unsigned int rs1,
                       unsigned int rs2, int offset)
{
    uint32_t imm = (uint32_t)offset & 0x1fff;

    return ((imm >> 12) & 1) << 31 | ((imm >> 5) & 0x3f) << 25 |
           (rs2 & 0x1f) << 20 | (rs1 & 0x1f) << 15 |
           (funct3 & 7) << 12 | ((imm >> 1) & 0xf) << 8 |
           ((imm >> 11) & 1) << 7 | 0x63;
}

int main(void)
{
    const uint32_t valid[] = {
        lw(10, 96),
        0x00150513, /* addi x10, x10, 1 */
        sw(10, 128),
        OPENGPU_SHADER_CEASE,
    };
    const uint32_t vector_valid[] = {
        slli(5, 8, 2),
        add(5, 1, 5),
        vsetivli(4),
        addi(6, 5, 96),
        vle32(2, 6),
        branch(0, 8, 0, 8), /* beq x8,x0 skips the ALU for warp zero */
        vector_alu(0x00, 3, 2, 2, 1), /* vadd.vi v2,v2,1 */
        addi(6, 5, 128),
        vse32(2, 6),
        OPENGPU_SHADER_CEASE,
    };
    const struct {
        unsigned int funct6;
        unsigned int form;
    } arithmetic[] = {
        { 0x00, 0 }, { 0x00, 3 }, { 0x02, 0 }, { 0x03, 3 },
        { 0x09, 0 }, { 0x09, 3 }, { 0x0a, 0 }, { 0x0a, 3 },
        { 0x0b, 0 }, { 0x0b, 3 },
    };
    const unsigned int branch_forms[] = { 0, 1, 4, 5, 6, 7 };
    uint32_t program[16];
    unsigned int i;

    assert(opengpu_shader_validate_words(valid, 4, 192, 8));
    assert(opengpu_shader_validate_words(vector_valid, 10, 192, 8));

    for (i = 0; i < sizeof(branch_forms) / sizeof(branch_forms[0]); i++) {
        program[0] = branch(branch_forms[i], 0, 0, 4);
        program[1] = OPENGPU_SHADER_CEASE;
        assert(opengpu_shader_validate_words(program, 2, 192, 8));
    }

    for (i = 0; i < sizeof(arithmetic) / sizeof(arithmetic[0]); i++) {
        unsigned int operand = arithmetic[i].form == 0 ? 2 : 1;

        program[0] = vsetivli(4);
        program[1] = addi(5, 1, 96);
        program[2] = vle32(2, 5);
        program[3] = vector_alu(arithmetic[i].funct6,
                                arithmetic[i].form, 3, 2, operand);
        program[4] = addi(5, 1, 128);
        program[5] = vse32(3, 5);
        program[6] = OPENGPU_SHADER_CEASE;
        assert(opengpu_shader_validate_words(program, 7, 192, 8));
    }

    program[0] = sw(10, 0); /* input array is read-only */
    program[1] = OPENGPU_SHADER_CEASE;
    assert(!opengpu_shader_validate_words(program, 2, 192, 8));

    program[0] = lw(1, 0); /* x1 must remain the kernarg base */
    assert(!opengpu_shader_validate_words(program, 2, 192, 8));

    program[0] = 0x0000006f; /* jal/control flow is not yet proven */
    assert(!opengpu_shader_validate_words(program, 2, 192, 8));

    program[0] = lw(10, 192); /* out of the bound kernarg range */
    assert(!opengpu_shader_validate_words(program, 2, 192, 8));

    program[0] = 0x00108093; /* addi x1, x1, 1 */
    assert(!opengpu_shader_validate_words(program, 2, 192, 8));

    program[0] = 0x00000013; /* no CEASE */
    program[1] = 0x00000013;
    assert(!opengpu_shader_validate_words(program, 2, 192, 8));

    program[0] = vle32(2, 1); /* vector length was never configured */
    program[1] = OPENGPU_SHADER_CEASE;
    assert(!opengpu_shader_validate_words(program, 2, 192, 8));

    program[0] = vsetivli(4);
    program[1] = addi(5, 1, 96);
    program[2] = vse32(2, 5); /* colour input is read-only */
    program[3] = OPENGPU_SHADER_CEASE;
    assert(!opengpu_shader_validate_words(program, 4, 192, 8));

    program[1] = addi(5, 1, 148); /* four words cross output_end */
    assert(!opengpu_shader_validate_words(program, 4, 192, 8));

    program[1] = addi(5, 10, 128); /* unproven scalar base */
    assert(!opengpu_shader_validate_words(program, 4, 192, 8));

    program[0] = vsetivli(4);
    program[1] = addi(5, 1, 128);
    program[2] = vse32(2, 5) & ~(1u << 25); /* masked memory is not profile v2 */
    program[3] = OPENGPU_SHADER_CEASE;
    assert(!opengpu_shader_validate_words(program, 4, 192, 8));

    program[0] = vsetivli(0);
    assert(!opengpu_shader_validate_words(program, 4, 192, 8));

    program[0] = addi(8, 0, 0); /* destroy trusted localLinearBase */
    for (i = 0; i < 10; i++)
        program[i + 1] = vector_valid[i];
    assert(!opengpu_shader_validate_words(program, 11, 192, 8));

    program[0] = sw(10, 128); /* x10 contains stale cross-task data */
    program[1] = OPENGPU_SHADER_CEASE;
    assert(!opengpu_shader_validate_words(program, 2, 192, 8));

    program[0] = vsetivli(4);
    program[1] = addi(5, 1, 128);
    program[2] = vse32(2, 5); /* v2 was never defined */
    program[3] = OPENGPU_SHADER_CEASE;
    assert(!opengpu_shader_validate_words(program, 4, 192, 8));

    program[0] = vector_alu(0x00, 3, 2, 2, 1); /* no vector config */
    program[1] = OPENGPU_SHADER_CEASE;
    assert(!opengpu_shader_validate_words(program, 2, 192, 8));

    program[0] = vsetivli(4);
    program[1] = vector_alu(0x00, 3, 2, 2, 1); /* undefined v2 input */
    program[2] = OPENGPU_SHADER_CEASE;
    assert(!opengpu_shader_validate_words(program, 3, 192, 8));

    program[1] = vector_alu(0x01, 0, 2, 1, 1); /* unsupported funct6 */
    assert(!opengpu_shader_validate_words(program, 3, 192, 8));

    program[1] = vector_alu(0x02, 3, 2, 1, 1); /* vsub.vi is reserved */
    assert(!opengpu_shader_validate_words(program, 3, 192, 8));

    program[1] = vector_alu(0x00, 3, 2, 1, 1) & ~(1u << 25);
    assert(!opengpu_shader_validate_words(program, 3, 192, 8));

    program[0] = addi(0, 1, 128); /* x0 discards writes; not a kernarg ptr */
    program[1] = vsetivli(4);
    program[2] = vse32(1, 0);
    program[3] = OPENGPU_SHADER_CEASE;
    assert(!opengpu_shader_validate_words(program, 4, 192, 8));

    program[0] = branch(0, 0, 0, -4); /* backward edge */
    program[1] = OPENGPU_SHADER_CEASE;
    assert(!opengpu_shader_validate_words(program, 2, 192, 8));

    program[0] = branch(0, 0, 0, 2); /* no compressed instructions */
    assert(!opengpu_shader_validate_words(program, 2, 192, 8));

    program[0] = branch(2, 0, 0, 4); /* reserved branch funct3 */
    assert(!opengpu_shader_validate_words(program, 2, 192, 8));

    program[0] = branch(0, 10, 0, 4); /* undefined scalar predicate */
    assert(!opengpu_shader_validate_words(program, 2, 192, 8));

    program[0] = branch(0, 0, 0, 8); /* target equals word_count */
    assert(!opengpu_shader_validate_words(program, 2, 192, 8));

    program[0] = branch(0, 0, 0, 8);
    program[1] = lw(10, 0); /* only the fallthrough path defines x10 */
    program[2] = sw(10, 128);
    program[3] = OPENGPU_SHADER_CEASE;
    assert(!opengpu_shader_validate_words(program, 4, 192, 8));

    program[0] = branch(0, 0, 0, 8);
    program[1] = OPENGPU_SHADER_CEASE; /* branch bypasses this exit */
    program[2] = OPENGPU_SHADER_CEASE;
    assert(!opengpu_shader_validate_words(program, 3, 192, 8));

    program[0] = vsetivli(4);
    program[1] = branch(0, 0, 0, 8);
    program[2] = vsetivli(2); /* differing VL reaches the join */
    program[3] = addi(5, 1, 128);
    program[4] = vse32(1, 5);
    program[5] = OPENGPU_SHADER_CEASE;
    assert(!opengpu_shader_validate_words(program, 6, 192, 8));

    for (i = 0; i < 5; i++)
        program[i] = branch(0, 0, 0, 40);
    for (i = 5; i < 15; i++)
        program[i] = addi(0, 0, 0);
    program[15] = OPENGPU_SHADER_CEASE;
    assert(!opengpu_shader_validate_words(program, 16, 192, 8));
    return 0;
}
