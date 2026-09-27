/* OPFVF smoke: flw f1 from kernarg, then vfadd/vfrsub/vfrdiv/vfmul/vfmacc.vf
 * and vmfgt.vf feeding a masked integer add. Requires local_items >= 4 and
 * the compute scalar FPU. Fill kernarg[0]=2.0f, [4..7]=3.0f, [8..11]=1.0f,
 * [12..15]=7; lane-0 results land in kernarg[16..21]. */
    .option norvc
    .text
    .globl shader
shader:
    /* vsetivli x0,4,e32,m1,ta,ma */
    .word 0xc1027057
    .word 0x0000a087 /* flw f1, 0(x1) */
    addi t0, x1, 16
    vle32.v v2, (t0)
    addi t0, x1, 32
    vle32.v v8, (t0)
    addi t0, x1, 48
    vle32.v v5, (t0)
    .word 0x0220d257 /* vfadd.vf v4, v2, f1 */
    .word 0x9e20d357 /* vfrsub.vf v6, v2, f1 */
    .word 0x8620d3d7 /* vfrdiv.vf v7, v2, f1 */
    .word 0x9220d4d7 /* vfmul.vf v9, v2, f1 */
    .word 0xb220d457 /* vfmacc.vf v8, f1, v2 */
    .word 0x7620d057 /* vmfgt.vf v0, v2, f1 */
    .word 0x02503557 /* vadd.vi v10, v5, 0 */
    .word 0x0050b557 /* vadd.vi v10, v5, 1, v0.t */
    /* vsetivli x0,1,e32,m1,ta,ma — store lane 0 only */
    .word 0xc100f057
    addi t0, x1, 64
    vse32.v v4, (t0)
    addi t0, x1, 68
    vse32.v v6, (t0)
    addi t0, x1, 72
    vse32.v v7, (t0)
    addi t0, x1, 76
    vse32.v v9, (t0)
    addi t0, x1, 80
    vse32.v v8, (t0)
    addi t0, x1, 84
    vse32.v v10, (t0)
    .insn i 0x73, 0, zero, zero, 0x305
