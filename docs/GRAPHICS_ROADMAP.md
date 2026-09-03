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

**Status (2026-09-03):** M1 through M4 are complete and verified — the fixed
function geometry front-end (MVP transform, near-plane clip, perspective
divide/viewport), the rasterizer (top-left fill rule, culling), the
interpolators (screen-space and perspective-correct colour + depth), the
output merger (depth test / write to software colour+depth buffers), the
command-buffer parser, and the composite `RenderPipeline`/`RenderCore` that
renders a command-driven scene into an exported PPM image. M5 now has the
production core-backed fragment path, quad-rate dispatch, parallel output
merging and texture taps, batched per-lane RVV shading, the fixed-function
texture sampler, and scalar plus per-lane texture instructions. Vertex and
fragment shaders now share one `GpuComputeUnit`; the standalone shader cores
were removed (2026-09-01) now that the core-backed path is the sole shading
backend.

Compute core already present: RV32 SIMT lanes + FPU (FMA/div/sqrt/est), RVV
ALU, register files, L1/L2 (SharedL2Slice), memory hierarchy with a standardized
`Decoupled` memory interface (`memoryRequest`/`memoryResponse`).

Current limitations that still drive the remaining roadmap:

1. The fixed-function compatibility path (`fragCore = false`) intentionally
   retains scalar fragment emission. The core-backed path uses aligned 2×2
   quads and is the throughput path; texture taps and the output merger are
   parallel there as well.
2. Vertex-core execution is complete in RTL, including shared-CU plumbing and
   the vertex command/kernarg ABI, but it is not yet exposed by the ARTI build
   switch or Linux/DRM submission path. That integration is the next sizeable
   milestone; fragment-core Linux execution remains complete.

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
  in the kernarg buffer. The `opengpu.graphics` `ShaderCore`/`RV32ShaderCore` were
  standalone verification stepping stones (removed 2026-09-01); the production path is to emit a
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
- Blending was deferred from M3c; source-over RGBA8888 blending through the
  per-draw `blendEnable` state is now complete (see the 2026-09-01 status
  below).

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

Status (implementation, 2026-08-27):
- **Incremental edge stepping landed.** `TriangleRasterizer`
  (`src/main/scala/opengpu/graphics/Rasterizer.scala`) now runs a two-stage
  engine: setup (one cycle after draw capture) evaluates each edge once at the
  clamped-bbox origin and precomputes the sub-pixel deltas
  (`A/B << subPixelBits`) plus hoisted per-edge top-left fill-rule flags; the
  scan loop advances the running edge values with one 64-bit add per edge per
  column step and one on each row wrap.  Modular addition distributes over the
  plane equation, so stepped values equal direct evaluation bit-exactly even
  under 64-bit wraparound.  The M1 contract (clamped bbox, top-left rule,
  strict x-then-y order, ready/done semantics that keep the M5 draw-boundary
  batch flush correct) is unchanged, so nothing downstream moved.
- Emitted SV check: the block's remaining multiplies sit only in the setup
  cone; the steady-state scan/emission datapath contains adds and sign tests
  only — the per-cycle multiply array is gone.
- **2×2 quad evaluation and emission complete.** `QuadCoverage` evaluates all
  four lanes from base + dx/dy. The fragment-core rasterizer walks aligned
  origins and emits TL/TR/BL/BR, including uncovered helper lanes; the
  fixed-function build retains covered-only scalar emission.
- Verification: `RasterizerSpec` keeps all four M3b tests green unchanged and
  adds a 24-triangle randomized sweep (solid/sliver/off-screen/reversed
  winding) asserted set-equal against the same software fill-rule reference;
  edge-sharing triangles still cover exactly once.
- PPA: `EmitPpaRtl raster-quad` emits the standalone block to
  `generated/ppa_raster_quad` and `scripts/run_raster_physical.py` drives the
  ASAP7 flow with the same settings as the compute blocks (1 GHz target,
  util 25 / density 60 / TC / SLVT).  Measured with ChipAgent (post-route,
  yosys, no retiming):

  | revision | fmax | slack | instances | area (µm²) | power |
  |---|---|---|---|---|---|
  | first incremental cut: whole setup cone in ONE cycle, operands padded to
    64b everywhere (13× 64x64 multiplies) | 525.85 MHz | −902 ps | 233k | 8 549 | 1.07 W |
  | + two-stage setup (coeffs → origin eval) and natural-width arithmetic
    (multipliers sized to real coordinate ranges) | 824.4 MHz | −213 ps | 227k | 8 457 | 0.36 W |
  | + third stage (product terms registered; final summation adds-only)
    | **1011.0 MHz** | **+10.9 ps** | 227k | 8 185 | 0.41 W |

  Final revision **passes the 1 GHz constraint** (post-route, TC/SLVT, zero
  setup violations, hold clean, zero route DRC).  The critical path moved
  exactly as predicted: off the scan loop entirely and down the setup cone
  one stage at a time, ending at `vHold → coeffReg` (the C cross-product,
  two narrow multiplies and a subtract).  Compared with the first cut, the
  final engine is ~2x faster at less than half the power.

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
  - derivatives use minimal custom-1 `vquad.dfdx/vquad.dfdy` primitives over
    2×2-mapped lanes (difference of neighbor lanes, replicated per row/column).
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
  shared memory hierarchy. The later trilinear extension is now complete
  (see the 2026-09-01 status below).
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

Status (implementation, 2026-08-27) — M5b texture unit:
- **Texture sampling hardware landed** as an independently verified
  fixed-function block: `TextureUnit`
  (`src/main/scala/opengpu/graphics/TextureUnit.scala`).  It samples an
  RGBA8888 texture in shared memory with a bilinear filter through the same
  word-port contract the OutputMerger and kernarg bridge use, so it drops
  into any fabric already serving graphics word clients.  Configuration is
  register-driven (`texBase`, width/height, REPEAT/CLAMP wrap mode), matching
  the design rule that hardware never owns or sizes a buffer.
  - Address decode uses the industry-standard half-texel alignment: sample
    coordinate maps to texel space minus one half texel, so u=(i+0.5)/N hits
    texel i's centre.  The bias is applied in the post-shift domain and wraps
    by two's-complement mask for REPEAT (power-of-two extents asserted),
    saturates into range for CLAMP; neither mode fetches out-of-bounds
    addresses (checked by the memory model).
  - Blend: per-channel fixed-point weights summing to exactly 65536,
    truncated shift back to 8 bits; NEAREST falls out as the degenerate
    whole-fraction case.  Alpha passes through opaque (blending stays with
    the OM hook).
  - FSM detail worth noting: an explicit sBlend state separates the final tap
    write from the blend read (same-cycle tap-write + blend-read latched
    stale data — same failure class as the M4b capture/use race).
- Verification: `TextureUnitSpec` mirrors every documented integer step in a
  software reference and asserts bit-exact agreement across: interior blends,
  REPEAT tiling across the seam, CLAMP overshoot returning solid edge texels,
  exact texel values at all 64 texel centres, a 20-sample randomized sweep,
  plus a stalled-consumer / follow-up-sample regression guarding the state
  leak class.  opengpu.graphics.* is now 56/56 green.
- **Wired into the fixed-function pipeline (2026-08-28).**  `RenderPipeline`
  (fragCore = false) now interpolates perspective-correct UVs per fragment and
  samples through `TexturedFragStage` (`TexUVInterpolator` + `TextureUnit`),
  MODULATE-ing the interpolated colour with the fetched texel before the
  OutputMerger; position/depth pass through and the bypass (texEnable = 0)
  keeps disabled draws sampler-free.  Supporting plumbing: the draw record
  grew to 32 words ([26..31] = per-vertex u,v, unsigned Q16.16), `RasterFragment`
  carries the rasterizer edge values so the stage reuses them without a second
  derivation, `RenderHost` gains TEX_BASE/WIDTH/HEIGHT/CONFIG registers
  (snapshotted at START like the rest) and `RenderCoreL2` arbitrates a fifth
  word client (texture bridge, txn range [4n,5n), its own requester slot for
  coherence).  End-to-end proof: `RenderCoreL2Spec` "render a texture-modulated
  draw through the shared L2" drives a register-programmed textured draw
  (solid half-strength red texture, MODULATE against interpolated (255,0,0))
  and asserts (127,0,0) at an interior pixel plus the depth word, all traffic
  through the single shared L2.  opengpu.graphics.* is 57/57 green.
- **Kernel `tex.sample` path wired (2026-08-28).**  The custom-0 scalar
  instruction carries Q16.16 `(u, v)` operands from decode through system
  dispatch to `TexSampleUnit`, reuses `TextureUnit` and the kernel fragment
  stage's shared word-memory port, then returns packed RGBA8888 through the
  ordinary scalar commit/writeback path.  `KernelFragStageSpec` executes a
  real shader binary and checks the sampled colour end to end.
- **Per-lane `vtex.sample` path wired (2026-08-28).**  A custom-1 vector
  instruction reads Q16.16 UVs from `vs1`/`vs2`, observes ordinary RVV
  `vl`/`vm`/v0 masking, serializes active lanes through the shared physical
  sampler, preserves inactive `vd` lanes, and returns packed RGBA8888 through
  vector commit/writeback. Unit verification covers masking and result
  backpressure; the kernel regression loads four distinct UV pairs, samples
  four texels, and writes all lane results with one vector store.

Risks: still the largest milestone, but the ISA/toolchain deletion removes
the worst of it. Split as: (a) dispatch + uniform bank + trivial color
program, (b) texture unit, (c) derivatives/discard, with a test at each.

### M5 Phase D — wire graphics to the core's kernel execution

**Goal:** the production, commercial-GPU-aligned path — a vertex/fragment
shader is a kernel launched on `GpuComputeUnit`'s SIMT warps, reusing the
core's fetch/decode/issue/RF/ALU/FPU/commit machinery. The standalone
`opengpu.graphics` `ShaderCore`/`RV32ShaderCore` were verification stepping stones
and are superseded by this (removed 2026-09-01).

Starting state (already present):
- `GpuComputeUnit.io.kernel: Decoupled(KernelLaunch{kernelPc, kernargAddress,
  gridSize, localSize})` and `io.completion: Decoupled(KernelCompletion)`
  (see `gpu/dispatch/DispatchTypes.scala`).
- `KernelEmit` (in `opengpu.graphics`) assembles a `KernelLaunch` from a draw's
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
  `[4*stride, 5*stride)` u, `[5*stride, 6*stride)` v,
  `[6*stride, 7*stride)` colour outputs, `[7*stride, 8*stride)` depth outputs,
  `[8*stride, 9*stride)` output-valid, `[9*stride, ...)` uniforms (the UV and
  depth-output slices were added by profile v7 below).
  `flush` is
  pulsed by the pipeline when the rasterizer goes idle (draw boundary) so a
  batch never mixes draws; `drained` reports an empty in-flight batch.
  `RenderPipeline`'s `fragCore` branch wires `kernelFrag.io.flush :=
  shader.io.done` and `io.done` reflects the drained batch. Verified in
  `KernelFragStageSpec` for a single fragment, a uniform-adding kernel, a
  multi-fragment batch (geometry/order preserved), and a full-warp vector
  batch whose shader reads the per-lane x array and adds it to the colour
  (reference computed per fragment in the spec).
- The standalone `ShaderCore`/`RV32ShaderCore`/`ShaderFragStage` (and
  `ShadedPipeline`/`FragmentShader`) were removed 2026-09-01: the core-backed
  kernel path is the sole shading backend, so the stepping stones and their
  specs were deleted.
- **Per-draw pacing (2026-08-28).** `RenderPipeline(fragCore = true)` now
  gates `draw.ready` on both raster-idle and `KernelFragStage.drained`. A
  command-buffer record cannot overwrite the shader descriptor or kernarg
  selection while the prior draw's batched kernel output is still reaching the
  OM. Full overlap/double buffering is still a throughput optimization.
  (Superseded 2026-09-01: admission is now gated only on raster-idle and
  producer-slot availability — see the per-draw overlap entry above.)
- Remaining: none — the store-drain completion contract landed 2026-09-01
  (see the per-draw overlap entry above).
- **Shared vertex/fragment CU (2026-09-02).** `KernelVertStage` now exposes
  the same external kernel-control interface as `KernelFragStage`; in
  `RenderPipeline(vertCore = true)` both feed one `KernelShaderStage`.  A
  launch-owner register routes completion and trap events to the issuing
  stage. The vertex and fragment word bridges retain independent four-ID
  ranges; the shared port prefixes each local ID with its stage, preserving
  up to eight outstanding requests and routing responses without aliasing.
  Vertex-command admission waits for the active vertex
  draw to complete, preventing descriptor overwrite or dropped draws. The
  command-buffer top level selects the vertex-record decoder in this mode,
  and multi-batch vertex draws alternate their two kernarg banks so staging
  writes cannot reuse private-L1 data from the preceding launch.
  `RenderPipelineSpec` elaborates this combined configuration, and the
  standalone vertex-stage regression remains available through its test-only
  compatibility CU.

Resolved — off-chip memory arbitration via `SharedL2Cache` (2026-08-26).
`RenderCoreL2` (`src/main/scala/opengpu/graphics/RenderCoreL2.scala`) composes
`RenderCore` with a `SharedL2Cache`, so the command-buffer, framebuffer, shader
kernarg, and the core-backed kernel's line traffic all arbitrate onto **one**
off-chip memory port (the integrated-SoC morphology where graphics and compute
share a single coherent L2).  Its four line-level clients each hold a disjoint
slice of the L2 transaction-ID space; the shader compute unit is requester slot
0 (the only private-L1 holder) and the word bridges occupy the remaining slots,
so an external write to a shared line triggers the L2 to invalidate the shader
unit's L1 (real coherence rather than a silent stale share).  To wire this, the
compute unit's L1-invalidate + global-atomic ports were threaded up through
`KernelShaderStage` → `KernelFragStage` → `RenderPipeline` → `RenderCore` (the
starting point of the previous in-progress `CacheLineInvalidate` import), and
each module's `fragCore=false` branch ties them off on the correct side
(`ready` for the Flipped inputs, `valid` for the outputs).  Verified:
`RenderCoreL2Spec` runs the lane-aware batched fragment shader through the L2
and checks the shaded colour and depth land in the single off-chip memory, and
the 44 prior graphics tests stay green.

Resolved — OM depth RMW through the shared L2 (2026-08-26).  Enabling
`depthTestEnable` on the `RenderCoreL2` path spuriously depth-rejected about
half of the covered pixels (the direct, no-L2 path rendered the same triangle
solid).  Root cause was response attribution, not the L2's data ordering: the
OM advances past a write at request *accept* (fire-and-forget), so a
fragment's colour/depth write acknowledgements are still in flight when the
next fragment's depth read is awaited.  The L2 returns store acks from the
store-transaction table on a different response channel than load fills and
arbitrates two banks onto one response port, so an ack (data = 0) can overtake
the read response — and the untagged `OmMemoryResponse` let the OM consume it
as the stored depth word, failing every LESS test.  (The direct word-memory
models never ack writes, so the hazard was invisible off the L2.)
`OmMemoryResponse` now echoes the request's `write` bit (tracked per
transaction slot in `OmWordToLinePort`), and the OM pops tagged write acks
wherever they arrive, consuming only `write = false` data in its depth-wait
state.  Read-after-write *data* ordering to a line is preserved by the L2
itself (reads are held while a store transaction is active, stores wait for
load MSHRs), so no cache change was needed.  `CommandBufferStage` is
read-only and `KernelFragStage` allows a single outstanding word transaction
(`wordPending`), so neither can misattribute a response.  Also found while
verifying: `RenderCoreL2.io.done` fires when the OM accepts its final write
request, while the write-through store is still in the L2's store queue — the
readback side must fence (the spec now services the off-chip port until it
has been quiet for 64 cycles).  A completion/fence signal that already
implies store-drain belongs to the M6 host-interface work.  (Resolved
2026-09-01: `RenderCoreL2.io.done` now waits for the shared L2 to report
`drained`, and the spec-side quiet-cycle fence is deleted — see the
store-drain completion entry under M5 Phase D.)  Regression:
`OutputMergerSpec` gains a model that releases write acks only while a later
read is in flight, and `RenderCoreL2Spec` now runs the full depth test
(`depthTestEnable = true`) through the shared L2, asserting every covered
pixel (integer sampling + top-left rule: `x >= 1, y >= 1, x+y <= 16`) is
shaded and its depth word written.  46 graphics tests green.

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

Status (implementation, 2026-09-03): The original M6 scope is complete. The
MMIO register file, AXI control port, completion/interrupt path, QEMU/Linux
integration, job queue/IH ring, and DRM-facing submission path are all covered
by RTL and ARTI regressions. The historical implementation milestones remain
listed below for traceability.
- **Register file + engine control + completion interrupt (hardware).**
  `RenderHost` (`src/main/scala/opengpu/graphics/RenderHost.scala`) presents a
  software-programmable MMIO register file wrapping `RenderCore`: a device ID
  (`RenderHostRegs.ID`), engine control (`CONTROL.START`), status
  (`STATUS.BUSY/DONE/ERROR`, write-1-to-clear), an interrupt enable/pending pair
  (`IRQ`), and the render configuration (command-buffer base, render-target
  bases, stride, depth test/func/write, cull mode).  The host writes the config
  registers then sets START; the engine snapshots the config into a shadow the
  render core runs from (so a host can programme the next frame while the
  current one is in flight) and asserts the completion interrupt when `done`
  rises.  The renderer's memory ports (command-buffer/framebuffer word ports and
  the core-backed shader kernel's line + coherence/atomic ports) pass straight
  through for the SoC to attach to the shared off-chip hierarchy.  Verified in
  `RenderHostSpec` (register read/write, ID, status lifecycle, interrupt, an
  end-to-end draw driven purely through the register file) plus the 46 prior
  graphics tests.
- The AXI4/NoC attachment is implemented by `GpuHostAxi`; ARTI bridges its
  standard channels into the QEMU SysBus model. The word-level register
  interface remains available for simpler NoC adapters.

Resolved — AXI4 host-control interface + Linux driver (2026-08-26).  `GpuHostAxi`
(`src/main/scala/opengpu/graphics/GpuHostAxi.scala`) wraps `RenderHost` in a
standard AXI4 slave: `s_axi_aw*/w*/b*` (burst-capable, word-at-a-time) and
`s_axi_ar*/r*`, `s_axi_aclk/aresetn`, and the completion interrupt `m_irq`.
This is the top ARTI (the RTL-to-QEMU integration tool) auto-bridges: it infers
AXI4 from the standard signal names and generates the embedded QEMU device
model + device-tree node.  Verified in `GpuHostAxiSpec` (single-beat and INCR
burst register R/W, SLVERR on unaligned/out-of-map reads, and a full draw
submitted purely over the bus until the interrupt fires). `EmitGpuHostAxi`
(`gpu/elaboration`) emits `GpuHostAxi.sv` for ARTI. The shared ABI is exported
to `driver/gpu_abi.h` (register map, the 40-word draw record, texture state and
the SoA kernarg layout). The layered driver under `driver/` binds to
`riscv-simt,opengpu`: its platform, hardware, memory, and execution modules map the
`ctrl` resource, allocate software command/colour/depth buffers, submit a
self-test draw, wait on the completion IRQ (with a STATUS poll fallback),
expose `/dev/opengpu0`, and print `OPENGPU DRIVER PASS` for the ARTI harness.
`driver/gpu.dtsi` and
`driver/gpu_integration.yaml` give the device-tree node and ARTI profile. See
`docs/HOST_INTERFACE.md` for the full interface/driver design.

Resolved — embedded QEMU/Linux integration (2026-08-28). ARTI compiles the
generated RTL into its QEMU SysBus device, bridges the renderer's command,
framebuffer, texture, and kernel memory clients directly to QEMU guest RAM, and
boots the real AArch64 Linux kernel with `gpu_drv.ko`. The end-to-end harness
passes device probe, AXI register access, interrupt-backed draw submission, and
framebuffer readback (`OPENGPU DRIVER PASS`). DRM handoff belongs to M7.


---

### M7 — Display-control handoff (image device)
GPU RTL boundary:
- Dedicated `SCANOUT_*` registers publish framebuffer address, pitch, size,
  format, enable and status independently of the render target registers.
- Scanout DMA, video timing, hotplug/EDID and HDMI/DP/eDP PHY are deliberately
  outside the GPU RTL. ARTI/QEMU provides the simulated display consumer; a
  physical SoC supplies a separate display controller or licensed display IP.

Software:
- Linux DRM/KMS exposing a DRM card, GEM DMA buffers, a virtual connector and
  atomic scanout commits.

Verification:
- QEMU virtual display; draw a framebuffer and confirm output.

Status (virtual display and DRM handoff, 2026-08-29):
- **Complete.** ARTI's `guest-memory` display source watches `SCANOUT_BASE` and
  `SCANOUT_STRIDE`, scans the driver-selected framebuffer from QEMU guest physical
  memory, converts packed RGBA8888 pixels to the QEMU display surface, and
  refreshes through `GraphicHwOps`. The GPU integration profile uses the 16x16
  self-test target; both the headless regression path and the macOS Cocoa
  display backend complete with the driver render self-test.
- **DRM/KMS phase complete.** The layered Linux driver registers a DRM device,
  fixed 16x16 virtual connector and simple display pipe, exposes GEM DMA dumb
  buffers, accepts atomic commits in native `RGBA8888`, and programs the
  dedicated scanout bank. The render self-test must pass before the DRM success
  marker is emitted.
- **Userspace KMS verification complete.** The no-libdrm AArch64 guest test
  opens `/dev/dri/card0`, discovers the virtual connector/CRTC/primary plane,
  creates and mmaps two GEM dumb buffers, performs an atomic modeset and flips
  to the second framebuffer. The one-click flow now requires
  `OPENGPU USERSPACE DRM PASS`, so DRM registration alone no longer passes M7.
- **Render/scanout synchronization complete.** The DRM render ioctl targets a
  GEM DMA handle, returns asynchronously, and publishes the completion IRQ as
  a write `dma_fence` in the GEM reservation object. The simple KMS plane
  extracts that fence during framebuffer preparation, and the atomic helper
  waits before updating the scanout registers. The guest test deterministically
  covers this for both initial modeset and the second-buffer page flip. The
  blocking modeset must wait for its delayed verification fence.
- **Virtual-vblank pacing complete.** A generic Linux hrtimer supplies a 60 Hz
  virtual CRTC vblank without extending the RTL display boundary. The second
  flip is submitted asynchronously with `DRM_MODE_PAGE_FLIP_EVENT`; the guest
  test validates its cookie and requires the resulting
  `DRM_EVENT_FLIP_COMPLETE` to arrive only after the delayed render fence and a
  refresh boundary.
- **Validated contexts complete.** Every DRM file owns explicit render-context
  IDs with a scheduler entity, resource state and fence lifetime. Submit copies
  command GEM records into per-job kernel staging before validation, limits
  batch size,
  checks target bounds and fixed-function fields, and rejects raw shader or
  kernarg addresses. The guest test covers a valid render, unsafe-command
  rejection, context destruction and stale-ID rejection.
- **GEM resource binding complete.** Contexts expose 16 typed slots retaining
  validated GEM subranges. `drm_exec` locks every command/target/resource BO,
  applies access-direction implicit synchronization and publishes the render
  fence to each BO. The guest binds a 1x1 texture, verifies the exact
  texture-modulated pixel through RTL, then covers unbind and stale-slot
  rejection. Shader/kernarg offsets are never accepted as raw addresses;
  core-backed execution stays gated until capability and program validation
  exist.
- The full-system texture test also exposed and fixed an RTL channel-order bug:
  `TexturedFragStage` now consumes the established `0xRRGGBBAA` layout.
- **Queued scheduling and explicit sync complete (2026-08-30).** Contexts own
  DRM scheduler entities sharing a one-credit hardware queue. Every queued job
  retains immutable command/depth DMA storage plus referenced GEM objects, so
  back-to-back submits are safe. GEM reservation fences and optional binary
  input syncobjs are scheduler dependencies; the scheduler finished fence is
  published to reservations and an optional output syncobj. The full ARTI guest
  test queues two textured draws, chains the first output syncobj into the
  second input, observes both jobs pending, waits both outputs, and verifies KMS
  fence waits and page-flip vblank. DRM interface version is now 1.3.
- **Fragment-core capability and shader sandbox complete (2026-08-30).** The
  read-only `CAPABILITIES` register reports fragment-core presence and batch
  capacity, while bit 2 reports the separately elaborated vertex-core path;
  the emitted fixed-function top reports zero for both shader-core bits, while
  a 4-lane × 2-warp fragment-core build reports `0x801`. The driver refuses
  shader slots on incapable hardware. On capable hardware it snapshots a bounded,
  cache-line-aligned shader binding into private per-job DMA, then validates the
  exact executed bytes. Profile v1 permits terminating linear RV32I/M, preserves
  x1 as the kernarg base, bounds loads to kernarg and stores to the colour-output
  slice, and rejects branch/jump/atomic/vector/custom instructions. A shared
  native test covers valid pass-through code, write escape, x1 corruption,
  out-of-range read, control flow and missing termination; RTL tests cover both
  capability values, and the ARTI guest covers the fixed-top `EOPNOTSUPP` gate.
- **Core-backed full-system execution complete (2026-08-30).** The RTL emitter
  accepts `--frag-core`, and `GPU_FRAG_CORE=1 ./scripts/run_arti_gpu.sh` builds
  that exact top into QEMU. Probe uses a trusted pass-through shader/kernarg
  self-test on capable hardware. DRM 1.3 exposes `CAPABILITIES` with `GET_PARAM`,
  allowing the adaptive guest to select texture or shader resources. The core
  path rejects an actually unsafe shader with `EINVAL`, executes the restored
  validated program from immutable per-job storage, queues two explicitly
  synchronized draws, and completes KMS modeset/page flip/vblank. Both fixed
  and core-backed Linux boots pass end to end.
- **Validated per-lane RVV output complete (2026-08-30).** Sandbox profile v2
  adds fixed e32/m1 `vsetivli` and unmasked unit-stride `vle32`/`vse32`.
  Abstract interpretation recognizes the trusted warp address form `x1 +
  4*x8 + constant`, proves vector loads remain inside kernarg and stores remain
  inside the colour-output SoA slice, and rejects unconfigured VL, unknown
  bases, input-array stores, boundary crossings and masked memory. Probe and
  DRM guest execute per-warp vector programs. The full-system
  test requires exactly all 120 covered triangle pixels in both queued
  framebuffers, while also proving an unsafe vector store returns `EINVAL`.
- **Lane-local vector ALU sandbox complete (2026-08-30).** Profile v3 admits
  unmasked `vadd/vsub/vrsub/vand/vor/vxor` in their implemented vv/vi forms.
  Validator definedness tracks both SGPRs and VGPRs from the launch ABI through
  loads and arithmetic, rejecting reads/stores of stale registers left by a
  prior task. Native tests cover every admitted encoding plus undefined scalar
  store, undefined vector ALU/store, missing configuration, masked arithmetic,
  reserved operand forms and unsupported funct6. The ARTI guest runs
  `vadd.vi` on `0x102030ff`, requires all 120 covered pixels to become
  `0x102031ff` in both queued framebuffers, and retains the unsafe-store test.
- **Structured forward branches complete (2026-08-30).** Profile v4 admits all
  RV32 conditional branch comparisons when both scalar operands are defined,
  the target is a strictly forward 4-byte-aligned instruction, and no more
  than four branches are simultaneously unreconverged (keeping the kernel
  validator stack below 2 KiB). At each target, dataflow merge intersects
  SGPR/VGPR definedness and retains address provenance/VL only when identical
  on every path. Backward edges, reserved funct3, missing definitions, path-
  local definitions consumed after a join, excessive nesting and branches
  bypassing the common final `CEASE` are rejected. The ARTI guest branches on
  x8: warp zero preserves `0x102030ff`, warp one executes `vadd.vi` to produce
  `0x102031ff`; each queued framebuffer must contain exactly 60 pixels of each.
- **Validated core-backed texture sampling complete (2026-08-31).** Profile v5
  admits only the unmasked custom-1 `vtex.sample` form, requires configured VL
  plus defined UV source VGPRs, and requires the same submit to carry a valid
  texture binding. Shader and texture bindings may now coexist; aliasing with
  command, target, shader or kernarg BOs is rejected, and sampler addresses are
  still generated exclusively from validated base/dimensions/wrap metadata.
  Native tests reject missing textures, masking, undefined coordinates and
  missing VL. The ARTI guest proves the missing-binding gate, samples a real
  texture through guest memory, branches by warp and requires each queued
  framebuffer to contain exactly 60 `0xff0000ff` and 60 `0xff0001ff` pixels.
- **Validated structured early-exit/discard complete (2026-08-31).** Profile v6
  introduced a per-fragment output-valid slice (relocated to
  `[8*stride,9*stride)` by profile v7). The staging
  RTL initializes them to one, reads them beside colour outputs, and suppresses
  zero-valid fragments before the output merger. Scalar and unit-stride vector
  stores may target only the colour/valid slices. Validator CFG propagation now
  stops at `CEASE` per path and resumes at pending forward branch targets, so
  paths may reconverge or terminate independently while backward/unbounded
  control flow remains rejected. Native tests cover valid and invalid early
  exits. The ARTI guest samples a real texture, clears all four valid lanes of
  warp zero and exits that path early; warp one writes colour and exits at its
  own `CEASE`. Both queued framebuffers must contain exactly 60 live sampled
  pixels and no undiscarded warp-zero colour.
- **Perspective-correct UV input and shader depth complete (2026-08-31).**
  Profile v7 expands the SoA ABI with per-lane `u/v` input slices and a bounded
  depth-output slice: input `x/y/depth/colour/u/v`, output
  `colour/depth/valid`, then uniforms. `RenderPipeline` reuses the existing
  perspective interpolator for the core path. `KernelFragStage` stages UVs,
  initializes output depth as pass-through, and reads shader depth beside
  colour/valid before OM. Validator stores remain confined to the three output
  slices. RTL tests load distinct per-lane UVs, sample four texels and require
  shader-written `depth+1` on every live fragment; `RenderCoreSpec` further
  requires that generated depth to reach the OM depth buffer as `0x11`.
- **Quad derivative execution primitive complete (2026-08-31).** Custom-1
  `vquad.dfdx` (funct6 `001100`) and `vquad.dfdy` (`001101`) route to the
  vector integer pipeline. For lane order TL,TR,BL,BR, dFdx replicates
  right-minus-left across each row and dFdy replicates bottom-minus-top across
  each column; inactive/masked lanes still preserve old `vd`. Directed ALU and
  decode tests cover exact results, legality and backpressure.
- **Quad-packed dispatch and safe helper lanes complete (2026-08-31).** The
  core-backed rasterizer emits aligned TL/TR/BL/BR groups. Covered and helper
  lanes all execute, but an immutable coverage bit initializes output-valid
  and is ANDed again after shader completion, so helpers contribute to
  derivatives without reaching OM. Sandbox profile v8 admits unmasked
  `vquad.dfdx/dfdy` only after VL setup with a defined source and zero reserved
  `rs1`. RTL tests cover grouping, helper suppression and real dFdx execution;
  the ARTI guest executes dFdx in its textured fragment shader. The bounded
  driver watchdog is 30 seconds because instruction-level QEMU must execute
  all 256 lanes of a 16×16 bbox, rather than only its 120 covered samples
  (measured about 12.5 seconds for probe and 20.8 seconds per textured draw).
- **Nearest mip selection complete (2026-08-31).** Texture GEMs use a packed
  level chain (base followed by successively halved, tightly packed RGBA8888
  images). Binding flags publish max level; the driver overflow-checks the sum
  of all advertised extents and rejects truncated chains. `TextureUnit` walks
  to the clamped requested level before tap decode. `TexSampleUnit` computes
  rho from all horizontal/vertical TL/TR/BL/BR UV differences scaled by base
  dimensions, selects `floor(log2(rho))`, and shares that nearest LOD across
  the quad. Tests distinguish green base/red mip1/blue mip2 and prove a
  two-texel-per-pixel gradient selects red mip1. The QEMU guest uses the same
  packed chain and validates mip1 through the framebuffer.
- **Sampler integer LOD bias/clamps complete (2026-09-01).** Draw word 33 now
  carries a signed five-bit LOD bias and inclusive minimum mip clamp. The core
  sampler applies saturating bias after quad-gradient selection and clamps to
  the validated `[min,max]` range; the driver rejects unknown bits, inverted
  ranges and clamps outside the bound mip chain.
- **Dual kernarg bank ABI complete (2026-09-01).** Draw word 34 optionally
  supplies a 64-byte-aligned stride between two complete, identical kernarg
  banks. The driver bounds both banks but validates shader addressing against
  one bank, and `KernelFragStage` alternates bank bases only at completed batch
  boundaries. This removes the scratch overwrite hazard required before M5
  can overlap rasterization of batch N+1 with SIMT execution of batch N; zero
  retains legacy single-bank execution.
- **Per-draw overlap / double buffering complete (2026-09-01).** The M5
  throughput milestone: `KernelFragStage` now keeps two ping-pong staging
  slots. While the consumer FSM streams, launches, runs, reads back and emits
  one slot's batch, the producer keeps accumulating the next draw's fragments
  into the other slot, so rasterization of draw N+1 overlaps SIMT execution
  of batch N instead of the whole pipeline serializing behind it. Batches
  never mix draws: a rising `flush` (raster-idle draw boundary) or a full
  batch commits the accumulated slot, and a committed slot backpressures the
  producer until the consumer drains and swaps. Each slot snapshots its
  draw's shader descriptor and sampler state at its first fragment and
  executes against the kernarg bank selected by slot parity, so consecutive
  batches alternate banks (the dual-bank ABI above; overlap with a zero
  stride would let the second kernel hit the first kernel's stale L1 lines
  for the same input addresses, which is exactly the hazard the ABI removes).
  `drawRetired` became `drawRetire`, an ordered valid/ready handshake that
  presents one completion event per draw boundary (including empty draws,
  which queue an immediately-done event) and holds it until the owner accepts
  and pops the matching draw context — so back-to-back retire events can no
  longer be lost. Each OM entry now snapshots its draw context, so that pop
  does not require the whole OM to become idle. `RenderPipeline`'s fragCore branch admits the
  next command-buffer record as soon as the rasterizer is idle and the
  producer slot can take fragments; per-draw state is carried by the context
  FIFO and the slot snapshots rather than by pipeline stalls. Verified by a
  new `KernelFragStageSpec` overlap regression (draw 2 accepted and committed
  while batch 1 is in flight; outputs and retire events in submission order;
  alternating banks observed in memory) plus the full graphics suite and the
  two-draw core-backed `RenderCoreSpec` regression.
- **Parallel output merger complete (2026-09-02).** `OutputMerger` now keeps
  four independent in-flight pixel entries and round-robins their depth reads,
  optional destination-colour reads and colour/depth writes onto the word
  memory port. Distinct pixels can therefore fill read latency with a new
  transaction every cycle, while an address hazard stalls a later fragment of
  the same pixel until the earlier RMW has issued its writes. Every entry
  snapshots render-target addresses plus depth/blend state, allowing draw
  contexts to retire while accepted fragments are still draining. Read
  responses carry their original byte address; `OmWordToLinePort` restores it
  from the transaction table even when shared-L2 responses return out of
  order. Directed tests cover four concurrent pixels, same-pixel ordering,
  concurrent source-over blends, delayed write acknowledgements and reversed
  line-response order.
- **Quad-rate fragment dispatch complete (2026-09-02).** The core-backed
  path now moves whole 2x2 quads per cycle instead of scalarizing them:
  `TriangleRasterizer(quadMode = true)` emits a complete TL/TR/BL/BR
  `RasterQuad` per beat (the per-lane `quadLane` stepping is gone; per-lane
  screen-bounds tests form the helper-lane mask), `RasterShader` shades all
  four lanes in parallel through four interpolators, and `KernelFragStage`'s
  producer stages all four lanes of a presented quad per `fragIn` beat, so a
  full `warps*lanes` batch accumulates in eight beats instead of 32. Batch
  counts stay multiples of four, preserving the 4-aligned lane mapping the
  `vquad.dfdx/dfdy` quad-derivative ops rely on; the kernarg SoA ABI, the
  top-left coverage rule, the draw-boundary (`flush`/retire) contract and the
  fixed-function path are all unchanged. `TexturedFragStage` gained a
  `quadUv` configuration that interpolates per-lane perspective-correct UVs
  for the presented quad. Tests cover one-quad-per-cycle raster emission
  (beat count equals the even-aligned bbox quad count), quad-mode shading
  against the scalar reference coverage, and the migrated
  `KernelFragStageSpec` driving whole quads (helper lanes default uncovered).
- **Store-drain completion contract complete (2026-09-01).** `SharedL2Cache`
  gained a `drained` output: the request queues are empty, each slice FSM is
  idle, no miss fill is in flight, and every store-table entry has been freed
  (freed means its write-through transaction was acknowledged by the lower
  memory).  `RenderCoreL2.io.done` now fires only once `core.io.done` holds
  AND the L2 reports drained, so completion implies the frame is visible in
  the off-chip memory — the readback-side "quiet for 64 cycles" fence hack in
  `RenderCoreL2Spec` is deleted; both L2 tests now read the framebuffer the
  moment `done` is observed.  This closes the M6 host-interface requirement
  that the completion status/IRQ already imply store drain (the driver can
  read the frame at DONE without a software fence).
- **Draw-token/context FIFO foundation complete (2026-09-01).** Each admitted
  draw pushes one `DrawContextFifo` entry (`DrawContextFifo.scala`) binding its
  shader descriptor, sampler configuration and OM render-target state. This
  was the state-tracking foundation for the per-draw overlap milestone above;
  the original retirement pulse has since become the lossless ordered
  `drawRetire` valid/ready handshake, and OM entries now snapshot the head
  context before it retires. `DrawContextFifoSpec`, `KernelFragStageSpec` and
  the two-draw core-backed `RenderCoreSpec` regression cover the resulting
  ordering contract.
- **Trilinear mip blending complete (2026-09-01).** `TextureUnit` now accepts
  an 8-bit fractional LOD and serially bilinear-filters the selected pair of
  packed mip levels before exact Q0.8 RGB mixing. `TexSampleUnit` derives an
  8-bit `log2` fractional weight from the quad gradient; scalar samples retain
  their exact integer-level behaviour. Hardware-side
  per-draw overlap/double buffering is complete (see the per-draw overlap
  entry above).
- **Parallel texture-tap fetch complete (2026-09-02).** For each bilinear mip
  level, `TextureUnit` now issues its four word reads in consecutive cycles and
  tracks issued/received taps independently. Address-tagged responses may
  return out of order; clamped taps that intentionally share one texel address
  are coalesced into one request and fan their response out to every logical
  tap. Trilinear filtering keeps the two mip groups sequential, so downstream
  capacity remains bounded at four outstanding reads rather than eight.
  `TextureUnitSpec` proves
  last-request-first completion, consecutive issue, repeated edge addresses,
  exact randomized filtering and result backpressure; `TexSampleUnitSpec`,
  `KernelFragStageSpec` and `RenderCoreL2Spec` cover the vector sampler and
  shared-L2 integration paths.
- **Source-over output blending complete (2026-09-01).** `OutputMerger`
  reads a passing fragment's destination colour and applies exact rounded
  RGBA8888 source-over composition. Each fragment serializes that read behind
  its own depth result, while distinct pixels proceed concurrently through the
  parallel OM above. Draw record word 32 bit 16 enables it only when the
  per-draw state override is valid; legacy draws remain unblended. The
  additive UAPI extension advances the DRM interface to 1.4.
- Hardware scanout DMA, timing generation and board PHY work are explicitly
  outside this repository's GPU RTL boundary.

Driver architecture for phase 2 is specified in
`docs/DRIVER_ARCHITECTURE.md`. It follows Nova's device/file/GEM ownership
boundaries and AMDGPU's hardware-IP/display/execution separation at a scale
appropriate to this GPU. The dedicated `SCANOUT_*` register bank is now
separate from execution `COLOR_BASE`/`STRIDE`, and ARTI follows the display
registers, so display and render/compute state have independent ownership.

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
existing RISC-V toolchain. The standalone `ShaderCore`/`RV32ShaderCore` were
reference implementations (removed 2026-09-01); the production path is the
core-kernel unification.
