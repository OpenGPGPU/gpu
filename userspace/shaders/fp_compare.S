/* OPFVV FP compare smoke: vmfeq / vmflt feeding a masked integer add.
 * Requires local_items >= 4. Fill kernarg[0..3]=1.0f, [4..7]=1.0f,
 * [8..11]=2.0f, [12..15]=7; lane-0 results land in kernarg[16..17]. */
    .option norvc
    .text
    .globl shader
shader:
    /* vsetivli x0,4,e32,m1,ta,ma */
    .word 0xc1027057
    vle32.v v2, (x1)
    addi t0, x1, 16
    vle32.v v3, (t0)
    addi t0, x1, 32
    vle32.v v8, (t0)
    addi t0, x1, 48
    vle32.v v5, (t0)
    .word 0x62219057 /* vmfeq.vv v0, v2, v3 */
    .word 0x02503257 /* vadd.vi v4, v5, 0 */
    .word 0x0050b257 /* vadd.vi v4, v5, 1, v0.t */
    .word 0x6e241057 /* vmflt.vv v0, v2, v8 */
    .word 0x02503357 /* vadd.vi v6, v5, 0 */
    .word 0x0050b357 /* vadd.vi v6, v5, 1, v0.t */
    /* vsetivli x0,1,e32,m1,ta,ma — store lane 0 only */
    .word 0xc100f057
    addi t0, x1, 64
    vse32.v v4, (t0)
    addi t0, x1, 68
    vse32.v v6, (t0)
    .insn i 0x73, 0, zero, zero, 0x305
