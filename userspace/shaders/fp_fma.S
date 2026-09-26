/* Unmasked OPFVV fused FMA smoke (all eight forms).
 * Requires local_items >= 4. Fill kernarg[0..3]=3.0f, [4..7]=2.0f,
 * [8..11]=1.0f (old vd); lane-0 results land in kernarg[12..19]. */
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
    vle32.v v4, (t0)
    vle32.v v5, (t0)
    vle32.v v6, (t0)
    vle32.v v7, (t0)
    vle32.v v8, (t0)
    vle32.v v9, (t0)
    vle32.v v10, (t0)
    vle32.v v11, (t0)
    .word 0xa2219257 /* vfmadd.vv  v4,  v3, v2 */
    .word 0xa62192d7 /* vfnmadd.vv v5,  v3, v2 */
    .word 0xaa219357 /* vfmsub.vv  v6,  v3, v2 */
    .word 0xae2193d7 /* vfnmsub.vv v7,  v3, v2 */
    .word 0xb2219457 /* vfmacc.vv  v8,  v3, v2 */
    .word 0xb62194d7 /* vfnmacc.vv v9,  v3, v2 */
    .word 0xba219557 /* vfmsac.vv  v10, v3, v2 */
    .word 0xbe2195d7 /* vfnmsac.vv v11, v3, v2 */
    /* vsetivli x0,1,e32,m1,ta,ma — store lane 0 only */
    .word 0xc100f057
    addi t0, x1, 48
    vse32.v v4, (t0)
    addi t0, x1, 52
    vse32.v v5, (t0)
    addi t0, x1, 56
    vse32.v v6, (t0)
    addi t0, x1, 60
    vse32.v v7, (t0)
    addi t0, x1, 64
    vse32.v v8, (t0)
    addi t0, x1, 68
    vse32.v v9, (t0)
    addi t0, x1, 72
    vse32.v v10, (t0)
    addi t0, x1, 76
    vse32.v v11, (t0)
    .insn i 0x73, 0, zero, zero, 0x305
