# GPU graphics roadmap

What the graphics path implements today, and what remains. Contracts:
[HOST_INTERFACE.md](HOST_INTERFACE.md), [DRIVER_ARCHITECTURE.md](DRIVER_ARCHITECTURE.md).
Physical numbers: [../timing/README.md](../timing/README.md).

**functional** = behavioral coverage on the unified path. **physical** = PPA.
A passing sim is not silicon; a completed tool run is not timing closure.

## Architecture (implemented)

- RISC-V SIMT with shared vertex/fragment shaders; fixed-function clip,
  raster, sample and depth/stencil/blend.
- Immediate-mode rendering; 2x2 fragment quads; 1x/2x/4x MSAA; perspective
  colour/UV and screen-space depth.
- RGBA8888 colour and D24S8 depth/stencil in software-owned shared DRAM.
- Product top `GpuHostSystemAxi`: one AXI4 control slave, one AXI4 memory
  master, one IRQ. GPU clients share GPU L2; CPU caches are separate.
- Unified commands for render, compute, copy, fill, strided copy, resolve and
  line invalidate. Render runs beside `RenderHost`; other opcodes use the
  system router; one completion slot/IRQ serves both.
- Driver scheduler owns GEM references, dependencies and fences. Display
  consumes GEM framebuffers; it does not own execution lifetime.
- Sv32 private VA windows and ASIDs. Command, framebuffer, texture and the
  programmable `vtex.sample` path translate with CU accesses. Global identity
  mappings remain but are read/write, non-executable; compute code runs from a
  private code window. This is not yet full VM isolation.
- External display hardware owns scanout and signal generation.

## Capability status

Bits live in `GpuCapabilities.scala` and `driver/gpu_abi.h`
(`GpuAbiLayoutSpec` fails on drift).

| Surface | Owner | Status |
|---|---|---|
| Unified commands (6) | `GpuCommandRouter` / `GpuCommandMmio` | functional |
| Unified render + descriptor fetch (20) | `RenderHost` | functional |
| Unified reset (18) | `RenderHost` + router | functional |
| Sample-mode admission (7, 17:16) | `RenderHost` + `Msaa` | functional |
| Descriptor fetch-fault retention | `RenderHost` | functional |
| Fragment / programmable shaders (0) | `KernelFragStage` | functional; physical FAIL |
| Vertex core (2) | `KernelVertStage` | functional |
| Clear / blit / strided (3–5) | DMA engines | functional; strided physical FAIL |
| MSAA (7) | `Msaa` / `OutputMerger` | functional |
| Persistent depth (19) | `OutputMerger` | functional |
| Texture sampling | `TextureUnit` / `TexturedFragStage` | functional |
| Sv32 + TLB flush | graphics + CU translators | functional |
| Scheduler / GEM / display | `opengpu_scheduler.c`, `opengpu_drm_device.c` | build + host tests |

Also in place: shared translated word clients, generated transaction-ID
ranges, one resolved-draw-state bundle, optional sim performance counters,
`scripts/benchmark_gpu.py` / `scripts/qualify_ppa.py`, platform DRM/GEM with
render-only DT, and the MSAA / stencil-blend / resolve contracts
([MSAA_DESIGN.md](MSAA_DESIGN.md),
[STENCIL_BLEND_DESIGN.md](STENCIL_BLEND_DESIGN.md)).

Legacy dedicated DMA MMIO remains supported. Execution shadow registers are
readable/writable for compatibility but do not configure unified renders.

Submission regressions that must stay green: reset with in-flight render and
delayed writes; reject-while-drain; invalid sample-mode admission (1/2/4);
descriptor-fetch fault retention; texture/page-fault reporting
(`RenderHostSpec`, `GpuHostSystemAxiSpec`, `GpuSystemSpec`).

Workload note (`scripts/benchmark_gpu.py`): flat draws are OM-bound
(`om_stall` ≈ `raster_stall`, `om_conflict` = 0). Measured changes kept:
`GraphicsConfig.omInflight` 4 → 8 (~5–7% flat cycles) and a same-cycle
retire/accept on graphics TLB hits (~1% more). Programmable shading is still
staging-bound. Framebuffer translation-stall cycles stay high because the
word client backs up on downstream memory ready, not only on walks — further
flat gains need outstanding translated requests or a wider OM memory port.
Numbers land in `generated/qualification/workloads/`.

## Next work

1. **Keep the submission contract covered** — every submission-path change
   must exercise descriptor errors, reset-during-work, delayed writes,
   completion backpressure, recovery and mixed sample modes. Boundary edits
   must pull system integration tests.
2. **Measure before optimizing** — compare with `scripts/benchmark_gpu.py`
   under the same source hash, scene and memory model. OM depth 8 and
   hit-path graphics translation (accept on response retire) are in after
   measured wins. Further flat gains likely need outstanding translated
   requests or a wider OM memory port (`om_conflict` remains 0).
3. **Physical closure** — pipeline the strided-copy descriptor address cone
   (~505 MHz today on both `gpu-system` and `strided-copy`; see
   [../timing/README.md](../timing/README.md)). Derive real parent IO budgets;
   1 GHz remains an objective.
4. **Software-driven growth** — grow the shader ISA from a small compiler
   corpus. Compute kernel code runs from a private, executable code window and
   the shared identity map is read/write but non-executable; the graphics
   shader core still fetches physical code. The remaining isolation step is
   enabling translation on that core and then removing or bounding the
   identity mappings.

## Known limits

- Guest ARTI/QEMU: both the default and fragment-core end-to-end DRM tests
  pass and power off cleanly. The programmable `vtex.sample` texture path
  routes through the translated texture client (a VM virtual address), while
  the kernarg staging port stays physical.
- Qualification gate is boundary suites + workload sweep, not the full Scala
  suite.
- No parent-level per-interface timing budgets.
- Display/scanout is simulation-only.
- Shared identity mappings remain (read/write, non-executable). The graphics
  shader core is untranslated, so fragment/vertex code is not yet isolated; no
  page-fault handling and not yet full VM isolation.
- Shader ISA growth is validation-profile driven, not a real compiler corpus.

## Later / out of scope

- Small Mesa/Gallium experiment once baselines are dependable.
- Nonblocking graphics translation, larger TLBs or wider OM only when
  counters justify the cost.
- Discrete PCIe/local VRAM, demand paging, tile-based rendering and display
  PHY/timing.

## Reproduce

```sh
sbt -batch 'set Test / parallelExecution := false' \
  'testOnly opengpu.graphics.GpuAbiLayoutSpec opengpu.graphics.RenderHostSpec \
   opengpu.graphics.OutputMergerSpec opengpu.graphics.MsaaSpec \
   opengpu.graphics.KernelFragStageSpec opengpu.graphics.RenderPipelineSpec \
   opengpu.core.memory.GraphicsAddressTranslatorSpec opengpu.system.GpuSystemSpec \
   opengpu.system.GpuHostAxiSpec opengpu.system.GpuHostSystemAxiSpec \
   opengpu.dma.StridedCopyEngineSpec'
python3 scripts/test_driver.py
python3 scripts/test_test_selection.py
python3 scripts/benchmark_gpu.py
```
