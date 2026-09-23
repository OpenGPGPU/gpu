/* One input (1), two outputs: RNU rounds 1>>1 to 1, RDN rounds it to 0. */
    .option norvc
    .text
    .globl shader
shader:
    vsetivli zero, 1, e32, m1, tu, mu
    vle32.v v2, (x1)
    csrwi vxrm, 0
    vssra.vi v3, v2, 1
    addi t0, x1, 4
    vse32.v v3, (t0)
    csrwi vxrm, 2
    vssra.vi v4, v2, 1
    addi t0, x1, 8
    vse32.v v4, (t0)
    .insn i 0x73, 0, zero, zero, 0x305
