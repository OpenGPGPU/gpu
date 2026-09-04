# Linux Driver Architecture

## Plan

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
- Fragment and vertex core submissions using the shared SIMT compute unit.
- Texture sampling, shader depth output, discard, quad derivatives, mipmapping
  and source-over blending in the validated graphics path.
- Atomic modeset, render-fence-aware page flip and virtual vblank events.
- Fixed-function and shader-backed probe paths selected from capabilities.

## Next

- Add general-compute and DMA job payloads without duplicating queue, memory or
  fence machinery.
- Generalize the queue payload for compute and strided-DMA jobs; migrate
  ordered fill/blit operations onto that common hardware queue.
- Define precise reset, timeout recovery and host-visible fault reporting.
- Grow the admitted shader ISA only with matching RTL, validator and ABI rules.
- Add runtime power management when required by the SoC integration.
