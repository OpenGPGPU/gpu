/* Fixed-profile lane-local widen and saturating narrow (vsext/vzext/vnclip). */
    .option norvc
    .text
    .globl shader
shader:
    vsetivli zero, 1, e32, m1, tu, mu
    vle32.v v2, (x1)
    addi t0, x1, 4
    vle32.v v3, (t0)
    vsext.vf2 v4, v2
    vzext.vf2 v5, v2
    csrwi vxrm, 0
    vnclip.wi v6, v2, 1
    addi t0, x1, 8
    vse32.v v4, (t0)
    addi t0, x1, 12
    vse32.v v5, (t0)
    addi t0, x1, 16
    vse32.v v6, (t0)
    .insn i 0x73, 0, zero, zero, 0x305
