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

The current ARTI prototype watches execution `COLOR_BASE`/`STRIDE` for scanout.
That is a temporary compatibility path. DRM/KMS will use a dedicated display
register bank so rendering to an off-screen target cannot accidentally change
the visible framebuffer.

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

Graphics draws and general-compute kernels may use different job payloads, but
share queue, memory and fence machinery. Display is not an execution job.

### Display client

`opengpu_display.c` will own DRM mode configuration, connector/CRTC/plane
objects, scanout validation, atomic commits, vblank and page flips. It consumes
GEM framebuffer objects and only calls the display-facing hardware API.

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

1. Refactor the existing bring-up driver into core, hardware, and execution
   modules without changing its userspace ABI or ARTI PASS marker.
2. Add dedicated scanout registers to RTL/ABI and point ARTI guest scanout at
   those registers.
3. Add DRM device registration, GEM DMA dumb buffers and a fixed KMS mode.
4. Add atomic page flips synchronized to render-completion fences.
5. Add modes, vblank timing and hardware scanout DMA when moving beyond QEMU.

## Non-goals

- Reproducing AMDGPU's ASIC discovery, firmware, VM, TTM, power-management, or
  dozens of IP blocks before the hardware needs them.
- Coupling the display lifetime to the bring-up self-test framebuffer.
- Exposing raw register writes as a stable userspace ABI.
