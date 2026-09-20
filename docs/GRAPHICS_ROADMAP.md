# GPU graphics roadmap

Current implementation and priorities, refreshed 2026-09-20. This document
states current behavior; the previous milestone narrative is preserved in
[history](history/GRAPHICS_ROADMAP_2026-09-20.md). Test existence, a passing
functional run and physical timing closure are separate claims.

## Architecture

- RISC-V SIMT execution with shared vertex/fragment shader execution and
  separated clipping, rasterization, sampling and depth/stencil/blending.
- Immediate-mode rendering; 2x2 fragment quads; 1x/2x/4x sample coverage;
  perspective-correct colour/UV and screen-space depth.
- RGBA8888 colour and D24S8 depth/stencil in software-owned shared DRAM.
- One AXI4 control slave and one AXI4 memory master in `GpuHostSystemAxi`.
  GPU clients share GPU L2; CPU caches are separate and not snooped.
- Unified commands for render, compute, copy, fill, strided copy, resolve and
  line invalidate. Rendering is dispatched beside `RenderHost`; other
  commands use the system router. One MMIO completion slot/IRQ serves both.
- Driver scheduler ownership of GEM references, dependencies and fences.
  Display consumes GEM framebuffers; it does not own execution lifetime.
- Sv32 private VA windows and ASIDs for contexts. Command, framebuffer and
  texture clients translate alongside CU data/instruction accesses. Global
  identity mappings remain for compatibility; this is not full VM isolation.
- External display hardware owns actual scanout and signal generation.

## Completed consolidation

The former P5/P6 submission and structural work is present: the render ring,
IH ring and legacy render START snapshot are removed; translated word clients
share one wrapper; transaction-ID ranges are generated and checked; resolved
draw state has one common bundle. Legacy DMA MMIO remains supported.

The current qualification pass adds:

1. Render-aware reset retirement, including descriptor fetch, the launch gap,
   execution, pending completion and delayed memory acknowledgements.
2. Whole-word sample-mode admission on unified descriptors, fault retention
   during descriptor fetch, and 1-sample-only elaboration coverage.
3. Boundary-aware CI selection and a standalone host driver-test job.
4. Optional event/counter visibility and reproducible checked workload sweeps.
5. Source-hashed physical runs with real clock ports, explicit IO budgets and
   SRAM libraries; measured failures remain failures in the report.
6. Common driver scheduler services and platform-owned DRM/GEM registration.
   KMS starts without a borrowed boot framebuffer; a render-only DT option is
   available.

Detailed evidence and remaining qualification limits live in
[QUALIFICATION.md](QUALIFICATION.md). Historical reports do not qualify changed
RTL; clock-invalidated figures are archived rather than used as baselines.

## Active priorities

### A. Preserve the submission contract

Maintain the RTL/ABI/validator/capability/test matrix. Every submission-path
change must exercise descriptor errors, reset during work, delayed writes,
completion backpressure, recovery and mixed sample modes. Tests selected for
shared graphics/command/memory boundaries must include system integration.

Exit: advertised behavior remains covered through the actual unified path;
errors cannot report success and reset cannot leave an old job running.

### B. Measure before optimizing

Use `scripts/benchmark_gpu.py` to sweep 16/32-pixel targets at 1x/2x/4x,
plus programmable shading, texturing and overdraw. Record submission-to-IRQ
cycles, OM/raster stalls, staging traffic, translation misses/walk/stalls,
L2 activity and lower-memory bytes. Counters overlap and must not be summed
as independent time components. The deterministic memory model is a baseline,
not an SoC bandwidth prediction.

Exit: candidate changes can be compared under the same source/configuration,
scene, memory model and image checks. Expand resolutions and memory latency
only after the small baseline remains stable.

### C. Physical qualification

Use `scripts/qualify_ppa.py` with emitted current RTL. Record clock source,
period, boundary delays, corner, SRAM models and exact input hashes. Treat
synthesis estimates, register-only STA and all-path post-route STA separately.
A completed tool run does not imply timing closure.

Exit: parent interfaces and every required block have accounted setup/hold,
routing and macro constraints. Integrated 1 GHz closure remains a design
objective, not an achieved milestone. Optimize measured critical cones before
attempting larger flat physical runs.

### D. Software-driven capability growth

Keep the explicit shader validation profile. Select new ISA coverage from a
small compiler-generated shader corpus and differential reference results.
Continue driver separation at tested boundaries; retain one scheduler for all
opcodes. GPUVM work should target measured translation cost and stronger
mapping isolation, rather than duplicate the existing private VA plumbing.

Exit: real shaders expose concrete missing operations/ABI requirements and
compile to validated executables with predictable performance.

## Later work

- A small Mesa/Gallium integration experiment once the submission and workload
  baselines are dependable; broader API coverage follows measured demand.
- Nonblocking graphics translation, larger TLBs or wider output merging only
  when workload counters justify their cost.
- Parent-level timing budgets and a realizable clock target informed by full
  timing evidence, including boundaries.

Discrete PCIe/local VRAM, demand paging, tile-based rendering and display
PHY/timing remain outside the present product scope.
