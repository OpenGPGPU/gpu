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

**Status (2026-08-28):** M1 through M4 are complete and verified — the fixed
function geometry front-end (MVP transform, near-plane clip, perspective
divide/viewport), the rasterizer (top-left fill rule, culling), the
interpolators (screen-space and perspective-correct colour + depth), the
output merger (depth test / write to software colour+depth buffers), the
command-buffer parser, and the composite `RenderPipeline`/`RenderCore` that
renders a command-driven scene into an exported PPM image. M5 now has the
production core-backed fragment path, batched per-lane RVV shading, the
fixed-function texture sampler, and scalar plus per-lane texture instructions.
Shaders run as kernels on `GpuComputeUnit`; the standalone shader cores remain
only as reference/compatibility paths.

Compute core already present: RV32 SIMT lanes + FPU (FMA/div/sqrt/est), RVV
ALU, register files, L1/L2 (SharedL2Slice), memory hierarchy with a standardized
`Decoupled` memory interface (`memoryRequest`/`memoryResponse`).

Current limitations that still drive the remaining roadmap:

1. The core-backed kernarg staging does not yet publish perspective-corrected
   per-fragment UVs automatically; shaders can use `vtex.sample` once UV arrays
   are supplied in kernarg memory.
2. Fragment `discard`, 2×2-quad neighbor operations, derivatives, mip LOD and
   mip storage/addressing are not implemented.
3. Rasterization and physical texture taps are serialized; wider issue/fetch
   is a performance iteration after functional M5 completion.
4. Per-draw overlap/double buffering remains open before the host/display path
   is production-ready; the core-backed path now gates the next draw until the
   previous batched kernel output is drained into the OM.

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
  in the kernarg buffer. The `opengpu.graphics` `ShaderCore`/`RV32ShaderCore` are
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
- **2×2 quad evaluation delivered as a reusable unit, not yet wired into
  emission.**  `QuadCoverage` evaluates all four lanes of a quad with pure
  additions from base + dx/dy and is spec-tested directly; wiring four-lane
  packed emission into the raster output lands with M5's quad-dispatch/
  derivative work so the OM stays single-fragment until then.
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
  vector commit/writeback.  Unit verification covers masking and result
  backpressure; the kernel regression loads four distinct UV pairs, samples
  four texels, and writes all lane results with one vector store.  Automatic
  perspective-UV staging, LOD/mips and quad derivatives remain follow-up work.

Risks: still the largest milestone, but the ISA/toolchain deletion removes
the worst of it. Split as: (a) dispatch + uniform bank + trivial color
program, (b) texture unit, (c) derivatives/discard, with a test at each.

### M5 Phase D — wire graphics to the core's kernel execution

**Goal:** the production, commercial-GPU-aligned path — a vertex/fragment
shader is a kernel launched on `GpuComputeUnit`'s SIMT warps, reusing the
core's fetch/decode/issue/RF/ALU/FPU/commit machinery. The standalone
`opengpu.graphics` `ShaderCore`/`RV32ShaderCore` are verification stepping stones
and are superseded by this.

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
  `[4*stride, 5*stride)` colour outputs, `[5*stride, 6*stride)` reserved,
  `[6*stride, ...)` per-draw uniforms.
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
- **Per-draw pacing (2026-08-28).** `RenderPipeline(fragCore = true)` now
  gates `draw.ready` on both raster-idle and `KernelFragStage.drained`. A
  command-buffer record cannot overwrite the shader descriptor or kernarg
  selection while the prior draw's batched kernel output is still reaching the
  OM. Full overlap/double buffering is still a throughput optimization.
- Remaining: explicit double buffering and completion contracts that include
  the final shared-L2 store drain.

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
implies store-drain belongs to the M6 host-interface work.  Regression:
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

Status (implementation, 2026-08-28):
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
- A minimal AXI4/NoC bus attachment (the `aw`/`w`/`b`/`ar`/`r` channels) is the
  remaining hardware piece; the word interface here is the register-file spine a
  bus adapter can sit on.  The QEMU host model, Linux driver, and device-tree
  binding are the software side and still open.

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
to `driver/gpu_abi.h` (register map, the 32-word draw record, texture state and
the SoA kernarg layout). `driver/gpu_drv.c` is a platform driver binding to
`riscv-simt,gpu`: it maps the `ctrl` resource, allocates the software
command/colour/depth buffers, submits a self-test draw, waits on the completion
IRQ (with a STATUS poll fallback), exposes `/dev/gpu0`, and prints `GPU DRIVER
PASS` for the ARTI harness. `driver/gpu.dtsi` and
`driver/gpu_integration.yaml` give the device-tree node and ARTI profile. See
`docs/HOST_INTERFACE.md` for the full interface/driver design.

Resolved — embedded QEMU/Linux integration (2026-08-28). ARTI compiles the
generated RTL into its QEMU SysBus device, bridges the renderer's command,
framebuffer, texture, and kernel memory clients directly to QEMU guest RAM, and
boots the real AArch64 Linux kernel with `gpu_drv.ko`. The end-to-end harness
passes device probe, AXI register access, interrupt-backed draw submission, and
framebuffer readback (`OPENGPU DRIVER PASS`). DRM handoff belongs to M7.


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

Status (phase 1 virtual display, 2026-08-28):
- **Complete.** ARTI's `guest-memory` display source watches `COLOR_BASE` and
  `STRIDE`, scans the driver-allocated render target from QEMU guest physical
  memory, converts packed RGBA8888 pixels to the QEMU display surface, and
  refreshes through `GraphicHwOps`. The GPU integration profile uses the 16x16
  self-test target; both the headless regression path and the macOS Cocoa
  display backend complete with `OPENGPU DRIVER PASS`.
- The next display phase is a Linux DRM/KMS driver exposing a real scanout
  framebuffer and modesetting API. Hardware scanout DMA, timing generation,
  and the board-specific video PHY remain separate RTL milestones.

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
