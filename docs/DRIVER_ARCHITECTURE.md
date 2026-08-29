# Linux Driver Architecture

The OpenGPU Linux driver follows the ownership boundaries used by modern DRM
drivers without copying their scale. Nova is the reference for keeping the
core device, DRM client, per-file state, and GEM objects separate. AMDGPU is
the reference for a shared device container with independently initialized
hardware/IP, memory, execution, and display blocks.

The implementation remains C for now because the out-of-tree AArch64 build and
DRM/KMS helpers are already validated in that environment. The interfaces are
language-neutral enough that an individual client can move to Rust later.

## Layering

```text
platform probe / device lifetime
              |
       hardware services
   MMIO, IRQ, reset, job launch
              |
      shared memory services
       DMA now, GEM later
          /           \
 execution client    display client
 render/compute       DRM/KMS scanout
 queues + fences      modeset + page flip
```

Dependencies only point downward. Display must not call execution internals,
and execution must not own the scanout state. Cross-engine synchronization is
expressed with shared buffer objects and fences rather than direct callbacks.

### Core/platform

`opengpu_drv.c` owns platform probe/remove, the root `opengpu_device`, device
tree resources, and subsystem initialization order. It contains no register
programming, command construction, userspace ABI, or KMS policy.

### Hardware layer

`opengpu_hw.c` owns register access, device identification, interrupt handling,
and bounded engine waits. Upper layers submit typed hardware descriptors; they
do not issue arbitrary MMIO writes. Reset, timeout recovery, and runtime power
management belong here when implemented.

The register ABI is divided into domains:

- common: identity, capabilities, global interrupt status;
- execution: command queues, render/compute targets, texture and kernel state;
- display: scanout base, pitch, size, format, enable and flip status.

ARTI watches the dedicated `SCANOUT_BASE`/`SCANOUT_STRIDE` display registers;
execution continues to own `COLOR_BASE`/`STRIDE`. Rendering to an off-screen
target therefore cannot accidentally change the visible framebuffer.

### Memory layer

The first implementation uses coherent DMA buffers. DRM introduces GEM DMA
objects without changing hardware submission interfaces. Buffer ownership,
mapping, pinning and dma-buf sharing belong to this layer, not to display or
execution code.

### Execution client

`opengpu_compute.c` owns render/compute submissions, per-file state, validation,
queue serialization and completion fences. The existing misc device is a
bring-up ABI and remains isolated here; it can later become a DRM render node
without changing platform or MMIO code.

The first DRM render ioctl accepts a GEM color-buffer handle and stride for a
driver-owned test draw. Hardware completion is represented by a standard
`dma_fence` signaled from the completion IRQ (or timeout worker). The execution
client publishes that fence into the target GEM object's `dma_resv` with write
usage. This ioctl remains a bring-up ABI until command validation and per-file
contexts replace the fixed command payload.

Graphics draws and general-compute kernels may use different job payloads, but
share queue, memory and fence machinery. Display is not an execution job.

### Display client

`opengpu_display.c` owns display state and the display-facing hardware API. Its
bring-up path performs an explicit scanout-buffer handoff, then registers a DRM
device with a virtual connector, a simple display pipe, atomic modesetting and
GEM DMA framebuffer objects. The fixed mode is currently 16x16 and the native
DRM format is `RGBA8888`; its 32-bit channel layout matches the renderer's
`0xRRGGBBAA` words without a conversion pass.

The DRM atomic commit programs only the dedicated `SCANOUT_*` control bank.
ARTI/QEMU consumes that control state and reads the selected guest-memory GEM
buffer for presentation. On a physical SoC the same register contract is
consumed by an external display subsystem or SoC display IP.

The simple display pipe uses `drm_gem_plane_helper_prepare_fb()` to extract the
target GEM reservation fence. DRM atomic helpers wait for render completion
before the pipe updates `SCANOUT_BASE`, so display never observes a partially
rendered buffer. There is no display-to-execution callback.

Nova currently provides the cleaner reference for DRM device/file/GEM
ownership, while AMDGPU provides the reference for keeping display state out of
the graphics execution block.

## Initialization and teardown

Initialization is ordered and unwound in reverse:

1. platform resources and root device;
2. hardware/MMIO and IRQ;
3. memory manager;
4. execution client;
5. display client.

Each block exposes `init`/`fini` entry points and owns everything it allocates.
No block frees another block's objects.

## Display implementation phases

1. Refactor the existing bring-up driver into core, hardware, memory, execution,
   and display modules without changing its userspace ABI or ARTI PASS marker.
   **Complete (2026-08-29).**
2. Add dedicated scanout registers to RTL/ABI and point ARTI guest scanout at
   those registers. **Complete (2026-08-29).**
3. Add DRM device registration, GEM DMA dumb buffers and a fixed KMS mode.
   **Complete (2026-08-29).**
4. Add a guest userspace test for GEM dumb-buffer allocation/mmap, atomic
   modeset and framebuffer page flip. **Complete (2026-08-29).**
5. Synchronize scanout flips to render-completion fences.
   **Complete (2026-08-29).**
6. Add optional virtual-vblank events when applications require paced flips.

## RTL boundary

OpenGPU RTL ends at rendering plus the display-control register interface. It
owns render-target production and publishes scanout base, pitch, dimensions,
format, enable and status. It does not fetch a continuous pixel stream or
generate an electrical display signal.

Consequently, scanout DMA, video timing, hotplug/EDID and HDMI/DP/eDP PHY are
not OpenGPU RTL milestones. In simulation they belong to ARTI/QEMU; in silicon
they belong to a separate SoC display controller or licensed board-specific IP.
This keeps the render GPU reusable across headless compute, virtual display and
different physical display subsystems.

## Non-goals

- Reproducing AMDGPU's ASIC discovery, firmware, VM, TTM, power-management, or
  dozens of IP blocks before the hardware needs them.
- Coupling the display lifetime to the bring-up self-test framebuffer.
- Exposing raw register writes as a stable userspace ABI.
- Implementing scanout DMA, video timing or a video PHY inside the GPU RTL.
