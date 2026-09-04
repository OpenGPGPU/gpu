# OpenGPU Development Priorities

## Plan

Build one SoC-facing GPU product surface around the existing SIMT compute core,
fixed-function graphics pipeline, shared memory hierarchy and Linux DRM driver.
Keep the integrated shared-memory design as v1; defer discrete-GPU concerns and
full userspace graphics APIs until the hardware and kernel ABI are stable.

Priority order:

1. Scale resolution and remove avoidable host-memory work.
2. Merge compute, DMA and graphics behind one host/Linux interface.
3. Expand the useful RVV and shader-sandbox surface.
4. Add output-merger features and physical-design coverage.
5. Start Mesa only after the ABI and performance envelope stabilize.

## Implemented

- Fixed-function transform, clipping, rasterization, interpolation, texturing,
  depth testing, blending and framebuffer output.
- Shared-CU vertex and fragment shading with quad dispatch, derivatives,
  texture sampling and validated RV32IMF+V programs.
- AXI host control, queued jobs, completion history, interrupts and hardware
  memory fill.
- Linux DRM contexts, GEM bindings, scheduling, fences, sync objects, KMS,
  page flips and virtual vblank.
- Scheduler-ordered hardware colour blits with GEM validation and implicit or
  explicit synchronization.
- ARTI/QEMU/Linux end-to-end execution for fixed-function, fragment-core and
  vertex-core configurations.
- Power-of-two render resolutions of at least 16x16, selected consistently by
  RTL elaboration, driver defaults and guest tests.
- Multi-CU dispatch and copy/fill/strided DMA in `GpuSystem` RTL.

## Next

### Near term

- Measure full-system cost by resolution and select a practical regression
  default.
- Unify `GpuSystem` and `GpuHostAxi` so graphics, multi-CU compute and DMA share
  one host attachment, memory hierarchy, queue and interrupt model.
- Expose general compute, fill and strided-DMA jobs through Linux, and migrate
  the existing blit ioctl onto the unified queue payload.

### Mid term

- Add high-value RVV reductions, widening/narrowing, slide/gather and richer
  memory operations; extend validation only alongside hardware support.
- Complete host-visible fault, reset and timeout contracts.
- Add output-merger features such as more blend modes, stencil and MSAA.
- Run physical timing and area analysis on the complete host + graphics + CU
  top.

### Deferred

- Mesa Gallium/OpenGL ES and later Vulkan support.
- Discrete PCIe, IOMMU and GPU-local VRAM.
- Tile-based deferred rendering.
- Scanout DMA, video timing and display PHY inside GPU RTL.
