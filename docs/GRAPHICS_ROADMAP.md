# GPU Graphics Roadmap

## Architecture and Goals

OpenGPU extends the existing RISC-V SIMT compute core into an integrated GPU.
Vertex and fragment programs execute on the shared SIMT lanes; rasterization,
clipping, texture filtering and output merging remain fixed-function blocks.

### Architecture

- Shader ISA: RV32IMF+V plus a small custom graphics subset for texture samples
  and quad derivatives. Use the existing RISC-V toolchain.
- Rendering: immediate mode with 2x2 fragment quads.
- Memory: command, shader, texture, colour and depth buffers live in shared
  software-managed DRAM. There is no v1 GPU-local VRAM.
- Address translation: an Sv32 GPU MMU translates CU data/instruction accesses
  and the fixed-function texture path, with a per-page cache policy and
  ASID-tagged TLBs. Mappings are driver-built and pinned; there is no
  page-fault handling. The full plan is milestone P4.
- Integration: the on-die RV64 Linux CPU and GPU have separate L2 caches and
  access shared DRAM through the SoC fabric. The GPU L2 is shared only by GPU
  clients (graphics, compute and DMA); the CPU does not access it.
- Host interface: one AXI4 control slave and one AXI4 memory master expose the
  integrated graphics, compute and DMA product surface.
- Synchronization: driver-managed cache maintenance, job fences and interrupts;
  no v1 CPU/GPU hardware snooping protocol. GPU-internal L1 invalidation and
  global atomics do not provide coherence with CPU caches.
- Colour: RGBA8888. Depth: D24S8 in a 32-bit word (depth bits 23:0, stencil
  bits 31:24).
- Coordinates: top-left origin, y down, CCW front faces and top-left fill rule.
- Interpolation: perspective-correct colour/UV and affine screen-space depth.
- Output: ordered per-sample depth/stencil/blend operations against software
  buffers, with per-pixel shading.
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
sample expansion -> parallel output merger -> GPU L2 -> AXI4 -> shared DRAM
                                                           |
                                                     scanout handoff
```

## Implementation Inventory

This inventory describes code present in the working tree, including ongoing
extensions. It is not a release qualification report. A feature is ready for
software use only when RTL, ABI definitions, driver validation, capability
advertising and integration tests agree. The milestone exit criteria below
track that distinction.

### Geometry and rasterization

- Full homogeneous-frustum clipping with interpolated position, colour, depth
  and UV attributes.
- Perspective divide, viewport mapping and fixed-point screen coordinates.
- Winding-independent top-left coverage, culling and degenerate rejection.
- Incremental edge stepping and one 2x2 raster quad per beat.
- Perspective-correct colour and UV interpolation; affine screen-space depth.
- 1x/2x/4x sample coverage and per-sample depth with fixed quarter-pixel patterns.

### Shading and texturing

- Shared `GpuComputeUnit` for vertex and fragment kernels; standalone shader
  cores have been removed.
- Structure-of-arrays kernarg exchange, per-lane RVV output and dual staging
  banks.
- Validated masked and unmasked lane-local RVV integer ALU, saturation,
  multiply, divide and remainder operations in `vv`, `vx` and legal `vi`
  forms, with defined predicate/destination checks for masked execution.
- Validated unmasked comparisons, single-width reductions, gather and slide
  operations in their supported `vv`, `vx`, `vs` and legal `vi` forms.
- Validated masked and unmasked lane-local `vsext/vzext.vf2/vf4/vf8`
  integer extensions in the fixed profile, with preserved inactive lanes,
  defined predicate/destination checks and reserved-overlap rejection.
- Masked and unmasked `vnsrl/vnsra.wv/wx/wi` and rounded saturating
  `vnclipu/vnclip.wv/wx/wi` over explicit even/odd low/high word pairs,
  with source-pair dependency tracking and matching shader validation.
  Clip supports all four hardware rounding modes and commit-time saturation.
- Masked and unmasked single-width `vssrl/vssra.vv/vx/vi` share the
  rounding path, with matching shader validation. The shader interface uses
  reset RNU; rounding-CSR writes are not admitted.
- Validated masked and unmasked unit-stride RVV word loads and stores, with
  preserved-destination and mask-register checks.
- Hardware `vlse8/16/32.v` and `vsse8/16/32.v` address generation with sparse
  cache-line coalescing and cross-line element reassembly. The Linux validator
  exposes the word forms when a direct signed constant stride proves every
  lane remains in the bound kernarg and writable output range.
- Hardware `vluxei32/vloxei32/vsuxei32/vsoxei32` byte-index address generation
  reuses the sparse-line coalescer. The driver tracks launch-time local-index
  provenance through an exact unmasked `vsll.vi ...,2` and validates the resulting
  complete-batch byte span before admitting indexed loads or stores.
- Ping-pong fragment batches overlapping rasterization and SIMT execution.
- Packed 2x2 fragment staging records and a dedicated consumer word-request
  register in KernelFragStage; physical timing closure is ongoing.
- Bilinear and trilinear RGBA8888 sampling, repeat/clamp modes, packed mip
  chains, gradient LOD, bias and clamps.
- Quad derivatives, helper lanes, shader depth output and fragment discard.

### Output and memory

- Parallel in-flight output merging with same-pixel hazard ordering.
- Programmable depth test/write and rounded source-over blending.
- D24S8 single-sided stencil and GL-style blend factors/equations, carried
  through the host registers, job record, both draw forms and the Linux UAPI
  with full driver validation (see [STENCIL_BLEND_DESIGN.md](STENCIL_BLEND_DESIGN.md)).
- Post-shading sample expansion and per-sample framebuffer addressing. MSAA is
  advertised on both backends - fixed-function and programmable - with
  programmable-path staging and ABI-1 depth selection exposed to Linux behind
  the fragment-core capability bit. Bit 7 is a functional claim; physical
  timing closure of the programmable stage remains a separate PPA gate.
- GPU-internal shared L2 arbitration for command, shader, texture and
  framebuffer traffic.
- Host-driven shared-L2 line invalidate: drops a resident line and snoops its
  L1 holders with no lower-memory traffic, because stores are write-through.
  It rides the normal lookup path and is used to make CPU-written memory
  visible to later GPU reads.
- Per-page GPU cache policy: Sv32 PTEs carry a policy in the reserved bits
  [9:8] (cached / write-through / uncached). The page-table walker decodes it,
  the data and instruction TLBs propagate it, and the L1 and L2 bypass both
  cache levels for `uncached` pages while stores stay write-through. This is
  the mechanism for mapping CPU-written buffers as uncached (coherent without
  an explicit invalidate). The integrated top exposes `VECTOR_SATP` /
  `INSTRUCTION_SATP` and a scoped `TLB_FLUSH`, and the driver enables Sv32 at
  init with a 4 MiB-superpage identity map, so the CU MMUs are live while every
  existing physical-address binding keeps working. The driver splits a 4 MiB
  region into a second-level table when a page needs a non-default policy, and
  maps CU-read kernargs uncached so a CPU write stays visible without a flush.
  The fixed-function texture client also translates through the same page
  tables (`GraphicsAddressTranslator`), so a texture mapped uncached is read
  past the L1 and L2; the other graphics clients stay on physical addresses. A
  resource binding may set `OPENGPU_RESOURCE_UNCACHED` to map its range
  uncached (kernargs default to it).
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
- Ordered DRM shared-L2 line-invalidate jobs (`DRM_IOCTL_OPENGPU_INVALIDATE`)
  over validated 64-byte-aligned GEM ranges, using the unified `INVALIDATE`
  command, so a driver can make CPU-written memory visible to a later GPU read.
  The operation costs one L2 lookup per line and issues no memory traffic, so
  callers should invalidate a buffer once after a CPU write (per upload) rather
  than per draw. The driver already invalidates a directly-read resource binding
  (texture, vertex buffer, kernarg) at bind time, which is the upload boundary;
  the ioctl covers re-uploads, the resolve operation invalidates its own source
  range implicitly, and exported GEM dma-bufs run the same invalidate from
  `end_cpu_access` so a `DMA_BUF_IOCTL_SYNC` CPU-write window is coherent with a
  later GPU read.
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
- Atomic DRM queries for the latest unified-command fault, including raw
  status, errno, command metadata, byte counts and timeout/protocol reasons.
- Safe unified-command reset that stops dispatch, drains in-flight commands and
  memory transactions, refuses racing submissions and discards the abandoned
  stream's completions, with explicit DRM recovery: `-EBUSY` while draining,
  `OPENGPU_FAULT_RESET_ISSUED`, and `-EIO` wedging on a drained timeout.
- KMS scanout handoff, atomic modeset, page flip and virtual vblank.
- ARTI/QEMU/Linux integration for the standard-AXI `GpuHostSystemAxi` product
  top in fixed-function, fragment-core and vertex-core configurations.
- Selectable Verilator and FlashSim backends in the ARTI runner; QEMU,
  Linux and the driver use the same integration path.
- Bounded fixed-function and vertex-core PPA emitters cover the complete AXI
  host, graphics, compute/DMA and GPU-internal shared-L2 integration top.
- Parameterized power-of-two render targets of at least 16x16.

## Prioritized Milestones

### P0: Complete and verify the submission contract

Close the current feature set before adding more shader operations.

- Synchronize RTL registers, draw/job record layouts, C/UAPI definitions and
  capability discovery. The stencil/blend portion of this item is complete
  (draw words 35–37, job words 10–12, registers 0x13C–0x144, validation and
  negative UAPI tests); remaining items track the other features below.
- Sample-mode admission is implemented for START and raw job-ring submission:
  both reject reserved/high-bit or above-build modes; queued rejection emits
  ordered DONE|ERROR with IH status 1 and waits for IH write acknowledgements
  before interrupt delivery. Queue regressions cover 1/2/4-sample builds,
  consecutive invalid jobs, prefetch, backpressure and valid-job recovery.
- Fragment ABI selection is defined by named mode-coupled profiles: sample
  mode 0 uses ABI 0; modes 1/2 use ABI 1. ABI 0 preserves any-nonzero emit and
  shader depth. ABI 1 discards reserved-bit violations and selects emit/depth
  override from bits 0/1. Per-batch snapshots and overlapping draws are covered
  by shader regressions. Programmable MSAA is advertised through the same
  mode-coupled contract, so the two paths share one ABI and one capability bit.
- Keep draw-context retirement distinct from job completion: context state may
  retire after its final sample reaches an OM entry that snapshots the state;
  job DONE/fences must wait for acknowledged memory writes and lower-path drain.
- ABI layout/encoding checks parse `driver/gpu_abi.h` and compare the register
  map, capability word, fragment control ABI, record sizes and blend/stencil/IH
  shifts against the Scala definitions (`GpuAbiLayoutSpec`), so the UAPI and
  hardware cannot drift silently. The bounded integration matrix covers
  fixed-function and fragment-core builds, legacy and ring submission, padded
  strides, invalid sample-modes and delayed/backpressured memory responses;
  vertex-core record decoding is covered by `CommandBufferStageSpec`.

Exit: accepted submissions have consistent semantics across supported paths;
invalid submissions have documented error behavior; mixed-draw state and
completion-visibility regressions pass. Header declarations alone do not
qualify a capability as implemented end to end.

### P1: Complete render, resolve and scanout

- Qualify fixed-function 1x/2x/4x rendering, including depth, stencil, blending
  and caller-initialized uncovered colour samples. A queued 1x/4x/2x/1x job
  sequence now checks that each job's sample layout follows its own mode and
  that uncovered pixels keep their caller-initialized samples; per-sample
  depth/stencil/blend stay covered by the `OutputMerger` unit regressions.
- Add the typed resolve operation, validated source/destination ranges and
  scheduler ownership. Start with the trusted compute-kernel design in
  [MSAA_DESIGN.md](MSAA_DESIGN.md); measure before adding a dedicated engine.
  The streaming backend (`MsaaResolveEngine`) and the `GPU_UCMD_OP_RESOLVE`
  router path into the shared L2 are implemented and tested, the unified MMIO
  bridge stages the resolve fields plus `UCMD_SAMPLE_MODE` (0x148), the
  driver-side range/overlap rules, command builder, `DRM_IOCTL_OPENGPU_RESOLVE`
  UAPI and scheduler-backed ioctl (source read / destination write reservations,
  output syncobj) are in place, and scanout of a resolved buffer is ordered by
  the destination BO's write fence through the standard KMS implicit-sync path.
  Before the engine reads, the resolve adapter invalidates the source range in
  the shared L2 with a host-driven line invalidate (drop a resident line, snoop
  its L1 holders, no lower-memory traffic), so a page recycled from an earlier
  GPU buffer cannot shadow what a non-coherent agent wrote to lower memory. A
  CPU-initialized source therefore needs no special allocation.
- Attach source-read and destination-write reservation fences and sync objects;
  KMS must wait for resolved output before scanout.
- Programmable MSAA is enabled: helper-lane, derivative, discard, per-sample
  depth and shader-depth-override tests cover its advertised modes, and the
  guest test performs a multisample render, resolve and scanout. Physical timing
  closure of the programmable stage remains a separate PPA gate.
- A submission without a depth attachment stays one pass with private cleared
  depth. Caller-owned persistent depth attachments and cross-submission load
  semantics are implemented: `depth_handle`/`depth_offset` bind a GEM range as
  the depth plane and `OPENGPU_SUBMIT_DEPTH_LOAD` keeps its contents, so a pass
  may be split across submissions that re-bind the attachment. Advertised as
  `OPENGPU_CAP_PERSISTENT_DEPTH` (bit 19). `RenderHostSpec` covers a queued
  two-submission pass at the RTL level, and the guest DRM test runs a
  two-submission pass through ARTI/QEMU under the Verilator backend: the first
  submission clears the bound plane and stores depth, the continuation re-binds
  it with `OPENGPU_SUBMIT_DEPTH_LOAD` and draws farther geometry with GREATER,
  which passes only against the stored nearer depth, and malformed bindings are
  rejected. The FlashSim model does not currently make the prior depth write
  visible to the continuation, so that path is verified on Verilator.

Exit: clear/render/resolve/fence/scanout runs through Linux under ARTI/QEMU;
1x -> 4x -> 2x -> 1x jobs do not leak state, and delayed writes cannot produce
early completion. No implicit resolve is required after every draw.

### P2: Consolidate state and ownership incrementally

Do this alongside P0/P1 when changing the relevant boundary, preserving each
stage's independent snapshot lifetime.

- Factor reusable depth/stencil/blend/target/sampler state bundles and explicit
  decode/copy helpers. These fields currently recur in `JobConfig`,
  `DrawRenderState`, `DrawContext`, vertex records and wrapper IOs.
- Reduce `RenderPipeline` wiring duplication around state selection, shader
  dispatch and output retirement; retain explicit ready/valid and drain rules.
- Separate driver command validation/job preparation from common scheduler,
  context and fence services in `opengpu_compute.c`. Render, compute, DMA and
  resolve should reuse those services rather than create parallel schedulers.
- Move root DRM lifetime toward platform ownership. Today `opengpu_display.c`
  allocates/registers DRM and probe hands it `gpu->compute.color`; remove this
  bring-up coupling when separating render-only and display configurations.
- Consider one machine-readable ABI description for generated constants/layout
  checks after encodings settle. Avoid a large framework rewrite first.

Exit: adding one render-state field has a small, explicit set of integration
points; behavior and supported configurations retain regression coverage;
display and execution no longer depend on bring-up buffer ownership.

### P3: Establish performance and physical closure baselines

Collect baseline measurements now; optimize after the functional contract is
stable. This work need not wait for all structural cleanup.

- Record cycles, shader staging traffic, OM stalls/conflicts, L2 misses and
  lower-memory bandwidth for representative resolutions and sample modes.
- Choose a practical small regression default plus larger workload sweeps.
  Rank changes using measured cost before expanding RVV or OM concurrency.
- Refresh PPA for the current RTL, including MSAA/stencil/blend. Historical
  block measurements do not qualify newly changed logic or the integrated top.
- The recorded KernelFragStage `emitquadsel` u50 run reaches 1022.98 MHz core
  Fmax with +13.27 ps worst setup slack and zero setup violations across groups.
  It still has 990 virtual-IO boundary hold violations (worst -34.79 ps);
  register-to-register hold is reported clean. Resolve interface constraints
  and physical integration before declaring closure.
- SharedL2Slice: the registered missEngine fill stage makes the core clock
  close (1047 MHz, +44.5 ps) and the block is now hold-clean and DRC-free. Its
  455-474 routing DRCs were placement-dependent, not structural: the fixed 6x3
  macro grid collides a pin column with the power mesh, and the adaptive grid
  (`timing/asap7/l2_sram_macro_placement_adaptive.tcl`, 12 um channel) clears
  them to 0. The only residual is the virtual-IO boundary setup group, the same
  flat-flow limit described below. VectorIntegerAlu has passed its documented
  block recipe. See [timing/README.md](../timing/README.md) for configurations
  and limitations.
- Per-block PPA is the current methodology. Whole-block (`VectorBackend`-scale)
  runs cannot close in the flat local ORFS image: the worst path is always a
  virtual input port to an internal capture register, and the block-level
  residuals above are that same IO-boundary class. They belong to parent-level
  integration, not block RTL. See the "Whole-block physical-flow limit" section
  of [timing/README.md](../timing/README.md).

Exit: reproducible functional/performance workloads and full-top PPA reports
identify the remaining limits; setup, hold and routing checks are all accounted
for. A block Fmax above 1 GHz alone is not full-top timing closure.

### P4: Virtual memory and multiple address spaces

The GPU MMU is enabled and carries per-page cache policy, but it currently runs
a single identity-mapped address space shared by all clients. This milestone
grows it into a real GPUVM layer.

Implemented:

- Sv32 `satp` (vector and instruction) and a scoped `TLB_FLUSH` are host
  programmable; the driver enables translation at init with a 4 MiB-superpage
  identity map, so existing physical-address bindings keep working.
- `TLB_FLUSH` (0x154) carries a scope: bit 0 is the legacy full flush, bit 1
  drops only entries whose ASID matches bits [11:3], and bit 2 drops only
  entries whose VPN matches bits [31:12]. Global mappings and other address
  spaces stay resident, and the CU data/instruction TLBs apply the scope
  independently. The driver exposes `opengpu_hw_flush_tlb_asid` /
  `opengpu_hw_flush_tlb_vpn`; the fixed-function texture translator tracks no
  ASID/VPN, so any flush pulse clears it.
- Sv32 PTE bits [9:8] carry a per-page data cache policy (cached / write-through /
  uncached); the walker, data TLB, texture translator, CU data L1 and shared L2
  honour it. Instruction fetch remains cached; the ITLB does not carry policy.
- `opengpu_mmu` splits a 4 MiB region into a second-level table on demand and
  maps a range with the chosen policy; compute kernargs default to uncached and
  texture bindings may request it with `OPENGPU_RESOURCE_UNCACHED`. Updates
  exclude submissions and drain execution before publishing tables and flushing.
- Graphics shader CUs currently run Bare. Their directly-read bindings retain
  cache invalidation at bind time, including bindings requesting uncached access.
- The fixed-function texture client translates through the same page tables
  (`GraphicsAddressTranslator`); the other graphics clients stay physical.
  Translation faults complete locally with a fault-marked zero response and
  never access the requested physical address. Texture translation and memory
  faults latch a per-job failure, drain rendering, and report STATUS.ERROR;
  queued jobs also publish IH status 2 and ERROR before raising the IRQ, so the
  driver signals the failed fence with -EIO. Framebuffer contents may be partial.

Design notes (from the MMU/ASID review):

- ASID tags TLB entries so entries from several address spaces coexist and a
  switch needs no flush. An ASID maps to a GPU address space (one root page
  table), not to a Linux PID; two processes may share one address space when
  they are given the same root, in which case sharing the ASID is intended.
- amdgpu uses a small VMID space (16) because a VMID selects a hardware
  page-table-base register set (plus GDS/GWS/OA context), and the driver
  multiplexes many VMs over it with an LRU, flushing that VMID's TLB on reuse.
  Sv32 gives 9 ASID bits (512), so reuse is rarer, but the same rule holds:
  flush an ASID before reusing it for a different page table.
- One CU has one active `satp`, so a driver-managed coarse switch (quiesce,
  then reprogram satp) is sufficient and needs no per-job `satp`. A hardware
  per-job `satp` or a VMID-style base-register bank only pays off for
  fine-grained interleaving of many address spaces.
- Page walks are two uncached DRAM reads and the TLBs are blocking, so large
  pages, adequate TLB capacity and (later) non-blocking translation dominate
  the runtime cost; an ASID-scoped flush preserves other address spaces' TLB
  entries, a full flush does not.

Remaining work, cheapest first:

- Driver VM manager: an ASID allocator, one root page table per VM, global (G)
  mappings for shared ranges, and a coarse VM switch in the scheduler. The
  scoped `TLB_FLUSH` above is the shootdown primitive it builds on.
- Keep per-VM mappings on large pages; size the TLBs for the working set and
  avoid blocking translation on the texture path. The `GraphicsAddressTranslator`
  is deliberately blocking today; measure before widening it.
- Only if many short-lived address spaces must interleave: per-job `satp` or a
  small VMID-style page-table-base bank beyond the current global `satp`.
- Translate the remaining graphics clients (command buffer, framebuffer) if a
  VM-addressed graphics space is required.

Exit: the driver can run several address spaces with correct scoped shootdown
and no flush on a plain ASID switch; performance baselines quantify the
walk/TLB cost; the single-identity-map limitation is gone.

### Later capability expansion

- Prioritize additional shader/RVV operations from real workload or compiler
  needs, with matching hardware, validator and ABI tests.
- Start Mesa Gallium/OpenGL ES integration after the submission/render-pass
  contract and performance envelope stabilize; Vulkan follows separately.

### Deferred

- Discrete PCIe/IOMMU/local-VRAM productization.
- Tile-based deferred rendering, scanout DMA and on-GPU display PHY/timing.
