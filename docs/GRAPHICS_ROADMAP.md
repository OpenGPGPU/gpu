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

Numbering note: M2 (barycentric coordinate generation) was implemented
together with M3a and is recorded above for completeness. New milestones
below continue from M3b.

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
reference), and none requires a full graphics API to demonstrate. Preferred
order: **M3b → M3c → M4 → M4b → M5**. M3b makes coverage correct, M3c
makes pixels memory-visible *and* depth-correct, M4 adds draw-call-driven
geometry with real clipping and projection, M4b removes the throughput
ceiling, and M5 delivers programmable shading on the ISA we already have.
