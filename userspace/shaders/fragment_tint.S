/* Per-lane fragment tint: XOR the interpolated colour with 0x00ff00ff.
 * A scalar store only reaches lane 0 of the 4-lane warp, which is why the
 * desktop triangle used to scan out as one yellow dot per batch. x8 is this
 * warp's localLinearBase; the colour SoA slice is 32 bytes on the 8-fragment
 * batch (4 lanes x 2 warps). */
    .option norvc
    .text
    .globl shader
shader:
    slli x5, x8, 2
    add x5, x1, x5
    /* vsetivli x0,4,e32,m1,ta,ma */
    .word 0xc1027057
    addi x6, x5, 96
    vle32.v v1, (x6)
    lui x7, 0xff0
    addi x7, x7, 0xff
    vxor.vx v1, v1, x7
    addi x6, x5, 192
    vse32.v v1, (x6)
    .insn i 0x73, 0, zero, zero, 0x305
