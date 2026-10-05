/* Vertex-core mat4 * vec4, plus colour and UV copies.
 * Position slices are IEEE floats. The column-major matrix is 16
 * floats at byte 512, and byte 576 holds 65536.0. Byte 580 stays 0.0.
 * Each term is a product onto a cleared register, scaled, converted
 * to Q16.16, then added as an integer. A fused multiply-add of two
 * nonzero values is wrong on this model, and so is vfmul of a
 * negative value. UV slices are already Q16.16. Depth is left zero;
 * the fixed-function depth test is a draw-record field. The shader
 * must stay inside 512 bytes. */
    .option norvc
    .text
    .globl shader
shader:
    .word 0x00241293 /* slli x5, x8, 2 */
    .word 0x005082b3 /* add x5, x1, x5 */
    .word 0xc1027057 /* vsetivli x0, 4, e32, m1, ta, ma */
    .word 0x08028313 /* addi x6, x5, 128 */
    .word 0x02036487 /* vle32.v v9, (x6) in-color */
    .word 0x18028313 /* addi x6, x5, 384 */
    .word 0x020364a7 /* vse32.v v9, (x6) out-color */
    .word 0x0c028313 /* addi x6, x5, 192 */
    .word 0x02036587 /* vle32.v v11, (x6) in-u */
    .word 0x1c028313 /* addi x6, x5, 448 */
    .word 0x020365a7 /* vse32.v v11, (x6) out-u */
    .word 0x0e028313 /* addi x6, x5, 224 */
    .word 0x02036607 /* vle32.v v12, (x6) in-v */
    .word 0x1e028313 /* addi x6, x5, 480 */
    .word 0x02036627 /* vse32.v v12, (x6) out-v */
    .word 0x00028313 /* addi x6, x5, 0 */
    .word 0x02036087 /* vle32.v v1, (x6) px */
    .word 0x02028313 /* addi x6, x5, 32 */
    .word 0x02036107 /* vle32.v v2, (x6) py */
    .word 0x04028313 /* addi x6, x5, 64 */
    .word 0x02036187 /* vle32.v v3, (x6) pz */
    .word 0x06028313 /* addi x6, x5, 96 */
    .word 0x02036207 /* vle32.v v4, (x6) pw */
    .word 0x2400a107 /* flw f2, 576(x1) 65536.0 */
    .word 0x2440a187 /* flw f3, 580(x1) 0.0 */
    .word 0x9211d2d7 /* vfmul.vf v5, v1, f3 */
    .word 0x2000a087 /* flw f1, 512(x1) */
    .word 0xb210d2d7 /* vfmacc.vf v5, f1, v1 */
    .word 0x925152d7 /* vfmul.vf v5, v5, f2 */
    .word 0x4a5092d7 /* vfcvt.x.f.v v5, v5 */
    .word 0x9221d6d7 /* vfmul.vf v13, v2, f3 */
    .word 0x2100a087 /* flw f1, 528(x1) */
    .word 0xb220d6d7 /* vfmacc.vf v13, f1, v2 */
    .word 0x92d156d7 /* vfmul.vf v13, v13, f2 */
    .word 0x4ad096d7 /* vfcvt.x.f.v v13, v13 */
    .word 0x025682d7 /* vadd.vv v5, v5, v13 */
    .word 0x9231d6d7 /* vfmul.vf v13, v3, f3 */
    .word 0x2200a087 /* flw f1, 544(x1) */
    .word 0xb230d6d7 /* vfmacc.vf v13, f1, v3 */
    .word 0x92d156d7 /* vfmul.vf v13, v13, f2 */
    .word 0x4ad096d7 /* vfcvt.x.f.v v13, v13 */
    .word 0x025682d7 /* vadd.vv v5, v5, v13 */
    .word 0x9241d6d7 /* vfmul.vf v13, v4, f3 */
    .word 0x2300a087 /* flw f1, 560(x1) */
    .word 0xb240d6d7 /* vfmacc.vf v13, f1, v4 */
    .word 0x92d156d7 /* vfmul.vf v13, v13, f2 */
    .word 0x4ad096d7 /* vfcvt.x.f.v v13, v13 */
    .word 0x025682d7 /* vadd.vv v5, v5, v13 */
    .word 0x9211d357 /* vfmul.vf v6, v1, f3 */
    .word 0x2040a087 /* flw f1, 516(x1) */
    .word 0xb210d357 /* vfmacc.vf v6, f1, v1 */
    .word 0x92615357 /* vfmul.vf v6, v6, f2 */
    .word 0x4a609357 /* vfcvt.x.f.v v6, v6 */
    .word 0x9221d6d7 /* vfmul.vf v13, v2, f3 */
    .word 0x2140a087 /* flw f1, 532(x1) */
    .word 0xb220d6d7 /* vfmacc.vf v13, f1, v2 */
    .word 0x92d156d7 /* vfmul.vf v13, v13, f2 */
    .word 0x4ad096d7 /* vfcvt.x.f.v v13, v13 */
    .word 0x02668357 /* vadd.vv v6, v6, v13 */
    .word 0x9231d6d7 /* vfmul.vf v13, v3, f3 */
    .word 0x2240a087 /* flw f1, 548(x1) */
    .word 0xb230d6d7 /* vfmacc.vf v13, f1, v3 */
    .word 0x92d156d7 /* vfmul.vf v13, v13, f2 */
    .word 0x4ad096d7 /* vfcvt.x.f.v v13, v13 */
    .word 0x02668357 /* vadd.vv v6, v6, v13 */
    .word 0x9241d6d7 /* vfmul.vf v13, v4, f3 */
    .word 0x2340a087 /* flw f1, 564(x1) */
    .word 0xb240d6d7 /* vfmacc.vf v13, f1, v4 */
    .word 0x92d156d7 /* vfmul.vf v13, v13, f2 */
    .word 0x4ad096d7 /* vfcvt.x.f.v v13, v13 */
    .word 0x02668357 /* vadd.vv v6, v6, v13 */
    .word 0x9211d3d7 /* vfmul.vf v7, v1, f3 */
    .word 0x2080a087 /* flw f1, 520(x1) */
    .word 0xb210d3d7 /* vfmacc.vf v7, f1, v1 */
    .word 0x927153d7 /* vfmul.vf v7, v7, f2 */
    .word 0x4a7093d7 /* vfcvt.x.f.v v7, v7 */
    .word 0x9221d6d7 /* vfmul.vf v13, v2, f3 */
    .word 0x2180a087 /* flw f1, 536(x1) */
    .word 0xb220d6d7 /* vfmacc.vf v13, f1, v2 */
    .word 0x92d156d7 /* vfmul.vf v13, v13, f2 */
    .word 0x4ad096d7 /* vfcvt.x.f.v v13, v13 */
    .word 0x027683d7 /* vadd.vv v7, v7, v13 */
    .word 0x9231d6d7 /* vfmul.vf v13, v3, f3 */
    .word 0x2280a087 /* flw f1, 552(x1) */
    .word 0xb230d6d7 /* vfmacc.vf v13, f1, v3 */
    .word 0x92d156d7 /* vfmul.vf v13, v13, f2 */
    .word 0x4ad096d7 /* vfcvt.x.f.v v13, v13 */
    .word 0x027683d7 /* vadd.vv v7, v7, v13 */
    .word 0x9241d6d7 /* vfmul.vf v13, v4, f3 */
    .word 0x2380a087 /* flw f1, 568(x1) */
    .word 0xb240d6d7 /* vfmacc.vf v13, f1, v4 */
    .word 0x92d156d7 /* vfmul.vf v13, v13, f2 */
    .word 0x4ad096d7 /* vfcvt.x.f.v v13, v13 */
    .word 0x027683d7 /* vadd.vv v7, v7, v13 */
    .word 0x9211d457 /* vfmul.vf v8, v1, f3 */
    .word 0x20c0a087 /* flw f1, 524(x1) */
    .word 0xb210d457 /* vfmacc.vf v8, f1, v1 */
    .word 0x92815457 /* vfmul.vf v8, v8, f2 */
    .word 0x4a809457 /* vfcvt.x.f.v v8, v8 */
    .word 0x9221d6d7 /* vfmul.vf v13, v2, f3 */
    .word 0x21c0a087 /* flw f1, 540(x1) */
    .word 0xb220d6d7 /* vfmacc.vf v13, f1, v2 */
    .word 0x92d156d7 /* vfmul.vf v13, v13, f2 */
    .word 0x4ad096d7 /* vfcvt.x.f.v v13, v13 */
    .word 0x02868457 /* vadd.vv v8, v8, v13 */
    .word 0x9231d6d7 /* vfmul.vf v13, v3, f3 */
    .word 0x22c0a087 /* flw f1, 556(x1) */
    .word 0xb230d6d7 /* vfmacc.vf v13, f1, v3 */
    .word 0x92d156d7 /* vfmul.vf v13, v13, f2 */
    .word 0x4ad096d7 /* vfcvt.x.f.v v13, v13 */
    .word 0x02868457 /* vadd.vv v8, v8, v13 */
    .word 0x9241d6d7 /* vfmul.vf v13, v4, f3 */
    .word 0x23c0a087 /* flw f1, 572(x1) */
    .word 0xb240d6d7 /* vfmacc.vf v13, f1, v4 */
    .word 0x92d156d7 /* vfmul.vf v13, v13, f2 */
    .word 0x4ad096d7 /* vfcvt.x.f.v v13, v13 */
    .word 0x02868457 /* vadd.vv v8, v8, v13 */
    .word 0x10028313 /* addi x6, x5, 256 */
    .word 0x020362a7 /* vse32.v v5, (x6) cx */
    .word 0x12028313 /* addi x6, x5, 288 */
    .word 0x02036327 /* vse32.v v6, (x6) cy */
    .word 0x14028313 /* addi x6, x5, 320 */
    .word 0x020363a7 /* vse32.v v7, (x6) cz */
    .word 0x16028313 /* addi x6, x5, 352 */
    .word 0x02036427 /* vse32.v v8, (x6) cw */
    .insn i 0x73, 0, zero, zero, 0x305
