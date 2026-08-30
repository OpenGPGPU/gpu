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
        addi(6, 5, 128),
        vse32(2, 6),
        OPENGPU_SHADER_CEASE,
    };
    uint32_t program[10];
    unsigned int i;

    assert(opengpu_shader_validate_words(valid, 4, 192, 8));
    assert(opengpu_shader_validate_words(vector_valid, 8, 192, 8));

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
    for (i = 0; i < 8; i++)
        program[i + 1] = vector_valid[i];
    assert(!opengpu_shader_validate_words(program, 9, 192, 8));
    return 0;
}
