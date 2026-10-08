/* Two mat4*vec4 products. Modelview at byte 640 is the proven
 * Q16.16 product. Those integers are already scaled by 65536, so
 * the projection at byte 512 must not scale them again. A negative
 * integer does not convert back to a float here, so each component
 * is biased by +2^23 before the convert and the same bias is
 * removed with an integer subtract after the multiply. A negative
 * coefficient is multiplied as its absolute value, then the integer
 * product is negated. The z column and the z row of the projection
 * are left out so that sign fix fits. vertex_mvp.S is unchanged. */
    .option norvc
    .text
    .globl shader
shader:
    .word 0x00241293
    .word 0x005082b3
    .word 0xc1027057
    .word 0x08028313
    .word 0x02036487
    .word 0x18028313
    .word 0x020364a7
    .word 0x0c028313
    .word 0x02036587
    .word 0x1c028313
    .word 0x020365a7
    .word 0x0e028313
    .word 0x02036607
    .word 0x1e028313
    .word 0x02036627
    .word 0x00028313
    .word 0x02036087
    .word 0x02028313
    .word 0x02036107
    .word 0x04028313
    .word 0x02036187
    .word 0x06028313
    .word 0x02036207
    .word 0x2400a107
    .word 0x2440a187
    .word 0x9211d2d7
    .word 0x2800a087
    .word 0xb210d2d7
    .word 0x925152d7
    .word 0x4a5092d7
    .word 0x9221d6d7
    .word 0x2900a087
    .word 0xb220d6d7
    .word 0x92d156d7
    .word 0x4ad096d7
    .word 0x025682d7
    .word 0x9231d6d7
    .word 0x2a00a087
    .word 0xb230d6d7
    .word 0x92d156d7
    .word 0x4ad096d7
    .word 0x025682d7
    .word 0x9241d6d7
    .word 0x2b00a087
    .word 0xb240d6d7
    .word 0x92d156d7
    .word 0x4ad096d7
    .word 0x025682d7
    .word 0x9211d357
    .word 0x2840a087
    .word 0xb210d357
    .word 0x92615357
    .word 0x4a609357
    .word 0x9221d6d7
    .word 0x2940a087
    .word 0xb220d6d7
    .word 0x92d156d7
    .word 0x4ad096d7
    .word 0x02668357
    .word 0x9231d6d7
    .word 0x2a40a087
    .word 0xb230d6d7
    .word 0x92d156d7
    .word 0x4ad096d7
    .word 0x02668357
    .word 0x9241d6d7
    .word 0x2b40a087
    .word 0xb240d6d7
    .word 0x92d156d7
    .word 0x4ad096d7
    .word 0x02668357
    .word 0x9211d3d7
    .word 0x2880a087
    .word 0xb210d3d7
    .word 0x927153d7
    .word 0x4a7093d7
    .word 0x9221d6d7
    .word 0x2980a087
    .word 0xb220d6d7
    .word 0x92d156d7
    .word 0x4ad096d7
    .word 0x027683d7
    .word 0x9231d6d7
    .word 0x2a80a087
    .word 0xb230d6d7
    .word 0x92d156d7
    .word 0x4ad096d7
    .word 0x027683d7
    .word 0x9241d6d7
    .word 0x2b80a087
    .word 0xb240d6d7
    .word 0x92d156d7
    .word 0x4ad096d7
    .word 0x027683d7
    .word 0x9211d457
    .word 0x28c0a087
    .word 0xb210d457
    .word 0x92815457
    .word 0x4a809457
    .word 0x9221d6d7
    .word 0x29c0a087
    .word 0xb220d6d7
    .word 0x92d156d7
    .word 0x4ad096d7
    .word 0x02868457
    .word 0x9231d6d7
    .word 0x2ac0a087
    .word 0xb230d6d7
    .word 0x92d156d7
    .word 0x4ad096d7
    .word 0x02868457
    .word 0x9241d6d7
    .word 0x2bc0a087
    .word 0xb240d6d7
    .word 0x92d156d7
    .word 0x4ad096d7
    .word 0x02868457
    /* x6 = 8388608. v14 = float(8388608), reused for every term. */
    lui x6, 2048
    vmv.v.x v14, x6
    vfcvt.f.x.v v14, v14
    /* clip x */
    vadd.vx v13, v5, x6
    vfcvt.f.x.v v13, v13
    flw f1, 512(x1)
    vfmv.v.f v17, f1
    vmflt.vf v0, v17, f3
    vfsgnj.vf v17, v17, f2
    vfmul.vv v16, v13, v17
    vfcvt.x.f.v v16, v16
    vfmul.vv v15, v14, v17
    vfcvt.x.f.v v15, v15
    vsub.vv v10, v16, v15
    vrsub.vi v18, v10, 0
    vmerge.vvm v10, v10, v18, v0
    vadd.vx v13, v6, x6
    vfcvt.f.x.v v13, v13
    flw f1, 528(x1)
    vfmv.v.f v17, f1
    vmflt.vf v0, v17, f3
    vfsgnj.vf v17, v17, f2
    vfmul.vv v16, v13, v17
    vfcvt.x.f.v v16, v16
    vfmul.vv v15, v14, v17
    vfcvt.x.f.v v15, v15
    vsub.vv v16, v16, v15
    vrsub.vi v18, v16, 0
    vmerge.vvm v16, v16, v18, v0
    vadd.vv v10, v10, v16
    vadd.vx v13, v8, x6
    vfcvt.f.x.v v13, v13
    flw f1, 560(x1)
    vfmv.v.f v17, f1
    vmflt.vf v0, v17, f3
    vfsgnj.vf v17, v17, f2
    vfmul.vv v16, v13, v17
    vfcvt.x.f.v v16, v16
    vfmul.vv v15, v14, v17
    vfcvt.x.f.v v15, v15
    vsub.vv v16, v16, v15
    vrsub.vi v18, v16, 0
    vmerge.vvm v16, v16, v18, v0
    vadd.vv v10, v10, v16
    addi x7, x5, 256
    vse32.v v10, (x7)
    /* clip y */
    vadd.vx v13, v5, x6
    vfcvt.f.x.v v13, v13
    flw f1, 516(x1)
    vfmv.v.f v17, f1
    vmflt.vf v0, v17, f3
    vfsgnj.vf v17, v17, f2
    vfmul.vv v16, v13, v17
    vfcvt.x.f.v v16, v16
    vfmul.vv v15, v14, v17
    vfcvt.x.f.v v15, v15
    vsub.vv v10, v16, v15
    vrsub.vi v18, v10, 0
    vmerge.vvm v10, v10, v18, v0
    vadd.vx v13, v6, x6
    vfcvt.f.x.v v13, v13
    flw f1, 532(x1)
    vfmv.v.f v17, f1
    vmflt.vf v0, v17, f3
    vfsgnj.vf v17, v17, f2
    vfmul.vv v16, v13, v17
    vfcvt.x.f.v v16, v16
    vfmul.vv v15, v14, v17
    vfcvt.x.f.v v15, v15
    vsub.vv v16, v16, v15
    vrsub.vi v18, v16, 0
    vmerge.vvm v16, v16, v18, v0
    vadd.vv v10, v10, v16
    vadd.vx v13, v8, x6
    vfcvt.f.x.v v13, v13
    flw f1, 564(x1)
    vfmv.v.f v17, f1
    vmflt.vf v0, v17, f3
    vfsgnj.vf v17, v17, f2
    vfmul.vv v16, v13, v17
    vfcvt.x.f.v v16, v16
    vfmul.vv v15, v14, v17
    vfcvt.x.f.v v15, v15
    vsub.vv v16, v16, v15
    vrsub.vi v18, v16, 0
    vmerge.vvm v16, v16, v18, v0
    vadd.vv v10, v10, v16
    addi x7, x5, 288
    vse32.v v10, (x7)
    /* clip w. z stays the cleared 0 */
    vadd.vx v13, v5, x6
    vfcvt.f.x.v v13, v13
    flw f1, 524(x1)
    vfmv.v.f v17, f1
    vmflt.vf v0, v17, f3
    vfsgnj.vf v17, v17, f2
    vfmul.vv v16, v13, v17
    vfcvt.x.f.v v16, v16
    vfmul.vv v15, v14, v17
    vfcvt.x.f.v v15, v15
    vsub.vv v10, v16, v15
    vrsub.vi v18, v10, 0
    vmerge.vvm v10, v10, v18, v0
    vadd.vx v13, v6, x6
    vfcvt.f.x.v v13, v13
    flw f1, 540(x1)
    vfmv.v.f v17, f1
    vmflt.vf v0, v17, f3
    vfsgnj.vf v17, v17, f2
    vfmul.vv v16, v13, v17
    vfcvt.x.f.v v16, v16
    vfmul.vv v15, v14, v17
    vfcvt.x.f.v v15, v15
    vsub.vv v16, v16, v15
    vrsub.vi v18, v16, 0
    vmerge.vvm v16, v16, v18, v0
    vadd.vv v10, v10, v16
    vadd.vx v13, v8, x6
    vfcvt.f.x.v v13, v13
    flw f1, 572(x1)
    vfmv.v.f v17, f1
    vmflt.vf v0, v17, f3
    vfsgnj.vf v17, v17, f2
    vfmul.vv v16, v13, v17
    vfcvt.x.f.v v16, v16
    vfmul.vv v15, v14, v17
    vfcvt.x.f.v v15, v15
    vsub.vv v16, v16, v15
    vrsub.vi v18, v16, 0
    vmerge.vvm v16, v16, v18, v0
    vadd.vv v10, v10, v16
    addi x7, x5, 352
    vse32.v v10, (x7)
    .insn i 0x73, 0, zero, zero, 0x305

