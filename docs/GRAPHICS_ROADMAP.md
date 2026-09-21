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
- Sv32 private VA windows and ASIDs. Command, framebuffer, texture,
  kernarg/VB staging, shader data loads and the programmable `vtex.sample`
  path translate with CU accesses. Global identity mappings remain but are
  read/write, non-executable; compute, fragment and vertex code use private
  executable windows. Host vertex→fragment translation and instruction-fault
  recovery are covered. Fill/blit/strided DMA on the shared kernel-word port
  remain physical. This is not yet full VM isolation.
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
| Fragment / programmable shaders (0) | `KernelFragStage` | functional; instr VA |
| Vertex core (2) | `KernelVertStage` | functional; instr VA pending |
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
descriptor-fetch fault retention; texture/page-fault reporting; mixed sample
modes in sequence; STATUS.ERROR W1C leaving the owning completion intact; and
completion-slot backpressure until POP (`RenderHostSpec`, `GpuCommandMmioSpec`,
`GpuHostSystemAxiSpec`, `ProgrammableTextureAxiSpec`, `GpuSystemSpec`). Invalid
sample words, mixed 1x/2x/4x draws, descriptor bus faults, ERROR/completion
retention and a follow-up render held behind an unpopped completion are covered
on the integrated AXI path; render-host port backpressure remains covered in
`RenderHostSpec`. Programmable texture coverage runs `vtex.sample` with
different texture VA/PA, invalid PTE and page-table/texel bus faults, recovery,
and reset while an accepted texture read awaits data.

Workload note (`scripts/benchmark_gpu.py`): flat draws are OM-bound
(`om_stall` ≈ `raster_stall`, `om_conflict` = 0). Measured changes kept:
`GraphicsConfig.omInflight` 4 → 8 (~5–7% flat cycles) and a same-cycle
retire/accept on graphics TLB hits (~1% more). Programmable shading is still
staging-bound. Framebuffer translation-stall cycles stay high because the
word client backs up on downstream memory ready, not only on walks — further
flat gains need outstanding translated requests or a wider OM memory port.
Numbers land in `generated/qualification/workloads/`.

## Qualification baseline

Recorded from a clean checkout of `28eebd47634843842c3254ca70bcd842139cabbf`
(instruction-translation commit; dirty-tree timing/command-processor work was
not included). Follow-up on the dirty tree that closes shared-CU reuse and
I-cache shootdown: fragment-core guest now also passes (see Known limits).

Re-measured on `1194224f492d53b838a48c9d09841f6465cecf38` (submission-contract
coverage; includes kernarg/VB `VECTOR_SATP` translation and shared-CU reuse
fixes after `28eebd4`):

| Workload | `28eebd4` cycles | `1194224` cycles | Δ |
|---|---:|---:|---:|
| `flat_16_1x` | 5271 | 5271 | 0 |
| `flat_32_1x` | 19266 | 19266 | 0 |
| `shader_16_1x` | 65237 | 60455 | −7.3% |
| `shader_16_4x` | 75749 | 70772 | −6.6% |
| `texture_16_1x` | 5743 | 5743 | 0 |
| `overdraw_16_1x` | 7973 | 7973 | 0 |

Flat still shows `om_conflict` = 0, `om_stall` ≈ `raster_stall`, and
`framebuffer_translation_stall_cycles` ≈ 60% of `flat_16_1x` despite only two
framebuffer TLB misses — the translator’s single respond slot backs up behind
memory ready. Shader improvement tracks the staging/VA work landed after the
instruction-translation baseline. Next flat lever remains outstanding
translated requests (or a wider OM memory port). Next lever tried on this tree:
a `maxOutstanding`-deep hit queue in `GraphicsAddressTranslator` collapses
`framebuffer_translation_stall` (3160 → ~17 on `flat_16_1x`) but a non-flow
queue adds a cycle of latency that only wins ~2% on `flat_16_1x` and regresses
larger flats; a flow queue combos through the graphics request arbiter. Keep
measuring before landing either outstanding translations (registered skid that
does not combo with the arbiter) or a wider OM port.

| Gate | Result |
|---|---|
| Boundary suites (roadmap Reproduce `testOnly` list + `GpuCommandMmioSpec`) | 131/131 pass on `1194224` |
| `scripts/test_driver.py` + `scripts/test_test_selection.py` | pass |
| Workload sweep (`scripts/benchmark_gpu.py`, 10 cases) | pass; `manifest.json` commit `1194224` |
| Guest DRM, default (`GPU_FRAG_CORE=0`) | pass; powers off (`OPENGPU USERSPACE DRM PASS`) |
| Guest DRM, fragment-core (`GPU_FRAG_CORE=1`) | pass on the follow-up tree (shared-CU L1 probe progress + I-cache invalidate on `TLB_FLUSH` + 5× frag-core draw watchdog); powers off (`OPENGPU USERSPACE DRM PASS`) |

Flat workloads remain OM-bound (`om_stall` ≈ `raster_stall`, `om_conflict` = 0).
`flat_16_1x` is 5271 cycles. Programmable `shader_16_1x` is staging-bound
(`om_stall` = 0, `raster_stall` = 55985, `staging_read_bytes` = 49152).


## Next work

1. **Keep the submission contract covered** — every submission-path change
   must exercise descriptor errors, reset-during-work, delayed writes,
   completion backpressure, recovery and mixed sample modes. Boundary edits
   must pull system integration tests.
2. **Measure before optimizing** — compare with `scripts/benchmark_gpu.py`
   under the same source hash, scene and memory model. OM depth 8 and
   hit-path graphics translation (accept on response retire) are in after
   measured wins. Flat counters on `1194224` still justify outstanding
   translated requests or a wider OM memory port (`om_conflict` remains 0;
   `framebuffer_translation_stall` stays high with only two TLB misses).
3. **Physical closure** — the strided-copy descriptor address cone and the
   command-router dispatch cone are pipelined; the FP32 FMA lane now runs
   four stages. The integrated top's binding path moved to the command-router
   completion round-robin arbiter grant logic, so 1 GHz is not met yet. See
   [../timing/README.md](../timing/README.md) for the per-run table (several
   rows are dirty-tree measurements and need a clean-commit re-run). Derive
   real parent IO budgets.
4. **Software-driven growth** — grow the shader ISA from a small compiler
   corpus. Compute and graphics fragment shader code use private executable
   windows; the shared identity map is read/write but non-executable. Shared-CU
   vertex→fragment reuse and vertex instruction-fault recovery are now covered:
   L1 probes progress while a demand miss waits for an L2 eviction, avoiding
   the circular wait exposed by vector-heavy vertex kernels.
  Remaining isolation work is removing or bounding the identity mappings and
  translating fill/blit/strided DMA off the shared kernel-word port.

## Known limits

- Guest ARTI/QEMU: the default, fragment-core, and vertex+fragment-core
  end-to-end DRM tests pass and power off cleanly on the follow-up tree
  above. Programmable `vtex.sample`, kernarg/VB staging and shader data loads
  all translate under the context ASID (`VECTOR_SATP`); fill/blit/strided DMA
  on the shared kernel-word port remains physical.
- Qualification gate is boundary suites + workload sweep, not the full Scala
  suite.
- No parent-level per-interface timing budgets.
- Display/scanout is simulation-only.
- Shared identity mappings remain (read/write, non-executable) as a fallback
  when a private VA window cannot be installed. Fragment instruction faults
  fail the render and allow a later draw to recover; vertex instruction faults
  and subsequent shared-CU reuse are also covered. No resumable page faults or
  full removal of identity maps yet.
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
   opengpu.system.ProgrammableTextureAxiSpec \
   opengpu.dma.StridedCopyEngineSpec'
python3 scripts/test_driver.py
python3 scripts/test_test_selection.py
python3 scripts/benchmark_gpu.py
```
