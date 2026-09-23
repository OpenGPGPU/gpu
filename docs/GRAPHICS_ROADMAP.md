# GPU graphics roadmap

What the graphics path implements today, and what remains. Contracts:
[HOST_INTERFACE.md](HOST_INTERFACE.md), [DRIVER_ARCHITECTURE.md](DRIVER_ARCHITECTURE.md).
Functional qualification: [FUNCTIONAL_QUALIFICATION.md](FUNCTIONAL_QUALIFICATION.md).
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
  kernarg/VB staging, fill/blit/strided/resolve DMA, shader data loads and the
  programmable `vtex.sample` path translate with CU accesses. Context roots
  start empty and only explicit private mappings grant access. ASID-0 identity
  mappings remain read/write and non-executable for Bare and line-invalidate;
  compute, fragment and vertex code use private executable windows. Host
  vertex→fragment translation and instruction-fault recovery are covered.
  VM-enabled mapping failures abort the operation.
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
| Vertex core (2) | `KernelVertStage` | functional; instr VA |
| Clear / blit / strided (3–5) | DMA engines | functional; strided PPA FAIL |
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
and reset while an accepted texture read awaits data. Kernarg/VB staging
covers non-identity VA→PA, invalid PTE and page-table bus faults with
recovery, ASID switch / scoped flush shootdowns, plus a CPU rewrite of the
translated vertex-buffer PA across draws (uncached staging must observe it
without an L2 invalidate).

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
not included). Intermediate re-measure on
`1194224f492d53b838a48c9d09841f6465cecf38` (submission-contract coverage;
kernarg/VB `VECTOR_SATP` translation and shared-CU reuse). Current clean
baseline is `e2e155efa26b164a708f2f4a532ca7b68717a838` (staging bus-fault /
CPU-update coverage and uncached staging policy preservation):

| Workload | `28eebd4` | `1194224` | `e2e155e` | Δ vs `1194224` |
|---|---:|---:|---:|---:|
| `flat_16_1x` | 5271 | 5271 | 5271 | 0 |
| `flat_32_1x` | 19266 | 19266 | 19266 | 0 |
| `shader_16_1x` | 65237 | 60455 | 68035 | +12.5% |
| `shader_16_4x` | 75749 | 70772 | 78388 | +10.8% |
| `texture_16_1x` | 5743 | 5743 | 5743 | 0 |
| `overdraw_16_1x` | 7973 | 7973 | 7973 | 0 |

Flat is unchanged and still shows `om_conflict` = 0, `om_stall` ≈
`raster_stall`, and `framebuffer_translation_stall_cycles` ≈ 60% of
`flat_16_1x` despite only two framebuffer TLB misses — the translator’s
single respond slot backs up behind memory ready. Shader cycles rose on
`e2e155e` because staging now preserves its uncached request policy through
Bare and Sv32 (CPU-coherent kernarg/VB); the earlier `1194224` win used
page/PTE cached policy on that path. Next flat lever remains outstanding
translated requests (or a wider OM memory port). Next lever tried on this
tree: a `maxOutstanding`-deep hit queue in `GraphicsAddressTranslator`
collapses `framebuffer_translation_stall` (3160 → ~17 on `flat_16_1x`) but a
non-flow queue adds a cycle of latency that only wins ~2% on `flat_16_1x`
and regresses larger flats; a flow queue combos through the graphics request
arbiter. Keep measuring before landing either outstanding translations
(registered skid that does not combo with the arbiter) or a wider OM port.

| Gate | Result |
|---|---|
| Boundary suites (roadmap Reproduce `testOnly` list + `GpuCommandMmioSpec`) | 136/136 pass on `e2e155e` |
| `scripts/test_driver.py` + `scripts/test_test_selection.py` | pass |
| Workload sweep (`scripts/benchmark_gpu.py`, 12 cases incl. `app_16_4x`) | 11-case baseline on `e2e155e`; `app_16_4x` measured on this tree |
| Guest DRM, default (`GPU_FRAG_CORE=0`) | pass on `cfc045a` (private fill/blit/strided DMA VAs); powers off |
| Guest DRM, fragment-core (`GPU_FRAG_CORE=1`) | pass on `cfc045a`; powers off |
| Guest DRM, vertex+fragment (`GPU_FRAG_CORE=1` `GPU_VERT_CORE=1`) | pass on `cfc045a`; powers off |

Flat workloads remain OM-bound (`om_stall` ≈ `raster_stall`, `om_conflict` = 0).
`flat_16_1x` is 5271 cycles. Programmable `shader_16_1x` is staging-bound
(`om_stall` = 0, `raster_stall` = 63089, `staging_read_bytes` = 49152).


## Next work

The functional baseline includes private Sv32 mappings with context-local
revocation and ASID reuse, failure cleanup across compute/render ioctls,
reset and completion-backpressure recovery, seeded AXI fault sequences,
shader assembly validation, helper-lane derivatives, full-image reference
comparisons, and persistent-depth continuation at 1x/2x/4x. A context root
must not address another context's resources or unbound storage; validated VM
jobs must not rely on VA equal to PA. Keep these properties in the
[functional qualification gate](FUNCTIONAL_QUALIFICATION.md). Reproduce the
seeded AXI sequence with `OPENGPU_AXI_SEED=0x5eed2026 sbt -batch 'testOnly
opengpu.system.GpuHostSystemAxiSpec -- -z "replay randomized commands"'`.

1. **Keep the submission contract covered** — every submission-path change
   must exercise descriptor errors, reset-during-work, delayed writes,
   completion backpressure, recovery and mixed sample modes. Boundary edits
   must pull system integration tests.
2. **Measure before optimizing** — compare with `scripts/benchmark_gpu.py`
   under the same source hash, scene and memory model. OM depth 8 and
   hit-path graphics translation (accept on response retire) are in after
   measured wins. Flat counters on `e2e155e` still justify outstanding
   translated requests or a wider OM memory port (`om_conflict` remains 0;
   `framebuffer_translation_stall` stays high with only two TLB misses).
   Shader staging is intentionally uncached for CPU coherence; treat the
   `e2e155e` shader cycle rise vs `1194224` as the coherent baseline, not a
   regression to claw back by re-caching.
3. **Physical closure** — the strided-copy descriptor address cone and the
   command-router dispatch cone are pipelined; the FP32 FMA lane now runs
   five stages (completion add cut from invert/LZD-mask/mask-valid).
   Carry-save performance counters, divide finalize, and MSAA resolve
   scanline row bases reach **781.65 MHz** (−279.34 ps), with the limiter
   back on the FMA `csaSumReg` cone. See
   [../timing/README.md](../timing/README.md). Derive real parent IO budgets.
4. **Software-driven growth** — grow the shader ISA from a small compiler
   corpus. Compute and graphics fragment shader code use private executable
   windows; the ASID-0 identity map is read/write but non-executable. Shared-CU
   vertex→fragment reuse and vertex instruction-fault recovery are now covered:
   L1 probes progress while a demand miss waits for an L2 eviction, avoiding
   the circular wait exposed by vector-heavy vertex kernels. Fill/blit/strided
   DMA now translate under `VECTOR_SATP` (legacy kernel-word and unified
   engines). Context fill/blit/strided/resolve jobs map into a private DMA VA
   window at run time; resolve still invalidates L2 with the physical source
   base (`UCMD_PATTERN`) because host invalidate is PA-tagged. Line-invalidate
   remains physical. The symbolic corpus now also validates fixed-profile
   `vsext`/`vzext`/`vnclip`/`vsmul` (`userspace/shaders/fixed_width.S`). Remaining
   ASID-0 identity use is Bare bring-up and explicit line-invalidate.
5. **Workload-driven ISA** — add remaining VFUNARY1 (and any further
   widening/narrowing beyond the fixed SEW=32 profile already in the corpus)
   when a shader in the validated corpus or a target workload needs them.
   Capture the motivating shader, validator rules and execution/guest coverage
   with each addition.
6. **Graphics feature decision** — measure target scenes before adding
   centroid or per-sample interpolation or framebuffer compression. Record
   the observed quality or bandwidth gap, expected benefit and verification
   scene; retain current center interpolation and uncompressed storage until
   a workload demonstrates a need. The sweep covers flat scenes at 1x/2x/4x,
   shader and overdraw at 1x/4x, texture at 1x, and `app_16_4x` (UV-mapped
   gradient texture, nearer overlapping triangle, 4x MSAA). The shared-L2
   and `app_16_4x` 4x scenes both see 106 partially covered textured samples
   that differ from a quarter-pixel per-sample UV reference (752 summed
   RGB-channel levels, max single-channel delta 35). On `app_16_4x` only
   4/106 of those samples reach a single-channel delta ≥ 8 (the visible
   threshold used below). Final-frame uniform 64-byte lines drop to 21/64
   colour and 27/64 depth versus 189/256 on solid `flat_32_4x` and 33/64 on
   `overdraw_16_4x`. The app frame takes 27,526 cycles and transfers 21,396
   read / 84,736 write bytes below L2.
   **Decision criteria (hold until a scene breaks them):** keep centre UV
   while fewer than 25% of partially covered textured samples have
   max-channel delta ≥ 8 against the per-sample reference; keep uncompressed
   storage while a representative frame stays under 50% uniform colour lines
   (solid flats are not predictive). Revisit only with a product quality bar
   or a frame that fails these thresholds.
7. **Platform integration** — replace virtual vblank/scanout after choosing
   display hardware and its interface. Evaluate a small Mesa/Gallium path
   against the stable userspace ABI, private-VM isolation and full functional
   qualification gate; scope it to a representative shader and draw first.

## Known limits

- Guest ARTI/QEMU: the default, fragment-core, and vertex+fragment-core
  end-to-end DRM tests pass and power off cleanly on `cfc045a`. Programmable
  `vtex.sample`, kernarg/VB staging, fill/blit/strided DMA and shader data
  loads all translate under the context ASID (`VECTOR_SATP`). Context
  fill/blit/strided/resolve jobs map buffers into a private DMA VA window at
  run time so they no longer depend on VA==PA; resolve still invalidates with
  the physical source base. VM-enabled mapping failures abort. Bare jobs and
  line-invalidate stay physical because L2 invalidate is PA-tagged. Context
  roots expose no identity fallback.
- Qualification gate is boundary suites + workload sweep, not the full Scala
  suite.
- No parent-level per-interface timing budgets.
- Display/scanout is simulation-only.
- ASID-0 identity mappings remain read/write and non-executable for Bare and
  explicit line-invalidate. Resolve engine traffic uses private DMA VAs under
  the context ASID; the pre-resolve L2 invalidate still names the physical
  source. No resumable page faults or full removal of identity maps yet.
- Shader ISA growth is validation-profile driven, not a real compiler corpus.

## Later / out of scope

- Nonblocking graphics translation, larger TLBs or wider OM only when
  counters justify the cost.
- Discrete PCIe/local VRAM, demand paging, tile-based rendering and display
  PHY/timing.

## Reproduce

Local runs keep the default parallel ScalaTest execution. GitHub CI sets
`Test / parallelExecution := false` so the runner stays within memory limits.

```sh
sbt -batch 'testOnly opengpu.graphics.GpuAbiLayoutSpec opengpu.graphics.RenderHostSpec \
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
