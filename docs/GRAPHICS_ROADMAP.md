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
  mappings remain read/write and non-executable for Bare bring-up;
  compute, fragment and vertex code use private executable windows. Host
  vertex→fragment translation and instruction-fault recovery are covered.
  VM-enabled mapping failures abort the operation.
- ARTI guest-memory GraphicHwOps owns QEMU scanout from SCANOUT_* registers;
  external display PHY remains out of scope.

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
`GraphicsConfig.omInflight` 4 → 8 (~5–7% flat cycles), then 8 → 16 with
`pendingDepth` 8 → 16 so translation is not refill-starved (~4–6% more on
flat); same-cycle retire/accept on graphics TLB hits (~1%); and a registered
non-flow outstanding-hit pending queue. Programmable shading is still
staging-bound. Further flat gains need a dual color/depth OM memory port or
a wider word fabric. Numbers land in `generated/qualification/workloads/`.

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
page/PTE cached policy on that path. A registered non-flow pending queue
(no `out.ready` combo into the shared arbiter) collapses that stall
(3160 → 17 on `flat_16_1x`, 14989 → 24 on `flat_32_1x`) and cuts
`flat_16_1x` 5271 → 5176 (−1.8%) while `flat_32_1x` moves 19266 → 19361
(+0.5%) at `omInflight` 8 / `pendingDepth` 8. Raising both to 16 keeps the
stall collapsed (17 / 23) and further cuts flats to **4975 / 18162**
(−3.9% / −6.2% vs the pending-8 point, −5.6% / −5.7% vs `e2e155e`). OM
remains the binder; next flat lever is a dual color/depth OM memory port.
A first dual-port wiring (separate colour/depth word clients through L2)
was measured and **not kept**: `flat_16_1x` 4975 → 4986 and `flat_32_1x`
18162 → 18831 — extra client/arbiter cost outweighed dual-issue. Revisit
only with a cheaper dual-issue path (shared bridge, dual-accept without a
second TLB client) or a wider word fabric.

| Gate | Result |
|---|---|
| Boundary suites (roadmap Reproduce `testOnly` list + `GpuCommandMmioSpec`) | 144/144 pass on `2eeb289` (2026-09-28) |
| `scripts/test_driver.py` + `scripts/test_test_selection.py` | pass on `2eeb289` (2026-09-28) |
| Shader corpus (`scripts/validate_shader_corpus.py`) | pass on `2eeb289` (2026-09-28), all profiles |
| Workload sweep (`scripts/benchmark_gpu.py`, 12 cases incl. `app_16_4x`) | 11-case baseline on `e2e155e`; `app_16_4x` measured on this tree |
| Guest DRM, default (`GPU_FRAG_CORE=0`) | pass on `cfc045a` (private fill/blit/strided DMA VAs); powers off |
| Guest DRM, fragment-core (`GPU_FRAG_CORE=1`) | pass on `cfc045a`; powers off |
| Guest DRM, vertex+fragment (`GPU_FRAG_CORE=1` `GPU_VERT_CORE=1`) | pass on `cfc045a`; powers off |
| `scripts/qualify_functional.sh` (full Scala suite, host driver, fixed-function and vertex+fragment guest DRM) | pass on `2eeb289` (2026-09-28): 557/557 Scala tests; both guests `OPENGPU USERSPACE DRM PASS` (vblank sequences 48 / 37) and power off |
| Guest userspace examples, fixed-function / fragment-core / vertex+fragment | all three pass on `2eeb289` (2026-09-28) with `OPENGPU USERSPACE EXAMPLES PASS`, including `fp_*`, `widen_alu`, `fixed_width`; `pipe_vertex_draw` correctly skips without a vertex core |

`2eeb289` adds the MSAA resolve read-use stage (`MsaaResolve.scala`: 9-state
FSM, `sReadResp` captures into `readData` and `sReadUse` accumulates), so these
runs are the functional coverage for that rewrite. `GpuAbiLayoutSpec` and the
guests exercise `typed resolve across advertised sample modes`. No `src/`
change landed between `2eeb289` and `675ffc9`, so the result carries to the
current tree; the dirty-tree display-size work in `scripts/run_arti_gpu.sh` is
not covered by these runs.

Flat workloads remain OM-bound (`om_stall` ≈ `raster_stall`, `om_conflict` = 0).
`flat_16_1x` is 4975 cycles after omInflight/pendingDepth 16. Programmable
`shader_16_1x` is staging-bound
(`om_stall` = 0, `raster_stall` = 63089, `staging_read_bytes` = 49152).


## Next work

Priority is **functional**: a usable DRM GPU under ARTI/QEMU (open card0,
submit, fence, read back). Cycle / PPA work stays secondary unless a guest
path is blocked.

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

1. **ARTI as a usable GPU** — `scripts/qualify_functional.sh` already boots
   fixed-function and vertex+fragment guests with `opengpu_drm_test`.
   Userspace apps on top of that API:
   - Fixed-function (`GPU_FRAG_CORE=0`): compute, `fp_unary`, `fp_binary`,
     `fp_fma`, `fp_div`, `fp_compare`, `fp_scalar`, `widen_alu`,
     `fixed_width`, triangle,
     `triangle_present`, `pipe_present`, `pipe_clear_draw`,
     `pipe_compute`, `pipe_blit`, `pipe_strided_blit`, `pipe_resolve`,
     `pipe_texture_draw`, `pipe_depth_pass`, `pipe_msaa_draw`.
   - Fragment core (`GPU_FRAG_CORE=1`, `GPU_VERT_CORE=0`): `fragment_tint`,
     `fragment_fp`,
     `fp_unary`, `fp_binary`, `fp_fma`, `fp_div`, `fp_compare`, `fp_scalar`,
     `widen_alu`,
     `fixed_width`, `triangle_present`, `pipe_present`,
     `pipe_clear_draw`, `pipe_compute`,
     `pipe_blit`, `pipe_strided_blit`, `pipe_resolve`, `pipe_texture_draw`
     (`vtex.sample` corpus binary staged as `/opengpu_fragment_texture.bin`),
     `pipe_depth_pass`, `pipe_msaa_draw`, `pipe_vertex_draw` (skips without a
     vertex core; corpus tint binary staged as `/opengpu_fragment_tint.bin`).
   - Vertex+fragment cores: `pipe_vertex_draw`, `pipe_resolve`.
   Preferred programmable bring-up:
   ```sh
   GPU_FRAG_CORE=1 GPU_USERSPACE_EXAMPLES=1 GPU_USERSPACE_EXAMPLES_ONLY=1 \
     scripts/run_arti_gpu.sh
   ```
   Vertex+fragment:
   ```sh
   GPU_FRAG_CORE=1 GPU_VERT_CORE=1 GPU_USERSPACE_EXAMPLES=1 \
     GPU_USERSPACE_EXAMPLES_ONLY=1 scripts/run_arti_gpu.sh
   ```
   Expect `OPENGPU USERSPACE EXAMPLES PASS`. `GPU_PIPE_SPIKE=1` is a
   compatibility alias for the fragment-core userspace path. Pipe DMA/draw
   surfaces for the spike are in. Debian interactive guest ships the same
   binaries on the OPENGPU ISO:
   ```sh
   scripts/run_arti_debian.sh
   # guest:
   /root/load_opengpu.sh
   /root/load_opengpu.sh examples
   /root/opengpu_triangle_present --hold
   ```
   (`BUILD_USERSPACE=0` skips the cross-build.) Mesa only if NIR stays small;
   see [GALLIUM_SPIKE.md](GALLIUM_SPIKE.md).
   **Display hardware is ARTI guest-memory GraphicHwOps** (`gpu_integration.yaml`:
   SCANOUT BASE/STRIDE/CONTROL/WIDTH/HEIGHT + `refresh_hz`). KMS programs those
   registers; QEMU presents guest GEM memory. ARTI uses the DRM soft timer with
   a nominal 30 Hz period to match `refresh_hz`: its RTL clock advances during
   transactions and IRQ polls, so a 100 MHz hardware period cannot track wall
   time. Rewriting only `SCANOUT_BASE` on same-mode flips reduced measured
   fixed 64x64 guest intervals from 2.21 s to 251 ms; vertex+fragment flips
   fell from 2.69–2.76 s to 293–297 ms. ARTI then shortened its post-MMIO
   settle for guest-memory `SCANOUT_BASE` writes only, yielding 33.61–33.76 ms
   and 33.29–33.74 ms respectively. Other registers retain the full settle.
   Continuously clocked devices use hardware vblank
   (`GPU_CAP_HW_VBLANK`, `SCANOUT_PERIOD`, IRQ bit2). ARTI's QEMU
   device truncates any programmed mode to the compile-time `ARTI_FB_WIDTH` /
   `ARTI_FB_HEIGHT` in `hw/misc/arti-rtl.c` and copies rows through a
   same-width on-stack buffer, so those constants have to follow the mode.
   The integration profile's `display:` block is the single source of the
   resolution: `scripts/gpu_display_config.py` reads it, and the scripts derive
   the elaboration, the driver default mode, the work-tree names and the PPM
   check from it, so the scanout cannot end up sized differently from the RTL.
   Change the mode by editing the profile rather than by setting a variable,
   and `scripts/test_display_config.py` guards the literals in
   `driver/gpu.dtsi` against it. `driver/gpu_integration_debian.yaml` is
   320x240 at 30 Hz; `driver/gpu_integration.yaml`, the reference profile the
   fast qualification runs use, is 64x64. Lowering `clk_freq_mhz` in the
   integration YAML does not
   help anything: ARTI ticks
   the model from `eval()` calls with no wall-time pacing, so the field only
   sets a SystemC period literal, and the driver independently hardcodes
   100 MHz for `SCANOUT_PERIOD` (`opengpu_hw.c`) while skipping it entirely
   under the `arti,rtl` compatible. Build the
   interactive Debian model once, then boot it:
   ```sh
   scripts/build_arti_debian_display.sh
   OPENGPU_AUTO_DISPLAY=triangle QEMU_DISPLAY=cocoa scripts/run_arti_debian.sh
   ```
   The standalone DRM scanout check uses `scripts/run_arti_display.sh` to
   capture the rendered modeset after the initial console scanout. The 64x64
   Verilator guest produced 2,016 green pixels, including `00fe00` at (1,1),
   and powered off after the DRM test. An earlier direct boot of the FlashSim
   QEMU and DRM initramfs produced a
   16x16 PPM with 120 rendered green pixels; pixel (1,1) was `00fe00`, matching
   the guest's expected framebuffer value. The Debian runner enables
   `opengpu-boot-display.service` by default: on boot it loads the driver,
   whose DRM fbdev client provides the Debian framebuffer console. Use
   `OPENGPU_AUTO_DISPLAY=triangle` for a GPU-rendered triangle on scanout,
   `OPENGPU_AUTO_DISPLAY=gradient` for the CPU KMS fill demo, or
   `OPENGPU_AUTO_DISPLAY=0` for manual loading. A headless Debian boot
   previously confirmed `/dev/dri/card0`, an active service, and a 16x16
   scanout PPM. `examples/triangle_present` and `examples/pipe_present` close
   the graphics-client loop: render into the mode buffer, then SETCRTC for
   ARTI/QEMU present (`pipe_opengpu_present` is the Gallium-shaped path).
   On a reused Debian disk, cloud-init stops any previously enabled gradient
   or triangle presenter, reloads the service unit and restarts it in console
   mode.
   Cocoa uses `zoom-to-fit=on`; an already-running window
   can enable **View → Zoom To Fit** and then be resized.
   Apps may still validate by reading colour GEMs; the QEMU window / PPM dump
   is the primary scanout path under ARTI.
   On 2026-09-24, the 64x64 Verilator model built with `--threads 4` and
   four parallel C++ compile jobs completed a 32 KiB hardware fill in a
   median 0.112 s (25,637 model ticks over five runs). The same RTL with
   `--threads 1` took a median 0.268 s: 2.39x slower for this operation.
   A headless Debian boot registered DRM at mode 64x64 and produced a nonblack
   64x64 scanout PPM.
   **320x240 (2026-09-28).** `scripts/build_arti_debian_display.sh` with no
   resolution variables builds the RTL, the Verilator model and QEMU at the
   profile's 320x240; the Debian runner then boots, registers DRM at
   `stride=1280 mode=320x240`, runs a fixed-function clear plus
   `opengpu_triangle_present`, and ARTI dumps a 320x240 P6 PPM whose pixel
   (1,1) is `ff0000` with 38,160 red triangle pixels against 38,640 clear
   pixels. Launch to that first GPU-present was 50 s host, of which the guest
   spent 18.30 s to the `mode=320x240` probe and 46.71 s to the last serial
   line, so clear + a 38k-pixel triangle + fence + SETCRTC is on the order of
   tens of seconds. A full-screen 320x240 redraw is 76,800 px at 18.8
   cycles/px, about 1.44M cycles, so it is a minutes-per-frame path, not a
   real-time one. Getting the size to take effect needed the resolution to
   stop being duplicated: it used to live in six places, and the
   `GPU_WIDTH`/`GPU_HEIGHT` variables reached only some of them, so a wider
   build rendered a 64x64 corner of a 64x64 window. It now has one source, and
   `build_arti_debian_display.sh` names each work tree after the mode instead
   of hardcoding `debian-64x64`. A zero-variable
   `scripts/run_arti_debian.sh` then reached the same render in 70 s host.
   The tick rates above are obsolete. They were measured against an older
   ARTI whose settle loop charged a fixed 20,000 idle cycles to every host MMIO,
   so the 0.23 MHz figure described settle overhead rather than model speed.
   The current model exits on real quiescence (`gpu_active()` = change flag +
   AXI queue occupancy, `ARTI_MODEL_IDLE_GRACE 16`) and pumps up to
   `ARTI_MODEL_IRQ_PUMP_CYCLES 262144` per 100 us IRQ poll while a job is live.
   `scripts/bench_arti_model.py` links the embedded model directly (no QEMU,
   no guest) and measures the two rates that matter. On the 64x64 **FlashSim**
   model:

   | Settle kind | Wall | Rate |
   |---|---:|---:|
   | idle (one quiet register read, 256 cycles) | 60-120 us | ~2-3 MHz |
   | active (32 KiB hardware clear, 5656 cycles) | 9-15 s | **~0.4-0.6 MHz** |

   and on the 320x240 **Verilator** model the same probe reports a 32 KiB
   clear in 0.97 s, about 5.8 kcycles/s, with an idle settle of 181 ms because
   the Verilator settle still charges its full idle budget per quiet MMIO.
    Runtime `--threads 8` changes neither figure: a hardware clear does not
    present enough parallel work to use the pool. Verilator is therefore about
    **10x FlashSim on active work and 1500x worse on idle MMIO**, so pick the
    backend by whether the run is GPU-bound or latency-bound.
    Verilator multithreading is already in effect and needs no runtime flag:
    the NBA phase is split into 8 tasks dispatched across the pool
    (`VGpuHostSystemAxi___024root___eval_nba` hands `__Vthread__nba__s0__t0`
    through `t7` to seven pool workers plus the calling thread and joins on
    `waitUntilUpstreamDone`), and `VerilatedContext` defaults `m_threads` to
    the process's available parallelism, so the pool already holds more
    workers than the model's `--threads 8` needs. A fill still does not speed
    up because Verilator partitions the logic graph, not memory traffic: with
    only the fill FSM and the memory AXI awake most tasks have nothing to do
    and only the barrier is left. Raising `ARTI_VERILATOR_THREADS`
    re-partitions the logic and needs a re-verilate; `contextp()->threads(n)`
    only sizes the pool and hard-aborts if it is below the model's `--threads`
    value, so it caps overhead on small jobs rather than adding parallelism.
    The binding limit is parallel *work*: the design defaults to one compute
    unit, and one draw is serial (raster, then fragment, then output merge).

   Run-to-run spread on a loaded host is wide (this is one shared machine), so
   quote the order of
   magnitude, not the fourth digit. The
   order-of-magnitude gap between idle and active on either backend is
   per-cycle evaluation cost while the design is awake, not settle policy, so
   `ARTI_MODEL_IDLE_GRACE` and `ARTI_MODEL_MMIO_ADVANCE` do not move it.
   `dut_commit.cpp` is compiled at
   `-O0` and its `_commit()` switch has 9,127 cases, which looks like the
   suspect; it is not. Recompiling that TU at `-O1` takes 1m52s and measures
   neutral over three interleaved trials, because the dirty list is nearly
   empty (`--instrument` reports width 6.2 mean / 28 max during a clear, but
   note it scans every page per tick and so inflates its own timings).
   Change detection is what fails to prune: 453 of 707 pages re-evaluate on
   every cycle of a *quiescent* design, 542 during a clear. Recovering the
   missing factor is a FlashSim emitter task, not a change here.
   **Consequence for anything that redraws.** A 64x64 frame is ~79k cycles
   (`flat_32_1x` at 18.8 cycles/px). The 2026-09-25 draw+present A/B below
   put that at 143 s on FlashSim and 32 s on Verilator, but those numbers are
   from an older model and no longer describe either backend. A measured
   320x240 run on the current Verilator model is the better reference: see
   the 320x240 entry below. 30 Hz remains out of reach at any resolution
   because a full-screen redraw needs tens of thousands of cycles per frame.
   Redraw on demand and pace on the completion
   fence instead of the vblank timer. A hardware clear is ~0.18 cycles/byte
   against the rasterizer's ~4.8, so 2D work should use fill/strided blit
   rather than the raster path.
   On 2026-09-25, a matched 64x64 Debian A/B used the same generated RTL,
   kernel, modules ISO, persistent-disk snapshot, QEMU arguments, and guest
   programs. The Verilator model was built with `--threads 8` (new builds
   default to 8; the 0.112 s Verilator fill above is a four-thread number).
   Each backend booted Debian twice, ran `opengpu_pipe_blit` (verified 1 KiB
   clear/blit), then ran
   `opengpu_pipe_present` (verified 64x64 draw and present with 2,016 painted
   pixels), and powered off. Host monotonic wall-time medians were:

   | Stage | FlashSim | Verilator 8 threads |
   | --- | ---: | ---: |
   | Launch to root shell | 26.54 s | 36.43 s |
   | Blit | 1.55 s | 18.81 s |
   | Draw + present | 151.74 s | 32.90 s |
   | Launch to poweroff | 179.93 s | 88.24 s |

   All eight guest program invocations passed. For this Debian graphics
   workflow, Verilator finishes about 2.04x sooner overall, although FlashSim
   is about 12.1x faster on the small blit. Both FlashSim draw runs emitted
   guest RCU stall warnings, so the draw result also reflects guest behavior
   under that backend. The separate full `opengpu_drm_test` failed on both
   backends with `short resolve stride accepted`; its timing is excluded from
   the passing comparison. That A/B used the existing 2026-09-24 modules ISO,
   while the host-built driver and test were refreshed on 2026-09-25. After
   refreshing the ISO, the full 64x64 Debian `opengpu_drm_test` passed with
   the eight-thread Verilator backend and QEMU powered off (451.90 s
   launch-to-poweroff). Consecutive KMS flip intervals were 33.085 ms and
   32.981 ms. The earlier resolve failure did not reproduce with the current
   guest artifacts. A passing A/B repeat with the refreshed ISO took 179.93 s
   on FlashSim versus 91.60 s on Verilator from launch to poweroff; the 1 KiB
   blit took 1.67 s versus 18.06 s, and the 64x64 draw/present took 143.24 s
   versus 32.42 s. Raw serial logs and host
   timing JSON are under `../arti-work/bench/debian-ab-20260925/` in the
   local work tree.
2. **Keep the submission contract covered** — every submission-path change
   must exercise descriptor errors, reset-during-work, delayed writes,
   completion backpressure, recovery and mixed sample modes. Boundary edits
   must pull system integration tests.
3. **Measure before optimizing** — compare with `scripts/benchmark_gpu.py`
   under the same source hash, scene and memory model. OM depth 16,
   hit-path graphics translation (accept on response retire), and a
   registered non-flow outstanding-hit pending queue (`pendingDepth` 16)
   are in after measured wins: framebuffer translation stall collapses and
   flats improve vs `e2e155e` (`flat_16_1x` 5271 → 4975, `flat_32_1x`
   19266 → 18162). Flat counters still show `om_conflict` = 0 with
   `om_stall` ≈ `raster_stall`, so the next flat lever needs a cheaper
   dual-issue path than a second framebuffer TLB client (a full dual colour/
   depth client split was measured and rejected: flat_32 +3.7%). Shader
   staging stays intentionally uncached for CPU coherence; treat the
   `e2e155e` shader cycle rise vs `1194224` as the coherent baseline, not a
   regression to claw back by re-caching.
4. **Physical closure** — the strided-copy descriptor address cone and the
   command-router dispatch cone are pipelined; the FP32 FMA lane now runs
   five stages (completion add cut from invert/LZD-mask/mask-valid).
   Carry-save performance counters, divide finalize, and MSAA resolve
   scanline row bases reach **781.65 MHz** (−279.34 ps), with the limiter
   back on the FMA `csaSumReg` cone. See
   [../timing/README.md](../timing/README.md). Derive real parent IO budgets.
5. **Software-driven growth** — grow the shader ISA from a small compiler
   corpus. Compute and graphics fragment shader code use private executable
   windows; the ASID-0 identity map is read/write but non-executable. Shared-CU
   vertex→fragment reuse and vertex instruction-fault recovery are now covered:
   L1 probes progress while a demand miss waits for an L2 eviction, avoiding
   the circular wait exposed by vector-heavy vertex kernels. Fill/blit/strided
   DMA now translate under `VECTOR_SATP` (legacy kernel-word and unified
   engines). Context fill/blit/strided/resolve jobs map into a private DMA VA
   window at run time; resolve still invalidates L2 with the physical source
   base (`UCMD_PATTERN`) because host invalidate is PA-tagged. Line-invalidate
   also submits physical addresses but keeps the context ASID (satp unused).
   The symbolic corpus also validates fixed-profile
   `vsext`/`vzext`/`vnclip`/`vsmul` (`fixed_width.S` / `examples/fixed_width`),
   `vwadd`/`vwsub`/`vwmul` (`widen_alu.S` / `examples/widen_alu`), the
   programmable `vtex.sample` fragment path (`fragment_texture.S`, exercised by
   `examples/pipe_texture_draw` under `GPU_FRAG_CORE=1`), and FP32 VFUNARY1
   (`fp_unary.S` / `examples/fp_unary`), and unmasked OPFVV
   `vfadd`/`vfsub`/`vfmul`/`vfmin`/`vfmax`/`vfsgnj*` (`fp_binary.S` /
   `examples/fp_binary`), the eight fused FMA forms (`fp_fma.S` /
   `examples/fp_fma`), `vfdiv` (`fp_div.S` / `examples/fp_div`), and OPFVV
   compares (`fp_compare.S` / `examples/fp_compare`). Fragment shaders use
   the same vector FP units on the shader CU (`fragment_fp.S` /
   `examples/fragment_fp`: `vfmul`/`vfdiv`/`vfmacc` into the output colour).
   Remaining ASID-0 identity use is Bare bring-up (`opengpu_hw_enable_mmu`).
6. **Workload-driven ISA** — FP32 VFUNARY1 (`vfsqrt`/`vfrec7`/`vfrsqrt7`/`vfclass`)
   is complete in RTL and admitted by the shader validator; the corpus shader
   `fp_unary.S` and `examples/fp_unary` exercise it under ARTI with
   `local_items=4` so every CU lane sees a defined `vs2` (VL=1 left inactive
   `vfsqrt` lanes on garbage and hung the guest). Unmasked OPFVV
   `vfadd`/`vfsub`/`vfmul`/`vfmin`/`vfmax`/`vfsgnj`/`vfsgnjn`/`vfsgnjx` is
   now admitted (`fp_binary.S` / `examples/fp_binary`), as are the eight
   fused FMA forms (`fp_fma.S` / `examples/fp_fma`; old `vd` must be
   defined). Unmasked `vfdiv` is covered by `fp_div.S` / `examples/fp_div`.
   OPFVV compares (`vmfeq`/`vmfle`/`vmflt`/`vmfne`) are covered by
   `fp_compare.S` / `examples/fp_compare`. Fixed-profile integer widen/narrow
   (`widen_alu` / `fixed_width`) has the same guest compute path. Grow further
   **validator + corpus** when a shader needs more vector FP. OPFVF forms
   (`vfadd.vf`, `vfrsub`, `vfrdiv`, `vfmacc.vf`, `vmfgt`/`vmfge`, ...) are
   admitted for compute shaders only on hardware advertising
   `GPU_CAP_COMPUTE_SCALAR_FPU` (bit22), with the scalar operand loaded by a
   validated `flw imm(x1)` (`fp_scalar.S` / `examples/fp_scalar`, which skips
   without the bit). The ARTI host system now builds its compute CUs with the
   scalar FPU backend; without it an `flw` shader hangs the CU. Fragment and
   vertex shader CUs have no scalar FPU. `vfmerge`/`vfmv.v.f` and scalar FP
   arithmetic remain excluded. Add further VFUNARY0 /
   widening beyond the fixed SEW=32 profile only with a motivating shader,
   validator rules and execution/guest coverage together.
7. **Graphics feature decision** — measure target scenes before adding
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

## Known limits

- Guest ARTI/QEMU: the default, fragment-core, and vertex+fragment-core
  end-to-end DRM tests pass and power off cleanly on `cfc045a`. Programmable
  `vtex.sample`, kernarg/VB staging, fill/blit/strided DMA and shader data
  loads all translate under the context ASID (`VECTOR_SATP`). Context
  fill/blit/strided/resolve jobs map buffers into a private DMA VA window at
  run time so they no longer depend on VA==PA; resolve still invalidates with
  the physical source base. VM-enabled mapping failures abort. Line-invalidate
  submits physical addresses under the context ASID (engine ignores satp). Bare
  bring-up still uses the ASID-0 identity map. Context roots expose no identity
  fallback.
- The release-style functional gate runs the full Scala suite, host driver
  tests, and fixed-function plus vertex/fragment ARTI guests
  (`scripts/qualify_functional.sh`). Userspace examples and the workload sweep
  are separate opt-in runs.
- No parent-level per-interface timing budgets.
- Display/scanout under ARTI is guest-memory GraphicHwOps (BASE/STRIDE/
  CONTROL/WIDTH/HEIGHT + refresh timer). ARTI uses the DRM soft timer for
  KMS flips; continuously clocked devices can use the hardware vblank IRQ.
- ASID-0 identity mappings remain read/write and non-executable for Bare
  bring-up (`opengpu_hw_enable_mmu`). Resolve and line-invalidate submit
  physical invalidate addresses; engine/resolve traffic uses private DMA VAs
  under the context ASID where applicable. No resumable page faults or full
  removal of the Bare identity table yet.
- Shader ISA growth is validation-profile driven, not a real compiler corpus.

## Later / out of scope

- OM depth and nonblocking graphics translation are landed after measured
  wins; larger TLBs or a dual color/depth OM port only when counters
  justify the cost.
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
python3 scripts/test_display_config.py
python3 scripts/benchmark_gpu.py
# ARTI model throughput, no QEMU or guest boot. Add --instrument for the
# dirty-list width and dirty-page count behind the active-rate figure.
python3 scripts/bench_arti_model.py --instrument
```
