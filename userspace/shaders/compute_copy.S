/* Symbolic OpenGPU compute shader. At launch x1 points to kernarg. */
    .option norvc
    .text
    .globl shader
shader:
    lw t0, 0(x1)
    sw t0, 4(x1)
    /* OpenGPU cease is a custom SYSTEM immediate. */
    .insn i 0x73, 0, zero, zero, 0x305
