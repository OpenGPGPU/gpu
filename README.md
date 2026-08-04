# RISC-V SIMT GPU Core

This repository is a Chisel 7.x implementation of a GPU core whose lanes
execute the RISC-V ISA using a SIMT execution model.

The design takes architectural guidance from the sibling `opengpu` project,
but keeps this implementation small, dependency-light, and incrementally
verifiable.

## Structure

- `src/main/scala/gpu/core/frontend/decode/` — unified scalar/FPU/RVV decoder and pipeline
- `src/main/scala/gpu/core/frontend/simt/` — SIMT divergence/reconvergence state
- `src/main/scala/gpu/core/execute/` — execution units
- `src/main/scala/gpu/core/simt/` — SIMT lane wrapper
- `src/main/scala/gpu/config/` — architectural configuration
- `src/test/scala/` — tests
- `build.sbt` — Scala and Chisel dependency configuration

## Quick start

1. Install Java 26 and sbt.
2. Run:
   - `sbt compile`
   - `sbt test`

## Current scope

- RV32I/M integer, branch, jump, load/store and upper-immediate decoding
- Zicsr, system/fence, and explicit illegal-instruction reporting
- F/D/Zfh floating-point pipeline routing and execution controls
- Precise allow-listed RVV arithmetic, mask, configuration, and vector-memory routing
- OpenGPU-compatible vector-branch, join, and cease warp-control decoding
- Round-robin hardware-warp allocation and active/blocked/finish state tracking
- Independent per-warp SIMT divergence stacks with recycling clear
- Scalar branch/JAL/JALR target resolution with signed and unsigned comparisons
- Lane-mask SIMT branch resolution and ordered divergence-stack write sequencing
- Branch and restored-path PC/mask feedback into warp scheduling
- Backpressured in-order instruction fetch with same-cycle request replacement
- Warp ID, PC, and active-mask preservation through unified decode
- Instruction-access-fault routing distinct from illegal-instruction reporting
- Table-derived scalar source-register usage for precise hazard checks
- Scalar decode/RF metadata alignment across scoreboard stalls
- Elastic RV32I integer execution with register/immediate/PC operand selection
- Exclusive scalar dispatch for integer/MUL/DIV/branch/memory/system/trap
- Elastic RV32M `MUL/MULH/MULHSU/MULHU` execution and atomic commit
- Iterative RV32M `DIV/DIVU/REM/REMU` with architectural corner cases
- Scalar conditional/JAL/JALR execution and scheduler redirect
- Atomic RF writeback and warp redirect with fair commit arbitration
- Integrated RV32I plus RV32M multiply fetch-to-commit loop in `GpuCore`
- Per-warp scalar register file with x0 and deterministic writeback bypass
- Atomic per-warp RAW/WAW scoreboard reservation and release
- Parameterized SIMT lane count and active-lane masking
- RV32I integer ALU operations

Planned next stages are vector execution, system/trap handling, and the
data-memory interface.
