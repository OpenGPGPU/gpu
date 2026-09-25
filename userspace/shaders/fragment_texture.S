/* Programmable fragment texture sample: load quad UVs, vtex.sample per
 * lane, discard warp zero, write dFdx(u) to output depth and sample colour.
 * Matches driver/tests/opengpu_drm_test.c write_vector_shader. */
    .option norvc
    .text
    .globl shader
shader:
    slli x5, x8, 2
    add x5, x1, x5
    /* vsetivli x0,4,e32,m1,ta,ma — pin the validator's accepted encoding. */
    .word 0xc1027057
    addi x6, x5, 128
    vle32.v v1, (x6)
    addi x6, x5, 160
    vle32.v v2, (x6)
    .word 0x0620812b /* vtex.sample v2, v1, v2 */
    bne x8, zero, 1f
    vxor.vv v3, v1, v1
    addi x6, x5, 256
    vse32.v v3, (x6)
    .insn i 0x73, 0, zero, zero, 0x305
1:
    addi x6, x5, 64
    vle32.v v4, (x6)
    .word 0x3210022b /* vquad.dfdx v4, v1 */
    addi x6, x5, 224
    vse32.v v4, (x6)
    addi x6, x5, 192
    vse32.v v2, (x6)
    .insn i 0x73, 0, zero, zero, 0x305
