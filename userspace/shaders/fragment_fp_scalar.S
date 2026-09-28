/* Programmable fragment uniform float: read a per-draw uniform with
 * flw off the kernarg base, broadcast it to every lane with vfadd.vf against
 * a zeroed vector, and XOR the result into the interpolated colour.
 *
 * This is the shape a `uniform float` lowers to, and it needs the scalar FPU on
 * the fragment shader CU, which only exists when the device advertises
 * OPENGPU_CAP_COMPUTE_SCALAR_FPU (the driver admits flw for the fragment
 * profile from the same capability). It does not use the scalar register file:
 * the .vf operand is read through the FPU sideband.
 *
 * Fill kernarg[72] (the first per-draw uniform word, 9 * 32) with 5.0f. The
 * interpolated colour is 0xfe00ffff, so every painted pixel becomes
 * 0xbea0ffff.
 *
 * Offsets follow the fragment kernarg ABI with stride 4 * warps * lanes = 32:
 * 96 = colour in (3 * 32), 192 = colour out (6 * 32), 288 = uniforms (9 * 32).
 */
    .option norvc
    .text
    .globl shader
shader:
    slli x5, x8, 2
    add x5, x1, x5
    /* vsetivli x0,4,e32,m1,ta,ma - pin the validator's accepted encoding. */
    .word 0xc1027057
    .word 0x1200a087 /* flw f1, 288(x1) */
    addi x6, x5, 96
    .word 0x02036087 /* vle32.v v1, (x6) - interpolated colour */
    vxor.vv v2, v1, v1
    .word 0x0220d257 /* vfadd.vf v4, v2, f1 - broadcast the uniform */
    vxor.vv v6, v1, v4
    addi x6, x5, 192
    .word 0x02036327 /* vse32.v v6, (x6) */
    .insn i 0x73, 0, zero, zero, 0x305
