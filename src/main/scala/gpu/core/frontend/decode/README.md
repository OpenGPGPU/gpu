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
