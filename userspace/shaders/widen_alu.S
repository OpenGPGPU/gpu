/* Fixed-profile lane-local widening integer ALU (vwadd/vwsub/vwmul).
 * Each lane sign-extends the low 16 bits, operates, and writes a 32-bit
 * result — not full RVV register-group widening.
 *
 * Encode with OpenGPU funct6 0x30..0x32 (see VectorDecoder); GNU as emits
 * different standard-RVV encodings for the same mnemonics. */
    .option norvc
    .text
    .globl shader
shader:
    vsetivli zero, 1, e32, m1, tu, mu
    vle32.v v2, (x1)
    addi t0, x1, 4
    vle32.v v3, (t0)
    .word 0xc2218257 /* vwadd.vv v4, v2, v3 */
    .word 0xc62182d7 /* vwsub.vv v5, v2, v3 */
    .word 0xca218357 /* vwmul.vv v6, v2, v3 */
    addi t0, x1, 8
    vse32.v v4, (t0)
    addi t0, x1, 12
    vse32.v v5, (t0)
    addi t0, x1, 16
    vse32.v v6, (t0)
    .insn i 0x73, 0, zero, zero, 0x305
