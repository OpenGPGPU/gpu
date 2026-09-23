/* Exercise masked comparison, reduction, gather and slide encodings. */
    .option norvc
    .text
    .globl shader
shader:
    vsetivli zero, 1, e32, m1, tu, mu
    vle32.v v2, (x1)
    vmseq.vi v0, v2, 1
    vmseq.vi v3, v2, 0
    vmseq.vi v3, v2, 1, v0.t
    vadd.vi v4, v2, 0
    vredsum.vs v4, v2, v4, v0.t
    vadd.vi v5, v2, 0
    vrgather.vi v5, v2, 0, v0.t
    vadd.vi v6, v2, 0
    vslideup.vi v6, v2, 1, v0.t
    vadd.vi v7, v2, 0
    vslidedown.vi v7, v2, 1, v0.t
    .insn i 0x73, 0, zero, zero, 0x305
