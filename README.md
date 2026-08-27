# RISC-V SIMT GPU Core

This repository is a Chisel 7.x implementation of a GPU core whose lanes
execute the RISC-V ISA using a SIMT execution model.

The design adopts a commercial-GPU-style architecture: unified shading on the
SIMT lanes plus separated fixed-function graphics, in a small, dependency-light
package that is incrementally verifiable.

## Structure

- `src/main/scala/opengpu/core/frontend/decode/` — unified scalar/FPU/RVV decoder and pipeline
- `src/main/scala/opengpu/core/frontend/simt/` — SIMT divergence/reconvergence state
- `src/main/scala/opengpu/core/execute/` — execution units
- `src/main/scala/opengpu/core/simt/` — SIMT lane wrapper
- `src/main/scala/opengpu/graphics/` — graphics pipeline (rasterizer, interpolators, output merger, geometry, command buffer), see `docs/GRAPHICS_ROADMAP.md`
- `src/main/scala/opengpu/config/` — architectural configuration
- `src/test/scala/` — tests
- `build.sbt` — Scala and Chisel dependency configuration
- `docs/GRAPHICS_ROADMAP.md` — graphics pipeline roadmap and resolved design decisions

## Quick start

1. Install Java 26 and sbt.
2. Run:
   - `sbt compile`
   - `sbt test`

## Current scope

- RV32I/M integer, branch, jump, load/store and upper-immediate decoding
- Zicsr, system/fence, and explicit illegal-instruction reporting
- F/D/Zfh floating-point pipeline routing and execution controls
- FP32 FMA plus exact sign-injection, min/max, compare, classify, bit-move,
  integer/FP32 conversion with dynamic frm, rtz, and NV/NX flags, and flw/fsw
  memory execution in the FPU backend
- Precise allow-listed RVV arithmetic, mask, configuration, and vector-memory routing
- RVV integer divide/remainder (`vdivu/vdiv/vremu/vrem`) in vv/vx forms
- RVV FP32 lane-local operations: sign injection, min/max, comparisons,
  add/sub/mul/div, vfrdiv/vfrsub, vfmv.v.f/vfmerge.vfm, the
  vfcvt.xu.f.v/vfcvt.x.f.v/vfcvt.f.xu.v/vfcvt.f.x.v and rtz integer forms,
  vfclass.v/vfsqrt.v/vfrec7.v/vfrsqrt7.v, and all eight fused FMA forms
  over FVV/FVF operands
- RVV FP exception flags (NV/DZ/OF/UF/NX) committed alongside scalar FPU flags
  and accumulated with scalar FPU flags into per-warp fflags state; frm feeds
  vector FP rounding and scalar FPU dynamic rounding
- Physical vector register file emission: per-warp 32 x VLEN register banks
  mirror ASAP7 1RW SRAM macros for three reads plus one write, with write-through
  bypass, selected by `useBlackBoxes` in `GpuCore` and emitted by `EmitTimingRtl`
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

## Graphics pipeline

`src/main/scala/opengpu/graphics/` implements a graphics front-end and render
pipeline on the unified-shader + separated-fixed-function model:
`MatrixTransform` (4x4 MVP) -> `GeometryStage` (perspective divide + viewport)
-> `NearClipStage` (Sutherland-Hodgman clipping) -> `TriangleRasterizer`
(fixed-point, top-left fill rule, cull) -> `FragmentInterpolator` /
`PerspectiveInterpolator` (barycentric colour + depth, perspective-correct) ->
`OutputMerger` (depth test + write to software-allocated colour/depth buffers),
driven by `CommandBufferStage` (reads draw-call records from host memory) and
composed in `RenderPipeline` / `RenderCore`. The framebuffer, depth buffer and
command buffer are all software-allocated shared memory; hardware only computes
addresses and issues reads/writes. See `docs/GRAPHICS_ROADMAP.md`.

Current development continues on RVV execution coverage, CSR/trap semantics,
host/software integration, unified (SIMT) shading, and full-system physical
timing closure.
