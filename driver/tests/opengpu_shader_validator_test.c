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

static uint32_t vlse32(unsigned int vd, unsigned int rs1,
                       unsigned int rs2)
{
    return 0x0a006007u | (rs2 & 0x1f) << 20 |
           (rs1 & 0x1f) << 15 | (vd & 0x1f) << 7;
}

static uint32_t vsse32(unsigned int vs3, unsigned int rs1,
                       unsigned int rs2)
{
    return 0x0a006027u | (rs2 & 0x1f) << 20 |
           (rs1 & 0x1f) << 15 | (vs3 & 0x1f) << 7;
}

static uint32_t vluxei32(unsigned int vd, unsigned int rs1,
                         unsigned int vs2)
{
    return 0x06006007u | (vs2 & 0x1f) << 20 |
           (rs1 & 0x1f) << 15 | (vd & 0x1f) << 7;
}

static uint32_t vsoxei32(unsigned int vs3, unsigned int rs1,
                         unsigned int vs2)
{
    return 0x0e006027u | (vs2 & 0x1f) << 20 |
           (rs1 & 0x1f) << 15 | (vs3 & 0x1f) << 7;
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
        { 0x04, 0 }, { 0x07, 4 },
        { 0x09, 0 }, { 0x09, 3 }, { 0x0a, 0 }, { 0x0a, 3 },
        { 0x0b, 0 }, { 0x0b, 3 },
        { 0x0c, 0 }, { 0x0c, 3 }, { 0x0c, 4 },
        { 0x0e, 3 }, { 0x0e, 4 }, { 0x0f, 3 }, { 0x0f, 4 },
        { 0x18, 3 }, { 0x1b, 0 },
        { 0x00, 2 }, { 0x01, 2 }, { 0x02, 2 }, { 0x03, 2 },
        { 0x04, 2 }, { 0x05, 2 }, { 0x06, 2 }, { 0x07, 2 },
        { 0x20, 4 }, { 0x23, 0 }, { 0x25, 3 }, { 0x29, 4 },
        { 0x25, 2 }, { 0x24, 6 }, /* vmul.vv, vmulhu.vx */
        { 0x20, 2 }, { 0x23, 6 }, /* vdivu.vv, vrem.vx */
    };
    const unsigned int branch_forms[] = { 0, 1, 4, 5, 6, 7 };
    uint32_t program[64];
    unsigned int i;

    assert(opengpu_shader_validate_words(valid, 4, 288, 8));
    assert(opengpu_shader_validate_words(vector_valid, 10, 288, 8));
    assert(opengpu_shader_validate_words_with_texture(
        discard_valid, 21, 288, 8, true));

    /* General compute may update any word in its bound kernarg range while
     * retaining the same control-flow and address proof. */
    program[0] = addi(10, 0, 42);
    program[1] = sw(10, 0);
    program[2] = OPENGPU_SHADER_CEASE;
    assert(opengpu_compute_shader_validate_words(program, 3, 64, 1));
    assert(!opengpu_shader_validate_words(program, 3, 64, 1));
    program[1] = sw(10, 64);
    assert(!opengpu_compute_shader_validate_words(program, 3, 64, 1));
    program[0] = vsetivli(4);
    program[1] = vtexsample(2, 1, 1);
    program[2] = OPENGPU_SHADER_CEASE;
    assert(!opengpu_compute_shader_validate_words(program, 3, 64, 4));

    /* Scalar-stride word accesses require a directly materialized, aligned
     * constant stride and prove every signed lane address independently. */
    program[0] = vsetivli(4);
    program[1] = addi(5, 1, 0);
    program[2] = addi(6, 0, 8);
    program[3] = vlse32(2, 5, 6);
    program[4] = vsse32(2, 5, 6);
    program[5] = OPENGPU_SHADER_CEASE;
    assert(opengpu_compute_shader_validate_words(program, 6, 64, 4));

    program[1] = addi(5, 1, 12);
    program[2] = addi(6, 0, -4);
    assert(opengpu_compute_shader_validate_words(program, 6, 64, 4));

    program[1] = addi(5, 1, 0);
    program[2] = addi(6, 0, 32); /* lane two starts past kernarg */
    assert(!opengpu_compute_shader_validate_words(program, 6, 64, 4));

    program[2] = lw(6, 0); /* runtime-dependent stride is not proven */
    assert(!opengpu_compute_shader_validate_words(program, 6, 64, 4));

    program[2] = addi(6, 0, 2); /* misaligned word stride */
    assert(!opengpu_compute_shader_validate_words(program, 6, 64, 4));

    program[1] = slli(5, 8, 2);
    program[2] = add(5, 1, 5); /* lane-relative bases need a wider proof */
    program[3] = addi(6, 0, 8);
    program[4] = vlse32(2, 5, 6);
    program[5] = OPENGPU_SHADER_CEASE;
    assert(!opengpu_compute_shader_validate_words(program, 6, 64, 4));

    /* Indexed word access is admitted only for byte offsets derived from the
     * trusted launch-time local IDs by an exact left shift of two. */
    program[0] = vsetivli(4);
    program[1] = vector_alu(0x25, 3, 2, 1, 2); /* vsll.vi v2,v1,2 */
    program[2] = addi(5, 1, 0);
    program[3] = vluxei32(3, 5, 2);
    program[4] = vsoxei32(3, 5, 2);
    program[5] = OPENGPU_SHADER_CEASE;
    assert(opengpu_compute_shader_validate_words(program, 6, 64, 4));

    program[2] = addi(5, 1, 52); /* complete four-lane span exceeds 64 */
    assert(!opengpu_compute_shader_validate_words(program, 6, 64, 4));

    program[1] = vector_alu(0x00, 3, 2, 1, 0); /* not trusted indices */
    program[2] = addi(5, 1, 0);
    assert(!opengpu_compute_shader_validate_words(program, 6, 64, 4));

    /* Scaling shifts need one source, accept odd registers and in-place vd. */
    for (unsigned int fn = 0x2a; fn <= 0x2b; fn++) {
        for (unsigned int form = 0; form < 8; form++) {
            bool legal = form == 0 || form == 3 || form == 4;
            program[0] = vsetivli(4);
            program[1] = vector_alu(0x00, 3, 31, 1, 0);
            program[2] = vector_alu(fn, form, 31, 31, 1);
            program[3] = OPENGPU_SHADER_CEASE;
            assert(opengpu_compute_shader_validate_words(program, 4, 64, 4) == legal);
            assert(opengpu_shader_validate_words(program, 4, 288, 8) == legal);
            assert(opengpu_vertex_shader_validate_words(program, 4, 512, 8) == legal);
            if (!legal)
                continue;
            program[2] = vector_alu(fn, form, 0, 31, 1); /* unmasked vd=v0 */
            assert(opengpu_compute_shader_validate_words(program, 4, 64, 4));
            program[2] = vector_alu(fn, form, 31, 30, 1); /* undefined vs2 */
            assert(!opengpu_compute_shader_validate_words(program, 4, 64, 4));
            program[2] = vector_alu(fn, form, 31, 31, 9); /* undefined register, valid imm */
            assert(opengpu_compute_shader_validate_words(program, 4, 64, 4) == (form == 3));
            program[2] = vector_alu(fn, form, 31, 31, 1) & ~(1u << 25);
            assert(!opengpu_compute_shader_validate_words(program, 4, 64, 4)); /* no v0 */
            program[2] = vector_alu(0x18, 0, 0, 1, 1);
            program[3] = vector_alu(fn, form, 31, 31, 1) & ~(1u << 25);
            program[4] = OPENGPU_SHADER_CEASE;
            assert(opengpu_compute_shader_validate_words(program, 5, 64, 4));
            assert(opengpu_shader_validate_words(program, 5, 288, 8));
            assert(opengpu_vertex_shader_validate_words(program, 5, 512, 8));
            program[3] = vector_alu(fn, form, 4, 31, 1) & ~(1u << 25); /* no old vd */
            assert(!opengpu_compute_shader_validate_words(program, 5, 64, 4));
            program[3] = vector_alu(fn, form, 0, 31, 1) & ~(1u << 25); /* masked vd=v0 */
            assert(!opengpu_compute_shader_validate_words(program, 5, 64, 4));
        }
        /* Even a zero scaling shift discards trusted byte-index provenance. */
        program[0] = vsetivli(4);
        program[1] = vector_alu(0x25, 3, 2, 1, 2);
        program[2] = vector_alu(fn, 3, 2, 2, 0);
        program[3] = vluxei32(3, 1, 2);
        program[4] = OPENGPU_SHADER_CEASE;
        assert(!opengpu_compute_shader_validate_words(program, 5, 64, 4));
    }

    /* Narrowing uses a defined even/odd source pair in all three forms. */
    for (unsigned int fn = 0x2c; fn <= 0x2f; fn++) {
        for (unsigned int form = 0; form < 8; form++) {
            bool legal = form == 0 || form == 3 || form == 4;
            program[0] = vsetivli(4);
            program[1] = vector_alu(0x00, 3, 2, 1, 0);
            program[2] = vector_alu(0x00, 3, 3, 1, 0);
            program[3] = vector_alu(fn, form, 4, 2, 1);
            program[4] = OPENGPU_SHADER_CEASE;
            assert(opengpu_compute_shader_validate_words(program, 5, 64, 4) == legal);
            assert(opengpu_shader_validate_words(program, 5, 288, 8) == legal);
            assert(opengpu_vertex_shader_validate_words(program, 5, 512, 8) == legal);
            if (!legal)
                continue;
            program[2] = vector_alu(0x00, 3, 5, 1, 0); /* undefined odd half */
            assert(!opengpu_compute_shader_validate_words(program, 5, 64, 4));
            program[2] = vector_alu(0x00, 3, 3, 1, 0);
            program[1] = vector_alu(0x00, 3, 5, 1, 0); /* undefined even half */
            assert(!opengpu_compute_shader_validate_words(program, 5, 64, 4));
            program[1] = vector_alu(0x00, 3, 2, 1, 0);
            for (unsigned int vd = 2; vd <= 3; vd++) {
                program[3] = vector_alu(fn, form, vd, 2, 1);
                assert(!opengpu_compute_shader_validate_words(program, 5, 64, 4));
            }
            program[3] = vector_alu(fn, form, 4, 3, 1); /* odd base */
            assert(!opengpu_compute_shader_validate_words(program, 5, 64, 4));
            program[3] = vector_alu(fn, form, 4, 31, 1); /* cannot wrap to v0 */
            assert(!opengpu_compute_shader_validate_words(program, 5, 64, 4));
            program[1] = vector_alu(0x00, 3, 30, 1, 0);
            program[2] = vector_alu(0x00, 3, 31, 1, 0);
            program[3] = vector_alu(fn, form, 4, 30, 1);
            assert(opengpu_compute_shader_validate_words(program, 5, 64, 4));

            program[1] = vector_alu(0x00, 3, 2, 1, 0);
            program[2] = vector_alu(0x00, 3, 3, 1, 0);
            program[3] = vector_alu(0x18, 0, 0, 1, 1);
            program[4] = vector_alu(0x00, 3, 4, 1, 0);
            program[5] = vector_alu(fn, form, 4, 2, 1) & ~(1u << 25);
            program[6] = OPENGPU_SHADER_CEASE;
            assert(opengpu_compute_shader_validate_words(program, 7, 64, 4));
            assert(opengpu_shader_validate_words(program, 7, 288, 8));
            assert(opengpu_vertex_shader_validate_words(program, 7, 512, 8));
            program[3] = vector_alu(0x00, 3, 5, 1, 0); /* undefined predicate */
            assert(!opengpu_compute_shader_validate_words(program, 7, 64, 4));
            program[3] = vector_alu(0x18, 0, 0, 1, 1);
            program[4] = vector_alu(0x00, 3, 5, 1, 0); /* undefined old vd */
            assert(!opengpu_compute_shader_validate_words(program, 7, 64, 4));
            program[5] = vector_alu(fn, form, 0, 2, 1) & ~(1u << 25);
            assert(!opengpu_compute_shader_validate_words(program, 7, 64, 4));
        }
        /* Narrowing overwrites trusted byte-index provenance. */
        program[0] = vsetivli(4);
        program[1] = vector_alu(0x00, 3, 2, 1, 0);
        program[2] = vector_alu(0x00, 3, 3, 1, 0);
        program[3] = vector_alu(0x25, 3, 4, 1, 2);
        program[4] = vector_alu(fn, 3, 4, 2, 0);
        program[5] = vluxei32(5, 1, 4);
        program[6] = OPENGPU_SHADER_CEASE;
        assert(!opengpu_compute_shader_validate_words(program, 7, 64, 4));
    }

    /* Fixed-profile vsext.vf2/vzext.vf2 widen low 16-bit integer lanes. */
    program[0] = vsetivli(4);
    program[1] = vector_alu(0x12, 2, 3, 1, 7); /* vsext.vf2 v3,v1 */
    program[2] = vector_alu(0x12, 2, 5, 1, 6); /* vzext.vf2 v5,v1 */
    program[3] = OPENGPU_SHADER_CEASE;
    assert(opengpu_compute_shader_validate_words(program, 4, 64, 4));

    program[1] = vector_alu(0x12, 2, 3, 1, 5); /* vsext.vf4 */
    program[2] = vector_alu(0x12, 2, 5, 1, 2); /* vzext.vf8 */
    assert(opengpu_compute_shader_validate_words(program, 4, 64, 4));

    program[1] = vector_alu(0x12, 2, 3, 4, 5); /* undefined source register */
    assert(!opengpu_compute_shader_validate_words(program, 4, 64, 4));

    program[1] = vector_alu(0x12, 2, 1, 1, 7); /* reserved vd/vs2 overlap */
    assert(!opengpu_compute_shader_validate_words(program, 4, 64, 4));

    /* Masked extensions require a defined predicate and preserved destination. */
    for (i = 2; i <= 7; i++) {
        program[0] = vsetivli(4);
        program[1] = vector_alu(0x18, 0, 0, 1, 1); /* vmseq.vv v0,v1,v1 */
        program[2] = vector_alu(0x00, 3, 3, 1, 0); /* define old v3 */
        program[3] = vector_alu(0x12, 2, 3, 1, i) & ~(1u << 25);
        program[4] = OPENGPU_SHADER_CEASE;
        assert(opengpu_compute_shader_validate_words(program, 5, 64, 4));
        assert(opengpu_shader_validate_words(program, 5, 288, 8));
        assert(opengpu_vertex_shader_validate_words(program, 5, 512, 8));

        program[1] = vector_alu(0x18, 0, 2, 1, 1); /* undefined v0 */
        assert(!opengpu_compute_shader_validate_words(program, 5, 64, 4));
        program[1] = vector_alu(0x18, 0, 0, 1, 1);
        program[2] = vector_alu(0x00, 3, 2, 1, 0); /* undefined old v3 */
        assert(!opengpu_compute_shader_validate_words(program, 5, 64, 4));
        program[2] = vector_alu(0x00, 3, 3, 1, 0);
        program[3] = vector_alu(0x12, 2, 0, 1, i) & ~(1u << 25);
        assert(!opengpu_compute_shader_validate_words(program, 5, 64, 4));
        program[3] = vector_alu(0x12, 2, 3, 3, i) & ~(1u << 25);
        assert(!opengpu_compute_shader_validate_words(program, 5, 64, 4));
        program[3] = vector_alu(0x12, 2, 3, 4, i) & ~(1u << 25);
        assert(!opengpu_compute_shader_validate_words(program, 5, 64, 4));
    }
    program[3] = vector_alu(0x12, 2, 3, 1, 1) & ~(1u << 25);
    assert(!opengpu_compute_shader_validate_words(program, 5, 64, 4));
    program[3] = vector_alu(0x18, 3, 3, 1, 0) & ~(1u << 25);
    assert(!opengpu_compute_shader_validate_words(program, 5, 64, 4));

    /* A masked extension must discard previously trusted index provenance. */
    program[0] = vsetivli(4);
    program[1] = vector_alu(0x18, 0, 0, 1, 1);
    program[2] = vector_alu(0x25, 3, 2, 1, 2);
    program[3] = vector_alu(0x12, 2, 2, 1, 6) & ~(1u << 25);
    program[4] = vluxei32(3, 1, 2);
    program[5] = OPENGPU_SHADER_CEASE;
    assert(!opengpu_compute_shader_validate_words(program, 6, 64, 4));

    /* Masked shifts cannot establish complete-batch byte-index provenance. */
    program[2] = vector_alu(0x00, 3, 2, 1, 0);
    program[3] = vector_alu(0x25, 3, 2, 1, 2) & ~(1u << 25);
    assert(!opengpu_compute_shader_validate_words(program, 6, 64, 4));
    program[3] |= 1u << 25;
    assert(opengpu_compute_shader_validate_words(program, 6, 64, 4));

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
        unsigned int operand =
            arithmetic[i].form == 0 || arithmetic[i].form == 2 ? 2 : 0;

        program[0] = vsetivli(4);
        program[1] = addi(5, 1, 96);
        program[2] = vle32(2, 5);
        program[3] = vle32(3, 5); /* vslideup preserves part of old vd */
        program[4] = vector_alu(arithmetic[i].funct6,
                                arithmetic[i].form, 3, 2, operand);
        program[5] = addi(5, 1, 192);
        program[6] = vse32(3, 5);
        program[7] = OPENGPU_SHADER_CEASE;
        assert(opengpu_shader_validate_words(program, 8, 288, 8));
    }

    /* Exercise every admitted masked lane-local opcode/form pair. */
    {
        const struct {
            unsigned int funct6;
            unsigned int forms; /* bitset of legal funct3 values */
        } masked_arithmetic[] = {
            { 0x00, 0x19 }, { 0x02, 0x11 }, { 0x03, 0x18 },
            { 0x04, 0x11 }, { 0x05, 0x11 }, { 0x06, 0x11 },
            { 0x07, 0x11 }, { 0x09, 0x19 }, { 0x0a, 0x19 },
            { 0x0b, 0x19 }, { 0x20, 0x5d }, { 0x21, 0x5d },
            { 0x22, 0x55 }, { 0x23, 0x55 }, { 0x24, 0x44 },
            { 0x25, 0x5d }, { 0x26, 0x44 }, { 0x27, 0x55 },
            { 0x28, 0x19 }, { 0x29, 0x19 },
        };
        unsigned int form;

        for (i = 0; i < sizeof(masked_arithmetic) /
                        sizeof(masked_arithmetic[0]); i++) {
            for (form = 0; form < 8; form++) {
                if (!(masked_arithmetic[i].forms & (1u << form)))
                    continue;
                program[0] = vsetivli(4);
                program[1] = vector_alu(0x18, 0, 0, 1, 1);
                program[2] = vector_alu(0x00, 3, 3, 1, 0);
                program[3] = vector_alu(masked_arithmetic[i].funct6,
                                        form, 3, 1, 1) & ~(1u << 25);
                program[4] = OPENGPU_SHADER_CEASE;
                assert(opengpu_compute_shader_validate_words(program, 5, 64, 4));
                assert(opengpu_shader_validate_words(program, 5, 288, 8));
                assert(opengpu_vertex_shader_validate_words(program, 5, 512, 8));

                program[1] = vector_alu(0x18, 0, 2, 1, 1);
                assert(!opengpu_compute_shader_validate_words(program, 5, 64, 4));
                program[1] = vector_alu(0x18, 0, 0, 1, 1);
                program[2] = vector_alu(0x00, 3, 2, 1, 0);
                assert(!opengpu_compute_shader_validate_words(program, 5, 64, 4));
                program[2] = vector_alu(0x00, 3, 3, 1, 0);
                program[3] &= ~(31u << 7); /* destination v0 is reserved */
                assert(!opengpu_compute_shader_validate_words(program, 5, 64, 4));
                program[3] |= 3u << 7;
                program[3] = (program[3] & ~(31u << 20)) | 4u << 20;
                assert(!opengpu_compute_shader_validate_words(program, 5, 64, 4));
                program[3] = (program[3] & ~(31u << 20)) | 1u << 20;
                program[3] = (program[3] & ~(31u << 15)) | 31u << 15;
                assert(opengpu_compute_shader_validate_words(program, 5, 64, 4)
                       == (form == 3)); /* immediates are not registers */
            }
        }
        /* These families still need their own masked validation contract. */
        const unsigned int excluded[][2] = {
            { 0x18, 0 }, { 0x1c, 3 }, { 0x1f, 4 },
            { 0x00, 2 }, { 0x07, 2 }, { 0x0c, 0 },
            { 0x0e, 3 }, { 0x0f, 4 }, { 0x02, 3 },
        };
        for (i = 0; i < sizeof(excluded) / sizeof(excluded[0]); i++) {
            program[3] = vector_alu(excluded[i][0], excluded[i][1],
                                    3, 1, 1) & ~(1u << 25);
            assert(!opengpu_compute_shader_validate_words(program, 5, 64, 4));
        }
    }

    program[0] = vsetivli(4);
    program[1] = vector_alu(0x0e, 3, 2, 1, 1); /* undefined old vd */
    program[2] = OPENGPU_SHADER_CEASE;
    assert(!opengpu_compute_shader_validate_words(program, 3, 64, 4));
    program[1] = vector_alu(0x0e, 3, 1, 1, 1); /* overlapping vslideup */
    assert(!opengpu_compute_shader_validate_words(program, 3, 64, 4));

    program[0] = vsetivli(4);
    program[1] = vector_alu(0x00, 2, 2, 1, 1); /* undefined reduction vd */
    program[2] = OPENGPU_SHADER_CEASE;
    assert(!opengpu_compute_shader_validate_words(program, 3, 64, 4));
    program[1] = vector_alu(0x00, 2, 1, 1, 1); /* reduction overlap is legal */
    assert(opengpu_compute_shader_validate_words(program, 3, 64, 4));

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
    program[2] = vse32(1, 5) & ~(1u << 25); /* v0 mask is undefined */
    program[3] = OPENGPU_SHADER_CEASE;
    assert(!opengpu_shader_validate_words(program, 4, 288, 8));

    program[0] = vsetivli(4);
    program[1] = vector_alu(0x18, 3, 0, 1, 1); /* vmseq.vi v0,v1,1 */
    program[2] = addi(5, 1, 192);
    program[3] = vse32(1, 5) & ~(1u << 25);
    program[4] = OPENGPU_SHADER_CEASE;
    assert(opengpu_shader_validate_words(program, 5, 288, 8));

    program[2] = addi(5, 1, 96);
    program[3] = vle32(2, 5) & ~(1u << 25); /* old v2 is undefined */
    assert(!opengpu_shader_validate_words(program, 5, 288, 8));
    program[2] = vector_alu(0x00, 3, 2, 1, 0); /* define v2 */
    program[3] = addi(5, 1, 96);
    program[4] = vle32(2, 5) & ~(1u << 25);
    program[5] = OPENGPU_SHADER_CEASE;
    assert(opengpu_shader_validate_words(program, 6, 288, 8));
    program[4] = vle32(0, 5) & ~(1u << 25); /* destination overlaps v0 */
    assert(!opengpu_shader_validate_words(program, 6, 288, 8));

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

    program[1] = vector_alu(0x04, 3, 2, 1, 1); /* vminu.vi is reserved */
    assert(!opengpu_shader_validate_words(program, 3, 288, 8));

    program[1] = vector_alu(0x24, 0, 2, 1, 1); /* no integer vv form */
    assert(!opengpu_shader_validate_words(program, 3, 288, 8));

    program[1] = vector_alu(0x00, 1, 2, 1, 1); /* floating vv is excluded */
    assert(!opengpu_shader_validate_words(program, 3, 288, 8));

    program[1] = vector_alu(0x00, 4, 2, 1, 10); /* undefined scalar x10 */
    assert(!opengpu_shader_validate_words(program, 3, 288, 8));

    program[1] = vector_alu(0x25, 2, 2, 1, 3); /* undefined vector v3 */
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
