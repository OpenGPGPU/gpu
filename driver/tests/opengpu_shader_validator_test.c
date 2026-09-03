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

static uint32_t vtexsample(unsigned int vd, unsigned int vs1,
                           unsigned int vs2)
{
    return 0x0600002bu | (vs2 & 0x1f) << 20 | (vs1 & 0x1f) << 15 |
           (vd & 0x1f) << 7;
}

static uint32_t vquad(unsigned int funct6, unsigned int vd,
                      unsigned int vs2)
{
    return (funct6 & 0x3f) << 26 | 1u << 25 | (vs2 & 0x1f) << 20 |
           (vd & 0x1f) << 7 | 0x2b;
}

int main(void)
{
    const uint32_t valid[] = {
        lw(10, 96),
        0x00150513, /* addi x10, x10, 1 */
        sw(10, 192),
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
        addi(6, 5, 192),
        vse32(2, 6),
        OPENGPU_SHADER_CEASE,
    };
    const uint32_t discard_valid[] = {
        0x00241293u, /* slli x5,x8,2 */
        0x005082b3u, /* add x5,x1,x5 */
        0xc1027057u, /* vsetivli x0,4,e32,m1,ta,ma */
        0x08028313u, /* addi x6,x5,128: u */
        0x02036087u, /* vle32.v v1,(x6) */
        0x0a028313u, /* addi x6,x5,160: v */
        0x02036107u, /* vle32.v v2,(x6) */
        0x0620812bu, /* vtex.sample v2,v1,v2 */
        0x00041a63u, /* bne x8,x0,+20 */
        0x2e1081d7u, /* vxor.vv v3,v1,v1 */
        0x10028313u, /* addi x6,x5,256 */
        0x020361a7u, /* vse32.v v3,(x6) */
        OPENGPU_SHADER_CEASE,
        0x04028313u, /* addi x6,x5,64: input depth */
        0x02036207u, /* vle32.v v4,(x6) */
        0x0240b257u, /* vadd.vi v4,v4,1 */
        0x0e028313u, /* addi x6,x5,224: output depth */
        0x02036227u, /* vse32.v v4,(x6) */
        0x0c028313u, /* addi x6,x5,192 */
        0x02036127u, /* vse32.v v2,(x6) */
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
    uint32_t program[64];
    unsigned int i;

    assert(opengpu_shader_validate_words(valid, 4, 288, 8));
    assert(opengpu_shader_validate_words(vector_valid, 10, 288, 8));
    assert(opengpu_shader_validate_words_with_texture(
        discard_valid, 21, 288, 8, true));

    /* Vertex stores target transformed attribute slices 8..15. */
    program[0] = lw(10, 0);
    program[1] = sw(10, 320);
    program[2] = OPENGPU_SHADER_CEASE;
    assert(opengpu_vertex_shader_validate_words(program, 3, 512, 8));
    assert(!opengpu_shader_validate_words(program, 3, 512, 8));
    program[1] = sw(10, 224); /* vertex input slice 7 is read-only */
    assert(!opengpu_vertex_shader_validate_words(program, 3, 512, 8));
    program[1] = sw(10, 508); /* final word in output slice 15 */
    assert(opengpu_vertex_shader_validate_words(program, 3, 512, 8));
    assert(!opengpu_vertex_shader_validate_words(program, 3, 511, 8));
    program[0] = vsetivli(4);
    program[1] = addi(5, 1, 320);
    program[2] = vse32(1, 5);
    program[3] = OPENGPU_SHADER_CEASE;
    assert(opengpu_vertex_shader_validate_words(program, 4, 512, 8));
    program[1] = addi(5, 1, 240); /* vector crosses read-only slice 7 */
    assert(!opengpu_vertex_shader_validate_words(program, 4, 512, 8));
    program[0] = vsetivli(4);
    program[1] = vquad(0x0c, 2, 1);
    program[2] = OPENGPU_SHADER_CEASE;
    assert(!opengpu_vertex_shader_validate_words(program, 3, 512, 8));

    /* Guest end-to-end passthrough: copy all eight four-lane input slices to
     * transformed output slices 8..15 for both warps. */
    program[0] = 0x00241293u; /* slli x5,x8,2 */
    program[1] = 0x005082b3u; /* add x5,x1,x5 */
    program[2] = vsetivli(4);
    for (i = 0; i < 8; i++) {
        program[3 + i * 4] = (i * 32u << 20) | 0x00028313u;
        program[4 + i * 4] = 0x02036087u;
        program[5 + i * 4] = ((8u + i) * 32u << 20) | 0x00028313u;
        program[6 + i * 4] = 0x020360a7u;
    }
    program[35] = OPENGPU_SHADER_CEASE;
    assert(opengpu_vertex_shader_validate_words(program, 36, 512, 8));

    program[0] = vsetivli(4);
    program[1] = vtexsample(2, 1, 1);
    program[2] = OPENGPU_SHADER_CEASE;
    assert(opengpu_shader_validate_words_with_texture(
        program, 3, 288, 8, true));
    assert(!opengpu_shader_validate_words(program, 3, 288, 8));

    program[1] = vtexsample(2, 1, 1) & ~(1u << 25);
    assert(!opengpu_shader_validate_words_with_texture(
        program, 3, 288, 8, true));
    program[1] = vtexsample(3, 2, 1); /* v2 coordinate is undefined */
    assert(!opengpu_shader_validate_words_with_texture(
        program, 3, 288, 8, true));
    program[0] = vtexsample(2, 1, 1); /* VL was not configured */
    program[1] = OPENGPU_SHADER_CEASE;
    assert(!opengpu_shader_validate_words_with_texture(
        program, 2, 288, 8, true));

    program[0] = vsetivli(4);
    program[1] = addi(5, 1, 96);
    program[2] = vle32(1, 5);
    program[3] = vquad(0x0c, 2, 1);
    program[4] = OPENGPU_SHADER_CEASE;
    assert(opengpu_shader_validate_words(program, 5, 288, 8));
    program[3] = vquad(0x0d, 2, 1);
    assert(opengpu_shader_validate_words(program, 5, 288, 8));
    program[3] = vquad(0x0d, 2, 1) & ~(1u << 25); /* masked */
    assert(!opengpu_shader_validate_words(program, 5, 288, 8));
    program[3] = vquad(0x0c, 2, 1) | 1u << 15; /* reserved rs1 */
    assert(!opengpu_shader_validate_words(program, 5, 288, 8));
    program[3] = vquad(0x0c, 2, 3); /* undefined source */
    assert(!opengpu_shader_validate_words(program, 5, 288, 8));

    for (i = 0; i < sizeof(branch_forms) / sizeof(branch_forms[0]); i++) {
        program[0] = branch(branch_forms[i], 0, 0, 4);
        program[1] = OPENGPU_SHADER_CEASE;
        assert(opengpu_shader_validate_words(program, 2, 288, 8));
    }

    for (i = 0; i < sizeof(arithmetic) / sizeof(arithmetic[0]); i++) {
        unsigned int operand = arithmetic[i].form == 0 ? 2 : 1;

        program[0] = vsetivli(4);
        program[1] = addi(5, 1, 96);
        program[2] = vle32(2, 5);
        program[3] = vector_alu(arithmetic[i].funct6,
                                arithmetic[i].form, 3, 2, operand);
        program[4] = addi(5, 1, 192);
        program[5] = vse32(3, 5);
        program[6] = OPENGPU_SHADER_CEASE;
        assert(opengpu_shader_validate_words(program, 7, 288, 8));
    }

    program[0] = sw(10, 0); /* input array is read-only */
    program[1] = OPENGPU_SHADER_CEASE;
    assert(!opengpu_shader_validate_words(program, 2, 288, 8));

    program[0] = lw(1, 0); /* x1 must remain the kernarg base */
    assert(!opengpu_shader_validate_words(program, 2, 288, 8));

    program[0] = 0x0000006f; /* jal/control flow is not yet proven */
    assert(!opengpu_shader_validate_words(program, 2, 288, 8));

    program[0] = lw(10, 288); /* out of the bound kernarg range */
    assert(!opengpu_shader_validate_words(program, 2, 288, 8));

    program[0] = 0x00108093; /* addi x1, x1, 1 */
    assert(!opengpu_shader_validate_words(program, 2, 288, 8));

    program[0] = 0x00000013; /* no CEASE */
    program[1] = 0x00000013;
    assert(!opengpu_shader_validate_words(program, 2, 288, 8));

    program[0] = vle32(2, 1); /* vector length was never configured */
    program[1] = OPENGPU_SHADER_CEASE;
    assert(!opengpu_shader_validate_words(program, 2, 288, 8));

    program[0] = vsetivli(4);
    program[1] = addi(5, 1, 96);
    program[2] = vse32(2, 5); /* colour input is read-only */
    program[3] = OPENGPU_SHADER_CEASE;
    assert(!opengpu_shader_validate_words(program, 4, 288, 8));

    program[1] = addi(5, 1, 276); /* four words cross validity end */
    assert(!opengpu_shader_validate_words(program, 4, 288, 8));

    program[0] = sw(0, 256); /* clear one output-valid word: discard */
    program[1] = OPENGPU_SHADER_CEASE;
    assert(opengpu_shader_validate_words(program, 2, 288, 8));

    program[0] = vsetivli(4);
    program[1] = addi(5, 1, 256);
    program[2] = vse32(1, 5); /* per-lane output-valid store */
    program[3] = OPENGPU_SHADER_CEASE;
    assert(opengpu_shader_validate_words(program, 4, 288, 8));

    program[1] = addi(5, 10, 192); /* unproven scalar base */
    assert(!opengpu_shader_validate_words(program, 4, 288, 8));

    program[0] = vsetivli(4);
    program[1] = addi(5, 1, 192);
    program[2] = vse32(2, 5) & ~(1u << 25); /* masked memory is not profile v2 */
    program[3] = OPENGPU_SHADER_CEASE;
    assert(!opengpu_shader_validate_words(program, 4, 288, 8));

    program[0] = vsetivli(0);
    assert(!opengpu_shader_validate_words(program, 4, 288, 8));

    program[0] = addi(8, 0, 0); /* destroy trusted localLinearBase */
    for (i = 0; i < 10; i++)
        program[i + 1] = vector_valid[i];
    assert(!opengpu_shader_validate_words(program, 11, 288, 8));

    program[0] = sw(10, 192); /* x10 contains stale cross-task data */
    program[1] = OPENGPU_SHADER_CEASE;
    assert(!opengpu_shader_validate_words(program, 2, 288, 8));

    program[0] = vsetivli(4);
    program[1] = addi(5, 1, 192);
    program[2] = vse32(2, 5); /* v2 was never defined */
    program[3] = OPENGPU_SHADER_CEASE;
    assert(!opengpu_shader_validate_words(program, 4, 288, 8));

    program[0] = vector_alu(0x00, 3, 2, 2, 1); /* no vector config */
    program[1] = OPENGPU_SHADER_CEASE;
    assert(!opengpu_shader_validate_words(program, 2, 288, 8));

    program[0] = vsetivli(4);
    program[1] = vector_alu(0x00, 3, 2, 2, 1); /* undefined v2 input */
    program[2] = OPENGPU_SHADER_CEASE;
    assert(!opengpu_shader_validate_words(program, 3, 288, 8));

    program[1] = vector_alu(0x01, 0, 2, 1, 1); /* unsupported funct6 */
    assert(!opengpu_shader_validate_words(program, 3, 288, 8));

    program[1] = vector_alu(0x02, 3, 2, 1, 1); /* vsub.vi is reserved */
    assert(!opengpu_shader_validate_words(program, 3, 288, 8));

    program[1] = vector_alu(0x00, 3, 2, 1, 1) & ~(1u << 25);
    assert(!opengpu_shader_validate_words(program, 3, 288, 8));

    program[0] = addi(0, 1, 192); /* x0 discards writes; not a kernarg ptr */
    program[1] = vsetivli(4);
    program[2] = vse32(1, 0);
    program[3] = OPENGPU_SHADER_CEASE;
    assert(!opengpu_shader_validate_words(program, 4, 288, 8));

    program[0] = branch(0, 0, 0, -4); /* backward edge */
    program[1] = OPENGPU_SHADER_CEASE;
    assert(!opengpu_shader_validate_words(program, 2, 288, 8));

    program[0] = branch(0, 0, 0, 2); /* no compressed instructions */
    assert(!opengpu_shader_validate_words(program, 2, 288, 8));

    program[0] = branch(2, 0, 0, 4); /* reserved branch funct3 */
    assert(!opengpu_shader_validate_words(program, 2, 288, 8));

    program[0] = branch(0, 10, 0, 4); /* undefined scalar predicate */
    assert(!opengpu_shader_validate_words(program, 2, 288, 8));

    program[0] = branch(0, 0, 0, 8); /* target equals word_count */
    assert(!opengpu_shader_validate_words(program, 2, 288, 8));

    program[0] = branch(0, 0, 0, 8);
    program[1] = lw(10, 0); /* only the fallthrough path defines x10 */
    program[2] = sw(10, 192);
    program[3] = OPENGPU_SHADER_CEASE;
    assert(!opengpu_shader_validate_words(program, 4, 288, 8));

    program[0] = branch(0, 0, 0, 8);
    program[1] = OPENGPU_SHADER_CEASE; /* independently terminating paths */
    program[2] = OPENGPU_SHADER_CEASE;
    assert(opengpu_shader_validate_words(program, 3, 288, 8));

    program[0] = branch(0, 0, 0, 8);
    program[1] = OPENGPU_SHADER_CEASE;
    program[2] = addi(10, 10, 1); /* alternate path uses undefined x10 */
    program[3] = OPENGPU_SHADER_CEASE;
    assert(!opengpu_shader_validate_words(program, 4, 288, 8));

    program[0] = vsetivli(4);
    program[1] = branch(0, 0, 0, 8);
    program[2] = vsetivli(2); /* differing VL reaches the join */
    program[3] = addi(5, 1, 192);
    program[4] = vse32(1, 5);
    program[5] = OPENGPU_SHADER_CEASE;
    assert(!opengpu_shader_validate_words(program, 6, 288, 8));

    for (i = 0; i < 5; i++)
        program[i] = branch(0, 0, 0, 40);
    for (i = 5; i < 15; i++)
        program[i] = addi(0, 0, 0);
    program[15] = OPENGPU_SHADER_CEASE;
    assert(!opengpu_shader_validate_words(program, 16, 288, 8));
    return 0;
}
