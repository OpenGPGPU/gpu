# GPU Graphics Roadmap

## Plan

OpenGPU extends the existing RISC-V SIMT compute core into an integrated GPU.
Vertex and fragment programs execute on the shared SIMT lanes; rasterization,
clipping, texture filtering and output merging remain fixed-function blocks.

### Architecture

- Shader ISA: RV32IMF+V plus a small custom graphics subset for texture samples
  and quad derivatives. Use the existing RISC-V toolchain.
- Rendering: immediate mode with 2x2 fragment quads.
- Memory: command, shader, texture, colour and depth buffers live in shared
  software-managed DRAM. There is no v1 GPU-local VRAM.
- Integration: an on-die RV64 Linux host and GPU share the SoC fabric and L2.
- Synchronization: driver-managed cache maintenance, job fences and interrupts;
  no v1 hardware snooping protocol.
- Colour: RGBA8888. Depth: D24 in a 32-bit word.
- Coordinates: top-left origin, y down, CCW front faces and top-left fill rule.
- Interpolation: perspective-correct by default, with flat interpolation where
  required.
- Output: ordered per-pixel depth/blend operations against software buffers.
- Display boundary: GPU RTL publishes render targets and scanout control state;
  an external display subsystem performs scanout and signal generation.

### Pipeline

```text
job/command queue
      |
vertex fetch -> SIMT vertex shader -> clip -> viewport
      |
quad rasterizer -> interpolation -> SIMT fragment shader -> texture sampler
      |
parallel output merger -> shared L2/DRAM -> scanout handoff
```

## Implemented

### Geometry and rasterization

- Full homogeneous-frustum clipping with interpolated position, colour, depth
  and UV attributes.
- Perspective divide, viewport mapping and fixed-point screen coordinates.
- Winding-independent top-left coverage, culling and degenerate rejection.
- Incremental edge stepping and one 2x2 raster quad per beat.
- Perspective-correct colour, depth and UV interpolation.

### Shading and texturing

- Shared `GpuComputeUnit` for vertex and fragment kernels; standalone shader
  cores have been removed.
- Structure-of-arrays kernarg exchange, per-lane RVV output and dual staging
  banks.
- Ping-pong fragment batches overlapping rasterization and SIMT execution.
- Bilinear and trilinear RGBA8888 sampling, repeat/clamp modes, packed mip
  chains, gradient LOD, bias and clamps.
- Quad derivatives, helper lanes, shader depth output and fragment discard.

### Output and memory

- Parallel in-flight output merging with same-pixel hazard ordering.
- Programmable depth test/write and rounded source-over blending.
- Shared L2 arbitration for command, shader, texture and framebuffer traffic.
- Completion waits for store drain so host-visible DONE implies framebuffer
  visibility.
- Hardware fill engine for aligned patterned clears.

### Host and Linux

- AXI4 control interface, capability discovery and legacy START submission.
- Host-memory job queue, interrupt-history ring and ordered completion.
- Linux DRM contexts, GEM bindings, immutable validated submissions, scheduler,
  fences and sync objects.
- KMS scanout handoff, atomic modeset, page flip and virtual vblank.
- ARTI/QEMU/Linux integration for fixed-function, fragment-core and vertex-core
  configurations.
- Parameterized power-of-two render targets of at least 16x16.

## Next

### Product integration

- Merge the graphics host top with multi-CU compute and DMA into one SoC-facing
  device.
- Add Linux compute, copy, fill and strided-DMA submissions using the existing
  queue and fence model.
- Order depth clears and colour blits as ordinary queued jobs.
- Establish practical resolution/performance budgets for full-system tests.

### Graphics capability

- Add stencil, more blend modes and MSAA.
- Broaden the shader/RVV subset together with its validator.
- Complete precise fault, timeout and reset behavior.
- Close timing and area on the complete integrated graphics top.

### Deferred

- Mesa Gallium/OpenGL ES, followed by Vulkan and shader compiler integration.
- Discrete PCIe/IOMMU/local-VRAM productization.
- Tile-based deferred rendering and on-GPU display PHY/timing.
