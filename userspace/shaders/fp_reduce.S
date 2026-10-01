/* OPFRED FP32 sum reductions: vfredusum.vs and vfredosum.vs.
 * Requires local_items >= 4. Fill kernarg[0..3] with the four elements and
 * kernarg[4] with the seed. Both reductions write element 0 of their
 * destination and keep the rest, so the results are kernarg[8] = the unordered
 * sum and kernarg[12] = the ordered sum, each followed by the untouched
 * elements the vadd.vv below left behind. */
    .option norvc
    .text
    .globl shader
shader:
    /* vsetivli x0,4,e32,m1,ta,ma */
    .word 0xc1027057
    vle32.v v2, (x1)
    addi t0, x1, 16
    vle32.v v4, (t0)
    /* The destinations start as the doubled elements so the untouched
     * elements are observable in the result. */
    vadd.vv v3, v2, v2
    vadd.vv v6, v2, v2
    vfredusum.vs v3, v2, v4
    vfredosum.vs v6, v2, v4
    addi t0, x1, 32
    vse32.v v3, (t0)
    addi t0, x1, 48
    vse32.v v6, (t0)
    .insn i 0x73, 0, zero, zero, 0x305
