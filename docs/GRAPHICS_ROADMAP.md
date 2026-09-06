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
- Host interface: one AXI4 control slave and one AXI4 memory master expose the
  integrated graphics, compute and DMA product surface.
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
- Multi-CU dispatch plus copy, fill and strided DMA share the integrated memory
  hierarchy, with collision-free transaction-ID ranges for private clients.
- Internal command-buffer, framebuffer and texture word-to-line bridges remove
  the need for separate lower memory ports.
- A dedicated coherent shader-client slot provides L1 invalidation and global
  atomics while keeping the integrated top at one external memory port.
- Completion waits for store drain so host-visible DONE implies framebuffer
  visibility.
- Hardware fill engine for aligned patterned clears.
- Hardware copy engine for aligned, non-overlapping colour blits.
- Scheduler-ordered hardware clearing of each DRM job's private depth plane,
  with a CPU fallback for devices without the fill engine.

### Host and Linux

- AXI4 control interface, capability discovery and legacy START submission.
- Host-memory job queue, interrupt-history ring and ordered completion.
- Shared sticky interrupt delivery for graphics and unified-command completion
  through the AXI IRQ enable and pending registers.
- Linux DRM contexts, GEM bindings, immutable validated submissions, scheduler,
  fences and sync objects.
- Ordered DRM blit jobs using the same context, reservation-fence and syncobj
  model as rendering.
- Ordered DRM patterned-fill jobs for validated GEM ranges.
- Ordered DRM strided-copy jobs for validated two-dimensional GEM ranges.
- Capability-selected unified-command submission for Linux fill, blit and
  strided-copy jobs, with legacy dedicated-register fallback.
- ABI-defined unified-command MMIO and capability discovery cover the common
  Linux compute and DMA submission path.
- IRQ-driven unified DMA completion fences, with a periodic progress poll for
  emulators that advance only on MMIO activity, plus timeout and abort recovery.
- General-compute DRM submissions with immutable validated shader snapshots,
  bounded read/write kernarg access, GEM reservation dependencies and syncobj
  completion fences.
- Generation-tagged hardware event waits and signals for unified compute and
  DMA ioctls, with failed dependencies surfaced through fence errors.
- KMS scanout handoff, atomic modeset, page flip and virtual vblank.
- ARTI/QEMU/Linux integration for the standard-AXI `GpuHostSystemAxi` product
  top in fixed-function, fragment-core and vertex-core configurations.
- Bounded fixed-function and vertex-core PPA emitters cover the complete AXI
  host, graphics, compute/DMA and shared-L2 integration top.
- Parameterized power-of-two render targets of at least 16x16.

## Next

### Product integration

- Broaden the compute sandbox alongside the supported RVV subset.
- Add high-value RVV reductions, widening/narrowing, slide/gather and richer
  memory operations, extending validation only with matching hardware support.
- Measure full-system cost by resolution, remove avoidable host-memory work and
  establish a practical regression default.
- Define ABI-visible unified-command fault reporting and reset recovery.

### Graphics capability

- Add stencil, more blend modes and MSAA.
- Broaden the shader/RVV subset together with its validator.
- Complete precise host-visible fault, timeout and reset behavior after the ABI
  contract is defined.
- Close timing and area on the complete integrated graphics top.

### Deferred

- Mesa Gallium/OpenGL ES, followed by Vulkan and shader compiler integration,
  after the kernel ABI and performance envelope stabilize.
- Discrete PCIe/IOMMU/local-VRAM productization.
- Tile-based deferred rendering, scanout DMA and on-GPU display PHY/timing.
