# Linux Driver Architecture

## Architecture

The driver uses a layered DRM architecture with downward-only dependencies:

```text
platform/device lifetime
        |
hardware services: MMIO, IRQ, reset, queues
        |
memory services: DMA and GEM
       / \
execution client     display client
render/compute       DRM/KMS scanout
```

- `opengpu_drv.c` owns probe/remove, the root device and subsystem lifetime.
- `opengpu_hw.c` owns register access, interrupts, typed job launch and bounded
  engine waits. Reset, recovery and power management belong here.
- `opengpu_memory.c` owns coherent DMA storage and GEM object services.
- `opengpu_compute.c` owns contexts, resource validation, scheduling,
  submissions and completion fences.
- `opengpu_display.c` owns the virtual connector, display pipe, atomic modeset,
  scanout programming, page flips and vblank.

Display and execution remain independent. They synchronize through GEM
reservation fences; display never calls execution internals. `SCANOUT_*`
registers are separate from render-target registers.

Each DRM file owns render contexts. A context owns a scheduler entity, a
resource-binding table and its latest fence. Submission snapshots command and
shader data into private DMA storage, validates binding-relative accesses and
retains all referenced GEM objects until completion. The scheduler resolves
GEM and sync-object dependencies, submits jobs through the hardware ring and
signals fences from interrupt-history records.

The shader sandbox admits only explicitly validated RV32IMF+V operations.
Scalar and vector memory accesses are proven to stay within the relevant
kernarg slices; defined-register tracking prevents stale-register disclosure;
bounded forward control flow must terminate. Texture and quad instructions are
allowed only with the required validated resources and lane configuration.

The display client uses GEM DMA framebuffers in native RGBA8888. Atomic helpers
wait for render fences before switching `SCANOUT_BASE`. A software 60 Hz vblank
source provides simulation pacing; physical scanout belongs to external SoC
display hardware.

Initialization order is platform, hardware, memory, execution, display. Teardown
unwinds the same sequence in reverse, and each layer frees only what it owns.

### Scope boundaries

- Raw MMIO is not a stable userspace ABI.
- AMDGPU-scale firmware, VM, TTM and ASIC discovery are deferred until the
  hardware requires them.
- Scanout DMA, display timing, hotplug/EDID and HDMI/DP/eDP PHY stay outside GPU
  RTL.

## Implemented

- Split platform, hardware, memory, execution and display modules.
- Dedicated scanout register bank and independent render/display ownership.
- DRM device, render node, GEM DMA buffers, contexts and typed bindings.
- Immutable command/shader snapshots, shader validation and relocation.
- Shared DRM scheduler, job and IH rings, implicit GEM synchronization and
  explicit binary sync objects.
- Ordered hardware blits with validated GEM-relative ranges, source read
  fences, destination write fences and syncobj chaining.
- Ordered hardware patterned fills with validated destination ranges and the
  same destination-fence and syncobj contracts.
- Ordered strided copies with validated two-dimensional source/destination
  ranges and the same reservation-fence contracts.
- Fragment and vertex core submissions using the shared SIMT compute unit.
- General-compute submissions with separate shader/kernarg bindings, immutable
  validated shader snapshots, GEM dependencies and sync-object fences.
- Generation-tagged compute/DMA events and atomic unified-command fault queries.
- MSAA submission on both backends (fixed-function and the fragment core) with
  capability checks, physical-stride validation and a private depth
  allocation/clear sized to the sample layout. Typed resolve is exposed through
  `DRM_IOCTL_OPENGPU_RESOLVE` with validated ranges and scheduler fences, and
  the guest test renders a multisample target, resolves it and scans it out.
- Caller-owned persistent depth/stencil attachments: `depth_handle`/
  `depth_offset` bind a validated GEM range and `OPENGPU_SUBMIT_DEPTH_LOAD`
  continues a render pass across submissions, advertised as
  `OPENGPU_CAP_PERSISTENT_DEPTH`. The cross-submission continuation is covered
  by `RenderHostSpec` and the guest test under the Verilator backend.
- Ordered shared-L2 line-invalidate jobs (`DRM_IOCTL_OPENGPU_INVALIDATE`) over
  validated 64-byte-aligned GEM ranges, so a caller can make CPU-written memory
  visible to a later GPU read without a full cache flush. Invalidate once per
  CPU write (per upload), not per draw: each line costs an L2 lookup and the
  operation is only worthwhile when the buffer changed. The driver drops the
  lines of a directly-read binding (texture, vertex buffer, kernarg) when it is
  bound, which is the upload boundary; shaders are snapshotted instead, and the
  resolve path invalidates its source implicitly.
- GEM objects export as dma-bufs whose `end_cpu_access` performs the same
  invalidate, so a `DMA_BUF_IOCTL_SYNC` CPU-write window is coherent with a
  later GPU read (the standard interface used by importers and cross-device
  sharing).
- An identity-mapped Sv32 GPU MMU (`opengpu_mmu.c`): the driver builds one root
  table of 4 MiB identity superpages and enables translation for the CU data and
  instruction paths; a region is split into a second-level table when a 4 KiB
  page needs a non-default cache policy (Sv32 PTE bits [9:8]). CU-read kernargs
  are mapped uncached, so CPU writes stay visible without a flush.
- Texture sampling, shader depth output, discard, quad derivatives, mipmapping
  and source-over blending in the validated graphics path.
- D24S8 stencil and GL-style blend factor/equation state: UAPI words 35–37 on
  both draw forms, layout/enum validation (reserved factors, equations and
  stencil words rejected), job words 10–12, the `0x13C`–`0x144` registers, and
  a stencil-gated multi-draw integration test.
- Atomic modeset, render-fence-aware page flip and virtual vblank events.
- Fixed-function and shader-backed probe paths selected from capabilities.
- Unified compute/DMA MMIO register definitions, capability discovery and
  capability-selected fill/blit/strided-copy submission with legacy fallback.
- IRQ-driven unified DMA fences with validated completion identity/status,
  timeout and abort signaling, plus an emulator progress-poll fallback.
- Safe unified-command reset with explicit recovery semantics. On a timeout or
  abort the hardware layer requests `UCMD_RESET` when the device advertises
  `GPU_CAP_UNIFIED_RESET`; `reset_pending` refuses new unified submissions with
  `-EBUSY` while the drain runs and is cleared when the drain completes (via the
  shared IRQ or the progress poll). A drain that exceeds the reset window
  records `OPENGPU_FAULT_RESET_TIMEOUT`, sets `wedged`, and fails later unified
  submissions with `-EIO` until reload. Faults use `OPENGPU_FAULT_RESET_ISSUED`
  to record that recovery was attempted.

## Next

- Grow the admitted shader ISA only with matching RTL, validator and ABI rules.
- Add runtime power management when required by the SoC integration.
