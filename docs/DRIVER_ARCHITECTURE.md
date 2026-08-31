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

Each DRM file owns an IDR of explicit render contexts. A context owns a DRM
scheduler entity, its binding table and latest completion fence. Every submit
allocates private DMA command/depth staging for that job, copies up to 64 draw
records from the command GEM and validates the immutable snapshot. Queued jobs
therefore cannot overwrite one another, and context destroy/close drains the
entity before releasing bindings.

Each context also owns a 16-slot GEM resource-binding table. Bindings retain
the GEM object and expose only a validated subrange; submit selects slots, not
DMA addresses. `drm_exec` locks command, target and resource objects as one
transaction. Texture reads receive read fences, while color/kernarg writes
receive write fences. The current fixed-function `GpuHostAxi` uses the
texture binding to program `TEX_*`, and the guest test verifies a sampled pixel
through the real RTL memory port. A read-only capability register advertises
fragment-core presence and batch capacity. Without it shader submissions return
`EOPNOTSUPP`. With it, the driver snapshots a bounded, cache-line-aligned shader
binding into per-job DMA and validates the snapshot before relocation, removing
the writable-GEM validate/execute race. Sandbox profile v6 accepts terminating
RV32I/M+V with an immutable x1 kernarg base. Scalar loads stay inside kernarg
and stores stay inside its colour-output or output-valid slice. The vector subset is
fixed e32/m1 `vsetivli`, unmasked unit-stride `vle32`/`vse32`, and lane-local
`vadd/vsub/vrsub/vand/vor/vxor` vv/vi forms. Abstract interpretation tracks the
trusted `x1 + 4*x8 + constant` per-warp address form and proves all active lanes
remain in their SoA input/output arrays. Defined-register tracking requires
every SGPR/VGPR source to come from the launch ABI, an admitted instruction or
a validated load, preventing stale cross-task register disclosure. More complex
control flow begins with up to four unreconverged forward conditional branches.
At every target, the validator intersects defined registers, preserves only
identical address provenance and invalidates differing VL. Paths may reconverge
or terminate independently in `CEASE`; every reachable path must terminate.
The RTL initializes per-fragment output-valid words to one, and a validated
zero store discards a fragment before output merging. The custom vector
`vtex.sample` is accepted
only when its UV VGPRs are defined, it is unmasked, and the same submit carries
a validated texture GEM; the hardware sampler derives all addresses from that
binding's base, dimensions and wrap mode. Backward edges, jumps,
masked/strided/gather memory, atomics and other custom instructions require
future validator profiles.
`DRM_IOCTL_OPENGPU_GET_PARAM` reports the capability word to userspace. The
trusted probe self-test follows the same split: fixed hardware uses the texture
pipeline, while capable hardware allocates private shader/kernarg buffers and
executes a pass-through fragment program before DRM registration.

The shared DRM GPU scheduler has one hardware credit and a fair runqueue across
context entities. It resolves GEM reservation and input-syncobj dependencies
without blocking submit, then launches one hardware descriptor at a time.
Hardware completion is represented by a `dma_fence` signaled from the IRQ (or
timeout worker); the scheduler's finished fence is published to all referenced
GEM reservations and to the optional output syncobj. Jobs retain their GEM
objects and DMA snapshots until that fence completes.

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
rendered buffer. There is no display-to-execution callback. A Linux hrtimer
provides the virtual CRTC's 60 Hz vblank source; nonblocking atomic page flips
arm `DRM_EVENT_FLIP_COMPLETE` only after the new scanout address is committed,
so userspace pacing observes both render-fence completion and the following
refresh boundary. This is simulation/driver timing and does not add a timing
generator to the GPU RTL.

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
   **Complete (2026-08-29).**

Validated command submission, per-file render contexts, GEM resource bindings,
queued DRM scheduling and explicit binary syncobjs are complete (2026-08-30).
Capability discovery, immutable shader validation and core-backed shader
execution through ARTI/QEMU/Linux are also complete (2026-08-30). Proven
unit-stride vector memory, lane-local integer arithmetic, bounded forward
control flow, core-backed texture sampling, structured early exit/discard and
full per-lane framebuffer output are complete as well. The next execution
milestone is richer fragment inputs and shader-generated depth.

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
