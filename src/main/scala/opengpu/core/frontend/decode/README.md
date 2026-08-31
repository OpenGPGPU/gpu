# Decode architecture

`DecodePipe` is the one-stage implementation. It decodes in parallel, stores
one in-order result, and exposes separate scalar, FPU, and vector Decoupled
outputs. Backpressure from the selected output stalls the shared input so
instructions cannot reorder across execution families.

The one-stage implementation is the canonical GPU decoder. A measured
two-stage experiment was removed after ASAP7 WC post-route results showed
negligible frequency improvement with substantially higher area and power.

`recognized` means that an instruction belongs to an opcode family. `valid`
means that its currently implemented function and format encoding is legal.
Unsupported encodings may therefore have `recognized = true` and
`valid = false`. `FullInstructionDecoder.illegalInstruction` is asserted for
every encoding outside the enabled implementation allow-lists. `DecodePipe`
routes that response to `scalarOut` with `ExecutionType.illegal`, preserving
the instruction, PC, and warp ID needed by the trap/warp-control path.

Scalar, FPU, and vector control generation all use Chisel
`DecodePattern`/`DecodeField`/`DecodeTable`. FPU and vector legality remains a
bounded implemented subset; new operations must be added as explicit table
rows together with directed decode tests. In particular, RVV arithmetic uses
an explicit per-instruction set of legal operand forms; it must never be
expanded as a blind `funct6 x funct3` Cartesian product.

The scalar table also carries the OpenGPU controls needed by later issue
stages: RV32M multiply/divide selection, CSR/system/fence classification, and
the custom vector-branch/join/cease warp controls. These controls are decoded
but do not imply that their execution units have already been implemented.

The graphics sampler instructions use two explicit custom encodings:
`tex.sample rd, rs1, rs2` (custom-0, opcode `0x0b`) samples one coordinate
per warp, while `vtex.sample vd, vs1, vs2` (custom-1, opcode `0x2b`) samples
Q16.16 coordinates independently for each active vector lane. Both forms are
legal only in their documented `funct7`/`funct3` rows and are covered by
directed decoder tests.

Custom-1 also carries unary-vs2 `vquad.dfdx` (funct6 `001100`) and
`vquad.dfdy` (funct6 `001101`). They route to the integer ALU and replicate
right-minus-left or bottom-minus-top differences across each 2x2 lane group.
Decode support alone does not expose them to untrusted shaders; the driver
validator waits for quad-packed fragment dispatch.
