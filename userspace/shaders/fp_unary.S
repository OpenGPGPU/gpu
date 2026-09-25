/* FP32 VFUNARY1 smoke: vfsqrt / vfrsqrt7 / vfrec7 / vfclass.
 * Requires local_items >= 4 (host CU has 4 lanes). Fill kernarg[0..3]=4.0f
 * and kernarg[4..7]=1.0f; results land in kernarg[8..11] (lane 0). */
    .option norvc
    .text
    .globl shader
shader:
    /* vsetivli x0,4,e32,m1,ta,ma */
    .word 0xc1027057
    vle32.v v2, (x1)
    .word 0x4e2011d7 /* vfsqrt.v v3, v2 */
    .word 0x4e229257 /* vfrsqrt7.v v4, v2 */
    .word 0x4e2812d7 /* vfclass.v v5, v2 */
    addi t0, x1, 16
    vle32.v v6, (t0)
    .word 0x4e621357 /* vfrec7.v v6, v6 */
    /* vsetivli x0,1,e32,m1,ta,ma — store lane 0 only */
    .word 0xc100f057
    addi t0, x1, 32
    vse32.v v3, (t0)
    addi t0, x1, 36
    vse32.v v4, (t0)
    addi t0, x1, 40
    vse32.v v6, (t0)
    addi t0, x1, 44
    vse32.v v5, (t0)
    .insn i 0x73, 0, zero, zero, 0x305
