// SPDX-License-Identifier: MIT
#include <assert.h>
#include <stdint.h>

#include "../opengpu_shader_validator.h"

static uint32_t load(unsigned int funct3, unsigned int rd, int imm)
{
    return ((uint32_t)imm & 0xfff) << 20 | 1u << 15 | (funct3 & 7) << 12 |
           (rd & 0x1f) << 7 | 0x03;
}

static uint32_t store(unsigned int funct3, unsigned int rs2, int imm)
{
    uint32_t value = (uint32_t)imm & 0xfff;

    return (value >> 5) << 25 | (rs2 & 0x1f) << 20 | 1u << 15 |
           (funct3 & 7) << 12 | (value & 0x1f) << 7 | 0x23;
}

static uint32_t lw(unsigned int rd, int imm)
{
    return load(2, rd, imm);
}

static uint32_t sw(unsigned int rs2, int imm)
{
    return store(2, rs2, imm);
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

static uint32_t vle_width(unsigned int width, unsigned int vd,
                          unsigned int rs1)
{
    return 0x02000007u | (width & 7) << 12 | (rs1 & 0x1f) << 15 |
           (vd & 0x1f) << 7;
}

static uint32_t vse_width(unsigned int width, unsigned int vs3,
                          unsigned int rs1)
{
    return 0x02000027u | (width & 7) << 12 | (rs1 & 0x1f) << 15 |
           (vs3 & 0x1f) << 7;
}

static uint32_t vlse_width(unsigned int width, unsigned int vd,
                           unsigned int rs1, unsigned int rs2)
{
    return 0x0a000007u | (width & 7) << 12 | (rs2 & 0x1f) << 20 |
           (rs1 & 0x1f) << 15 | (vd & 0x1f) << 7;
}

static uint32_t vsse_width(unsigned int width, unsigned int vs3,
                           unsigned int rs1, unsigned int rs2)
{
    return 0x0a000027u | (width & 7) << 12 | (rs2 & 0x1f) << 20 |
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

/* Unmasked OPFVV vfcvt. vs1 is the conversion, not a vector register. */
static uint32_t vfcvt(unsigned int vs1, unsigned int vd, unsigned int vs2)
{
    return 0x12u << 26 | 1u << 25 | (vs2 & 0x1f) << 20 | (vs1 & 0x1f) << 15 |
           1u << 12 | (vd & 0x1f) << 7 | 0x57;
}

static uint32_t flw(unsigned int rd, unsigned int rs1, int offset)
{
    return ((uint32_t)offset & 0xfff) << 20 | (rs1 & 0x1f) << 15 |
           2u << 12 | (rd & 0x1f) << 7 | 0x07;
}

static uint32_t fsw(unsigned int rs2, unsigned int rs1, int imm)
{
    uint32_t value = (uint32_t)imm & 0xfff;

    return (value >> 5) << 25 | (rs2 & 0x1f) << 20 | (rs1 & 0x1f) << 15 |
           2u << 12 | (value & 0x1f) << 7 | 0x27;
}

static uint32_t fp_op(unsigned int funct5, unsigned int rm, unsigned int rd,
                      unsigned int rs1, unsigned int rs2)
{
    return (funct5 & 0x1f) << 27 | (rs2 & 0x1f) << 20 | (rs1 & 0x1f) << 15 |
           (rm & 7) << 12 | (rd & 0x1f) << 7 | 0x53;
}

static uint32_t fp_fma(unsigned int opcode, unsigned int rm, unsigned int rd,
                       unsigned int rs1, unsigned int rs2, unsigned int rs3)
{
    return (rs3 & 0x1f) << 27 | (rs2 & 0x1f) << 20 | (rs1 & 0x1f) << 15 |
           (rm & 7) << 12 | (rd & 0x1f) << 7 | (opcode & 0x7f);
}

static bool fpu_valid(const uint32_t *words, uint32_t count)
{
    return opengpu_compute_shader_validate_words_fpu(words, count, 64, 4, true);
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
    const uint32_t vxrm_valid[] = {
        OPENGPU_SHADER_SET_VXRM(0),
        OPENGPU_SHADER_SET_VXRM(1),
        OPENGPU_SHADER_SET_VXRM(2),
        OPENGPU_SHADER_SET_VXRM(3),
        OPENGPU_SHADER_CEASE,
    };
    uint32_t vxrm_invalid[] = {
        OPENGPU_SHADER_SET_VXRM(4), OPENGPU_SHADER_CEASE,
    };
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
        { 0x0e, 0 }, { 0x0e, 3 }, { 0x0e, 4 },
        { 0x0f, 0 }, { 0x0f, 3 }, { 0x0f, 4 },
        { 0x18, 3 }, { 0x1b, 0 },
        { 0x00, 2 }, { 0x01, 2 }, { 0x02, 2 }, { 0x03, 2 },
        { 0x04, 2 }, { 0x05, 2 }, { 0x06, 2 }, { 0x07, 2 },
        { 0x20, 4 }, { 0x23, 0 }, { 0x25, 3 }, { 0x29, 4 },
        { 0x25, 2 }, { 0x24, 6 }, /* vmul.vv, vmulhu.vx */
        { 0x20, 2 }, { 0x23, 6 }, /* vdivu.vv, vrem.vx */
        /* Widening integer operations: vwadd, vwsub, vwmul, vwmulu, vwmulsu */
        { 0x30, 0 }, { 0x30, 3 }, { 0x30, 4 }, /* vwadd.vv, vwadd.vi, vwadd.vx */
        { 0x31, 0 }, { 0x31, 4 },               /* vwsub.vv, vwsub.vx */
        { 0x32, 0 }, { 0x32, 4 },               /* vwmul.vv, vwmul.vx */
        { 0x33, 0 }, { 0x33, 4 },               /* vwmulu.vv, vwmulu.vx */
        { 0x34, 0 },                             /* vwmulsu.vv */
    };
    const unsigned int branch_forms[] = { 0, 1, 4, 5, 6, 7 };
    uint32_t program[64];
    unsigned int i;

    assert(opengpu_compute_shader_validate_words(vxrm_valid, 5, 64, 1));
    assert(opengpu_shader_validate_words(vxrm_valid, 5, 288, 8, false));
    assert(opengpu_vertex_shader_validate_words(vxrm_valid, 5, 512, 8, false));
    assert(!opengpu_compute_shader_validate_words(vxrm_invalid, 2, 64, 1));
    vxrm_invalid[0] = OPENGPU_SHADER_SET_VXRM(1) | (1u << 7);
    assert(!opengpu_compute_shader_validate_words(vxrm_invalid, 2, 64, 1));
    vxrm_invalid[0] = OPENGPU_SHADER_SET_VXRM(1) | (1u << 20);
    assert(!opengpu_compute_shader_validate_words(vxrm_invalid, 2, 64, 1));
    assert(opengpu_shader_validate_words(valid, 4, 288, 8, false));
    assert(opengpu_shader_validate_words(vector_valid, 10, 288, 8, false));
    assert(opengpu_shader_validate_words_with_texture(
        
        discard_valid, 21, 288, 8, true, false));

    /* Scalar byte and halfword accesses use the same imm(x1) window. */
    program[0] = load(0, 10, 1); /* lb */
    program[1] = load(4, 11, 1); /* lbu */
    program[2] = load(1, 12, 2); /* lh */
    program[3] = load(5, 13, 2); /* lhu */
    program[4] = store(0, 10, 16); /* sb */
    program[5] = store(1, 12, 18); /* sh */
    program[6] = OPENGPU_SHADER_CEASE;
    assert(opengpu_compute_shader_validate_words(program, 7, 64, 1));
    program[2] = load(1, 12, 1); /* odd halfword */
    assert(!opengpu_compute_shader_validate_words(program, 7, 64, 1));
    program[2] = load(1, 12, 2);
    program[5] = store(1, 12, 17); /* odd halfword store */
    assert(!opengpu_compute_shader_validate_words(program, 7, 64, 1));
    program[0] = load(3, 10, 0); /* reserved width */
    program[5] = store(1, 12, 18);
    assert(!opengpu_compute_shader_validate_words(program, 7, 64, 1));
    program[0] = load(0, 10, 63); /* last byte */
    program[1] = OPENGPU_SHADER_CEASE;
    assert(opengpu_compute_shader_validate_words(program, 2, 64, 1));
    program[0] = load(0, 10, 64);
    assert(!opengpu_compute_shader_validate_words(program, 2, 64, 1));

    /* General compute may update any word in its bound kernarg range while
     * retaining the same control-flow and address proof. */
    program[0] = addi(10, 0, 42);
    program[1] = sw(10, 0);
    program[2] = OPENGPU_SHADER_CEASE;
    assert(opengpu_compute_shader_validate_words(program, 3, 64, 1));
    assert(!opengpu_shader_validate_words(program, 3, 64, 1, false));
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

    /* 8- and 16-bit unit and constant-stride accesses. One element per lane.
     * Indexed forms stay 32-bit, and a 64-bit width is not implemented. */
    program[0] = vsetivli(4);
    program[1] = addi(5, 1, 1);
    program[2] = vle_width(0, 2, 5);
    program[3] = vse_width(0, 2, 5);
    program[4] = OPENGPU_SHADER_CEASE;
    assert(opengpu_compute_shader_validate_words(program, 5, 64, 4));
    program[1] = addi(5, 1, 62); /* four bytes would pass the end */
    assert(!opengpu_compute_shader_validate_words(program, 5, 64, 4));
    program[1] = addi(5, 1, 2);
    program[2] = vle_width(5, 2, 5);
    program[3] = vse_width(5, 2, 5);
    assert(opengpu_compute_shader_validate_words(program, 5, 64, 4));
    program[1] = addi(5, 1, 1); /* odd halfword base */
    assert(!opengpu_compute_shader_validate_words(program, 5, 64, 4));
    program[1] = addi(5, 1, 0);
    program[2] = addi(6, 0, 1);
    program[3] = vlse_width(0, 2, 5, 6);
    program[4] = vsse_width(0, 2, 5, 6);
    program[5] = OPENGPU_SHADER_CEASE;
    assert(opengpu_compute_shader_validate_words(program, 6, 64, 4));
    program[2] = addi(6, 0, 2);
    program[3] = vlse_width(5, 2, 5, 6);
    program[4] = vsse_width(5, 2, 5, 6);
    assert(opengpu_compute_shader_validate_words(program, 6, 64, 4));
    program[2] = addi(6, 0, 1); /* odd halfword stride */
    assert(!opengpu_compute_shader_validate_words(program, 6, 64, 4));
    program[2] = vle_width(7, 2, 5); /* vle64 */
    program[3] = OPENGPU_SHADER_CEASE;
    assert(!opengpu_compute_shader_validate_words(program, 4, 64, 4));
    program[2] = 0x06000007u | 5u << 15 | 2u << 7; /* indexed byte load */
    assert(!opengpu_compute_shader_validate_words(program, 4, 64, 4));
    program[0] = vsetivli(4);
    program[1] = vector_alu(0x18, 0, 0, 1, 1);
    program[2] = vector_alu(0x00, 3, 2, 1, 0);
    program[3] = addi(5, 1, 0);
    program[4] = vle_width(0, 2, 5) & ~(1u << 25);
    program[5] = OPENGPU_SHADER_CEASE;
    assert(opengpu_compute_shader_validate_words(program, 6, 64, 4));
    program[1] = addi(9, 0, 2); /* v0 undefined */
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

    /* Scaling shifts need one source, accept odd registers and in-place vd.
     * Skip OPFVV (form 1): 0x2a/0x2b are fused FMA there, covered below. */
    for (unsigned int fn = 0x2a; fn <= 0x2b; fn++) {
        for (unsigned int form = 0; form < 8; form++) {
            bool legal = form == 0 || form == 3 || form == 4;

            if (form == 1)
                continue;
            /* 0x2b is also vnmsub.vv/vx in the multiply unit, which has its
             * own destination-is-an-operand rules below. */
            if (fn == 0x2b && (form == 2 || form == 6))
                continue;
            program[0] = vsetivli(4);
            program[1] = vector_alu(0x00, 3, 31, 1, 0);
            program[2] = vector_alu(fn, form, 31, 31, 1);
            program[3] = OPENGPU_SHADER_CEASE;
            assert(opengpu_compute_shader_validate_words(program, 4, 64, 4) == legal);
            assert(opengpu_shader_validate_words(
                program, 4, 288, 8, false) == legal);
            assert(opengpu_vertex_shader_validate_words(
                program, 4, 512, 8, false) == legal);
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
            assert(opengpu_shader_validate_words(program, 5, 288, 8, false));
            assert(opengpu_vertex_shader_validate_words(
                
                program, 5, 512, 8, false));;
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

    /* vid.v reads no vector source: vs2 is v0 and vs1 is the fixed EEW/EMUL
     * selector 10001. Any other field combination stays reserved. */
    {
        uint32_t vid[3];
        uint32_t masked_vid[6];

        vid[0] = vsetivli(4);
        vid[1] = 0x5208a257u; /* vid.v v4 */
        vid[2] = OPENGPU_SHADER_CEASE;
        assert(opengpu_compute_shader_validate_words(vid, 3, 64, 4));
        assert(opengpu_shader_validate_words(vid, 3, 288, 8, false));
        vid[1] = (0x5208a257u | (1u << 20)); /* vs2 must stay v0 */
        assert(!opengpu_compute_shader_validate_words(vid, 3, 64, 4));
        vid[1] = (0x5208a257u & ~(31u << 15)) | (2u << 15);
        assert(!opengpu_compute_shader_validate_words(vid, 3, 64, 4));
        vid[1] = 0x5208a257u & ~(31u << 15); /* vs1 selector 0 */
        assert(!opengpu_compute_shader_validate_words(vid, 3, 64, 4));

        /* viota.m is the same funct6 selected by vs1 = 10000, and its vs2 is
         * the mask it accumulates rather than a fixed v0. */
        {
            uint32_t iota[4];
            uint32_t masked_iota[6];

            iota[0] = vsetivli(4);
            iota[1] = vle32(2, 1);
            iota[2] = 0x522822d7u; /* viota.m v5, v2 */
            iota[3] = OPENGPU_SHADER_CEASE;
            assert(opengpu_compute_shader_validate_words(iota, 4, 64, 4));
            assert(opengpu_shader_validate_words(iota, 4, 288, 8, false));
            iota[2] = (0x522822d7u & ~(31u << 7)) | (2u << 7); /* vd == vs2 */
            assert(!opengpu_compute_shader_validate_words(iota, 4, 64, 4));
            iota[2] = 0x522822d7u;
            iota[1] = vle32(6, 1); /* undefined mask */
            assert(!opengpu_compute_shader_validate_words(iota, 4, 64, 4));

            masked_iota[0] = vsetivli(4);
            masked_iota[1] = vector_alu(0x18, 0, 0, 1, 1); /* vmseq.vv v0 */
            masked_iota[2] = vle32(2, 1);
            masked_iota[3] = vle32(5, 1);
            masked_iota[4] = 0x522822d7u & ~(1u << 25); /* masked viota */
            masked_iota[5] = OPENGPU_SHADER_CEASE;
            assert(opengpu_compute_shader_validate_words(masked_iota, 6, 64,
                                                         4));
            masked_iota[1] = vle32(2, 1); /* no v0 */
            assert(!opengpu_compute_shader_validate_words(masked_iota, 6, 64,
                                                          4));
            masked_iota[1] = vector_alu(0x18, 0, 0, 1, 1);
            masked_iota[3] = vle32(2, 1); /* no old v5 */
            assert(!opengpu_compute_shader_validate_words(masked_iota, 6, 64,
                                                          4));
            masked_iota[3] = vle32(5, 1);
            masked_iota[4] = 0x522822d7u & ~(1u << 25) & ~(31u << 7);
            assert(!opengpu_compute_shader_validate_words(masked_iota, 6, 64,
                                                          4));
        }

        /* Masked vid.v needs a defined v0 and old destination. */
        masked_vid[0] = vsetivli(4);
        masked_vid[1] = vector_alu(0x18, 0, 0, 1, 1); /* vmseq.vv v0 */
        masked_vid[2] = vle32(4, 1);
        masked_vid[3] = 0x5208a257u & ~(1u << 25);
        masked_vid[4] = OPENGPU_SHADER_CEASE;
        assert(opengpu_compute_shader_validate_words(masked_vid, 5, 64, 4));
        masked_vid[1] = vle32(2, 1); /* no v0 */
        assert(!opengpu_compute_shader_validate_words(masked_vid, 5, 64, 4));
        masked_vid[1] = vector_alu(0x18, 0, 0, 1, 1);
        masked_vid[2] = vle32(2, 1); /* no old v4 */
        assert(!opengpu_compute_shader_validate_words(masked_vid, 5, 64, 4));
        masked_vid[2] = vle32(4, 1);
        masked_vid[3] = 0x5208a257u & ~(1u << 25) & ~(31u << 7); /* vd = v0 */
        assert(!opengpu_compute_shader_validate_words(masked_vid, 5, 64, 4));
    }

    /* vcompress.vm packs the vs1 mask's selected elements of vs2 into the low
     * elements of vd. It is unmasked only, and the destination must be
     * disjoint from both sources. */
    {
        uint32_t compress[6] = { 0 };

        compress[0] = vsetivli(4);
        compress[1] = vector_alu(0x18, 0, 0, 1, 1); /* vmseq.vv v0 */
        compress[2] = vle32(2, 1);
        compress[3] = vle32(3, 1);
        compress[4] = vector_alu(0x17, 2, 3, 2, 0); /* vcompress v3, v2, v0 */
        compress[5] = OPENGPU_SHADER_CEASE;
        assert(opengpu_compute_shader_validate_words(compress, 6, 64, 4));
        assert(opengpu_shader_validate_words(compress, 6, 288, 8, false));
        compress[4] = vector_alu(0x17, 2, 3, 2, 0) & ~(1u << 25);
        assert(!opengpu_compute_shader_validate_words(compress, 6, 64, 4));
        compress[4] = vector_alu(0x17, 2, 2, 2, 0); /* vd == vs2 */
        assert(!opengpu_compute_shader_validate_words(compress, 6, 64, 4));
        compress[4] = vector_alu(0x17, 2, 0, 2, 0); /* vd == mask */
        assert(!opengpu_compute_shader_validate_words(compress, 6, 64, 4));
        compress[4] = vector_alu(0x17, 2, 3, 6, 0); /* undefined vs2 */
        assert(!opengpu_compute_shader_validate_words(compress, 6, 64, 4));
        compress[4] = vector_alu(0x17, 2, 3, 2, 5); /* undefined mask */
        assert(!opengpu_compute_shader_validate_words(compress, 6, 64, 4));
        /* funct6 010111 stays the merge family in the immediate form, and the
         * unmasked vmv.v.i still needs vs2 to be v0. */
        compress[4] = vector_alu(0x17, 3, 3, 2, 0);
        assert(!opengpu_compute_shader_validate_words(compress, 6, 64, 4));
        compress[4] = vector_alu(0x17, 3, 3, 2, 0) & ~(1u << 25);
        assert(opengpu_compute_shader_validate_words(compress, 6, 64, 4));
    }

    /* Single-width multiply-accumulate. The destination is the third operand,
     * so it must be defined in the unmasked forms too, not just under a mask. */
    {
        const unsigned int forms[] = { 0x29, 0x2b, 0x2d, 0x2f };
        uint32_t unmasked[6] = { 0 };
        uint32_t masked[7] = { 0 };
        unsigned int fn, form;

        for (fn = 0; fn < sizeof(forms) / sizeof(forms[0]); fn++) {
            for (form = 2; form <= 6; form += 4) {
                /* vs1 in the vector-vector form, the kernarg base otherwise. */
                unsigned int operand = form == 2 ? 4 : 1;

                unmasked[0] = vsetivli(4);
                unmasked[1] = vle32(2, 1);
                unmasked[2] = vle32(3, 1);
                unmasked[3] = vle32(4, 1);
                unmasked[4] = vector_alu(forms[fn], form, 3, 2, operand);
                unmasked[5] = OPENGPU_SHADER_CEASE;
                assert(opengpu_compute_shader_validate_words(unmasked, 6, 64, 4));
                assert(opengpu_shader_validate_words(unmasked, 6, 288, 8,
                                                     false));
                assert(opengpu_vertex_shader_validate_words(unmasked, 6, 512, 8,
                                                            false));
                unmasked[4] = vector_alu(forms[fn], form, 5, 2, operand);
                assert(!opengpu_compute_shader_validate_words(unmasked, 6, 64,
                                                              4));
                unmasked[4] = vector_alu(forms[fn], form, 3, 6, operand);
                assert(!opengpu_compute_shader_validate_words(unmasked, 6, 64,
                                                              4));
                unmasked[4] = vector_alu(forms[fn], form, 3, 2, 10);
                assert(!opengpu_compute_shader_validate_words(unmasked, 6, 64,
                                                              4));

                masked[0] = vsetivli(4);
                masked[1] = vector_alu(0x18, 0, 0, 1, 1); /* vmseq.vv v0 */
                masked[2] = vle32(2, 1);
                masked[3] = vle32(3, 1);
                masked[4] = vle32(4, 1);
                masked[5] = vector_alu(forms[fn], form, 3, 2, operand) &
                            ~(1u << 25);
                masked[6] = OPENGPU_SHADER_CEASE;
                assert(opengpu_compute_shader_validate_words(masked, 7, 64, 4));
                masked[1] = vle32(2, 1); /* no v0 */
                assert(!opengpu_compute_shader_validate_words(masked, 7, 64, 4));
                masked[1] = vector_alu(0x18, 0, 0, 1, 1);
                masked[3] = vle32(2, 1); /* no old vd */
                assert(!opengpu_compute_shader_validate_words(masked, 7, 64, 4));
                masked[3] = vle32(3, 1);
                masked[5] = vector_alu(forms[fn], form, 0, 2, operand) &
                            ~(1u << 25); /* masked vd = v0 */
                assert(!opengpu_compute_shader_validate_words(masked, 7, 64, 4));
            }
        }
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
            assert(opengpu_shader_validate_words(
                program, 5, 288, 8, false) == legal);
            assert(opengpu_vertex_shader_validate_words(
                program, 5, 512, 8, false) == legal);
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
            assert(opengpu_shader_validate_words(program, 7, 288, 8, false));
            assert(opengpu_vertex_shader_validate_words(
                
                program, 7, 512, 8, false));;
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

    /* Widening integer operations: vwadd, vwsub, vwmul, vwmulu, vwmulsu.
     * These sign/zero extend the lower 16 bits of each lane and operate. */
    program[0] = vsetivli(4);
    program[1] = vector_alu(0x30, 0, 2, 1, 1); /* vwadd.vv v2,v1,v1 */
    program[2] = OPENGPU_SHADER_CEASE;
    assert(opengpu_compute_shader_validate_words(program, 3, 64, 4));
    assert(opengpu_shader_validate_words(program, 3, 288, 8, false));
    assert(opengpu_vertex_shader_validate_words(program, 3, 512, 8, false));

    program[1] = vector_alu(0x30, 4, 2, 1, 0); /* vwadd.vx v2,v1,x1 */
    assert(opengpu_compute_shader_validate_words(program, 3, 64, 4));

    program[1] = vector_alu(0x30, 3, 2, 1, 5); /* vwadd.vi v2,v1,5 */
    assert(opengpu_compute_shader_validate_words(program, 3, 64, 4));

    program[1] = vector_alu(0x31, 0, 2, 1, 1); /* vwsub.vv v2,v1,v1 */
    assert(opengpu_compute_shader_validate_words(program, 3, 64, 4));

    program[1] = vector_alu(0x31, 4, 2, 1, 0); /* vwsub.vx v2,v1,x1 */
    assert(opengpu_compute_shader_validate_words(program, 3, 64, 4));

    program[1] = vector_alu(0x32, 0, 2, 1, 1); /* vwmul.vv v2,v1,v1 */
    assert(opengpu_compute_shader_validate_words(program, 3, 64, 4));

    program[1] = vector_alu(0x32, 4, 2, 1, 0); /* vwmul.vx v2,v1,x1 */
    assert(opengpu_compute_shader_validate_words(program, 3, 64, 4));

    program[1] = vector_alu(0x33, 0, 2, 1, 1); /* vwmulu.vv v2,v1,v1 */
    assert(opengpu_compute_shader_validate_words(program, 3, 64, 4));

    program[1] = vector_alu(0x33, 4, 2, 1, 0); /* vwmulu.vx v2,v1,x1 */
    assert(opengpu_compute_shader_validate_words(program, 3, 64, 4));

    program[1] = vector_alu(0x34, 0, 2, 1, 1); /* vwmulsu.vv v2,v1,v1 */
    assert(opengpu_compute_shader_validate_words(program, 3, 64, 4));

    /* Invalid widening forms are rejected. */
    program[1] = vector_alu(0x31, 3, 2, 1, 1); /* vwsub.vi is invalid */
    assert(!opengpu_compute_shader_validate_words(program, 3, 64, 4));

    program[1] = vector_alu(0x32, 3, 2, 1, 1); /* vwmul.vi is invalid */
    assert(!opengpu_compute_shader_validate_words(program, 3, 64, 4));

    program[1] = vector_alu(0x33, 3, 2, 1, 1); /* vwmulu.vi is invalid */
    assert(!opengpu_compute_shader_validate_words(program, 3, 64, 4));

    program[1] = vector_alu(0x34, 3, 2, 1, 1); /* vwmulsu.vi is invalid */
    assert(!opengpu_compute_shader_validate_words(program, 3, 64, 4));

    program[1] = vector_alu(0x34, 4, 2, 1, 0); /* vwmulsu.vx is invalid */
    assert(!opengpu_compute_shader_validate_words(program, 3, 64, 4));

    /* Undefined source registers are rejected. */
    program[1] = vector_alu(0x30, 0, 2, 4, 1); /* undefined vs2 */
    assert(!opengpu_compute_shader_validate_words(program, 3, 64, 4));

    program[1] = vector_alu(0x30, 0, 2, 1, 4); /* undefined vs1 */
    assert(!opengpu_compute_shader_validate_words(program, 3, 64, 4));

    /* Masked widening requires defined predicate and old destination. */
    program[0] = vsetivli(4);
    program[1] = vector_alu(0x18, 0, 0, 1, 1); /* vmseq.vv v0,v1,v1 */
    program[2] = vector_alu(0x00, 3, 3, 1, 0); /* define old v3 */
    program[3] = vector_alu(0x30, 0, 3, 1, 1) & ~(1u << 25); /* masked vwadd */
    program[4] = OPENGPU_SHADER_CEASE;
    assert(opengpu_compute_shader_validate_words(program, 5, 64, 4));
    assert(opengpu_shader_validate_words(program, 5, 288, 8, false));
    assert(opengpu_vertex_shader_validate_words(program, 5, 512, 8, false));

    program[1] = vector_alu(0x18, 0, 2, 1, 1); /* undefined v0 */
    assert(!opengpu_compute_shader_validate_words(program, 5, 64, 4));
    program[1] = vector_alu(0x18, 0, 0, 1, 1);
    program[2] = vector_alu(0x00, 3, 2, 1, 0); /* undefined old v3 */
    assert(!opengpu_compute_shader_validate_words(program, 5, 64, 4));
    program[2] = vector_alu(0x00, 3, 3, 1, 0);
    program[3] = vector_alu(0x30, 0, 0, 1, 1) & ~(1u << 25); /* masked vd=v0 */
    assert(!opengpu_compute_shader_validate_words(program, 5, 64, 4));

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
        assert(opengpu_shader_validate_words(program, 5, 288, 8, false));
        assert(opengpu_vertex_shader_validate_words(program, 5, 512, 8, false));

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
    /* Masked comparison, reduction, gather and slides require v0 and old vd. */
    {
        const struct { unsigned funct6, form, operand; } cases[] = {
            { 0x18, 3, 0 }, /* vmseq.vi */
            { 0x00, 2, 1 }, /* vredsum.vs */
            { 0x0c, 3, 0 }, /* vrgather.vi */
            { 0x0e, 3, 1 }, /* vslideup.vi */
            { 0x0f, 3, 1 }, /* vslidedown.vi */
            { 0x0e, 0, 1 }, /* vslideup.vv, per-element offsets */
            { 0x0f, 0, 1 }, /* vslidedown.vv, per-element offsets */
        };
        for (i = 0; i < sizeof(cases) / sizeof(cases[0]); i++) {
            program[3] = vector_alu(cases[i].funct6, cases[i].form, 3, 1,
                                    cases[i].operand) & ~(1u << 25);
            assert(opengpu_compute_shader_validate_words(program, 5, 64, 4));
            assert(opengpu_shader_validate_words(program, 5, 288, 8, false));
            assert(opengpu_vertex_shader_validate_words(
                
                program, 5, 512, 8, false));;
            program[1] = vector_alu(0x18, 0, 2, 1, 1); /* no v0 */
            assert(!opengpu_compute_shader_validate_words(program, 5, 64, 4));
            program[1] = vector_alu(0x18, 0, 0, 1, 1);
            program[2] = vector_alu(0x00, 3, 4, 1, 0); /* no old v3 */
            assert(!opengpu_compute_shader_validate_words(program, 5, 64, 4));
            program[2] = vector_alu(0x00, 3, 3, 1, 0);
            program[3] = (program[3] & ~(31u << 7)); /* masked vd=v0 */
            assert(!opengpu_compute_shader_validate_words(program, 5, 64, 4));
        }
        program[3] = vector_alu(0x0e, 3, 1, 1, 1) & ~(1u << 25);
        assert(!opengpu_compute_shader_validate_words(program, 5, 64, 4));
        /* The vslideup overlap rule covers the vector-vector form. */
        program[3] = vector_alu(0x0e, 0, 1, 1, 1) & ~(1u << 25);
        assert(!opengpu_compute_shader_validate_words(program, 5, 64, 4));
        program[3] = vector_alu(0x0e, 0, 1, 1, 1);
        assert(!opengpu_compute_shader_validate_words(program, 5, 64, 4));
        program[3] = vector_alu(0x0e, 0, 2, 1, 1);
        assert(!opengpu_compute_shader_validate_words(program, 5, 64, 4));
        program[3] = vector_alu(0x0e, 0, 3, 1, 1); /* unmasked, all defined */
        assert(opengpu_compute_shader_validate_words(program, 5, 64, 4));
    }

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
    assert(opengpu_vertex_shader_validate_words(program, 3, 512, 8, false));
    assert(!opengpu_shader_validate_words(program, 3, 512, 8, false));
    program[1] = sw(10, 224); /* vertex input slice 7 is read-only */
    assert(!opengpu_vertex_shader_validate_words(program, 3, 512, 8, false));
    program[1] = sw(10, 508); /* final word in output slice 15 */
    assert(opengpu_vertex_shader_validate_words(program, 3, 512, 8, false));
    assert(!opengpu_vertex_shader_validate_words(program, 3, 511, 8, false));
    program[0] = vsetivli(4);
    program[1] = addi(5, 1, 320);
    program[2] = vse32(1, 5);
    program[3] = OPENGPU_SHADER_CEASE;
    assert(opengpu_vertex_shader_validate_words(program, 4, 512, 8, false));
    program[1] = addi(5, 1, 240); /* vector crosses read-only slice 7 */
    assert(!opengpu_vertex_shader_validate_words(program, 4, 512, 8, false));
    program[0] = vsetivli(4);
    program[1] = vquad(0x0c, 2, 1);
    program[2] = OPENGPU_SHADER_CEASE;
    assert(!opengpu_vertex_shader_validate_words(program, 3, 512, 8, false));

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
    assert(opengpu_vertex_shader_validate_words(program, 36, 512, 8, false));

    program[0] = vsetivli(4);
    program[1] = vtexsample(2, 1, 1);
    program[2] = OPENGPU_SHADER_CEASE;
    assert(opengpu_shader_validate_words_with_texture(
        program, 3, 288, 8, true, false));
    assert(!opengpu_shader_validate_words(program, 3, 288, 8, false));

    program[1] = vtexsample(2, 1, 1) & ~(1u << 25);
    assert(!opengpu_shader_validate_words_with_texture(
        program, 3, 288, 8, true, false));
    program[1] = vtexsample(3, 2, 1); /* v2 coordinate is undefined */
    assert(!opengpu_shader_validate_words_with_texture(
        program, 3, 288, 8, true, false));
    program[0] = vtexsample(2, 1, 1); /* VL was not configured */
    program[1] = OPENGPU_SHADER_CEASE;
    assert(!opengpu_shader_validate_words_with_texture(
        program, 2, 288, 8, true, false));

    program[0] = vsetivli(4);
    program[1] = addi(5, 1, 96);
    program[2] = vle32(1, 5);
    program[3] = vquad(0x0c, 2, 1);
    program[4] = OPENGPU_SHADER_CEASE;
    assert(opengpu_shader_validate_words(program, 5, 288, 8, false));
    program[3] = vquad(0x0d, 2, 1);
    assert(opengpu_shader_validate_words(program, 5, 288, 8, false));
    program[3] = vquad(0x0d, 2, 1) & ~(1u << 25); /* masked */
    assert(!opengpu_shader_validate_words(program, 5, 288, 8, false));
    program[3] = vquad(0x0c, 2, 1) | 1u << 15; /* reserved rs1 */
    assert(!opengpu_shader_validate_words(program, 5, 288, 8, false));
    program[3] = vquad(0x0c, 2, 3); /* undefined source */
    assert(!opengpu_shader_validate_words(program, 5, 288, 8, false));

    for (i = 0; i < sizeof(branch_forms) / sizeof(branch_forms[0]); i++) {
        program[0] = branch(branch_forms[i], 0, 0, 4);
        program[1] = OPENGPU_SHADER_CEASE;
        assert(opengpu_shader_validate_words(program, 2, 288, 8, false));
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
        assert(opengpu_shader_validate_words(program, 8, 288, 8, false));
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
                assert(opengpu_shader_validate_words(
                    program, 5, 288, 8, false));
                assert(opengpu_vertex_shader_validate_words(
                    
                    program, 5, 512, 8, false));;

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
        /* Reserved operand forms remain rejected under a mask. */
        const unsigned int excluded[][2] = {
            { 0x02, 3 }, /* vsub.vi */
            { 0x0e, 1 }, /* vslideup has no OPFVV encoding */
            { 0x18, 2 }, /* comparison reduction form */
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

    /* OPFRED sums. vs1 is the seed register and vd keeps its old value in
     * every element but 0, so both must be defined. */
    program[0] = vsetivli(4);
    program[1] = vle32(2, 1);
    program[2] = vle32(3, 1);
    program[3] = vle32(4, 1); /* v4 seeds vfredusum.vs v3, v2, v4 */
    program[4] = vle32(6, 1); /* v6 is the vfredosum destination */
    program[5] = vector_alu(0x01, 1, 3, 2, 4);
    program[6] = vector_alu(0x03, 1, 6, 2, 4); /* vfredosum.vs v6, v2, v4 */
    program[7] = OPENGPU_SHADER_CEASE;
    assert(opengpu_compute_shader_validate_words(program, 8, 64, 4));
    program[5] = vector_alu(0x01, 1, 3, 2, 5); /* v5 undefined seed */
    assert(!opengpu_compute_shader_validate_words(program, 8, 64, 4));
    program[5] = vector_alu(0x01, 1, 5, 2, 4); /* v5 undefined destination */
    assert(!opengpu_compute_shader_validate_words(program, 8, 64, 4));
    program[5] = vector_alu(0x01, 1, 3, 2, 4) & ~(1u << 25); /* no v0 */
    assert(!opengpu_compute_shader_validate_words(program, 8, 64, 4));
    /* The standard vfredmin.vs encoding is funct6 001010 in OPFVV, which this
     * core spends on vfsgnjx.vv. That collision is deliberate and one-way:
     * the word validates and runs as vfsgnjx.vv, so vfredmin/vfredmax are not
     * implemented and a compiler emitting vfredmin.vs would be misdecoded
     * rather than rejected. */
    program[5] = vector_alu(0x0a, 1, 3, 2, 4);
    assert(opengpu_compute_shader_validate_words(program, 8, 64, 4));

    uint32_t fp_reduce_masked[] = {
        vsetivli(4),
        vector_alu(0x18, 0, 0, 1, 1), /* vmseq.vv v0, v1, v1 */
        vle32(2, 1),
        vle32(3, 1),
        vle32(4, 1),
        vector_alu(0x01, 1, 3, 2, 4) & ~(1u << 25), /* masked vfredusum */
        OPENGPU_SHADER_CEASE,
    };
    assert(opengpu_compute_shader_validate_words(fp_reduce_masked, 7, 64, 4));
    /* A reduction is vector-only, so no scalar-FPU capability is needed and the
     * fragment and vertex profiles admit it too. */
    assert(opengpu_shader_validate_words(fp_reduce_masked, 7, 288, 8, false));
    assert(opengpu_vertex_shader_validate_words(fp_reduce_masked, 7, 512, 8,
                                                  false));
    fp_reduce_masked[1] = vle32(2, 1); /* no v0 */
    assert(!opengpu_compute_shader_validate_words(fp_reduce_masked, 7, 64, 4));
    fp_reduce_masked[1] = vector_alu(0x18, 0, 0, 1, 1);
    fp_reduce_masked[3] = vector_alu(0x18, 0, 0, 1, 1); /* no old v3 */
    assert(!opengpu_compute_shader_validate_words(fp_reduce_masked, 7, 64, 4));
    fp_reduce_masked[3] = vle32(3, 1);
    fp_reduce_masked[5] = vector_alu(0x01, 1, 0, 2, 4) & ~(1u << 25);
    assert(!opengpu_compute_shader_validate_words(fp_reduce_masked, 7, 64, 4));

    program[0] = sw(10, 0); /* input array is read-only */
    program[1] = OPENGPU_SHADER_CEASE;
    assert(!opengpu_shader_validate_words(program, 2, 288, 8, false));

    program[0] = lw(1, 0); /* x1 must remain the kernarg base */
    assert(!opengpu_shader_validate_words(program, 2, 288, 8, false));

    program[0] = 0x0000006f; /* jal/control flow is not yet proven */
    assert(!opengpu_shader_validate_words(program, 2, 288, 8, false));

    program[0] = lw(10, 288); /* out of the bound kernarg range */
    assert(!opengpu_shader_validate_words(program, 2, 288, 8, false));

    program[0] = 0x00108093; /* addi x1, x1, 1 */
    assert(!opengpu_shader_validate_words(program, 2, 288, 8, false));

    program[0] = 0x00000013; /* no CEASE */
    program[1] = 0x00000013;
    assert(!opengpu_shader_validate_words(program, 2, 288, 8, false));

    program[0] = vle32(2, 1); /* vector length was never configured */
    program[1] = OPENGPU_SHADER_CEASE;
    assert(!opengpu_shader_validate_words(program, 2, 288, 8, false));

    program[0] = vsetivli(4);
    program[1] = addi(5, 1, 96);
    program[2] = vse32(2, 5); /* colour input is read-only */
    program[3] = OPENGPU_SHADER_CEASE;
    assert(!opengpu_shader_validate_words(program, 4, 288, 8, false));

    program[1] = addi(5, 1, 276); /* four words cross validity end */
    assert(!opengpu_shader_validate_words(program, 4, 288, 8, false));

    program[0] = sw(0, 256); /* clear one output-valid word: discard */
    program[1] = OPENGPU_SHADER_CEASE;
    assert(opengpu_shader_validate_words(program, 2, 288, 8, false));

    program[0] = vsetivli(4);
    program[1] = addi(5, 1, 256);
    program[2] = vse32(1, 5); /* per-lane output-valid store */
    program[3] = OPENGPU_SHADER_CEASE;
    assert(opengpu_shader_validate_words(program, 4, 288, 8, false));

    program[1] = addi(5, 10, 192); /* unproven scalar base */
    assert(!opengpu_shader_validate_words(program, 4, 288, 8, false));

    program[0] = vsetivli(4);
    program[1] = addi(5, 1, 192);
    program[2] = vse32(1, 5) & ~(1u << 25); /* v0 mask is undefined */
    program[3] = OPENGPU_SHADER_CEASE;
    assert(!opengpu_shader_validate_words(program, 4, 288, 8, false));

    program[0] = vsetivli(4);
    program[1] = vector_alu(0x18, 3, 0, 1, 1); /* vmseq.vi v0,v1,1 */
    program[2] = addi(5, 1, 192);
    program[3] = vse32(1, 5) & ~(1u << 25);
    program[4] = OPENGPU_SHADER_CEASE;
    assert(opengpu_shader_validate_words(program, 5, 288, 8, false));

    program[2] = addi(5, 1, 96);
    program[3] = vle32(2, 5) & ~(1u << 25); /* old v2 is undefined */
    assert(!opengpu_shader_validate_words(program, 5, 288, 8, false));
    program[2] = vector_alu(0x00, 3, 2, 1, 0); /* define v2 */
    program[3] = addi(5, 1, 96);
    program[4] = vle32(2, 5) & ~(1u << 25);
    program[5] = OPENGPU_SHADER_CEASE;
    assert(opengpu_shader_validate_words(program, 6, 288, 8, false));
    program[4] = vle32(0, 5) & ~(1u << 25); /* destination overlaps v0 */
    assert(!opengpu_shader_validate_words(program, 6, 288, 8, false));

    program[0] = vsetivli(0);
    assert(!opengpu_shader_validate_words(program, 4, 288, 8, false));

    program[0] = addi(8, 0, 0); /* destroy trusted localLinearBase */
    for (i = 0; i < 10; i++)
        program[i + 1] = vector_valid[i];
    assert(!opengpu_shader_validate_words(program, 11, 288, 8, false));

    program[0] = sw(10, 192); /* x10 contains stale cross-task data */
    program[1] = OPENGPU_SHADER_CEASE;
    assert(!opengpu_shader_validate_words(program, 2, 288, 8, false));

    program[0] = vsetivli(4);
    program[1] = addi(5, 1, 192);
    program[2] = vse32(2, 5); /* v2 was never defined */
    program[3] = OPENGPU_SHADER_CEASE;
    assert(!opengpu_shader_validate_words(program, 4, 288, 8, false));

    program[0] = vector_alu(0x00, 3, 2, 2, 1); /* no vector config */
    program[1] = OPENGPU_SHADER_CEASE;
    assert(!opengpu_shader_validate_words(program, 2, 288, 8, false));

    program[0] = vsetivli(4);
    program[1] = vector_alu(0x00, 3, 2, 2, 1); /* undefined v2 input */
    program[2] = OPENGPU_SHADER_CEASE;
    assert(!opengpu_shader_validate_words(program, 3, 288, 8, false));

    program[1] = vector_alu(0x01, 0, 2, 1, 1); /* unsupported funct6 */
    assert(!opengpu_shader_validate_words(program, 3, 288, 8, false));

    program[1] = vector_alu(0x02, 3, 2, 1, 1); /* vsub.vi is reserved */
    assert(!opengpu_shader_validate_words(program, 3, 288, 8, false));

    program[1] = vector_alu(0x04, 3, 2, 1, 1); /* vminu.vi is reserved */
    assert(!opengpu_shader_validate_words(program, 3, 288, 8, false));

    program[1] = vector_alu(0x24, 0, 2, 1, 1); /* no integer vv form */
    assert(!opengpu_shader_validate_words(program, 3, 288, 8, false));

    program[1] = vector_alu(0x00, 1, 2, 3, 3); /* vfadd with undefined v3 */
    assert(!opengpu_shader_validate_words(program, 3, 288, 8, false));

    /* FP32 VFUNARY1 (OPFVV funct6=0x13): vs1 encodes vfsqrt/vfrec7/vfrsqrt7/vfclass. */
    {
        const uint32_t vfunary1_valid[] = {
            vsetivli(1),
            addi(5, 1, 0),
            vle32(2, 5),
            0x4e201157u, /* vfsqrt.v v2, v2 */
            0x4e221157u, /* vfrec7.v v2, v2 */
            0x4e229157u, /* vfrsqrt7.v v2, v2 */
            0x4e281157u, /* vfclass.v v2, v2 */
            vse32(2, 5),
            OPENGPU_SHADER_CEASE,
        };
        const uint32_t vfunary1_bad_op[] = {
            vsetivli(1),
            addi(5, 1, 0),
            vle32(2, 5),
            0x4e211157u, /* reserved VFUNARY1 vs1=2 */
            OPENGPU_SHADER_CEASE,
        };
        const uint32_t vfunary1_masked[] = {
            vsetivli(1),
            addi(5, 1, 0),
            vle32(2, 5),
            0x4c201157u, /* vfsqrt masked, v0 undefined */
            OPENGPU_SHADER_CEASE,
        };
        const uint32_t vfunary1_masked_ok[] = {
            vsetivli(1),
            addi(9, 0, 2),
            0x6a14c057u, /* vmsltu.vx v0, v1, x9 */
            addi(5, 1, 0),
            vle32(2, 5),
            0x4c201157u, /* vfsqrt.v v2, v2, v0.t */
            OPENGPU_SHADER_CEASE,
        };

        assert(opengpu_compute_shader_validate_words(
            vfunary1_valid, 9, 64, 1));
        assert(!opengpu_compute_shader_validate_words(
            vfunary1_bad_op, 5, 64, 1));
        assert(!opengpu_compute_shader_validate_words(
            vfunary1_masked, 5, 64, 1));
        assert(opengpu_compute_shader_validate_words(
            vfunary1_masked_ok, 7, 64, 1));
    }

    /* Unmasked OPFVV vfadd/vfsub/vfmul; vs1 is a defined VGPR. */
    {
        const uint32_t opfvv_valid[] = {
            vsetivli(4),
            addi(5, 1, 0),
            vle32(2, 5),
            addi(5, 1, 16),
            vle32(3, 5),
            vector_alu(0x00, 1, 4, 2, 3), /* vfadd.vv v4, v2, v3 */
            vector_alu(0x02, 1, 5, 2, 3), /* vfsub.vv v5, v2, v3 */
            vector_alu(0x24, 1, 6, 2, 3), /* vfmul.vv v6, v2, v3 */
            vector_alu(0x20, 1, 7, 2, 3), /* vfdiv.vv v7, v2, v3 */
            vector_alu(0x04, 1, 8, 2, 3), /* vfmin.vv v8, v2, v3 */
            vector_alu(0x06, 1, 9, 2, 3), /* vfmax.vv v9, v2, v3 */
            vector_alu(0x08, 1, 10, 2, 3), /* vfsgnj.vv v10, v2, v3 */
            vector_alu(0x09, 1, 11, 2, 3), /* vfsgnjn.vv v11, v2, v3 */
            vector_alu(0x0a, 1, 12, 2, 3), /* vfsgnjx.vv v12, v2, v3 */
            vector_alu(0x18, 1, 0, 2, 3), /* vmfeq.vv v0, v2, v3 */
            vector_alu(0x19, 1, 0, 2, 3), /* vmfle.vv v0, v2, v3 */
            vector_alu(0x1b, 1, 0, 2, 3), /* vmflt.vv v0, v2, v3 */
            vector_alu(0x1c, 1, 0, 2, 3), /* vmfne.vv v0, v2, v3 */
            addi(5, 1, 32),
            vse32(4, 5),
            OPENGPU_SHADER_CEASE,
        };
        const uint32_t opfvv_bad_op[] = {
            vsetivli(4),
            addi(5, 1, 0),
            vle32(2, 5),
            vector_alu(0x1d, 1, 0, 2, 2), /* vmfgt is FVF-only */
            OPENGPU_SHADER_CEASE,
        };
        const uint32_t opfvv_masked[] = {
            vsetivli(4),
            addi(5, 1, 0),
            vle32(2, 5),
            vector_alu(0x00, 1, 3, 2, 2) & ~(1u << 25), /* no v0, no old vd */
            OPENGPU_SHADER_CEASE,
        };
        const uint32_t opfvv_undef_vs1[] = {
            vsetivli(4),
            addi(5, 1, 0),
            vle32(2, 5),
            vector_alu(0x00, 1, 3, 2, 4), /* v4 undefined */
            OPENGPU_SHADER_CEASE,
        };

        assert(opengpu_compute_shader_validate_words(
            opfvv_valid, 21, 64, 4));
        assert(!opengpu_compute_shader_validate_words(
            opfvv_bad_op, 5, 64, 4));
        assert(!opengpu_compute_shader_validate_words(
            opfvv_masked, 5, 64, 4));
        assert(!opengpu_compute_shader_validate_words(
            opfvv_undef_vs1, 5, 64, 4));
    }

    /* Unmasked OPFVV fused FMA; old vd must be defined. */
    {
        const uint32_t opfvv_fma_valid[] = {
            vsetivli(4),
            addi(5, 1, 0),
            vle32(2, 5),
            addi(5, 1, 16),
            vle32(3, 5),
            addi(5, 1, 32),
            vle32(4, 5),
            vector_alu(0x28, 1, 4, 2, 3), /* vfmadd.vv v4, v3, v2 */
            vector_alu(0x2c, 1, 4, 2, 3), /* vfmacc.vv v4, v3, v2 */
            addi(5, 1, 48),
            vse32(4, 5),
            OPENGPU_SHADER_CEASE,
        };
        const uint32_t opfvv_fma_undef_vd[] = {
            vsetivli(4),
            addi(5, 1, 0),
            vle32(2, 5),
            addi(5, 1, 16),
            vle32(3, 5),
            vector_alu(0x28, 1, 4, 2, 3), /* v4 undefined */
            OPENGPU_SHADER_CEASE,
        };
        const uint32_t opfvv_fma_masked[] = {
            vsetivli(4),
            addi(5, 1, 0),
            vle32(2, 5),
            addi(5, 1, 16),
            vle32(3, 5),
            addi(5, 1, 32),
            vle32(4, 5),
            vector_alu(0x28, 1, 4, 2, 3) & ~(1u << 25), /* v0 undefined */
            OPENGPU_SHADER_CEASE,
        };

        assert(opengpu_compute_shader_validate_words(
            opfvv_fma_valid, 12, 80, 4));
        assert(!opengpu_compute_shader_validate_words(
            opfvv_fma_undef_vd, 7, 64, 4));
        assert(!opengpu_compute_shader_validate_words(
            opfvv_fma_masked, 9, 64, 4));
    }

    /* OPFVV FMA ignores integer narrowing pair rules: odd vs2, vd == vs2. */
    {
        const uint32_t opfvv_fma_odd_vs2[] = {
            vsetivli(4),
            addi(5, 1, 0),
            vle32(3, 5),
            vector_alu(0x2c, 1, 3, 3, 3), /* vfmacc.vv v3, v3, v3 */
            OPENGPU_SHADER_CEASE,
        };

        assert(opengpu_compute_shader_validate_words(
            opfvv_fma_odd_vs2, 5, 64, 4));
    }

    /* OPFVF reads a scalar FP register that must come from flw imm(x1),
     * and only compute CUs with the scalar FPU admit flw. */
    {
        const uint32_t opfvf_valid[] = {
            vsetivli(4),
            flw(1, 1, 0),
            addi(5, 1, 16),
            vle32(2, 5),
            vector_alu(0x00, 5, 4, 2, 1), /* vfadd.vf v4, v2, f1 */
            vector_alu(0x21, 5, 5, 2, 1), /* vfrdiv.vf v5, v2, f1 */
            vector_alu(0x27, 5, 6, 2, 1), /* vfrsub.vf v6, v2, f1 */
            vector_alu(0x2c, 5, 4, 2, 1), /* vfmacc.vf v4, f1, v2 */
            vector_alu(0x1d, 5, 0, 2, 1), /* vmfgt.vf v0, v2, f1 */
            vector_alu(0x1f, 5, 5, 2, 1) & ~(1u << 25), /* masked vmfge */
            addi(5, 1, 32),
            vse32(4, 5),
            OPENGPU_SHADER_CEASE,
        };
        const uint32_t flw_only[] = {
            flw(1, 1, 0),
            OPENGPU_SHADER_CEASE,
        };
        uint32_t fvf[6];

        assert(fpu_valid(opfvf_valid, 13));
        assert(!opengpu_compute_shader_validate_words(opfvf_valid, 13, 64, 4));
        assert(fpu_valid(flw_only, 2));
        assert(!opengpu_compute_shader_validate_words(flw_only, 2, 64, 4));
        assert(!opengpu_shader_validate_words(flw_only, 2, 288, 8, false));
        assert(!opengpu_vertex_shader_validate_words(
            flw_only, 2, 512, 8, false));
        /* The graphics shader CU is built with the FP backend whenever
         * GPU_CAP_COMPUTE_SCALAR_FPU is advertised, so the same capability
         * admits flw for the fragment and vertex profiles too. This is the
         * path a `uniform float` lowers to. */
        assert(opengpu_shader_validate_words(flw_only, 2, 288, 8, true));
        assert(opengpu_vertex_shader_validate_words(flw_only, 2, 512, 8, true));
        assert(opengpu_shader_validate_words_with_texture(
            
            flw_only, 2, 288, 8, false, true));
        /* A .vf operand still needs a defined f-register, so dropping the flw
         * must stay rejected even with the capability set. */
        {
            const uint32_t vf_no_flw[] = {
                vsetivli(4),
                vector_alu(0x00, 5, 4, 2, 1), /* vfadd.vf v4, v2, f1 */
                OPENGPU_SHADER_CEASE,
            };
            /* Rejected by the defined-register analysis itself, not only by
             * the missing capability, so the expectation is simply invalid. */
            assert(!opengpu_shader_validate_words(vf_no_flw, 3, 288, 8, true));
            assert(!opengpu_vertex_shader_validate_words(
                vf_no_flw, 3, 512, 8, true));
        }
        /* masked vmfge needs a defined old vd */
        fvf[0] = vsetivli(4);
        fvf[1] = flw(1, 1, 0);
        fvf[2] = vle32(2, 1);
        fvf[3] = vector_alu(0x1d, 5, 0, 2, 1);
        fvf[4] = vector_alu(0x1f, 5, 7, 2, 1) & ~(1u << 25);
        fvf[5] = OPENGPU_SHADER_CEASE;
        assert(!fpu_valid(fvf, 6));
        /* undefined scalar FP operand */
        fvf[1] = addi(5, 1, 0);
        fvf[3] = vector_alu(0x00, 5, 4, 2, 1);
        fvf[4] = OPENGPU_SHADER_CEASE;
        assert(!fpu_valid(fvf, 5));
        /* flw defines only its own destination */
        fvf[1] = flw(2, 1, 0);
        assert(!fpu_valid(fvf, 5));
        fvf[1] = flw(1, 1, 0);
        assert(fpu_valid(fvf, 5));
        /* masked OPFVF still needs v0 and the old destination */
        fvf[3] = vector_alu(0x00, 5, 4, 2, 1) & ~(1u << 25);
        assert(!fpu_valid(fvf, 5));
        /* vfmv.v.f broadcasts the defined f-register. VFUNARY is not FVF. */
        fvf[3] = vector_alu(0x17, 5, 4, 2, 1);
        assert(fpu_valid(fvf, 5));
        fvf[3] = vector_alu(0x13, 5, 4, 2, 1);
        assert(!fpu_valid(fvf, 5));
        /* FVF FMA reads the old destination */
        fvf[3] = vector_alu(0x28, 5, 4, 2, 1);
        assert(!fpu_valid(fvf, 5));
        fvf[3] = vector_alu(0x28, 5, 2, 2, 1);
        assert(fpu_valid(fvf, 5));
        /* flw stays an aligned x1-relative kernarg read */
        fvf[3] = vector_alu(0x00, 5, 4, 2, 1);
        fvf[1] = flw(1, 1, 60);
        assert(fpu_valid(fvf, 5));
        fvf[1] = flw(1, 1, 64);
        assert(!fpu_valid(fvf, 5));
        fvf[1] = flw(1, 1, 2);
        assert(!fpu_valid(fvf, 5));
        fvf[1] = flw(1, 1, -4);
        assert(!fpu_valid(fvf, 5));
        fvf[1] = flw(1, 5, 0);
        assert(!fpu_valid(fvf, 5));
    }

    /* flw on one branch path leaves f1 undefined after the merge. */
    {
        const uint32_t fvf_branch[] = {
            vsetivli(4),
            branch(0, 0, 0, 8), /* skip the flw */
            flw(1, 1, 0),
            vle32(2, 1),
            vector_alu(0x00, 5, 4, 2, 1),
            OPENGPU_SHADER_CEASE,
        };

        assert(!fpu_valid(fvf_branch, 6));
    }

    /* vfmerge.vfm needs v0, the old destination, and vs2. vfmv.v.f does not. */
    {
        const uint32_t masked[] = {
            vsetivli(4),
            flw(1, 1, 0),
            addi(9, 0, 2),
            0x6a14c057u, /* vmsltu.vx v0, v1, x9 */
            vle32(2, 1),
            vle32(4, 1),
            vector_alu(0x17, 5, 4, 2, 1) & ~(1u << 25),
            vse32(4, 1),
            OPENGPU_SHADER_CEASE,
        };
        const uint32_t no_vs2[] = {
            vsetivli(4),
            flw(1, 1, 0),
            addi(9, 0, 2),
            0x6a14c057u,
            vle32(4, 1),
            vector_alu(0x17, 5, 4, 2, 1) & ~(1u << 25),
            OPENGPU_SHADER_CEASE,
        };
        const uint32_t no_old[] = {
            vsetivli(4),
            flw(1, 1, 0),
            addi(9, 0, 2),
            0x6a14c057u,
            vle32(2, 1),
            vector_alu(0x17, 5, 4, 2, 1) & ~(1u << 25),
            OPENGPU_SHADER_CEASE,
        };
        const uint32_t no_mask[] = {
            vsetivli(4),
            flw(1, 1, 0),
            vle32(2, 1),
            vle32(4, 1),
            vector_alu(0x17, 5, 4, 2, 1) & ~(1u << 25),
            OPENGPU_SHADER_CEASE,
        };
        const uint32_t no_scalar[] = {
            vsetivli(4),
            vector_alu(0x17, 5, 4, 0, 1), /* vfmv.v.f, f1 undefined */
            OPENGPU_SHADER_CEASE,
        };

        assert(fpu_valid(masked, 9));
        assert(!opengpu_compute_shader_validate_words(masked, 9, 64, 4));
        assert(!fpu_valid(no_vs2, 7));
        assert(!fpu_valid(no_old, 7));
        assert(!fpu_valid(no_mask, 6));
        assert(!fpu_valid(no_scalar, 3));
    }

    /* vmerge needs v0, vs2, and the old destination. vmv ignores vs2=v0. */
    {
        const uint32_t merge[] = {
            vsetivli(4),
            addi(9, 0, 2),
            0x6a14c057u, /* vmsltu.vx v0, v1, x9 */
            vle32(2, 1),
            vle32(3, 1),
            vle32(4, 1),
            vector_alu(0x17, 0, 4, 2, 3) & ~(1u << 25),
            vse32(4, 1),
            vector_alu(0x17, 4, 5, 0, 9), /* vmv.v.x v5, x9 */
            vector_alu(0x17, 3, 6, 0, 31), /* vmv.v.i v6, -1 */
            vector_alu(0x17, 0, 7, 0, 1), /* vmv.v.v v7, v1 */
            OPENGPU_SHADER_CEASE,
        };
        const uint32_t no_vs2[] = {
            vsetivli(4),
            addi(9, 0, 2),
            0x6a14c057u,
            vle32(3, 1),
            vle32(4, 1),
            vector_alu(0x17, 0, 4, 2, 3) & ~(1u << 25),
            OPENGPU_SHADER_CEASE,
        };
        const uint32_t no_old[] = {
            vsetivli(4),
            addi(9, 0, 2),
            0x6a14c057u,
            vle32(2, 1),
            vle32(3, 1),
            vector_alu(0x17, 0, 4, 2, 3) & ~(1u << 25),
            OPENGPU_SHADER_CEASE,
        };
        const uint32_t no_mask[] = {
            vsetivli(4),
            vle32(2, 1),
            vle32(3, 1),
            vle32(4, 1),
            vector_alu(0x17, 0, 4, 2, 3) & ~(1u << 25),
            OPENGPU_SHADER_CEASE,
        };
        const uint32_t reserved_vs2[] = {
            vsetivli(4),
            vle32(1, 1),
            vector_alu(0x17, 0, 4, 2, 1), /* vmv.v.v with vs2 != v0 */
            OPENGPU_SHADER_CEASE,
        };
        const uint32_t undefined_scalar[] = {
            vsetivli(4),
            vector_alu(0x17, 4, 5, 0, 10), /* vmv.v.x x10 */
            OPENGPU_SHADER_CEASE,
        };

        assert(opengpu_compute_shader_validate_words(merge, 12, 64, 4));
        assert(!opengpu_compute_shader_validate_words(no_vs2, 7, 64, 4));
        assert(!opengpu_compute_shader_validate_words(no_old, 7, 64, 4));
        assert(!opengpu_compute_shader_validate_words(no_mask, 6, 64, 4));
        assert(!opengpu_compute_shader_validate_words(reserved_vs2, 4, 64, 4));
        assert(!opengpu_compute_shader_validate_words(
            undefined_scalar, 3, 64, 4));
        program[0] = vsetivli(4);
        program[1] = vector_alu(0x17, 0, 0, 1, 1) & ~(1u << 25);
        program[2] = OPENGPU_SHADER_CEASE;
        assert(!opengpu_compute_shader_validate_words(program, 3, 64, 4));
    }

    /* Mask logical combines two defined masks and may write v0. */
    {
        unsigned int funct6;

        program[0] = vsetivli(4);
        program[1] = addi(9, 0, 2);
        program[2] = vector_alu(0x1a, 4, 4, 1, 9); /* vmsltu.vx v4, v1, x9 */
        program[3] = addi(10, 0, 1);
        program[4] = vector_alu(0x1a, 4, 5, 1, 10);
        program[5] = vector_alu(0x19, 2, 0, 4, 5); /* vmand.mm v0, v4, v5 */
        program[6] = OPENGPU_SHADER_CEASE;
        assert(opengpu_compute_shader_validate_words(program, 7, 64, 4));
        for (funct6 = 0x18; funct6 <= 0x1f; funct6++) {
            program[5] = vector_alu(funct6, 2, 0, 4, 5);
            assert(opengpu_compute_shader_validate_words(program, 7, 64, 4));
            program[5] &= ~(1u << 25); /* masked encoding is reserved */
            assert(!opengpu_compute_shader_validate_words(program, 7, 64, 4));
        }
        program[5] = vector_alu(0x19, 2, 0, 6, 5); /* v6 undefined */
        assert(!opengpu_compute_shader_validate_words(program, 7, 64, 4));
    }

    /* vmv.s.x writes element 0. vmv.x.s reads it into an integer other than x1. */
    {
        uint32_t move[8] = {
            vsetivli(4),
            addi(9, 0, 0x5a),
            vector_alu(0x10, 6, 2, 0, 9), /* vmv.s.x v2, x9 */
            vector_alu(0x10, 2, 10, 2, 0), /* vmv.x.s x10, v2 */
            sw(10, 32),
            OPENGPU_SHADER_CEASE,
        };
        uint32_t writes_x1[6] = {
            vsetivli(4),
            addi(9, 0, 0x5a),
            vector_alu(0x10, 6, 2, 0, 9),
            vector_alu(0x10, 2, 1, 2, 0), /* rd = x1 */
            OPENGPU_SHADER_CEASE,
        };
        uint32_t no_vector[6] = {
            vsetivli(4),
            addi(9, 0, 1),
            vector_alu(0x10, 2, 10, 4, 0), /* v4 undefined */
            OPENGPU_SHADER_CEASE,
        };
        uint32_t no_scalar[5] = {
            vsetivli(4),
            vector_alu(0x10, 6, 2, 0, 10), /* x10 undefined */
            OPENGPU_SHADER_CEASE,
        };
        uint32_t still_undefined_vector[8] = {
            vsetivli(4),
            addi(9, 0, 0x5a),
            vector_alu(0x10, 6, 2, 0, 9),
            vector_alu(0x10, 2, 10, 2, 0),
            vector_alu(0x00, 0, 3, 10, 1), /* v10 was not written */
            OPENGPU_SHADER_CEASE,
        };

        assert(opengpu_compute_shader_validate_words(move, 6, 64, 4));
        assert(!opengpu_compute_shader_validate_words(writes_x1, 5, 64, 4));
        assert(!opengpu_compute_shader_validate_words(no_vector, 4, 64, 4));
        assert(!opengpu_compute_shader_validate_words(no_scalar, 3, 64, 4));
        assert(!opengpu_compute_shader_validate_words(
            still_undefined_vector, 6, 64, 4));
        program[0] = vsetivli(4);
        program[1] = addi(9, 0, 1);
        program[2] = vector_alu(0x00, 3, 2, 1, 0);
        program[3] = vector_alu(0x10, 2, 10, 2, 1); /* vs1 != 0 */
        program[4] = OPENGPU_SHADER_CEASE;
        assert(!opengpu_compute_shader_validate_words(program, 5, 64, 4));
        program[3] = vector_alu(0x10, 2, 10, 2, 0) & ~(1u << 25);
        assert(!opengpu_compute_shader_validate_words(program, 5, 64, 4));
        program[3] = vector_alu(0x10, 6, 2, 1, 9); /* vs2 != v0 */
        assert(!opengpu_compute_shader_validate_words(program, 5, 64, 4));
        program[3] = vector_alu(0x10, 6, 2, 0, 9) & ~(1u << 25);
        assert(!opengpu_compute_shader_validate_words(program, 5, 64, 4));
    }

    /* vfmv.s.f writes element 0. vfmv.f.s reads it into an f-register. */
    {
        uint32_t move[8] = {
            vsetivli(4),
            flw(1, 1, 0),
            vector_alu(0x10, 5, 2, 0, 1), /* vfmv.s.f v2, f1 */
            vector_alu(0x10, 1, 2, 2, 0), /* vfmv.f.s f2, v2 */
            fsw(2, 1, 32),
            OPENGPU_SHADER_CEASE,
        };
        uint32_t no_float[5] = {
            vsetivli(4),
            vector_alu(0x10, 5, 2, 0, 1), /* f1 undefined */
            OPENGPU_SHADER_CEASE,
        };
        uint32_t no_vector[6] = {
            vsetivli(4),
            flw(1, 1, 0),
            vector_alu(0x10, 1, 3, 4, 0), /* v4 undefined */
            OPENGPU_SHADER_CEASE,
        };
        uint32_t still_undefined_vector[8] = {
            vsetivli(4),
            flw(1, 1, 0),
            vector_alu(0x10, 5, 2, 0, 1),
            vector_alu(0x10, 1, 3, 2, 0), /* f3, not v3 */
            vector_alu(0x00, 0, 4, 3, 1), /* v3 was not written */
            OPENGPU_SHADER_CEASE,
        };

        assert(fpu_valid(move, 6));
        assert(!opengpu_compute_shader_validate_words(move, 6, 64, 4));
        assert(!fpu_valid(no_float, 3));
        assert(!fpu_valid(no_vector, 4));
        assert(!fpu_valid(still_undefined_vector, 6));
        program[0] = vsetivli(4);
        program[1] = flw(1, 1, 0);
        program[2] = vector_alu(0x00, 3, 2, 1, 0);
        program[3] = vector_alu(0x10, 1, 2, 2, 1); /* vs1 != 0 */
        program[4] = OPENGPU_SHADER_CEASE;
        assert(!fpu_valid(program, 5));
        program[3] = vector_alu(0x10, 1, 2, 2, 0) & ~(1u << 25);
        assert(!fpu_valid(program, 5));
        program[3] = vector_alu(0x10, 5, 2, 1, 1); /* vs2 != v0 */
        assert(!fpu_valid(program, 5));
        program[3] = vector_alu(0x10, 5, 2, 0, 1) & ~(1u << 25);
        assert(!fpu_valid(program, 5));
    }

    /* vslide1up inserts the scalar at element 0. vslide1down inserts it
     * at vl-1. vslide1up needs a defined destination and rejects overlap. */
    {
        uint32_t slide[8];

        slide[0] = vsetivli(3);
        slide[1] = addi(10, 0, 0x5a);
        slide[2] = vector_alu(0x00, 3, 3, 1, 0); /* vadd.vi v3, v1, 0 */
        slide[3] = vector_alu(0x0e, 6, 3, 1, 10); /* vslide1up.vx v3, v1, x10 */
        slide[4] = vector_alu(0x0f, 6, 4, 1, 10); /* vslide1down.vx v4, v1, x10 */
        slide[5] = OPENGPU_SHADER_CEASE;
        assert(opengpu_compute_shader_validate_words(slide, 6, 64, 4));
        assert(opengpu_shader_validate_words(slide, 6, 288, 8, false));
        assert(opengpu_vertex_shader_validate_words(slide, 6, 512, 8, false));
        slide[3] = vector_alu(0x0e, 6, 3, 1, 11); /* x11 undefined */
        assert(!opengpu_compute_shader_validate_words(slide, 6, 64, 4));
        slide[3] = vector_alu(0x0e, 6, 3, 4, 10); /* v4 undefined */
        assert(!opengpu_compute_shader_validate_words(slide, 6, 64, 4));
        slide[3] = vector_alu(0x0e, 6, 5, 1, 10); /* v5 undefined destination */
        assert(!opengpu_compute_shader_validate_words(slide, 6, 64, 4));
        slide[3] = vector_alu(0x0e, 6, 3, 3, 10); /* vd overlaps vs2 */
        assert(!opengpu_compute_shader_validate_words(slide, 6, 64, 4));
        slide[3] = vector_alu(0x0f, 6, 3, 3, 10); /* vslide1down may overlap */
        assert(opengpu_compute_shader_validate_words(slide, 6, 64, 4));
        slide[3] = vector_alu(0x0e, 6, 3, 1, 10) & ~(1u << 25); /* no v0 */
        assert(!opengpu_compute_shader_validate_words(slide, 6, 64, 4));

        slide[2] = vector_alu(0x18, 0, 0, 1, 1); /* vmseq.vv v0, v1, v1 */
        slide[3] = vector_alu(0x0e, 6, 3, 1, 10) & ~(1u << 25);
        slide[4] = OPENGPU_SHADER_CEASE;
        assert(!opengpu_compute_shader_validate_words(slide, 5, 64, 4)); /* no old v3 */

        slide[2] = vector_alu(0x00, 3, 3, 1, 0); /* define v3 */
        slide[3] = vector_alu(0x00, 3, 4, 1, 0); /* define v4 */
        slide[4] = vector_alu(0x18, 0, 0, 1, 1); /* define v0 */
        slide[5] = vector_alu(0x0e, 6, 3, 1, 10) & ~(1u << 25);
        slide[6] = vector_alu(0x0f, 6, 4, 1, 10) & ~(1u << 25);
        slide[7] = OPENGPU_SHADER_CEASE;
        assert(opengpu_compute_shader_validate_words(slide, 8, 64, 4));
        slide[5] = vector_alu(0x0e, 6, 0, 1, 10) & ~(1u << 25); /* masked vd=v0 */
        assert(!opengpu_compute_shader_validate_words(slide, 8, 64, 4));
    }

    /* Masked non-compare FP keeps the old destination on clear lanes. */
    {
        const unsigned int opfvv_funct6[] = {
            0x00, 0x02, 0x04, 0x06, 0x08, 0x09, 0x0a, 0x12,
            0x20, 0x24, 0x28, 0x29, 0x2a, 0x2b, 0x2c, 0x2d, 0x2e, 0x2f,
        };
        const unsigned int opfvf_funct6[] = {
            0x00, 0x02, 0x04, 0x06, 0x08, 0x09, 0x0a,
            0x20, 0x21, 0x24, 0x27,
            0x28, 0x29, 0x2a, 0x2b, 0x2c, 0x2d, 0x2e, 0x2f,
        };
        const unsigned int unary1_vs1[] = { 0, 4, 5, 16 };
        unsigned int n;

        for (n = 0; n < sizeof(opfvv_funct6) / sizeof(opfvv_funct6[0]); n++) {
            program[0] = vsetivli(4);
            program[1] = vector_alu(0x18, 0, 0, 1, 1); /* vmseq.vv v0, v1, v1 */
            program[2] = vector_alu(0x00, 3, 3, 1, 0); /* vadd.vi v3, v1, 0 */
            program[3] = vector_alu(opfvv_funct6[n], 1, 3, 1, 1) & ~(1u << 25);
            program[4] = OPENGPU_SHADER_CEASE;
            assert(opengpu_compute_shader_validate_words(program, 5, 64, 4));
        }
        for (n = 0; n < sizeof(unary1_vs1) / sizeof(unary1_vs1[0]); n++) {
            program[3] = vector_alu(0x13, 1, 3, 1, unary1_vs1[n]) & ~(1u << 25);
            assert(opengpu_compute_shader_validate_words(program, 5, 64, 4));
        }
        program[1] = addi(9, 0, 2); /* v0 stays undefined */
        program[3] = vector_alu(0x00, 1, 3, 1, 1) & ~(1u << 25);
        assert(!opengpu_compute_shader_validate_words(program, 5, 64, 4));
        program[1] = vector_alu(0x18, 0, 0, 1, 1);
        program[2] = addi(5, 1, 0); /* old vd stays undefined */
        assert(!opengpu_compute_shader_validate_words(program, 5, 64, 4));
        program[2] = vector_alu(0x00, 3, 3, 1, 0);
        program[3] &= ~(31u << 7); /* masked vd=v0 */
        assert(!opengpu_compute_shader_validate_words(program, 5, 64, 4));

        for (n = 0; n < sizeof(opfvf_funct6) / sizeof(opfvf_funct6[0]); n++) {
            program[0] = vsetivli(4);
            program[1] = flw(1, 1, 0);
            program[2] = vector_alu(0x18, 0, 0, 1, 1);
            program[3] = vector_alu(0x00, 3, 3, 1, 0);
            program[4] = vector_alu(opfvf_funct6[n], 5, 3, 1, 1) & ~(1u << 25);
            program[5] = OPENGPU_SHADER_CEASE;
            assert(fpu_valid(program, 6));
            assert(!opengpu_compute_shader_validate_words(program, 6, 64, 4));
        }
    }

    /* SEW=32 vfcvt. vs1 is the opcode, so v0 need not be a defined vector. */
    {
        const uint32_t cvt[] = {
            vsetivli(4),
            vle32(2, 1),
            vfcvt(0, 3, 2), /* vfcvt.xu.f.v */
            vfcvt(1, 4, 2), /* vfcvt.x.f.v */
            vfcvt(2, 5, 2), /* vfcvt.f.xu.v */
            vfcvt(3, 6, 2), /* vfcvt.f.x.v */
            vfcvt(6, 7, 2), /* vfcvt.rtz.xu.f.v */
            vfcvt(7, 8, 2), /* vfcvt.rtz.x.f.v */
            vfcvt(4, 9, 2), /* vfcvt.rtz.f.xu.v */
            vfcvt(5, 10, 2), /* vfcvt.rtz.f.x.v */
            vse32(3, 1),
            OPENGPU_SHADER_CEASE,
        };
        const uint32_t bad_op[] = {
            vsetivli(4),
            vle32(2, 1),
            vfcvt(8, 3, 2), /* float-to-float conversion is unimplemented */
            OPENGPU_SHADER_CEASE,
        };
        const uint32_t undef_src[] = {
            vsetivli(4),
            vfcvt(1, 3, 2),
            OPENGPU_SHADER_CEASE,
        };
        const uint32_t masked_cvt[] = {
            vsetivli(4),
            addi(9, 0, 2),
            0x6a14c057u, /* vmsltu.vx v0, v1, x9 */
            vle32(2, 1),
            vle32(3, 1),
            vfcvt(1, 3, 2) & ~(1u << 25),
            OPENGPU_SHADER_CEASE,
        };

        const uint32_t inplace[] = {
            vsetivli(4),
            vle32(2, 1),
            vfcvt(1, 2, 2), /* vfcvt.x.f.v v2, v2 */
            OPENGPU_SHADER_CEASE,
        };
        const uint32_t masked_no_v0[] = {
            vsetivli(4),
            vle32(2, 1),
            vle32(3, 1),
            vfcvt(1, 3, 2) & ~(1u << 25),
            OPENGPU_SHADER_CEASE,
        };
        const uint32_t masked_vd0[] = {
            vsetivli(4),
            addi(9, 0, 2),
            0x6a14c057u,
            vle32(2, 1),
            vle32(3, 1),
            (vfcvt(1, 0, 2) & ~(1u << 25)), /* destination overlaps v0 */
            OPENGPU_SHADER_CEASE,
        };

        assert(opengpu_compute_shader_validate_words(cvt, 12, 64, 4));
        assert(!opengpu_compute_shader_validate_words(bad_op, 4, 64, 4));
        assert(!opengpu_compute_shader_validate_words(undef_src, 3, 64, 4));
        assert(opengpu_compute_shader_validate_words(masked_cvt, 7, 64, 4));
        assert(opengpu_compute_shader_validate_words(inplace, 4, 64, 4));
        assert(!opengpu_compute_shader_validate_words(masked_no_v0, 5, 64, 4));
        assert(!opengpu_compute_shader_validate_words(masked_vd0, 7, 64, 4));
    }

    /* fsw stores a defined f-register through S-type imm(x1) into the
     * profile output window, and only when the scalar FPU is enabled. */
    {
        const uint32_t compute_fsw[] = {
            flw(1, 1, 0),
            fsw(1, 1, 4),
            OPENGPU_SHADER_CEASE,
        };
        const uint32_t compute_wide[] = {
            flw(1, 1, 0),
            fsw(1, 1, 0x7fc),
            OPENGPU_SHADER_CEASE,
        };
        const uint32_t compute_neg[] = {
            flw(1, 1, 0),
            fsw(1, 1, -4),
            OPENGPU_SHADER_CEASE,
        };
        const uint32_t fragment_fsw[] = {
            flw(1, 1, 0),
            fsw(1, 1, 192),
            OPENGPU_SHADER_CEASE,
        };
        const uint32_t fragment_input[] = {
            flw(1, 1, 0),
            fsw(1, 1, 0),
            OPENGPU_SHADER_CEASE,
        };
        const uint32_t fragment_last[] = {
            flw(1, 1, 0),
            fsw(1, 1, 284),
            OPENGPU_SHADER_CEASE,
        };
        const uint32_t fragment_past[] = {
            flw(1, 1, 288),
            fsw(1, 1, 288),
            OPENGPU_SHADER_CEASE,
        };
        const uint32_t vertex_fsw[] = {
            flw(1, 1, 0),
            fsw(1, 1, 256),
            OPENGPU_SHADER_CEASE,
        };
        const uint32_t vertex_input[] = {
            flw(1, 1, 0),
            fsw(1, 1, 192),
            OPENGPU_SHADER_CEASE,
        };
        const uint32_t undef_f[] = {
            flw(2, 1, 0),
            fsw(1, 1, 4),
            OPENGPU_SHADER_CEASE,
        };
        const uint32_t integer_src[] = {
            lw(10, 0),
            fsw(10, 1, 4),
            OPENGPU_SHADER_CEASE,
        };
        const uint32_t unaligned[] = {
            flw(1, 1, 0),
            fsw(1, 1, 2),
            OPENGPU_SHADER_CEASE,
        };
        const uint32_t other_base[] = {
            flw(1, 1, 0),
            fsw(1, 5, 4),
            OPENGPU_SHADER_CEASE,
        };
        const uint32_t fsw_branch[] = {
            branch(0, 0, 0, 8), /* skip the flw */
            flw(1, 1, 0),
            fsw(1, 1, 4),
            OPENGPU_SHADER_CEASE,
        };

        assert(fpu_valid(compute_fsw, 3));
        assert(!opengpu_compute_shader_validate_words(compute_fsw, 3, 64, 4));
        assert(opengpu_compute_shader_validate_words_fpu(
            compute_wide, 3, 0x800, 4, true));
        assert(!opengpu_compute_shader_validate_words_fpu(
            compute_neg, 3, 0x800, 4, true));
        assert(opengpu_shader_validate_words(fragment_fsw, 3, 288, 8, true));
        assert(opengpu_shader_validate_words_with_texture(
            fragment_fsw, 3, 288, 8, false, true));
        assert(!opengpu_shader_validate_words(fragment_fsw, 3, 288, 8, false));
        assert(!opengpu_shader_validate_words(fragment_input, 3, 288, 8, true));
        assert(opengpu_shader_validate_words(fragment_last, 3, 320, 8, true));
        assert(!opengpu_shader_validate_words(fragment_past, 3, 320, 8, true));
        assert(opengpu_vertex_shader_validate_words(vertex_fsw, 3, 512, 8, true));
        assert(!opengpu_vertex_shader_validate_words(
            vertex_fsw, 3, 512, 8, false));
        assert(!opengpu_vertex_shader_validate_words(
            vertex_input, 3, 512, 8, true));
        assert(!fpu_valid(undef_f, 3));
        assert(!fpu_valid(integer_src, 3));
        assert(!fpu_valid(unaligned, 3));
        assert(!fpu_valid(other_base, 3));
        assert(!fpu_valid(fsw_branch, 4));
    }

    /* Scalar FP-to-FP arithmetic defines its destination f-register. */
    {
        const uint32_t fp_arith[] = {
            flw(1, 1, 0),
            flw(2, 1, 4),
            fp_op(0x00, 0, 3, 1, 2), /* fadd.s f3, f1, f2 */
            fp_op(0x01, 7, 4, 2, 1), /* fsub.s f4, f2, f1, dyn */
            fp_op(0x02, 0, 5, 1, 2), /* fmul.s f5, f1, f2 */
            fp_op(0x04, 1, 6, 1, 2), /* fsgnjn.s f6, f1, f2 */
            fp_op(0x05, 0, 7, 1, 2), /* fmin.s f7, f1, f2 */
            fp_fma(0x43, 0, 8, 1, 2, 1), /* fmadd.s f8, f1, f2, f1 */
            fsw(8, 1, 8),
            OPENGPU_SHADER_CEASE,
        };
        const uint32_t chained[] = {
            flw(1, 1, 0),
            flw(2, 1, 4),
            fp_op(0x00, 0, 3, 1, 2),
            fp_op(0x00, 0, 4, 3, 1), /* fadd.s f4, f3, f1 */
            fsw(4, 1, 8),
            OPENGPU_SHADER_CEASE,
        };
        const uint32_t uses_sum[] = {
            vsetivli(4),
            flw(1, 1, 0),
            flw(2, 1, 4),
            fp_op(0x00, 0, 3, 1, 2),
            vle32(2, 1),
            vector_alu(0x00, 5, 4, 2, 3), /* vfadd.vf v4, v2, f3 */
            vse32(4, 1),
            OPENGPU_SHADER_CEASE,
        };
        const uint32_t undef_src[] = {
            flw(1, 1, 0),
            fp_op(0x00, 0, 3, 1, 2),
            OPENGPU_SHADER_CEASE,
        };
        const uint32_t bad_rm[] = {
            flw(1, 1, 0),
            flw(2, 1, 4),
            fp_op(0x00, 5, 3, 1, 2),
            OPENGPU_SHADER_CEASE,
        };
        const uint32_t fdiv[] = {
            flw(1, 1, 0),
            flw(2, 1, 4),
            fp_op(0x03, 0, 3, 1, 2),
            OPENGPU_SHADER_CEASE,
        };
        const uint32_t fsqrt[] = {
            flw(1, 1, 0),
            fp_op(0x0b, 0, 3, 1, 0),
            OPENGPU_SHADER_CEASE,
        };
        const uint32_t fsqrt_rs2[] = {
            flw(1, 1, 0),
            fp_op(0x0b, 0, 3, 1, 1),
            OPENGPU_SHADER_CEASE,
        };
        const uint32_t feq[] = {
            flw(1, 1, 0),
            flw(2, 1, 4),
            fp_op(0x14, 2, 3, 1, 2),
            OPENGPU_SHADER_CEASE,
        };
        const uint32_t fcvt[] = {
            flw(1, 1, 0),
            fp_op(0x18, 0, 3, 1, 0),
            OPENGPU_SHADER_CEASE,
        };
        const uint32_t not_integer[] = {
            flw(1, 1, 0),
            flw(2, 1, 4),
            fp_op(0x00, 0, 9, 1, 2), /* defines f9, not x9 */
            sw(9, 8),
            OPENGPU_SHADER_CEASE,
        };
        const uint32_t undef_fma[] = {
            flw(1, 1, 0),
            flw(2, 1, 4),
            fp_fma(0x43, 0, 8, 1, 2, 3), /* f3 undefined */
            OPENGPU_SHADER_CEASE,
        };
        const uint32_t fragment_sum[] = {
            flw(1, 1, 0),
            flw(2, 1, 4),
            fp_op(0x00, 0, 3, 1, 2),
            fsw(3, 1, 192),
            OPENGPU_SHADER_CEASE,
        };
        const uint32_t fp_branch[] = {
            branch(0, 0, 0, 12),
            flw(1, 1, 0),
            flw(2, 1, 4),
            fp_op(0x00, 0, 3, 1, 2),
            fsw(3, 1, 8),
            OPENGPU_SHADER_CEASE,
        };
        const uint32_t double_add[] = {
            flw(1, 1, 0),
            flw(2, 1, 4),
            fp_op(0x00, 0, 3, 1, 2) | (1u << 25),
            OPENGPU_SHADER_CEASE,
        };

        assert(fpu_valid(fp_arith, 10));
        assert(!opengpu_compute_shader_validate_words(fp_arith, 10, 64, 4));
        assert(fpu_valid(chained, 6));
        assert(fpu_valid(uses_sum, 8));
        assert(opengpu_shader_validate_words(fragment_sum, 5, 288, 8, true));
        assert(!fpu_valid(undef_src, 3));
        assert(!fpu_valid(bad_rm, 4));
        assert(fpu_valid(fdiv, 4));
        assert(fpu_valid(fsqrt, 3));
        assert(!fpu_valid(fsqrt_rs2, 3));
        assert(fpu_valid(feq, 4));
        assert(fpu_valid(fcvt, 3));
        assert(!fpu_valid(not_integer, 5));
        assert(!fpu_valid(undef_fma, 4));
        assert(!fpu_valid(fp_branch, 6));
        assert(!fpu_valid(double_add, 4));
    }

    /* Compares, conversions and bit moves cross the integer file. An integer
     * destination cannot be x1 and does not stay a pointer. */
    {
        const uint32_t compare_store[] = {
            flw(1, 1, 0),
            flw(2, 1, 4),
            fp_op(0x14, 2, 10, 1, 2), /* feq.s x10, f1, f2 */
            fp_op(0x14, 0, 11, 1, 2), /* fle.s x11, f1, f2 */
            fp_op(0x14, 1, 12, 1, 2), /* flt.s x12, f1, f2 */
            sw(10, 8),
            OPENGPU_SHADER_CEASE,
        };
        const uint32_t convert_store[] = {
            flw(1, 1, 0),
            fp_op(0x18, 0, 10, 1, 0), /* fcvt.w.s x10, f1 */
            fp_op(0x18, 7, 11, 1, 1), /* fcvt.wu.s x11, f1, dyn */
            fp_op(0x1c, 0, 12, 1, 0), /* fmv.x.w x12, f1 */
            fp_op(0x1c, 1, 13, 1, 0), /* fclass.s x13, f1 */
            sw(12, 8),
            OPENGPU_SHADER_CEASE,
        };
        const uint32_t int_to_fp[] = {
            addi(10, 0, 5),
            fp_op(0x1a, 0, 3, 10, 0), /* fcvt.s.w f3, x10 */
            fp_op(0x1a, 0, 4, 10, 1), /* fcvt.s.wu f4, x10 */
            fp_op(0x1e, 0, 5, 10, 0), /* fmv.w.x f5, x10 */
            fsw(5, 1, 8),
            OPENGPU_SHADER_CEASE,
        };
        const uint32_t fragment_cmp[] = {
            flw(1, 1, 0),
            flw(2, 1, 4),
            fp_op(0x14, 2, 10, 1, 2),
            sw(10, 192),
            OPENGPU_SHADER_CEASE,
        };
        const uint32_t writes_x1[] = {
            flw(1, 1, 0),
            flw(2, 1, 4),
            fp_op(0x14, 2, 1, 1, 2),
            OPENGPU_SHADER_CEASE,
        };
        const uint32_t undef_int[] = {
            fp_op(0x1a, 0, 3, 10, 0),
            OPENGPU_SHADER_CEASE,
        };
        const uint32_t clobber_ptr[] = {
            flw(1, 1, 0),
            flw(2, 1, 4),
            addi(5, 1, 0),
            vsetivli(4),
            fp_op(0x14, 2, 5, 1, 2),
            vse32(1, 5),
            OPENGPU_SHADER_CEASE,
        };
        const uint32_t not_fp[] = {
            flw(1, 1, 0),
            fp_op(0x1c, 0, 10, 1, 0), /* defines x10, not f10 */
            fsw(10, 1, 8),
            OPENGPU_SHADER_CEASE,
        };
        const uint32_t bad_rs2[] = {
            flw(1, 1, 0),
            fp_op(0x18, 0, 10, 1, 2),
            OPENGPU_SHADER_CEASE,
        };
        const uint32_t cmp_branch[] = {
            branch(0, 0, 0, 12),
            flw(1, 1, 0),
            flw(2, 1, 4),
            fp_op(0x14, 2, 10, 1, 2),
            sw(10, 8),
            OPENGPU_SHADER_CEASE,
        };

        assert(fpu_valid(compare_store, 7));
        assert(!opengpu_compute_shader_validate_words(
            compare_store, 7, 64, 4));
        assert(fpu_valid(convert_store, 7));
        assert(fpu_valid(int_to_fp, 6));
        assert(opengpu_shader_validate_words(fragment_cmp, 5, 288, 8, true));
        assert(!fpu_valid(writes_x1, 4));
        assert(!fpu_valid(undef_int, 2));
        assert(!fpu_valid(clobber_ptr, 7));
        assert(!fpu_valid(not_fp, 4));
        assert(!fpu_valid(bad_rs2, 3));
        assert(!fpu_valid(cmp_branch, 6));
    }

    program[1] = vector_alu(0x00, 4, 2, 1, 10); /* undefined scalar x10 */
    assert(!opengpu_shader_validate_words(program, 3, 288, 8, false));

    program[1] = vector_alu(0x25, 2, 2, 1, 3); /* undefined vector v3 */
    assert(!opengpu_shader_validate_words(program, 3, 288, 8, false));

    program[1] = vector_alu(0x00, 3, 2, 1, 1) & ~(1u << 25);
    assert(!opengpu_shader_validate_words(program, 3, 288, 8, false));

    program[0] = addi(0, 1, 192); /* x0 discards writes; not a kernarg ptr */
    program[1] = vsetivli(4);
    program[2] = vse32(1, 0);
    program[3] = OPENGPU_SHADER_CEASE;
    assert(!opengpu_shader_validate_words(program, 4, 288, 8, false));

    program[0] = branch(0, 0, 0, -4); /* backward edge */
    program[1] = OPENGPU_SHADER_CEASE;
    assert(!opengpu_shader_validate_words(program, 2, 288, 8, false));

    program[0] = branch(0, 0, 0, 2); /* no compressed instructions */
    assert(!opengpu_shader_validate_words(program, 2, 288, 8, false));

    program[0] = branch(2, 0, 0, 4); /* reserved branch funct3 */
    assert(!opengpu_shader_validate_words(program, 2, 288, 8, false));

    program[0] = branch(0, 10, 0, 4); /* undefined scalar predicate */
    assert(!opengpu_shader_validate_words(program, 2, 288, 8, false));

    program[0] = branch(0, 0, 0, 8); /* target equals word_count */
    assert(!opengpu_shader_validate_words(program, 2, 288, 8, false));

    program[0] = branch(0, 0, 0, 8);
    program[1] = lw(10, 0); /* only the fallthrough path defines x10 */
    program[2] = sw(10, 192);
    program[3] = OPENGPU_SHADER_CEASE;
    assert(!opengpu_shader_validate_words(program, 4, 288, 8, false));

    program[0] = branch(0, 0, 0, 8);
    program[1] = OPENGPU_SHADER_CEASE; /* independently terminating paths */
    program[2] = OPENGPU_SHADER_CEASE;
    assert(opengpu_shader_validate_words(program, 3, 288, 8, false));

    program[0] = branch(0, 0, 0, 8);
    program[1] = OPENGPU_SHADER_CEASE;
    program[2] = addi(10, 10, 1); /* alternate path uses undefined x10 */
    program[3] = OPENGPU_SHADER_CEASE;
    assert(!opengpu_shader_validate_words(program, 4, 288, 8, false));

    program[0] = vsetivli(4);
    program[1] = branch(0, 0, 0, 8);
    program[2] = vsetivli(2); /* differing VL reaches the join */
    program[3] = addi(5, 1, 192);
    program[4] = vse32(1, 5);
    program[5] = OPENGPU_SHADER_CEASE;
    assert(!opengpu_shader_validate_words(program, 6, 288, 8, false));

    for (i = 0; i < 5; i++)
        program[i] = branch(0, 0, 0, 40);
    for (i = 5; i < 15; i++)
        program[i] = addi(0, 0, 0);
    program[15] = OPENGPU_SHADER_CEASE;
    assert(!opengpu_shader_validate_words(program, 16, 288, 8, false));
    return 0;
}
