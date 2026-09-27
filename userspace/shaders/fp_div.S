/* Unmasked OPFVV smoke: vfdiv.
 * Requires local_items >= 4. Fill kernarg[0..3]=6.0f and kernarg[4..7]=3.0f;
 * lane-0 result lands in kernarg[8]. */
    .option norvc
    .text
    .globl shader
shader:
    /* vsetivli x0,4,e32,m1,ta,ma */
    .word 0xc1027057
    vle32.v v2, (x1)
    addi t0, x1, 16
    vle32.v v3, (t0)
    .word 0x82219257 /* vfdiv.vv v4, v2, v3 */
    /* vsetivli x0,1,e32,m1,ta,ma — store lane 0 only */
    .word 0xc100f057
    addi t0, x1, 32
    vse32.v v4, (t0)
    .insn i 0x73, 0, zero, zero, 0x305
