# Qualification

Evidence matrix for the features the GPU advertises, refreshed 2026-09-20. A
feature is ready for software use only when the RTL, the ABI header, driver
validation and an integration test agree. This document is the link between
those four; [GRAPHICS_ROADMAP.md](GRAPHICS_ROADMAP.md) states priorities and
[../timing/README.md](../timing/README.md) states physical status.

Test existence is not a functional claim, a passing simulation is not a
silicon claim, and a completed physical tool run is not timing closure. The
"Status" column below is deliberately conservative: **functional** means a
passing behavioral test through the unified path, **physical** names the
separate PPA result.

## Reproduce

```sh
# Behavioral boundary suites (Scala).
sbt -batch 'set Test / parallelExecution := false' 'testOnly \
  opengpu.graphics.GpuAbiLayoutSpec opengpu.graphics.RenderHostSpec \
  opengpu.graphics.OutputMergerSpec opengpu.graphics.MsaaSpec \
  opengpu.graphics.KernelFragStageSpec opengpu.graphics.RenderPipelineSpec \
  opengpu.core.memory.GraphicsAddressTranslatorSpec opengpu.system.GpuSystemSpec \
  opengpu.system.GpuHostAxiSpec opengpu.system.GpuHostSystemAxiSpec \
  opengpu.dma.StridedCopyEngineSpec'

# Host-side driver programs and test-selection guard.
python3 scripts/test_driver.py
python3 scripts/test_test_selection.py

# Checked workload baselines.
python3 scripts/benchmark_gpu.py

# Physical qualification (current RTL, real clock, SRAM macros).
python3 scripts/qualify_ppa.py generated/qualification/rtl/gpu-system GpuSystem \
    --output generated/qualification/ppa/gpu-system --stage synthesis
python3 scripts/qualify_ppa.py generated/qualification/rtl/strided-copy StridedCopyEngine \
    --output generated/qualification/ppa/strided-copy --stage route
```

## Advertised-feature evidence matrix

Capability bits are defined in `GpuCapabilities.scala` and mirrored in
`driver/gpu_abi.h`; `GpuAbiLayoutSpec` fails the build if the two drift.

| Advertised surface (bit) | RTL owner | Behavioral coverage | Driver / ABI | Status |
|---|---|---|---|---|
| Unified commands (6) | `GpuCommandRouter`, `GpuCommandMmio` | `GpuCommandRouterSpec`, `GpuCommandMmioSpec` | `GPU_UCMD_*` in `gpu_abi.h` | functional |
| Unified render, descriptor fetch (20) | `RenderHost` | `RenderHostSpec` mode tests, `GpuHostSystemAxiSpec` render reset | `gpu_job_record` words; `opengpu_compute.c` builds the descriptor | functional |
| Unified reset (18) | `RenderHost` `drained`, router reset | `GpuSystemSpec` drain/reset, `GpuHostSystemAxiSpec` reset, reject-while-drain, delayed-write drain | `GPU_UCMD_OP_RESET` | functional |
| Descriptor sample-mode admission (7, 17:16) | `RenderHost` + `Msaa.validModeWord` | `RenderHostSpec` invalid-word recovery at capacities 1/2/4 | `GPU_RENDER_STATUS_INVALID_SAMPLE_MODE` | functional |
| Descriptor fetch-fault retention | `RenderHost` `textureFaultNow` during fetch/launch | `RenderHostSpec` pre-launch fault retention, `GpuHostSystemAxiSpec` texture/page fault reporting | `GPU_RENDER_STATUS_MEMORY_FAULT` | functional |
| Fragment core / programmable shaders (0) | `KernelShaderStage`, `KernelFragStage` | `KernelFragStageSpec`, `KernelShaderStageSpec`, `RenderPipelineSpec` | `tests/opengpu_shader_validator_test.c` | functional; **physical FAIL** |
| Vertex core (2) | `KernelVertStage` | `KernelVertStageSpec` | `gpu_vert_draw_record` | functional |
| Clear engine (3) | `FillEngine` | `FillEngineSpec`, `GpuSystemSpec` fill | `GPU_UCMD_OP_FILL` | functional |
| Blit engine (4) | `CopyEngine` | `CopyEngineSpec` | `GPU_UCMD_OP_COPY` | functional |
| Strided engine (5) | `StridedCopyEngine` | `StridedCopyEngineSpec`, `GpuSystemSpec` strided | `GPU_UCMD_OP_STRIDED_COPY` | functional; **physical FAIL** |
| MSAA sample modes (7) | `Msaa`, `SampleCoverage`, `OutputMerger` | `MsaaSpec`, `OutputMergerSpec`, `GpuSystemSpec` resolve | `GPU_REG_MSAA_CONFIG` legacy shadow | functional; physical separate |
| Persistent depth (19) | `OutputMerger` depth path | `OutputMergerSpec`, `RenderPipelineSpec` | depth words in the render descriptor | functional |
| Texture sampling | `TextureUnit`, `TexSampleUnit`, `TexturedFragStage` | `TextureUnitSpec`, `TexSampleUnitSpec`, `RenderCoreL2Spec`, `GpuWorkloadSpec` | texture descriptor words 6/7/8 | functional |
| Sv32 translation + scoped TLB flush | `GraphicsAddressTranslator`, `Sv32PageTableWalker` | `GraphicsAddressTranslatorSpec`, `GpuAbiLayoutSpec` flush encoding | `tests/opengpu_tlb_flush_test.c`, `opengpu_mmu_test.c` | functional |
| ABI layout | header + bundles | `GpuAbiLayoutSpec` (10 cases) | `driver/gpu_abi.h` | functional |
| Scheduler / GEM / display ownership | `opengpu_scheduler.c`, `opengpu_drm_device.c`, `opengpu_display.c` | host programs via `scripts/test_driver.py` | DRM/GEM, render-only DT | build + host tests |

## Submission-path regression evidence

The reset/admission work (roadmap priority A) is covered by cases that exercise
active rendering, delayed responses, invalid modes and clean recovery:

- `GpuHostSystemAxiSpec` "drain a unified render and delayed writes before
  acknowledging reset" — a render is in flight and the memory master delays
  write acknowledgements; reset must not complete until retirement.
- `GpuHostSystemAxiSpec` "complete a unified reset through the AXI control
  path" and "reject work while a unified reset drains and recover afterwards".
- `GpuSystemSpec` "drain in-flight unified work and reset the command path on
  request" and "wait for graphics retirement even when the shared L2 is empty".
- `RenderHostSpec` "reject invalid descriptor sample words and recover
  (capacity=1/2/4)" — whole-word admission, `GPU_RENDER_STATUS_INVALID_SAMPLE_MODE`,
  and the engine returns to idle.
- `RenderHostSpec` "fail a descriptor fetch fault before launch and retain it
  until completion" — a fault during descriptor fetch is reported, not lost in
  the launch gap.
- `GpuHostSystemAxiSpec` texture/page fault reporting with recovery.

## Validation performed (2026-09-20)

| Check | Command | Result |
|---|---|---|
| Boundary Scala suites | 11-spec `testOnly` list above | 99 tests / 11 suites, all passed (12m42s) |
| New reset/admission cases | `RenderHostSpec` + `GpuHostSystemAxiSpec` | passed |
| Host driver programs | `python3 scripts/test_driver.py` | 7/7 pass (`shader_validator`, `resolve_validator`, `depth_validator`, `tlb_flush`, `kernarg_va`, `asid`, `mmu`) |
| Test selection | `python3 scripts/test_test_selection.py` | PASS (boundary edits force system regressions) |
| Driver build | `driver/` against cached Linux | `gpu_drv.ko` built, kernel release 7.2.0 |
| Workload sweep | `python3 scripts/benchmark_gpu.py` | 10/10, `generated/qualification/workloads/results.json` |
| Physical | `scripts/qualify_ppa.py` | both runs **FAIL** at 1 GHz; see below |

## Workload baselines

Deterministic simulation of `GpuHostSystemAxi` (4 lanes, 2 warps, 8-set/2-way
L2, 8-byte memory data, 4-cycle write-ack delay, Sv32 identity superpage). These
are a repeatable reference for relative comparisons, not an SoC bandwidth
prediction. Counters overlap and must not be summed as independent time.
Source, configuration and per-file SHA-256 are in
`generated/qualification/workloads/manifest.json`.

| Workload | Cycles | OM stall | Raster stall | Staging r/w bytes | Lower r/w bytes | FB xlate stall | L2 misses | MSHR merges |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| flat_16_1x | 5728 | 4397 | 4399 | 0 / 0 | 7568 / 15360 | 174 | 98 | 36 |
| flat_16_2x | 11240 | 9726 | 9878 | 0 / 0 | 11664 / 32768 | 343 | 202 | 76 |
| flat_16_4x | 21534 | 19777 | 20132 | 0 / 0 | 19152 / 67328 | 710 | 476 | 233 |
| flat_32_1x | 20800 | 18646 | 18706 | 0 / 0 | 18704 / 63488 | 723 | 431 | 195 |
| flat_32_2x | 41333 | 38623 | 39238 | 0 / 0 | 33688 / 131072 | 1459 | 905 | 435 |
| flat_32_4x | 81538 | 77818 | 79333 | 0 / 0 | 59496 / 265984 | 2922 | 1926 | 1053 |
| shader_16_1x | 65451 | 1864 | 60628 | 49152 / 131072 | 56784 / 150528 | 282 | 118 | 55 |
| shader_16_4x | 78054 | 15476 | 73231 | 49152 / 131072 | 65808 / 194688 | 1083 | 375 | 171 |
| texture_16_1x | 5948 | 2547 | 4592 | 0 / 0 | 8020 / 15360 | 397 | 95 | 26 |
| overdraw_16_1x | 8448 | 5943 | 6231 | 0 / 0 | 16016 / 15360 | 2005 | 145 | 71 |

Observations the baseline supports: flat rendering is dominated by output-merge
serialization (`om_stall` tracks `raster_stall`), MSAA scales cycles roughly with
sample count, and the programmable shader adds ~60 k cycles and ~180 KB of
staging traffic over the equivalent flat draw. Texture sampling adds only a small
cost here (one texture TLB miss, no gradient LOD path).

## Physical status

Both current qualification runs fail at 1 GHz; neither is timing closure.

- `gpu-system` (bounded integrated top, synthesis estimate): 505.13 MHz
  `core_clock`, worst setup -979.70 ps, TNS -1.47 M ps, 801,488 cells, 62 SRAM
  macros, 155,887 um^2, 1.021 W.
- `strided-copy` (post-route, includes IO): 505.06 MHz `core_clock`, worst setup
  -979.97 ps / 718 violations, hold +30.70 ps / 0 violations, DRC 0 / antenna 0.
  The failing path is a flop-to-flop descriptor address-arithmetic carry cone.

The two runs independently identify the same cone, which is the next RTL lever.
Details and artifact paths are in [../timing/README.md](../timing/README.md).

## Not verified / explicit limits

- **Guest boot / ARTI-QEMU**: the recorded guest run reached QEMU launch but
  timed out at 300 s with no result and was terminated. It is not a pass and no
  ARTI co-simulation result is claimed.
- **No full RTL regression re-run**: the passing evidence is the focused
  boundary suites and the workload sweep, not the entire Scala test set.
- **PPA is not closed**: see above. The 25% boundary budget is a placeholder, and
  no parent-level per-interface budget exists yet.
- **Texture MODULATE truncates**: `(frag * texel) >> 8` maps full scale to 254,
  by design and asserted in `RenderCoreL2Spec` and `GpuWorkloadSpec`. It is a
  fixed-function convention, not a rounding bug, but it is lossy.
- **Display/scanout** is exercised only in simulation; no signal generation or
  hardware scanout is verified.
- **Address translation** keeps global identity mappings for compatibility and
  has no page-fault handling; it is not full VM isolation.
- **Shader ISA growth** is driven by the validation profile; broader RVV/ISA
  coverage is not yet qualified against a real compiler corpus.
