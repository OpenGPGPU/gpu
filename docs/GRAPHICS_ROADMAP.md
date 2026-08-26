# GPU Graphics Pipeline Roadmap

Target: extend the RISC-V SIMT core (`gpu/`) into a full GPU — graphics
rendering, Linux-visible as an accelerator/display device.

Guiding reference: [vortexgpgpu/vortex](https://github.com/vortexgpgpu/vortex),
a complete RISC-V GPU with unified shading plus fixed-function raster/texture/
output-merger and a host command/MMIO interface. Architecture principle adopted
here is the same **unified-shader + separated-fixed-function** model:

- **Shading (vertex/fragment programs)** executes on the **same SIMT lanes +
  FPU** that already do compute (this repo's core strength).
- **Fixed-function stages** (rasterizer, texture unit, output merger, clipper)
  are dedicated RTL, separate from the compute pipeline.
- **Framebuffer and depth buffer are memory regions allocated by software**
  (driver/host memory manager). Hardware only **computes addresses and issues
  memory reads/writes** — it never owns or sizes a buffer.
- **The shader ISA is RV32IMF(+V) plus a handful of custom graphics
  instructions**, not a new ISA. We reuse the existing toolchain
  (assembler/linker/LLVM) instead of building one.

---

## Current state (committed)

| Milestone | commit | Content |
|---|---|---|
| M1 rasterizer | `6caff90` | Fixed-point bounding-box triangle rasterizer (plane-coefficient `A·x+B·y+C`, Vortex-style), `TriangleCoverage` correct |
| M2 barycentrics | (folded into `9f576a6`) | Per-pixel barycentric coordinates: `RasterPixel` carries the three edge values `e0/e1/e2` and twice-area `area`, i.e. unnormalized barycentrics `λᵢ = eᵢ/area`. Delivered as part of the M3a commit rather than as a standalone milestone |
| M3a interpolation | `9f576a6` | `FragmentInterpolator` (screen-space barycentric `attr=(Σ eᵢ·aᵢ)/area`), `RasterShader` emitting `RasterFragment(x,y,color)`; full-triangle shading verified |
| M3b correct coverage | `9806680` | Top-left fill rule (winding-independent), cull mode (none/back/front), degenerate-triangle rejection, integer pixel coords at the fragment interface |
| M3c output merger | `2bd1224` | `OutputMerger` serialized depth read-modify-write: programmable colour/depth base, stride, depth func, depth-write enable; writes through the shared memory port |
| M4a viewport/perspective | `93291e8` | `GeometryStage`: clip (Q16.16) -> fixed-point screen space + per-vertex `1/w` (perspective divide) |
| M4b near-plane clip | `d6e3086` | `NearClipStage`: 4-cycle Sutherland-Hodgman clip against `w >= wNear`, interpolating position/depth/varyings, emitting up to two triangles |
| MVP transform | `a60ada7` | `MatrixTransform`: programmable row-major 4x4 matrix (Q16.16) applied to a vertex (retired by M5) |
| perspective correction | `9508b19` | `PerspectiveInterpolator`: correct `1/w`-weighted interpolation of varyings |
| render pipeline | `aaa6e41` | `RenderPipeline` (composite Geometry -> RasterShader -> OutputMerger) + PPM export |
| command buffer | `0610c33` | `CommandBufferStage`: reads draw-call records from host memory |
| render core | `53f996c` | `RenderCore`: composite CommandBufferStage -> RenderPipeline with separate command/framebuffer memory ports |

Numbering note: M2 (barycentric coordinate generation) was implemented
together with M3a and is recorded above for completeness.

**Status (2026-08-24):** M1 through M4 are complete and verified — the fixed
function geometry front-end (MVP transform, near-plane clip, perspective
divide/viewport), the rasterizer (top-left fill rule, culling), the
interpolators (screen-space and perspective-correct colour + depth), the
output merger (depth test / write to software colour+depth buffers), the
command-buffer parser, and the composite `RenderPipeline`/`RenderCore` that
renders a command-driven scene into an exported PPM image. M5 shading is in
progress: `ShaderCore` (lock-step SIMT, uniform bank), `ShaderFragStage`
(fragment -> SIMT shade), and `RV32ShaderCore` (real RV32IM instruction
execution) validate the shading model. The production direction is
**commercial-GPU-aligned unified shading**: reuse `GpuComputeUnit`'s SIMT
kernel launch/completion (see the resolved decision above) so a shader is a
kernel on the core, superseding the standalone shader cores. 44 graphics
tests pass.

Compute core already present: RV32 SIMT lanes + FPU (FMA/div/sqrt/est), RVV
ALU, register files, L1/L2 (SharedL2Slice), memory hierarchy with a standardized
`Decoupled` memory interface (`memoryRequest`/`memoryResponse`).

Known limitations of the committed hardware, each addressed by a milestone
below:

1. **No fill rule** — coverage is `all ≥ 0 || all ≤ 0` inclusive, so shared
   edges are drawn twice. → M3b.
2. **No perspective correction** — interpolation is screen-space linear, wrong
   after a projective transform. → decision below, hardware in M3c/M4.
3. **No depth buffering** — fragments are blind writes. → M3c.
4. **Serial single-pixel rasterizer** — full edge re-evaluation per cycle,
   one pixel per cycle. Acceptable now; performance iteration → M4b.

---

## Resolved design decisions

These were open questions in the previous revision; they are now decided so
that hardware interfaces can be frozen.

- **Pixel format:** RGBA8888 (32-bit) for color; **depth format:** 24-bit
  fixed-point depth in a 32-bit word (`D24`) in a separate depth buffer.
  16-bit color/depth formats are a later optimization only.
- **Coordinate system / fill rule:** top-left origin, y down (matches the
  rasterizer). **CCW winding is front-facing.** Coverage uses the **top-left
  fill rule**: a sample on an edge belongs to the triangle iff the edge is a
  top edge or a left edge. This gives crack-free, exactly-once coverage of
  shared edges and is a precondition for correct depth testing and blending.
- **Perspective-correct interpolation:** the rasterizer carries `1/w` per
  vertex (produced by the vertex stage after clipping). The fixed-function
  interpolator computes, per varying `a`:
  `a_px = Σ(λᵢ·aᵢ/wᵢ) / Σ(λᵢ·1/wᵢ)` with screen-space-linear barycentrics
  `λᵢ = eᵢ/area`. One divider per interpolator lane (the FPU already has a
  divider; a fixed-point reciprocal-multiply is acceptable if timing demands
  it). Flat (non-perspective) interpolation is a per-varying flag for
  values that must not be corrected (e.g. integer varyings).
- **Rendering approach:** immediate-mode rendering. Tile-based deferred
  rendering is a possible later optimization and is explicitly out of scope.
- **Memory model:** framebuffer, depth buffer, command buffer, and textures
  live in a single shared memory region managed by the driver. No GPU-local
  VRAM in v1; the memory hierarchy (`SharedL2Slice`) is shared between
  compute and graphics traffic.
- **Depth test policy:** fragments are tested in submission order per pixel;
  the OM serializes read-modify-write per pixel address (see M3c). Default
  depth func is `LESS`, depth write enabled; both programmable.
- **SoC morphology & host CPU:** v1 is an **integrated SoC** — an on-die
  RISC-V host (RV64 + Sv39, running Linux) and the GPU live in the same
  SoC, attached to a single AXI/NoC and sharing one physical DRAM region
  through the shared memory hierarchy (`SharedL2Slice`); there is **no
  GPU-local VRAM** in v1. The bus/wiring between the host and the GPU is a
  SoC-internal fabric port, not a PCIe root. A future **dedicated
  (discrete)** form factor — the GPU with its own memory and a PCIe host
  interface — is a later productization option and is explicitly out of
  scope for v1 (kept in mind so the MMIO/command interface stays portable
  across both).

- **Cache coherence (CPU-GPU):** v1 uses **driver-driven synchronization,
  not a hardware snooping protocol.** Point of coherence: the shared L2
  (`SharedL2Slice`). (a) For a CPU→GPU submission the driver writes the
  command/data, then issues a **cache-clean (write-back) to the coherence
  point + a barrier** before writing the doorbell/MMIO that releases the
  GPU, so the GPU always reads the command stream at the L2. (b) For a
  GPU→CPU result (frame, compute output) the GPU flushes its writes and
  raises an interrupt/fence; the driver waits before reading. (c) Hardware
  support is performance-only: a doorbell (MMIO write) plus an optional
  cache-maintenance command (clean/invalidate) — no MESI/IO-coherence
  engine. This is exactly how Adreno/Mali/PowerVR integrated GPUs do it. A
  later dedicated/discrete form may add an IOMMU or a coherence engine; the
  MMIO/command interface stays compatible so it can be layered in.

- **Commercial GPU alignment (unified shading):** shading is executed on the
  **same SIMT lanes as the compute kernel** (an NVIDIA SM / AMD CU model),
  fixed-function graphics (rasterizer, texture, ROP) remain separate. The
  repository already provides this: `GpuComputeUnit.io.kernel` /
  `io.completion` carry a `KernelLaunch{kernelPc, kernargAddress, gridSize,
  localSize}` and `KernelCompletion`, so a vertex/fragment shader is a **kernel
  launched on the core's SIMT warps** with its varyings/uniforms/output placed
  in the kernarg buffer. The `gpu.graphics` `ShaderCore`/`RV32ShaderCore` are
  standalone verification stepping stones; the production path is to emit a
  `KernelLaunch` per draw and consume `KernelCompletion`, reusing the core's
  warp/register/ALU/FPU machinery rather than a separate shader datapath. Real
  GPUs use a dedicated GPU ISA (PTX/SASS/GCN/RDNA) + compiler; this design uses
  the RISC-V RV32IMF+V the core already executes, for toolchain reuse.

---

## Roadmap

### M3b — Coverage correctness (fill rule, winding, integer coords)
**Goal:** fix the known coverage bugs before any pixel ever reaches memory;
every later milestone depends on exactly-once coverage.

Hardware:
- `TriangleCoverage`: implement the top-left fill rule (edge is top-left via
  the standard `A<0 || (A==0 && B<0)`-style test on each edge's plane
  coefficients, with a per-triangle bias so the rule is winding-independent
  in the setup).
- Fix winding to CCW-front; add a programmable cull mode
  (`none/back/front`) in the rasterizer setup stage. Degenerate triangles
  (`area == 0`) emit nothing.
- `RasterFragment` carries **integer pixel coordinates**; the sub-pixel
  shift (`x << subPixelBits`) stays inside the rasterizer. Document the
  fixed-point boundary: fixed-point inside, integer at the fragment
  interface.

Verification:
- Rasterize a mesh of edge-sharing triangles; assert per-pixel coverage
  count is exactly 1 across the mesh (no cracks, no double-draws).
- Cull-mode tests: back-face culled triangle emits nothing.

---

### M3c — Output Merger with depth (ROP)
**Goal:** shaded fragments become *tested* memory operations against
software-allocated color and depth buffers. This is the milestone where 3D
scenes with overlapping triangles render correctly.

Hardware:
- `OutputMerger` with programmable registers: `colorBufferBase`,
  `depthBufferBase`, `strideBytes`, `bytesPerPixel`, `screenSize`,
  `depthTestEnable`, `depthFunc`, `depthWriteEnable` (driver-writable).
- Fragment address: `base + (y·stride + x)·bpp` on **integer** coords.
- Depth pipeline: read depth word, compare per `depthFunc`, conditionally
  write depth and color. Because the memory port is shared and responses
  may be out of order, the OM keeps a small **in-flight pixel table**
  keyed by pixel address: a fragment whose address matches an in-flight
  entry is stalled (or merged) until the earlier RMW completes. This
  preserves per-pixel submission order without global ordering.
- Color/depth writes go through the existing `memoryRequest`/
  `memoryResponse` `Decoupled` port (reuses the compute core's memory
  path). Backpressure: rasterizer stalls when the OM queue is full; this
  path must be deadlock-free against the L2's own queueing (assert in
  simulation).
- Blending: out of scope for M3c; the OM interface leaves a `blendEnable`
  hook for later.

Software / host:
- Driver/host allocates color + depth regions and programs the OM
  registers; per-frame clear is a driver-issued fill (or a later clear
  fast-path).

Verification:
- Scala test plays the driver: render two overlapping triangles at
  different depths in both submission orders; assert the nearer triangle
  wins in both cases. Read the memory array back, encode a PNG/PPM, assert
  corner/interior colours.

---

### M4 — Geometry front-end (clip, project, assemble, command buffer)
Hardware:
- Vertex stage (interim, fixed-function): programmable 4×4 matrix
  (model/view/projection) transform in fp32. **This block is explicitly
  throwaway**: M5 moves vertex shading onto the SIMT lanes and this block
  is deleted or reduced to passthrough. Do not extend it beyond MVP.
- **Clipping in homogeneous clip space** against the full view frustum
  (`-w ≤ x,y,z ≤ w`), implemented as guard-band clipping: trivial
  accept/reject per plane, actual polygon clipping only for near-plane and
  guard-band violations. Clipping emits new vertices with interpolated
  `1/w` and varyings.
- Perspective divide → viewport mapping → fixed-point screen space
  (sub-pixel precision). Output: screen-space triangle + per-vertex `1/w`
  + varyings, i.e. exactly what the M3c interpolator consumes.
- Command buffer parser: reads a host-memory command list (draw calls:
  vertex count, attribute pointers, matrix, buffer config) written by the
  driver. VS output record staging (small FIFO) feeding the rasterizer.

Software / host:
- Driver writes draw-call records into a command buffer (base is an MMIO
  register; until M6 exists, the testbench pokes it).

Verification:
- Assemble a quad plus a near-plane-crossing triangle from a command buffer
  through transform→clip→project→raster→OM; match against a software
  rasterizer reference (Python/PIL), including the clipped case and a
  perspective-textured-color gradient that fails without perspective
  correction.

---

### M4b — Rasterizer performance iteration
**Goal:** remove the known throughput/timing ceiling before fragment
shading makes it the bottleneck. Small, self-contained, PPA-checked with
the repo's existing `generated/ppa_*` flow.

Hardware:
- **Incremental edge stepping**: evaluate edge functions once at the
  bounding-box origin, then step by `A`/`B` per pixel instead of
  re-evaluating `A·x+B·y+C` per cycle (kills the per-cycle 64-bit
  multiply array).
- **2×2 quad evaluation**: 4 coverage tests per cycle; quads are also the
  fragment-dispatch unit M5 needs for derivatives, so this is not just a
  performance change.
- Set and meet a frequency target in the PPA flow; report area/timing
  deltas against M1.

Verification:
- Bit-exact coverage match against M3b tests (fill rule unchanged).

---

### M5 — Unified shading on the SIMT lanes (the substantive step)
**Goal:** vertices and fragments are *programs* run on the existing SIMT
cores. **No new ISA.**

ISA and toolchain:
- Shader ISA = **RV32IMF(+V) as-is**, plus custom instructions in the
  reserved `custom-0/1` opcode space, kept minimal:
  - `tex.sample` (texture unit request: coord, LOD/bias, sampler id →
    RGBA fp32),
  - `ld.var` / varying read: consume the fixed-function interpolator's
    per-lane attributes (perspective-corrected in hardware),
  - `discard` (kill fragment: predicate off through OM write),
  - derivatives `dFdx/dFdy` come free from 2×2-quad lane mapping
    (difference of neighbor lanes), no instruction needed beyond a
    lane-id read if the core lacks one.
- Toolchain: existing RISC-V gcc/LLVM + a tiny header/library for the
  custom intrinsics. No assembler, no compiler backend.

Hardware:
- Fragment dispatch: rasterizer emits 2×2 quads (M4b); a dispatcher packs
  quads into warps on the SIMT lanes, loads per-lane inputs (x, y,
  barycentrics/`1/w`, interpolated varyings) into registers, and launches
  the fragment program. Lane results + live mask go to the OM.
- Program memory + uniform/constant bank (uniforms are per-draw, read via
  a base register).
- Texture unit: format decode (RGBA8888 first), bilinear sampling, LOD
  selection from quad derivatives, small tag/data cache backed by the
  shared memory hierarchy. Trilinear later.
- Long-latency handling: texture and FPU div/sqrt latencies are hidden by
  warp occupancy; define the stall/scoreboard behavior explicitly (this
  replaces the previous vague "multicycle fixup").
- Vertex shading moves onto the SIMT lanes; the M4 fixed-function
  transform block is retired (kept behind an elaboration flag for one
  release for A/B testing).

Verification:
- Texture-mapped triangle with perspective correction, compared against a
  CPU shader reference (same RV32 binary run on a software model, or a
  Python reference implementation of the ISA subset).
- `discard` test (cutout), derivative test (checkerboard from `dFdx`).

Risks: still the largest milestone, but the ISA/toolchain deletion removes
the worst of it. Split as: (a) dispatch + uniform bank + trivial color
program, (b) texture unit, (c) derivatives/discard, with a test at each.

### M5 Phase D — wire graphics to the core's kernel execution

**Goal:** the production, commercial-GPU-aligned path — a vertex/fragment
shader is a kernel launched on `GpuComputeUnit`'s SIMT warps, reusing the
core's fetch/decode/issue/RF/ALU/FPU/commit machinery. The standalone
`gpu.graphics` `ShaderCore`/`RV32ShaderCore` are verification stepping stones
and are superseded by this.

Starting state (already present):
- `GpuComputeUnit.io.kernel: Decoupled(KernelLaunch{kernelPc, kernargAddress,
  gridSize, localSize})` and `io.completion: Decoupled(KernelCompletion)`
  (see `gpu/dispatch/DispatchTypes.scala`).
- `KernelEmit` (in `gpu.graphics`) assembles a `KernelLaunch` from a draw's
  shader descriptor (the graphics side of the contract).

Steps:
1. **Core kernel-execution harness.** Build a testbench that drives
   `GpuComputeUnit.io.kernel`, provides `fetchRequest/fetchResponse` (the
   shader program from a program memory) and `memoryRequest/memoryResponse`
   (kernarg/uniform reads and shader output writes), and observes
   `io.completion`. Sanity: a trivial RV32 kernel (e.g. `addi` + `ecall` /
   `halt`) launches and completes on the core.
2. **Shader descriptor in the command stream.** Extend the draw-call record
   (or add a shader-setup command) so a draw carries a shader entry PC +
   kernel-arg buffer address (plus grid/local = fragment/pixel counts). The
   driver writes the kernarg buffer (per-draw uniforms, and per-fragment
   varyings once the raster/interpolate stage has produced them).
3. **Graphics → core connectivity.** A top-level driver wires
   `CommandBufferStage` -> (per draw) `KernelEmit.kernel ->
   GpuComputeUnit.io.kernel`; `GpuComputeUnit.io.completion` -> draw-done;
   `KernelEmit` produces the launch; the core executes the shader on its SIMT
   warps and raises completion. This replaces `ShaderFragStage` in
   `ShadedPipeline` with the core-backed kernel.
4. **Fragment varyings as kernarg.** For a fragment shader, pack the
   interpolated per-fragment attributes (x, y, perspective-correct varyings,
   depth, barycentrics/1/w) into the kernarg buffer; the RV32 shader reads
   them (`ld`), applies the uniform, writes the output colour/depth word.
5. **Output to OM.** The shader writes the colour (and depth, or leaves it to
   the fixed-function OM) to the output pointer; the OM depth-tests and writes
   the software colour/depth buffers as already implemented.
6. **Pacing.** Per-draw wait on `KernelCompletion`; multi-buffer pipeline
   (double buffering) is a later throughput optimization.

Verification:
- Compile a small RV32 fragment shader (reads a varying + a uniform from
  kernarg, writes a colour) with the existing RISC-V toolchain; run it through
  CommandBuffer -> KernelEmit -> GpuComputeUnit (with the harness) -> OM;
  compare the framebuffer against a software shader reference. A texture and a
  `discard`/derivative shader are follow-on tests.

Note: this is a large cross-system integration requiring the core's
kernel-execution harness (fetch + memory + completion) plus the graphics
wiring; it should be done as a focused effort rather than layered onto other
work.

Status (implementation):
- The **core-backed path is folded into the existing single modules** rather
  than parallel `Kernel*` clones. `RenderCore`/`RenderPipeline` take a
  `fragCore: Boolean` parameter: `false` (default) is the fixed-function
  interpolated-colour path; `true` inserts `KernelFragStage` between the
  raster/interp stage and the OM, so each fragment is shaded by a compiled RV32
  kernel launched on `GpuComputeUnit` via `KernelShaderStage` (KernelEmit).
  `KernelFragStage`, `KernelShaderStage` and `OmWordToLinePort` are reusable
  parts; the former `KernelShadedPipeline`/`KernelRenderCore` clones were
  consolidated away.
- The draw record carries a shader descriptor (entry PC + kernarg address) that
  `CommandBufferStage` decodes; with `fragCore = true` the kernel's program,
  kernarg and output live in the line-based memory exposed on
  `RenderCore.kernelMemReq/Resp` + `kernelWordMemReq/Resp`, which a shared L2
  arbitrates with the command/framebuffer ports.
- Verified end to end: a command-driven draw is rasterized, shaded by a
  pass-through core kernel reading a varying from kernarg (x1), depth-tested
  and written to the framebuffer (`RenderCoreSpec`, `fragCore`).
- **Batched fragment dispatch (2026-08-25).** `KernelFragStage` no longer
  launches one kernel per fragment: it accumulates fragments into a batch of up
  to `warps*lanes` and, at the draw boundary (`flush`), launches **one** kernel
  with `localSize = count`, waits for completion, reads back the per-fragment
  output words, and re-emits the batch in submission order. Fragments buffer
  their x/y/depth locally so geometry and ordering survive the round-trip. The
  kernarg ABI for a batch is structure-of-arrays (so a lane-aware shader can
  fetch each attribute with one unit-stride vector load — an AoS record would
  need the strided/gather accesses the vector memory unit does not implement);
  with `stride = 4 * warps * lanes`:
  `[0*stride, 1*stride)` per-fragment x (i32), `[1*stride, 2*stride)` y,
  `[2*stride, 3*stride)` depth, `[3*stride, 4*stride)` packed-colour inputs,
  `[4*stride, 5*stride)` colour outputs, `[6*stride, ...)` per-draw uniforms.
  `flush` is
  pulsed by the pipeline when the rasterizer goes idle (draw boundary) so a
  batch never mixes draws; `drained` reports an empty in-flight batch.
  `RenderPipeline`'s `fragCore` branch wires `kernelFrag.io.flush :=
  shader.io.done` and `io.done` reflects the drained batch. Verified in
  `KernelFragStageSpec` for a single fragment, a uniform-adding kernel, a
  multi-fragment batch (geometry/order preserved), and a full-warp vector
  batch whose shader reads the per-lane x array and adds it to the colour
  (reference computed per fragment in the spec).
- The standalone `ShaderCore`/`RV32ShaderCore`/`ShaderFragStage` remain as the
  fixed-function stepping stones (default `fragCore = false`) and are to be
  removed once the core-backed path is the sole shading backend.
- Remaining: per-draw pacing and double buffering, and off-chip memory
  arbitration via `SharedL2Cache`.

Resolved — vector-memory load/store round-trip (2026-08-26).  Per-lane
batched shading (fragment `i` = lane `i`, indexing `kernarg + 4*i` via a
unit-stride `vle32`/`vse32` based at `x1 + 4*localLinearBase`, i.e. `x1 +
(x8 << 2)`) was gated on a corrupt vector memory round-trip: a 4-lane word
store to a fresh line emitted a masked/rotated `byteMask`, and a `vle32`
issued no lower-memory request at all, leaving `vd` stale.  Root cause was
not in the memory path (the coalescer/cache/TLB units all passed their own
specs) but in the operand read: `VectorRegisterBank` derived `predicateMask`
from the **vs1 read port's** lane-0 low bits instead of from **v0**.  For
loads/stores `instruction(19,15)` encodes the scalar base register `rs1`,
so the "mask" was whatever unrelated vector register `v<rs1>` happened to
hold — for `vle32 v2, (x1)` that is `v1`, whose lane 0 the context
initializer sets to `localLinearBase` (= 0 for warp 0), giving
`predicateMask = 0` and a coalescer short-circuit with no request.  The bank
now reads v0 on a dedicated port (a fourth macro set in the ASAP7 physical
bank) and exposes its lane-0 word's low `lanes` bits as `predicateMask`,
matching the packed layout mask-producing instructions write back
(`packedMask` in `VectorBackend`); the scoreboard already tracked v0 as a
source under `useMask`.  Verified: `VectorRegisterFileSpec` (mask follows
v0, not vs1), `KernelFragStageSpec` full-warp batched shading with distinct
per-lane colours and a single `0xffff` output store, and `RenderCoreSpec`
`fragCore` shading every covered pixel through the core kernel.

Resolved — multi-workgroup / multi-warp completion accounting.  The failure
(kernels with more than one concurrent warp tripping `SingleCuKernelController`'s
"completion must identify a resident dispatched warp" assertion) had two
interlocking causes in the warp-launch/schedule path, both now fixed:

  1. **Lost updates on `WarpScheduler`'s whole-register state.** `blocked` and
     `active` were assigned inside four separate `when` blocks (launch /
     issue-load / resume / finish); Chisel's last-write-wins semantics made a
     coincident `resume` (or `finish`/`launch`) silently drop the block bit set
     by an issue load in the same cycle. The just-issued warp became eligible
     again while its fetch was still in flight, so `issueBits` re-loaded the
     stale `(warp, pc)` — duplicate `addi`, double `resume`, the `cease` fetched
     and issued twice, `finish` emitted twice, and `running`/workgroup warp
     counts corrupted. `active`/`blocked` now have a single composed next-state
     assignment (`WarpScheduler`), so coincident events merge bit-wise.
  2. **Launch slot re-derived at the wrong time.** `WarpContextInitializer`
     snapshots the lowest free slot *before* initializing its registers, but
     the scheduler independently re-picked the lowest free slot at
     `launch.fire`; a concurrent `finish` freeing a lower slot during the
     initialization window made the scheduler launch a different slot than the
     one whose registers were initialized (and than the controller's
     `running` accounting). `WarpLaunch` now carries an explicit `warpId`
     chosen by the dispatcher, and `launch.ready` backpressures unless that
     named slot is free.

Regression coverage: `GpuComputeUnitSpec` gains a three-warp single-workgroup
test (the deterministic reproduction: warp A finishes during warp C's
initialization window) and a multi-workgroup grid test
(`gridSize=(2,1,1)`).

---

### M6 — Host interface / Linux device
Hardware:
- MMIO register file (command-buffer base, buffer bases, engine control,
  completion status), device ID.
- Command submission + completion (interrupt) path.
- Bus attachment: minimal AXI4 (or the project's existing NoC port).
  PCIe is out of scope for v1.

Software / host:
- QEMU-modeled RV64 + Sv39 host booting Linux; the GPU is an accelerator
  on the SoC bus.
- Linux kernel driver (char device first, DRM later) + device-tree
  binding; submit workloads, program MMIO.

Verification:
- QEMU host + device model; driver submits a draw and reads the
  framebuffer back.

---

### M7 — Display output (image device)
Hardware:
- Display controller: video timing generator + **scanout DMA** that reads
  the framebuffer from memory (with pixel-format conversion) and drives
  the output.
- Video PHY (TMDS/HDMI/DP encoder) — RTL or licensed IP; compliance is a
  hard constraint and may force a dev-board-specific choice.

Software:
- Linux DRM/KMS (or fbdev first) exposing `/dev/fb0`.

Verification:
- QEMU virtual display; draw a framebuffer and confirm output.

---

### M8 — Userspace graphics (optional / long)
- Mesa Gallium driver, OpenGL ES, eventually Vulkan; shader compiler to
  SPIR-V targeting RV32IMF + the custom graphics instructions (an LLVM
  backend extension, not a new toolchain).
- Much larger than everything above combined; only after hardware is
  stable.

---

## Small-step bias
Each milestone is verifiable in simulation (Chisel spec + a software
reference), and none requires a full graphics API to demonstrate. M1–M4 are
complete (see the status note above). The confirmed direction for M5 is to
**align with commercial GPUs**: run vertex/fragment shaders as kernels on the
core's SIMT lanes via `GpuComputeUnit`'s kernel launch/completion, reusing the
existing RISC-V toolchain. The standalone `ShaderCore`/`RV32ShaderCore` are
reference implementations; the production path is the core-kernel unification.
