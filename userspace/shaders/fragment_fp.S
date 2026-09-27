/* Programmable fragment vector FP: build 1.5f and 2.0f per lane, compute
 * vfmul (3.0), vfdiv (1.5) and vfmacc (1.5 * 2.0 + 3.0 = 6.0 = 0x40c00000),
 * then XOR the result into the interpolated colour. Runs on the fragment
 * shader CU, which has the vector FP units but no scalar FPU. */
    .option norvc
    .text
    .globl shader
shader:
    slli x5, x8, 2
    add x5, x1, x5
    /* vsetivli x0,4,e32,m1,ta,ma — pin the validator's accepted encoding. */
    .word 0xc1027057
    addi x6, x5, 96
    vle32.v v1, (x6)
    lui x7, 0x3fc00
    vxor.vv v2, v1, v1
    vadd.vx v2, v2, x7
    lui x7, 0x40000
    vxor.vv v3, v1, v1
    vadd.vx v3, v3, x7
    .word 0x92219257 /* vfmul.vv v4, v2, v3 */
    .word 0x824192d7 /* vfdiv.vv v5, v4, v3 */
    .word 0xb2329257 /* vfmacc.vv v4, v5, v3 */
    vxor.vv v6, v1, v4
    addi x6, x5, 192
    vse32.v v6, (x6)
    .insn i 0x73, 0, zero, zero, 0x305
