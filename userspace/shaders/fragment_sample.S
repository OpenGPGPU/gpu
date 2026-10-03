/* Per-lane texture sample. The rasterizer writes perspective-correct Q16.16
 * UVs at kernarg slices 4 and 5. vtex.sample returns the packed texel, which
 * this stores to the colour output at slice 6. */
    .option norvc
    .text
    .globl shader
shader:
    slli x5, x8, 2
    add x5, x1, x5
    /* vsetivli x0,4,e32,m1,ta,ma */
    .word 0xc1027057
    addi x6, x5, 128
    vle32.v v1, (x6)
    addi x6, x5, 160
    vle32.v v2, (x6)
    .word 0x0620812b /* vtex.sample v2, v1, v2 */
    addi x6, x5, 192
    vse32.v v2, (x6)
    .insn i 0x73, 0, zero, zero, 0x305
