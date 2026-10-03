/* Vertex-core copy of the fixed 32-byte vertex into the fragment varyings.
 * Input slices 0..7, output slices 8..15, 32 bytes apart on an 8-vertex batch. */
    .option norvc
    .text
    .globl shader
shader:
    slli x5, x8, 2
    add x5, x1, x5
    /* vsetivli x0,4,e32,m1,ta,ma */
    .word 0xc1027057
    addi x6, x5, 0
    vle32.v v1, (x6)
    addi x6, x5, 256
    vse32.v v1, (x6)
    addi x6, x5, 32
    vle32.v v1, (x6)
    addi x6, x5, 288
    vse32.v v1, (x6)
    addi x6, x5, 64
    vle32.v v1, (x6)
    addi x6, x5, 320
    vse32.v v1, (x6)
    addi x6, x5, 96
    vle32.v v1, (x6)
    addi x6, x5, 352
    vse32.v v1, (x6)
    addi x6, x5, 128
    vle32.v v1, (x6)
    addi x6, x5, 384
    vse32.v v1, (x6)
    addi x6, x5, 160
    vle32.v v1, (x6)
    addi x6, x5, 416
    vse32.v v1, (x6)
    addi x6, x5, 192
    vle32.v v1, (x6)
    addi x6, x5, 448
    vse32.v v1, (x6)
    addi x6, x5, 224
    vle32.v v1, (x6)
    addi x6, x5, 480
    vse32.v v1, (x6)
    .insn i 0x73, 0, zero, zero, 0x305
