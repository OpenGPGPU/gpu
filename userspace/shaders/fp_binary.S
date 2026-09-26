/* Unmasked OPFVV smoke: vfadd/vfsub/vfmul, vfmin/vfmax, vfsgnj.
 * Requires local_items >= 4. Fill kernarg[0..3]=1.0f, [4..7]=2.0f,
 * [12..15]=-1.0f; lane-0 results land in kernarg[8..11] and [13..14]. */
    .option norvc
    .text
    .globl shader
shader:
    /* vsetivli x0,4,e32,m1,ta,ma */
    .word 0xc1027057
    vle32.v v2, (x1)
    addi t0, x1, 16
    vle32.v v3, (t0)
    addi t0, x1, 48
    vle32.v v10, (t0)
    .word 0x02219257 /* vfadd.vv v4, v2, v3 */
    .word 0x0a2192d7 /* vfsub.vv v5, v2, v3 */
    .word 0x92219357 /* vfmul.vv v6, v2, v3 */
    .word 0x122193d7 /* vfmin.vv v7, v2, v3 */
    .word 0x1a219457 /* vfmax.vv v8, v2, v3 */
    .word 0x222514d7 /* vfsgnj.vv v9, v2, v10 */
    /* vsetivli x0,1,e32,m1,ta,ma — store lane 0 only */
    .word 0xc100f057
    addi t0, x1, 32
    vse32.v v4, (t0)
    addi t0, x1, 36
    vse32.v v5, (t0)
    addi t0, x1, 40
    vse32.v v6, (t0)
    addi t0, x1, 44
    vse32.v v7, (t0)
    addi t0, x1, 52
    vse32.v v8, (t0)
    addi t0, x1, 56
    vse32.v v9, (t0)
    .insn i 0x73, 0, zero, zero, 0x305
