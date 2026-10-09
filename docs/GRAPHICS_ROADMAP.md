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

## Debian graphical desktop

The goal is a graphical desktop in the Debian guest under ARTI. Its purpose
is to prove that the RTL and the software stack work together: the session
submits through the DRM driver, the RTL executes the jobs, and the pixels on
scanout are the pixels those jobs wrote. The QEMU window is a view of guest
memory. Host simulation time is not part of the proof; wall-clock numbers
later in this file size the model, and they do not gate this goal.

Graphics rendering uses this GPU. Vertex shading runs on the vertex core,
fragment shading runs on the fragment core, and raster, sample, depth,
stencil, and blend run in the fixed-function RTL. Fill, blit, strided copy,
and resolve are RTL jobs as well. The guest CPU packs vertex buffers, shader
binaries, and ioctls, then waits on the fence. It does not execute a shader,
rasterize a primitive, or write colour pixels in place of those jobs. A Mesa
state tracker is in scope only when its Gallium driver submits through that
same path. llvmpipe, softpipe, and the Gallium `draw` module are not a
stand-in for a shader or a rasterizer that the RTL does not yet run. A shader
that does not lower into the sandbox fails the draw.

`opengpu_drm_test`, `opengpu_triangle_present`, and the `pipe_*` examples
already take that path. `triangle_present` calls `opengpu_render`, waits for
the fence, checks the colour GEM, then `SETCRTC`s that buffer. The 320x240
Debian run (38,160 red triangle pixels, pixel (1,1) `ff0000`) is
fixed-function RTL output. Boot now defaults to both shader cores and to
`OPENGPU_AUTO_DISPLAY=desktop`. `gradient` and `opengpu_kms_present` paint
the framebuffer on the CPU, so they stay outside this proof.

Still to do:

1. **Desktop client, landed.** `userspace/examples/pipe_desktop` fills the
   background and panel, blits the window, draws a triangle into it, blits a
   pointer, and `pipe_opengpu_present`s that GEM. It checks those pixels
   before printing `OPENGPU DESKTOP PASS`. `--hold` keeps the session and
   moves the pointer from evdev. `OPENGPU_AUTO_DISPLAY=desktop` is the Debian
   boot default (`opengpu-boot-display.service` runs it). `triangle` remains
   the single-draw boot.
2. **A desktop mode.** KMS advertises one mode, and the Debian profile is
   320x240. Raise it in `driver/gpu_integration_debian.yaml` and rebuild with
   `scripts/build_arti_debian_display.sh`. Arti bakes the framebuffer size in
   at compile time, and `run_arti_debian.sh` does not rebuild the RTL. There
   is no cursor plane, so the compositor draws the pointer with a GPU fill or
   blit. Rebuild once before expecting shader pixels: a model already on disk
   keeps the elaboration it was built with. The fragment shader binaries are
   staged by default.
3. **Confirm it on the Debian guest.** Boot `scripts/run_arti_debian.sh` and
   expect `OPENGPU DESKTOP PASS` (panel, window, triangle and pointer values).
   The scanout image is that same GEM. `fragment_tint`, `pipe_texture_draw`,
   and `pipe_vertex_draw` remain the shader coverage beside this frame.
4. **Mesa only after that frame is real.** Stock GTK and Qt clients need
   OpenGL. The Gallium spike is [GALLIUM_SPIKE.md](GALLIUM_SPIKE.md). The
   shader sandbox is still 256 instructions, four forward branches, no loops
   or calls, e32/m1 only; see "Workload-driven ISA" below. Widen it when a
   desktop shader hits the limit. Index buffers, instancing, and a second
   colour target wait for the same reason.

## Next work

Priority is **functional**: the Debian graphical desktop above, which is a
usable DRM GPU under ARTI/QEMU (open card0, submit, fence, scan out RTL
output). Cycle / PPA work stays secondary unless a guest path is blocked.
Simulation wall-clock is not a gate.

An opt-in tile-order raster traversal is available through
`EmitGpuHostSystemAxi ... --tile-size 16`. It visits every pixel or 2x2 helper
quad in 16x16 tile order, preserving edge planes, coverage masks, scissor,
MSAA, and backpressure. The default remains scanline order. This is a first
step toward mobile tile rendering: the output merger still reads and writes
external color/depth memory, so tile order alone does not reduce framebuffer
traffic. `--tile-size 16 --tile-attachments` additionally enables an
experimental 1x/2x/4x RGBA8/D24S8 tile store. It fetches words on demand, keeps
dirty color/depth words on chip, and writes them back at tile/draw boundaries
before draw completion. The store retains one 16x16 tile with up to four
samples per pixel. DMA clears/copies and render jobs are serialized in this
mode because a resident tile is newer than external memory. Primitive binning
across draws begins with opt-in `--tile-binning`, used alongside
`--tile-size 16 --tile-attachments`. It buffers eight ordered inline triangle
commands at a time and replays each group tile by tile. A tile stays resident
across the group's draws and is flushed between groups. Command lists can
exceed eight draws. A bin-building pass records an ordered eight-bit membership
mask per tile. Parallel scissor checks discard empty tiles in one cycle;
candidate draws then receive conservative clip-space bounds tests. Replay
visits only members of each mask. The masks use one byte per tile. Unscissored
draws still require a bounds check for each draw/tile pair. Vertex-core commands
are future work. Fragment-core shading and 1x/2x/4x samples are supported.
The opt-in schedule assumes draws do not read the render target being written
or depend on cross-pixel shader side effects within a command list.

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
     `fp_fma`, `fp_div`, `fp_compare`, `fp_scalar`, `fp_reduce`, `widen_alu`,
     `fixed_width`, triangle,
     `triangle_present`, `pipe_present`, `pipe_clear_draw`,
     `pipe_compute`, `pipe_blit`, `pipe_strided_blit`, `pipe_resolve`,
     `pipe_texture_draw`, `pipe_depth_pass`, `pipe_msaa_draw`.
   - Fragment core (`GPU_FRAG_CORE=1`, `GPU_VERT_CORE=0`): `fragment_tint`,
     `fragment_fp`,
     `fp_unary`, `fp_binary`, `fp_fma`, `fp_div`, `fp_compare`, `fp_scalar`,
     `fp_reduce`,
     `widen_alu`,
     `fixed_width`, `triangle_present`, `pipe_present`,
     `pipe_clear_draw`, `pipe_compute`,
     `pipe_blit`, `pipe_strided_blit`, `pipe_resolve`, `pipe_texture_draw`
     (`vtex.sample` corpus binary staged as `/opengpu_fragment_texture.bin`),
     `pipe_depth_pass`, `pipe_msaa_draw`, `pipe_vertex_draw` (skips without a
     vertex core; corpus tint binary staged as `/opengpu_fragment_tint.bin`).
   - Vertex+fragment cores: `pipe_vertex_draw`, `pipe_resolve`.
   Both shader cores are the default. Preferred programmable bring-up:
   ```sh
   GPU_USERSPACE_EXAMPLES=1 GPU_USERSPACE_EXAMPLES_ONLY=1 \
     scripts/run_arti_gpu.sh
   ```
   Fixed-function is the opt-out:
   ```sh
   GPU_FRAG_CORE=0 scripts/run_arti_gpu.sh
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
   see [GALLIUM_SPIKE.md](GALLIUM_SPIKE.md). Guest GLES proofs on the
   320×240 FlashSim desktop, after `gl_fade`: `opengpu_gl_glass` (source-alpha
   over the clear, inside `0x003f80bf`), `opengpu_gl_quad` (two triangles,
   both `0x007f007f`), and `opengpu_gl_pane` (scissor `[80,160)×[0,16)`,
   clipped pixel stays the clear).
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
   QEMU_DISPLAY=cocoa scripts/run_arti_debian.sh
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
   `OPENGPU_AUTO_DISPLAY` defaults to `desktop` (`examples/pipe_desktop`).
   `OPENGPU_AUTO_DISPLAY=triangle` is the single GPU triangle.
   `OPENGPU_AUTO_DISPLAY=0` loads manually. `gradient` asks the CPU to
   paint the framebuffer and is not a GPU proof. A headless Debian boot
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
   tens of seconds.    A full-screen 320x240 redraw is 76,800 px at 18.8
   cycles/px, about 1.44M cycles, so it is a minutes-per-frame path, not a
   real-time one. Getting the size to take effect needed the resolution to
   stop being duplicated: it used to live in six places, and the
   `GPU_WIDTH`/`GPU_HEIGHT` variables reached only some of them, so a wider
   build rendered a 64x64 corner of a 64x64 window. It now has one source, and
   `build_arti_debian_display.sh` names each work tree after the mode instead
   of hardcoding `debian-64x64`. A zero-variable
   `scripts/run_arti_debian.sh` then reached the same render in 70 s host.
   **What a frame costs, measured (2026-09-28).** `bench_arti_model.py
   --render WxH` submits one full-screen fixed-function triangle (descriptor
   plus a 40-word draw record, `GPU_UCMD_OP_RENDER`, bare identity mapping)
   and checks the centre pixel, so the render path is timed directly instead
   of being inferred from a DMA engine. On the 320x240 Verilator model:

   | Scene | Pixels | Wall | Per pixel |
   |---|---:|---:|---:|
   | 16x16 | 256 | 2.28 s | 8915 us |
   | 64x64 | 4,096 | 3.9-6.4 s | 963-1551 us |
   | 320x240 | 76,800 | **14.25 s** | 186 us |

   Per-pixel cost falls with area, so a whole frame costs far less than
   repeating a small one, and 30 Hz at 320x240 is still about 430x out of
   reach. Two corrections to the numbers above follow from this: a *clear* is
   several MMIO transactions and the Verilator model charges its full idle
   budget to each, so the 0.97 s "5.8 kcycles/s" clear figure measured
   transaction overhead rather than cycles, and the fixed-function frame cost
   is far better than that figure implied. Treat the earlier
   FlashSim-side active rate as the conservative number and the render
   measurement as the one that bounds a game.
   The tick rates above are obsolete. They were measured against an older
   ARTI whose settle loop charged a fixed 20,000 idle cycles to every host MMIO,
   so the 0.23 MHz figure described settle overhead rather than model speed.
   The current model exits on real quiescence (`gpu_active()` = change flag +
   AXI queue occupancy, `ARTI_MODEL_IDLE_GRACE 16`) and pumps up to
   `ARTI_MODEL_IRQ_PUMP_CYCLES 262144` per 100 us IRQ poll while a job is live.
   `scripts/bench_arti_model.py` links the embedded model directly (no QEMU,
   no guest) and measures a quiet register read, a hardware clear and a
   full-screen render. On the 64x64 **FlashSim** model:

   | Settle kind | Wall | Rate |
   |---|---:|---:|
   | idle (one quiet register read, 256 cycles) | 60-120 us | ~2-3 MHz |
   | active (32 KiB hardware clear, 5656 cycles) | 9-15 s | **~0.4-0.6 MHz** |

   and on the 320x240 **Verilator** model a 4 KiB clear takes 0.94 s and an
   idle settle 181 ms, because the Verilator settle still charges its full
   idle budget to every quiet MMIO and a clear is five transactions. That
   means the Verilator clear figure measures transaction overhead, not
   cycles: the render measurement above is the one to use.
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
    and only the barrier is left. The partition count is fixed at 8 inside
    ARTI's generated `build_embedded.sh` (`ARTI_EVAL_REGIONS`, not
    user-facing; the old `ARTI_VERILATOR_THREADS` knob is gone), which fails
    the build if codegen emits fewer than two `__Vthread__` partitions.
    Changing it re-partitions the logic and needs a re-verilate;
    `contextp()->threads(n)`
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
   scalar FPU backend; without it an `flw` shader hangs the CU.
   The fragment and vertex shader CU is built the same way as of
   2026-09-28: `KernelShaderStage` passes `enableFpuBackend = true`, so `flw`
   decodes and issues its kernarg load there
   (`KernelShaderStageSpec`, "retire a scalar FP load from kernarg on the
   shader CU"). Vertex and fragment share that one CU, so it covers both.
   The path a compiler needs first works: `flw` reads the uniform into an
   f-register and a vector op consumes it through the `.vf` operand sideband,
   verified end to end in `KernelShaderStageSpec` ("feed a scalar FP load into
   a vector .vf operand on the shader CU"), which stores 3.0f plus a 2.0f
   uniform as 5.0f. The integer scalar register file is not on that path.
   The f-register file lives in `FpuIssueStage`. `committedFpuWriteback` only
   observes a commit that has already updated that file, so
   `KernelShaderStage` does not consume it. A later `fsw` reads the same
   file (`KernelShaderStageSpec`, "round trip a float through an f-register
   with fsw on the shader CU").

   `frm` and `fflags` are per-warp state in `VectorConfigurationUnit`, and the
   per-workgroup reset already existed: `GpuCore` drives `clearWarp` from the
   launch fire, but the handler only cleared `vxrm`, so a warp reused by a
   second kernel inherited the first kernel's rounding mode and sticky
   exception flags. It now clears all three, covered by
   `VectorConfigurationUnitSpec` ("clear frm and fflags with the warp on
   reuse"), which fails without the change. This is the gap that mattered once
   fragment shaders could actually execute floating-point work; the
   `fpuInitialize` port on `GpuComputeUnit` stays tied invalid because the
   clear belongs on the vector configuration state, not the f-register file.

   The driver admits it for graphics as of 2026-09-28: both graphics
   validators take a `scalar_fpu_enabled` argument and it is derived from
   bit22, which now requires the FP backend on the compute CUs *and* the
   graphics shader CU (`graphicsScalarFpu` is plumbed from `GpuHostSystemAxi`
   through `GpuHostAxi`, `RenderHost`, `RenderCore` and `RenderPipeline` into
   `KernelShaderStage`). A `.vf` operand with an undefined f-register is still
   rejected by the defined-register analysis.

   Covered end to end under ARTI on the fragment-core build: the corpus shader
   `fragment_fp_scalar.S` (flw off the kernarg base, broadcast with vfadd.vf)
   and `examples/fragment_fp_scalar` are in the guest run list, and the run
   reported `fragment_fp_scalar completed; flw uniform 0x40a00000 through
   vfadd.vf, pixels: 2016 sample=0xbea0ffff` with capabilities 0x007e08f9,
   that is bit22 set. `validate_shader_corpus.py` checks the shader against a
   new fragment profile 6, the vertex counterpart is profile 7.

   `fsw` was recorded here as a latent hardware bug on the strength of a probe
   that served 8 bytes per fetch response and so decoded zeros for the rest of
   the instruction line. That was a harness artefact and the claim is
   withdrawn. With whole lines served, a scalar FP store round trips:
   `KernelShaderStageSpec` ("round trip a float through an f-register with fsw
   on the shader CU") does `flw f4, 4(x1)`, `addi x2, x0, 0x100`,
   `fsw f4, 0(x2)` and reads 5.0f back out of 0x104. The f-register read the
   store depends on is the same file the `.vf` path uses, so the two now share
   one tested route rather than one of them being inferred.

   The store's *encoding* was a real defect, and it is what a compiler hits
   next. `FpuIssueStage` reads `rs2` from instruction bits 24:20, which is the
   source f-register for a store, while `FpuMemoryUnit` built the offset as
   `sext(instruction(31, 20))`. The two fields overlapped: the source register
   number *was* the low five bits of the store's own offset. A store's offset
   was not independently encodable, and a 4-byte store only stayed aligned when
   the register index was a multiple of four, which is why the first version of
   the round-trip test had to store f4 rather than f1.

   That is fixed. A store now uses the S-type layout, with `imm[11:5]` in
   31:25 and `imm[4:0]` in 11:7, leaving 24:20 free for the source register.
   This is what RISC-V specifies for stores, and `flw` was already correct as
   I-type, so the two now differ exactly as they do in the base ISA. Only the
   immediate assembly in `FpuMemoryUnit` changed: the decoder constrains just
   `funct3` and the opcode, so it admitted the new layout untouched. Two cases
   cover it, storing f1 at a 0x10 offset, which was not encodable before, and a
   single program taking a 0x7fc offset to exercise the bits above the low five
   and a -4 offset to exercise the sign reaching 31:25.

   Masked vector load and store are already implemented and wired, which is
   worth stating because it is easy to assume otherwise: `VectorBackend`
   builds `memoryMask` as `decode.activeMask & vlMask & Mux(vm, allLanes,
   predicateMask)`, and `VectorRegisterBank.predicateMask` is the architectural
   v0 read port (the low `lanes` bits of v0's flat data). Every unit folds the
   mask in the same place, so the memory unit needs no v0 port of its own.

   The per-lane index a compiler needs for a predicated access is available
   on the graphics shader CU too: `KernelShaderStage` instantiates
   `GpuComputeUnit`, whose `SingleCuKernelController` owns the
   `WarpContextInitializer` that publishes `v1 = localLinearBase + lane`.
   The whole bounds-check chain is covered: `KernelShaderStageSpec`
   ("predicate a store on a vmsltu.vx mask built from v1") computes
   `vmsltu.vx v0, v1, x9` with x9 = 2 and a store predicated on v0 writes
   lanes 0 and 1 only, and `VectorBackendSpec` pins the same compare plus
   back-to-back masked and unmasked stores below the CU.

   The integer vector compare is not broken. The earlier probe encoded
   `vmsltu.vx` with funct3 010, which is OPMVX, not OPIVX: the encoding
   0x6a12a057 fails `VectorDecoder` and raises an illegal-instruction trap, and
   funct3 100 is required, giving 0x6a12c057 for `vmsltu.vx v0, v1, x5`. Run
   with the correct encoding on the graphics shader CU, the compare decodes to
   unit 7, `VectorExecutionDispatch` routes it to the integer ALU, and v0 comes
   back as 0xb3865eb7: the low nibble is the packed mask 0x7 for lanes 0 to 2,
   and the high 28 bits are the old v0, which nothing in the program ever
   wrote. The same 0xb3865eb0 prefix appears in the words this was recorded
   against before, which differ only in the low nibble (6 there, 7 here), so
   the two observations are the same uninitialised destination with the mask in
   the one field that the compare sets.

   The expectation "v0 should hold 0x00000007" was the error. RVV defines only
   the low bits of v0 for a mask-producing instruction; the bits above them and
   lanes 1 to 3 are architecturally undefined, so an unwritten destination
   reading back whatever the black-box SRAM powers up to is not a defect in the
   compare. That value is stable across runs and across source changes because
   Verilator seeds its random reset from a fixed constant, which is what made
   it look like a computed result. The unpacking in `VectorBackend` was checked
   directly and is correct as written: lane 0 occupies the low `xLen` bits of
   the flat word, so dropping the low `lanes` bits of `aluFlat` drops exactly
   lane 0's low bits and leaves every other lane where it was. Rewriting it as
   a per-lane splice changed nothing observable, and a unit test that pins the
   packed value passes against both forms, so the rewrite was dropped.

   What does remain is that the vector register file is never initialised, so
   any read of an unwritten vector register returns the SRAM's power-up
   contents rather than zero. That is a separate question from the compare and
   is not recorded as a compare defect. The shader validator's
   defined-register tracking already keeps guest shaders from reading it.

   A `vle32.v` followed directly by `vse32.v` of the same register stores the
   loaded words (`KernelShaderStageSpec`, "store a vector load's data when the
   store follows it directly"), so the `VectorRegisterScoreboard` RAW
   interlock on the store's `readVd` holds without an intervening op.

   `fsw` is admitted on the same scalar-FPU capability as `flw`. It is S-type
   `fsw fs, imm(x1)`: a non-negative 4-aligned offset, a source f-register
   defined by a validated `flw`, and an address inside the profile's writable
   window (fragment colour/depth/validity, vertex output slices, or the whole
   compute kernarg). The hardware round trip was already covered; the sandbox
   was the missing piece. The same capability now admits the FP32 operations
   that retire on the scalar FPU fast path: `fadd`/`fsub`/`fmul`,
   `fsgnj`/`fsgnjn`/`fsgnjx`, `fmin`/`fmax`, and `fmadd`/`fmsub`/`fnmsub`/
   `fnmadd`. Sources must already be defined and the destination becomes
   defined. `KernelShaderStageSpec` ("combine two scalar floats and store the
   results on the shader CU") checks 2.0f and 3.0f through that set. The same
   capability admits `feq`/`flt`/`fle`, `fcvt.w.s`/`fcvt.wu.s`, `fmv.x.w`,
   `fclass.s`, `fcvt.s.w`/`fcvt.s.wu` and `fmv.w.x`. Integer destinations
   cannot be `x1` and lose any pointer kind. The compare mapper now matches
   RISC-V funct3 (`feq` is equality, `fle` is `<=`); the exact unit's internal
   codes were the other way around. `KernelShaderStageSpec` ("cross scalar
   floats and integers on the shader CU") checks 1.0f against 2.0f and a
   conversion of the integer 5. `fdiv.s` and `fsqrt.s` now retire on the
   same scalar FPU: the iterative divide and square-root lanes used by the
   vector unit. `fsqrt.s` requires `rs2` = 0. `KernelShaderStageSpec`
   ("divide and take a square root on the shader CU") checks 4.0/2.0 and
   sqrt(4.0).

   The `0xfff` byte mask a `VL=4` `vse32.v` produced on the bare
   `KernelShaderStage` harness is the harness, not the CU: `runShader` launches
   `localX = 3`, so lane 3 is inactive and three 4-byte lanes are correct.

   `vfmerge.vfm` and `vfmv.v.f` are admitted on the scalar-FPU capability.
   `vfmv.v.f` broadcasts a defined f-register. `vfmerge.vfm` reads `v0`,
   the old destination, and `vs2` for the mask-clear lanes.
   `KernelShaderStageSpec` ("merge a scalar float into a vector on the
   shader CU") checks both. Unmasked SEW=32 `vfcvt.xu.f.v`, `vfcvt.x.f.v`,
   `vfcvt.f.xu.v`, `vfcvt.f.x.v`, and the two rtz float-to-integer forms are
   admitted. `vs1` is the conversion opcode, not a vector register.
   `KernelShaderStageSpec` ("convert floats and integers on the shader CU")
   checks `vfcvt.rtz.x.f.v` of 1.5/2.5/−1.0 and `vfcvt.f.x.v` of 5/−5/0.
   The rtz integer-to-FP forms `vfcvt.rtz.f.xu.v` and `vfcvt.rtz.f.x.v` are
   admitted too. Their rounding mux already existed for the float-to-integer
   forms, so this was vfrm selectors 4 and 5 in the decode and the validator
   table, which now admits every selector below 8. Selectors 8 and 9 are the
   unimplemented float-to-float conversions and stay reserved.
   `KernelShaderStageSpec` ("convert integers to float with RTZ on the
   shader CU") converts 2^32−1 and 2^31+1 both signed and unsigned.
   Masked non-compare FP uses the same units: a clear mask lane keeps the
   old destination, so `vfadd`/`vfsub`/`vfmul`/`vfdiv`, the sign-injection
   and min/max forms, VFUNARY0/1, the eight FMA forms, and the matching
   OPFVF ops including `vfrdiv`/`vfrsub` are admitted when `v0` and that
   old destination are already defined. `KernelShaderStageSpec` ("mask
   vector float arithmetic on the shader CU") checks a masked `vfadd.vv`
   and a masked `vfcvt.rtz.x.f.v`. In-place `vfcvt` is allowed; the
   source/destination overlap rule stays on the integer widening form.
   Unit-stride and constant-stride vector memory now admit 8-bit and 16-bit
   elements as well as 32-bit. Each lane holds one zero-extended element, and
   the base and a constant stride must be aligned to that element. Indexed
   accesses stay 32-bit. `KernelShaderStageSpec` ("load and store narrow
   vector elements on the shader CU") checks `vle8.v`, `vle16.v`, `vlse16.v`
   with stride 4, and the matching `vse8.v` / `vse16.v` byte masks.
   Scalar `lb`/`lbu`/`lh`/`lhu`/`lw` and `sb`/`sh`/`sw` are admitted as
   naturally aligned `imm(x1)` accesses. `KernelShaderStageSpec` ("load and
   store scalar bytes and halfwords on the shader CU") checks sign and zero
   extension of `0x80` and `0xabcd`.
   `vmerge.vvm`/`vmerge.vxm`/`vmerge.vim` and the unmasked `vmv.v.v`/
   `vmv.v.x`/`vmv.v.i` forms retire on the integer ALU. A set mask lane
   takes `vs1`, the scalar, or the sign-extended immediate; a clear mask
   lane takes `vs2`; lanes outside VL keep the old destination. `vmv`
   encodes `vs2` as `v0` and does not read it. `KernelShaderStageSpec`
   ("merge an integer vector on the shader CU") checks a `vmerge.vvm` of
   1/2/3 over 10/20/30 with lanes 0 and 1 selected, and a `vmv.v.x` of 2.
   `vmand`/`vmor`/`vmxor` and `vmandn`/`vmorn`/`vmnand`/`vmnor`/`vmxnor`
   combine two packed masks. They are unmasked, may write `v0`, and keep
   bits outside VL. `KernelShaderStageSpec` ("combine compare masks on the
   shader CU") ANDs `id < 2` with `id < 1` and ORs the same pair, then
   predicates a store on each result.
   `vmv.s.x` writes a scalar into element 0 and leaves every other element
   unchanged. `vmv.x.s` copies element 0 into an integer register other than
   `x1` and does not write the vector register. Both encodings are unmasked,
   with `vs1` = 0 for `vmv.x.s` and `vs2` = `v0` for `vmv.s.x`. The integer
   destination is reserved on the scalar scoreboard and joins the same
   writeback port as `vset*`. `KernelShaderStageSpec` ("move element zero
   through an integer register on the shader CU") splats 7, replaces element
   0 with `0x5a`, stores the vector, and stores the integer read back.
   `vfmv.s.f` writes an f-register into element 0 and leaves every other
   element unchanged. `vfmv.f.s` copies element 0 into an f-register and does
   not write the vector register. An inactive element 0 writes the canonical
   NaN. Both encodings are unmasked and need the scalar FPU, with `vs1` = 0
   for `vfmv.f.s` and `vs2` = `v0` for `vfmv.s.f`. The f-register is reserved
   until the vector unit writes it back. `KernelShaderStageSpec` ("move
   element zero through a scalar float on the shader CU") replaces element 0
   of 4/5/6 with 2.0 and stores that float.
   `vslide1up.vx` inserts an integer at element 0 and slides the source up by
   one. `vslide1down.vx` slides the source down by one and writes that
   integer at element `vl-1`. Both are OPMVX. `vslide1up` keeps the
   `vslideup` rule that the destination is already defined and does not
   overlap the source. Masked-off lanes and lanes outside `vl` keep the old
   destination. `KernelShaderStageSpec` ("slide one element by a scalar on
   the shader CU") slides 11/22/33 with `VL=3` and inserts `0x5a`.
   The OPFRED sums `vfredusum.vs` (funct6 000001) and `vfredosum.vs`
   (funct6 000011) are the FP32 reductions; `vs1` is the seed register, not an
   opcode, so both are ordinary OPFVV three-operand encodings. `VectorFReduceAlu`
   folds `vs1[0]` and the participating `vs2` elements into element 0 and
   preserves the rest of the destination. It runs one add at a time through a
   single elastic `Fp32FmaLane`: FP addition is not associative, so a
   `lanes`-1 adder tree would satisfy the unordered form while quietly
   breaking the ordered one, and a sequential fold serves both: one lane of
   area instead of `lanes`-1, paid for with one five-stage add per element. A non-participating
   element issues no add at all instead of adding an identity, so a `-0.0`
   accumulator cannot become `+0.0`. `frm` and the NV/NX flags come from the
   same per-add path as `vfadd.vv`. The min/max reductions stay unimplemented:
   `vfredmin.vs` is funct6 001010, which this core already spends on
   `vfsgnjx.vv`, so that word validates and runs as a sign-injection instead of
   being rejected. `VectorFReduceAluSpec` ("fold in element order rather than
   through a tree") pins the sequential result against a value a tree cannot
   produce, and `KernelShaderStageSpec` ("reduce a vector of floats into element
   zero on the shader CU") runs both forms end to end. The corpus shader
   `fp_reduce.S` and `examples/fp_reduce` check them under ARTI.
   funct6 `010100` in OPMVV is one family selected by the vs1 field rather
   than by vs1 as a register: 10001 is `vid.v`, 10000 is `viota.m`, and the
   set-before-first `vmsbf.m` (00001) is the unimplemented third member. Only
   `vid.v` and `viota.m` are admitted. `viota.m` writes each element the count
   of set mask bits below it, reading vs2 as its mask and, when masked,
   counting only enabled elements. Both reuse the prefix popcount that
   `vcompress` needs, and the integer request now carries the encoded vs1 field
   so the ALU can read the selector. Together they give the spec's canonical
   compaction idiom, which this core's indexed stores already implement:
   `KernelShaderStageSpec` ("scatter selected elements with viota on the shader
   CU") runs `vmseq.vx`, `viota.m`, `vsll.vi` and `vsoxei32.v` and checks the
   packed output. The driver does not yet admit that sequence for a guest
   shader, because an indexed access still requires its index vector to carry
   the trusted local-byte provenance that only `vsll.vi` on `v1` establishes.
   `vid.v` and `vcompress.vm` complete the index-and-pack pair a compiler
   emits around a compare: `vid.v` (funct6 `010100`, OPMVV) writes each
   element's own index and reads no vector source at all -- its vs2 field is
   fixed to v0 and its vs1 field holds the EEW/EMUL selector rather than a
   register number -- and `vcompress.vm` (funct6 `010111`, OPMVV, unmasked
   only) packs the mask-selected elements of vs2 into the low elements of vd,
   which the ALU does by ranking the selected lanes and selecting the matching
   source. That funct6 is the one `vmerge`/`vmv` use, but those are
   OPIVV/OPIVI/OPIVX here, so the forms do not collide; the destination must be
   disjoint from both sources. `VectorIntegerAluSpec` and
   `KernelShaderStageSpec` ("index and compact elements on the shader CU",
   which runs `vmseq.vx` followed by both) cover them.
   The single-width integer multiply-accumulate family (`vmadd`, `vnmsub`,
   `vmacc`, `vnmsac`, funct6 `101001`/`101011`/`101101`/`101111` in `vv` and
   `vx`) rides the existing radix-4 Booth pipeline: `vmacc`/`vnmsac` add the
   low product into the destination and `vmadd`/`vnmsub` into `vs2` with the
   destination as the third operand, so the destination register is a source
   as well and the driver requires it to be defined in the unmasked forms too.
   Only the low half of the product is architecturally visible, which is why
   the family needs neither a signedness distinction nor a rounding path.
   `VectorMultiplyAluSpec` covers all four forms, the vector-scalar form, the
   masked form and the signed/unsigned agreement of the low product, and
   `KernelShaderStageSpec` ("multiply-accumulate on the shader CU") runs
   `vmacc.vv` and `vmadd.vv` end to end on the shader CU.
   `vslideup.vv` and `vslidedown.vv` read the offset from a defined vector
   instead of a scalar or immediate, so each element shifts by its own amount.
   The offset register was already routed to the slide network for the `.vx`
   and `.vi` forms, so this was a decode row, a validator classification and
   coverage. `KernelShaderStageSpec` ("slide by a per-element vector offset on
   the shader CU") slides 11/22/33 by 3/2/1/0 and 0/1/2/3 with `VL=3`.
   Further VFUNARY0 forms and widening beyond the fixed SEW=32 profile wait
   for a motivating shader, validator rules, and execution/guest coverage
   together.
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
# ARTI model timing, no QEMU or guest boot. --render times the path a game
# frame actually uses; --instrument adds the FlashSim dirty-page counters.
python3 scripts/bench_arti_model.py --render 320x240
python3 scripts/bench_arti_model.py --model-dir "$ARTI_WORK/debian-64x64/arti-embedded-gen/generated/embedded" --instrument
```
