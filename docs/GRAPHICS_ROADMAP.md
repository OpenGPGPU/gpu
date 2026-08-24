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
- **Framebuffer = a memory region allocated by software** (driver/host memory
  manager). Hardware only **computes pixel addresses and issues memory stores**
  — it never owns or sizes a framebuffer.

---

## Current state (committed)

| Milestone | commit | Content |
|---|---|---|
| M1 rasterizer | `6caff90` | Fixed-point bounding-box triangle rasterizer (plane-coefficient `A·x+B·y+C`, Vortex-style), `TriangleCoverage` correct |
| M3a interpolation | `9f576a6` | `FragmentInterpolator` (barycentric `attr=(Σ eᵢ·aᵢ)/area`), `RasterShader` emitting `RasterFragment(x,y,color)`; full-triangle shading verified |

Compute core already present: RV32 SIMT lanes + FPU (FMA/div/sqrt/est), RVV
ALU, register files, L1/L2 (SharedL2Slice), memory hierarchy with a standardized
`Decoupled` memory interface (`memoryRequest`/`memoryResponse`).

---

## Roadmap

### M3b — Output Merger (stores pixels to software-allocated framebuffer)
**Goal:** shaded fragments become memory writes the GPU issues, not a buffered
array. The framebuffer exists only in memory, programmed by the driver.

Hardware:
- `OutputMerger` with programmable registers: `framebufferBase`,
  `strideBytes`, `bytesPerPixel`, `screenSize` (driver-writable).
- On a `RasterFragment(x,y,color)`: address `base + (y·stride + x)·bpp`, then
  issue a pixel store via the existing `memoryRequest`/`memoryResponse`
  `Decoupled` port (reuses the compute core's memory path).
- Pixel format: RGBA8888 first.

Software / host:
- Driver/host allocates a memory region and programs the OM registers.

Verification:
- Scala test plays the driver: set base/stride, render a triangle, read the
  memory array back, write a PNG (e.g. via a small PPM/BMP encoder in the test)
  and assert corner/interior colours.

---

### M4 — Geometry front-end (vertex transform + command buffer)
Hardware:
- Vertex stage: fixed-function transform with a programmable **4×4 matrix**
  (model/view/projection), viewport mapping, near-plane clip, primitive
  assembly (triangle).
- Command buffer parser: reads a host memory command list (draw calls: vertex
  count, attribute pointers, matrix, framebuffer config) written by the driver.
- VS output record staging (in memory or a small FIFO) fed to the rasterizer.

Software / host:
- Driver writes draw-call records into a command buffer (base also an MMIO reg).

Verification:
- Assemble a quad/triangle from a command buffer through transform→clip→raster,
  match output against a software rasterizer (e.g. Python/PIL reference).

---

### M5 — Unified shading on the SIMT lanes (the substantive step)
Goal: vertices and fragments are *programs* run on the existing SIMT cores.

Hardware:
- Define a small **shader ISA** (scalar/vector ops over the existing ALU/FPU)
  and a program memory + uniform/constant bank.
- Fragment dispatch: feed interpolated attributes + barycentric coordinates per
  pixel (or per 2x2 quad) to the SIMT lanes; lanes execute the fragment
  program; results go to the OM.
- Texture unit (`tex_*`-like): format decode, bilinear/trilinear sampling, LOD,
  cache.
- Multicycle fixup for the shader program length.

Software:
- A shader compiler / assembler from a high-level form to the ISA, plus a
  minimal runtime to schedule programs.

Verification:
- A texture-mapped triangle; compare against a CPU shader reference.

Risks: largest effort here. Requires defining ISA + compiler and a SIMT
scheduler; scope should be split (ISA → dispatch → texture) with tests at each.

---

### M6 — Host interface / Linux device
Hardware:
- MMIO register file (command-buffer base, framebuffer base, engine control,
  completion status), device ID.
- Command submission + completion (interrupt) path.
- Bus attachment (AXI/NoC minimal; PCIe only if targeting a real slot).

Software / host:
- RISC-V **host CPU** (RV64 + Sv39) or external host that boots Linux; core is
  an accelerator attached to it.
- Linux kernel driver (char/DRM) + device-tree binding; submit workloads,
  program MMIO.

Verification:
- QEMU host + device model; driver submits a kernel and reads results.

---

### M7 — Display output (image device)
Hardware:
- Display controller: video timing generator + **scanout DMA** that reads the
  framebuffer from memory and drives the output.
- Video PMHY (TMDS/HDMI/DP encoder) — RTL or licensed IP; compliance is a hard
  constraint.

Software:
- Linux DRM/KMS or simple fbdev driver exposing `dev/fb0`.

Verification:
- QEMU virtual display; draw a framebuffer and confirm output.

---

### M8 — Userspace graphics (optional / long)
- Mesa Gallium driver, OpenGL ES, eventually Vulkan; shader compiler to SPIR-V.
- Much larger than everything above combined; only after hardware is stable.

---

## Cross-cutting decisions to confirm before M4+
- **Pixel format** (RGBA8888 / BGR / 16-bit).
- **Coordinate system** (top-left origin, y-down matches rasterizer) and
  winding/culling convention.
- **Immediate vs tile-based** rendering (start immediate; tile is an
  optimization).
- **Memory model**: framebuffer + command buffer in a shared region; whether a
  GPU-local VRAM exists or host-backed.
- **Host CPU**: does the target include a RV64+Sv39 RISC-V host core (to run
  Linux), or does the GPU attach to an external host?

## Small-step bias
Each milestone is verifiable in simulation (Chisel spec + a software reference),
and none requires a full graphics API to demonstrate. Prefer completing M3b →
M4 → M5 in that order: M3b gives "memory-visible pixels", M4 gives
"draw-call-driven geometry", M5 gives "programmable shading" (the unification).
