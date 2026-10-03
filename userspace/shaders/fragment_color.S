/* Per-lane fragment colour copy. The rasterizer writes the interpolated
 * colour at kernarg slice 3 (byte 96). This stores that word to the colour
 * output at slice 6 (byte 192) for every lane of the warp. */
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
    addi x6, x5, 192
    vse32.v v1, (x6)
    .insn i 0x73, 0, zero, zero, 0x305
