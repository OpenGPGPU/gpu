# Host Interface and Driver

This document specifies the M6 host-facing interface and Linux driver for the
RISC-V SIMT GPU, designed to be dropped into the
[ARTI](https://github.com/arti/) RTL-to-QEMU integration framework so that a
Linux guest runs against the hardware with no hand-written QEMU code.

## 1. Morphology

The GPU is an **accelerator on an integrated SoC**, exactly the morphology the
roadmap adopted:

- The **host** is an on-die RV64 (+ Sv39) CPU running Linux.
- The **GPU** is a separate engine on the same SoC bus, with:
  - a **slave** AXI4 **control** port (registers only — this is what ARTI
    bridges), and
  - a **master** **memory** port (or, in the current RTL, word/line clients
    coalesced behind one `SharedL2Cache`) that reads the command buffer and
    writes the framebuffer in the host's physical DRAM.
- There is **no GPU-local VRAM**. All buffers are software-allocated host
  memory; the hardware only computes addresses and issues reads/writes.

```
  +-------------------- SoC ----------------
  |  RV64 host (Linux)                      |
  |    gpu_drv.ko  -> MMIO (AXI4 ctrl)      |
  |        |                |               |
  |        +-- shared DRAM <+-> GPU AXI4    |
  |              (cmd, kernarg,             |  -> RenderCore (RTL)
  |               color, depth)             |
  +-----------------------------------------
```

## 2. Control port: AXI4 slave (`GpuHostAxi`)

`opengpu.graphics.GpuHostAxi` is the top ARTI parses. It exposes a standard AXI4
memory-mapped slave plus a completion interrupt:

| Signal group | Ports | Meaning |
|---|---|---|
| clock/reset | `s_axi_aclk`, `s_axi_aresetn` | single-clock, active-low reset |
| write address | `s_axi_awaddr/awlen/awsize/awburst/awvalid/awready` | write channel |
| write data | `s_axi_wdata/wstrb/wlast/wvalid/wready` | write-channel data |
| write response | `s_axi_bresp/bvalid/bready` | B channel |
| read address | `s_axi_araddr/arlen/arsize/arburst/arvalid/arready` | read channel |
| read data | `s_axi_rdata/rresp/rlast/rvalid/rready` | R channel |
| interrupt | `m_irq` | completion interrupt |

Because the signal names match ARTI's AXI4 rule set (`AWADDR/AWLEN/AWVALID/
WDATA/WLAST/ARADDR/ARLEN/RDATA/RLAST` + `AWLEN`/`ARLEN`/`WLAST`/`RLAST`), ARTI
infers AXI4 automatically and generates the embedded QEMU SysBus device, the
MMIO mapping, the interrupt wiring, and the device-tree node.

Bursts are handled word-at-a-time; the register file is single-ported so one
transaction (read or write) is in flight at a time. For a register-style device
the usable granularity is a single 32-bit word (`awsize/arsize = 2`), but INCR
bursts are accepted and decoded per beat.

### 2.1 Register map (byte offsets)

These match `RenderHostRegs` in `opengpu/graphics/RenderHost.scala` and are the
single source of truth exported to C in `driver/gpu_abi.h`.

| Offset | Name | Access | Description |
|---|---|---|---|
| 0x00 | ID | RO | `device_id << 16 \| version` (0x4755_0001) |
| 0x04 | CONTROL | W1P | bit0 START (write-1 pulses a launch) |
| 0x08 | STATUS | RO + W1C | bit0 BUSY, bit1 DONE, bit2 ERROR; write-1 clears DONE/ERROR |
| 0x0C | IRQ | RW | bit0 ENABLE, bit1 PENDING (W1C) |
| 0x10 | CMD_BASE | RW | command-buffer physical (byte) address |
| 0x14 | CMD_COUNT | RW | number of draw records |
| 0x18 | COLOR_BASE | RW | colour-buffer physical (byte) address |
| 0x1C | DEPTH_BASE | RW | depth-buffer physical (byte) address |
| 0x20 | STRIDE | RW | framebuffer stride in bytes |
| 0x24 | DEPTH_TEST_ENABLE | RW | nonzero enables depth test |
| 0x28 | DEPTH_FUNC | RW | 0 LESS, 1 LEQUAL, 2 GREATER, 3 ALWAYS |
| 0x2C | DEPTH_WRITE_ENABLE | RW | nonzero enables depth write |
| 0x30 | CULL_MODE | RW | 0 none, 1 cull back, 2 cull front |
| 0x34 | TEX_BASE | RW | texture texel (0,0) physical byte address |
| 0x38 | TEX_WIDTH | RW | texture width in texels |
| 0x3C | TEX_HEIGHT | RW | texture height in texels |
| 0x40 | TEX_CONFIG | RW | bit0 CLAMP, bits 5:2 max mip level, bit8 texture enable |
| 0x44 | SCANOUT_BASE | RW | display framebuffer physical byte address |
| 0x48 | SCANOUT_STRIDE | RW | display pitch in bytes |
| 0x4C | SCANOUT_WIDTH | RW | active width in pixels |
| 0x50 | SCANOUT_HEIGHT | RW | active height in pixels |
| 0x54 | SCANOUT_FORMAT | RW | 0 packed RGBA8888 |
| 0x58 | SCANOUT_CONTROL | RW | bit0 ENABLE |
| 0x5C | SCANOUT_STATUS | RO | bit0 ACTIVE |
| 0x60 | CAPABILITIES | RO | bit0 fragment-core; bit1 job queue + IH; bits 15:8 fragment batch capacity |
| 0x64 | JOB_RING_BASE | RW | job-ring physical (byte) address in host memory |
| 0x68 | JOB_RING_SIZE | RW | job-ring entry count (power of two); 0 keeps the queue idle |
| 0x6C | JOB_WPTR | RW | host doorbell: index of the next free ring entry |
| 0x70 | JOB_RPTR | RO | device read pointer (next descriptor to fetch) |
| 0x74 | JOB_CONTROL | RW | bit0 ENABLE, bit1 RESET (w1p); ro bit8 ACTIVE, bit9 PENDING |
| 0x78 | IH_BASE | RW | interrupt-history (IH) ring physical byte address |
| 0x7C | IH_SIZE | RW | IH record count (power of two) |
| 0x80 | IH_WPTR | RO | device write pointer (next IH record to write) |
| 0x84 | IH_RPTR | RW | host read pointer (next IH record to drain) |

At START the engine snapshots the configuration so the host can program the
next frame while the current one is in flight (a minimal double-buffered
submission); the completion interrupt (`m_irq`) rises when the draw retires.

### 2.1 Hardware job queue and interrupt history (IH) ring

When `CAPABILITIES` bit1 is set the device supports AMDGPU-style submission:
job descriptors live in a host-memory ring and, on completion, the device
first records the interrupt *details* into a second host-memory ring (the IH
ring) and only then raises `m_irq`. The IRQ handler drains IH records instead
of guessing which submission completed. Legacy register-programmed START
submissions remain fully functional; the two paths share one engine and never
run concurrently.

**Submission.** The host writes a 64-byte descriptor (section 3.5) into the
entry at `JOB_WPTR & (entries-1)`, then writes the new `JOB_WPTR` (the
doorbell). Pointers are free-running modulo 65536; the host must keep fewer
jobs in flight than ring entries. The device fetches one descriptor while the
previous job is still rendering (queue depth two, no reordering), launches
jobs strictly in order, and advances the read-only `JOB_RPTR` per fetch.
`JOB_CONTROL` reads back `ENABLE` (bit0), `ACTIVE` (bit8: a job is running)
and `PENDING` (bit9: a descriptor is staged). `JOB_CONTROL.RESET` (bit1,
write-1 pulse) drops the queue state — staged jobs, pointers and pending IH
records; it is only meaningful while `ACTIVE` is clear.

**Completion.** For each completed job the device writes a 16-byte IH record
(section 3.5) at `IH_WPTR & (records-1)` — job id, job-ring slot, status —
bumps `IH_WPTR`, and then pulses the completion interrupt. The host drains
records from its `IH_RPTR` up to `IH_WPTR` and retires the fence named by the
job id; writing `IH_RPTR` back is informational. The device never waits for
the host to drain, so `IH_SIZE` should be sized for the drain latency.

## 3. Shared-memory data ABI

All buffers live in host physical memory. The host publishes their addresses in
the registers above, so the ABI is a driver-side concern only (see
`driver/gpu_abi.h`).

### 3.1 Draw record (`CommandBufferStage`, 40 words)

One record per draw call. The command reader prefetches decoded records into a
configurable draw FIFO (`GraphicsConfig.drawFifoDepth`, default 8), while
preserving submission order and backpressuring memory at capacity. The 40-word
layout is fixed by the RTL:

When a FIFO entry is accepted by the render pipeline, the active render-target,
depth/cull, and texture configuration is snapshotted with that draw. Later host
or queue configuration changes therefore cannot affect an in-flight draw.

| word(s) | field |
|---|---|
| 0–11 | v0/v1/v2 clip-space (x,y,z,w), Q16.16 |
| 12–20 | v0/v1/v2 colour (r,g,b), one 32-bit word per component (low 8 bits used) |
| 21–23 | v0/v1/v2 depth (signed 32-bit fixed-point) |
| 24 | shader entry PC (kernel address) |
| 25 | kernarg buffer address |
| 26–31 | v0/v1/v2 texture `(u,v)`, unsigned Q16.16 |
| 32 | state override: bit 0 valid, bit 1 depth test, bits 6:4 depth function, bit 7 depth write, bits 9:8 cull, bit 10 texture enable, bit 11 clamp, bits 15:12 max mip |
| 33 | core sampler controls: bits 4:0 signed integer LOD bias, bits 11:8 inclusive minimum mip clamp |
| 34 | optional kernarg bank stride in bytes; zero selects legacy single-bank operation |
| 35–39 | reserved; must be zero |

When word 32 bit 0 is clear, all draw state inherits from the job descriptor
and every other bit in the word must be zero. Resource addresses and texture
dimensions are never command-controlled: they remain sourced from validated
job bindings. The driver rejects unknown bits, invalid depth/cull values,
texture enable without a bound texture, and max-mip requests beyond the bound
mip chain. The minimum clamp must not exceed the draw's maximum mip; gradient
LOD is biased with signed saturation and then clamped to this validated range.

For core-backed pipelining, word 34 may describe two complete kernarg banks.
The stride must be 64-byte aligned and at least `9*stride` bytes, where the
inner SoA stride is defined below. Both banks have identical layouts and
uniform data; software places bank 1 at `kernarg + bank_stride`. The driver
checks that both banks fit the bound resource and validates shader accesses
against one bank only, preventing a shader in batch N from reaching batch
N+1. Hardware alternates banks only after a completed batch. Zero preserves
the original single-bank behavior.

### 3.2 Kernarg SoA ABI (core-backed fragment shading)

For the unified-shading path, per-fragment attributes are packed
structure-of-arrays so a lane-aware RV32 kernel can fetch each attribute with
one unit-stride vector load. With `stride = 4 * warps * lanes`:

| slice | content |
|---|---|
| `[0*stride, 1*stride)` | per-fragment x (i32) |
| `[1*stride, 2*stride)` | per-fragment y (i32) |
| `[2*stride, 3*stride)` | depth (i32) |
| `[3*stride, 4*stride)` | packed colour inputs (u32) |
| `[4*stride, 5*stride)` | perspective-correct u (unsigned Q16.16) |
| `[5*stride, 6*stride)` | perspective-correct v (unsigned Q16.16) |
| `[6*stride, 7*stride)` | colour outputs (u32) |
| `[7*stride, 8*stride)` | depth outputs (i32) |
| `[8*stride, 9*stride)` | output-valid words (`1` = emit, `0` = discard) |
| `[9*stride, ...)` | per-draw uniforms |

### 3.3 Colour / depth buffers

Colour is r8g8b8a8 (`0xRRGGBBAA`; little-endian bytes `[AA BB GG RR]`). Depth
is a 24-bit fixed-point value in a 32-bit word. The framebuffer is addressed
`base + (y*stride + x)*4` on integer pixel coordinates.

### 3.4 Texture mip chain

RGBA8888 mip levels are tightly packed from `TEX_BASE`: level 0 first, then
each `max(1,width>>level) × max(1,height>>level)` image with no row padding.
The texture GEM binding advertises its deepest level in flags bits 7:4. The
driver checks that the complete chain fits in the bound range before exposing
its base to hardware. `vtex.sample` derives one nearest integer LOD per
TL/TR/BL/BR quad as `floor(log2(max UV gradient in base-level texels))`, clamped
to `[0,maxLevel]`; the scalar/fixed-function sampler selects level 0.

### 3.5 Job ring descriptor and IH record

Queued jobs carry the complete per-job configuration in the descriptor, so no
register programming is needed between jobs (`driver/gpu_abi.h` mirrors this
as `struct gpu_job_record`). 16 words, little-endian:

| word(s) | field |
|---|---|
| 0 | bits 15:0 job id, bits 31:16 command record count |
| 1–4 | CMD_BASE, COLOR_BASE, DEPTH_BASE, STRIDE |
| 5 | bit0 depth-test enable, bits 6:4 depth func, bit7 depth-write enable, bits 9:8 cull mode |
| 6 | TEX_BASE |
| 7 | bits 13:0 texture width, bits 29:16 texture height |
| 8 | TEX_CONFIG (bit0 CLAMP, bits 5:2 max mip level, bit8 sampling enable) |
| 9–15 | reserved (zero) |

The completion record written into the IH ring is 4 words
(`struct gpu_ih_record`):

| word(s) | field |
|---|---|
| 0 | bits 15:0 job id, bit16 DONE, bit17 ERROR |
| 1 | bits 15:0 job-ring slot index (queue position) |
| 2 | status code (0 = completed) |
| 3 | reserved |

## 4. Device-tree binding

`driver/gpu.dtsi` provides the node (an ARTI-generated variant uses its own
`compatible`, MMIO base and IRQ base):

```dts
gpu@b000000 {
    compatible = "riscv-simt,opengpu";
    reg = <0x0b000000 0x1000>;
    reg-names = "ctrl";
    interrupts = <180>;
    interrupt-parent = <&intc>;
    opengpu,width = <16>;
    opengpu,height = <16>;
    opengpu,stride = <0x40>;
};
```

## 5. Linux driver

The layered driver under `driver/` binds to `riscv-simt,opengpu` (execution
character device first, DRM display client next, per the roadmap):

- **probe**: map the `ctrl` resource, verify the device ID, allocate the
  command/colour/depth buffers with `dma_alloc_coherent`, request the
  completion IRQ, programme the register file, run a self-test draw and print
  `OPENGPU DRIVER PASS`.
- **submission**: when the device advertises `CAPABILITIES` bit1 the driver
  allocates the host-memory job ring and IH ring with `dma_alloc_coherent`,
  publishes them via JOB_RING_*/IH_* registers and submits each draw as a
  ring descriptor plus doorbell write; the IRQ handler drains IH records and
  completes the matching fence by job id. Otherwise the driver writes the
  draw record and depth clear into the shared buffers, writes the register
  file (CMD_BASE, COLOR_BASE, DEPTH_BASE, STRIDE, depth/cull state), enables
  the IRQ, writes `CONTROL.START`, and waits on `gpu_irq` (with a
  `STATUS.DONE` polling fallback).
- **readback**: `/dev/opengpu0` exposes the framebuffer; `GPU_IOCTL_SUBMIT` re-runs
  a draw from userspace.
- **DRM render contexts**: context create/destroy ioctls give each DRM file
  independently scheduled entities, resource bindings and fence lifetime.
- **validated DRM submission**: `DRM_IOCTL_OPENGPU_SUBMIT` references command
  and color GEM handles, copies at most 64 draw records into immutable
  per-job kernel-owned DMA staging, and validates their fixed-function fields
  before queueing. Per-job staging prevents a later submit from changing a
  queued command snapshot.
- **GEM resource bindings**: each context has 16 typed shader, kernarg or
  texture slots retaining validated GEM subranges. Submit uses `drm_exec` to
  lock all referenced objects and publishes read/write fences by access
  direction. The scheduler waits their implicit dependencies asynchronously.
  The current fixed-function RTL fully exercises texture bindings.
- **fragment shader safety boundary**: `CAPABILITIES` distinguishes the current
  fixed-function top (zero) from a fragment-core build (bit0 plus batch capacity
  in bits 15:8). Shader submission is rejected with `EOPNOTSUPP` unless bit0 is
  present; `DRM_IOCTL_OPENGPU_GET_PARAM` exposes the same value so userspace can
  select texture or shader/kernarg bindings. On a capable build the driver waits
  for prior shader writers, copies
  the complete 64-byte-aligned binding into per-job DMA, validates that immutable
  snapshot, and relocates only validated entry offsets. Sandbox profile v8 is
  RV32I/M+V with independently terminating forward paths: x1 cannot be
  overwritten, scalar loads are bounded to kernarg, and stores are bounded to
  the batch colour-output, depth-output and output-valid slices. Its RVV subset is fixed
  e32/m1 configuration, unmasked unit-stride
  `vle32`/`vse32`, and lane-local `vadd/vsub/vrsub/vand/vor/vxor` in vv/vi
  forms. Abstract address tracking proves `x1 + 4*x8 + constant` accesses stay
  within the SoA arrays for every active lane. Scalar and vector definedness
  tracking prevents a shader from exporting stale registers left by another
  task. Up to four unreconverged, 4-byte-aligned forward conditional branches
  are admitted; every target conservatively merges definedness, address
  provenance and VL. A path may reconverge or terminate independently in
  `CEASE`, but every reachable path must terminate. The RTL initializes each
  depth output from interpolated depth and each output-valid word to one;
  shaders may override depth or suppress a fragment by clearing validity.
  `vtex.sample` is admitted only in its unmasked vector form, with defined UV
  sources and an attached, independently validated texture binding; address
  generation remains inside the bounded hardware sampler. Unmasked
  `vquad.dfdx/dfdy` accepts one defined VGPR source after VL configuration;
  complete TL/TR/BL/BR dispatch plus immutable coverage keeps helper lanes out
  of OM. Backward branches,
  jumps, atomics, masked/strided/gather memory and other custom instructions
  remain gated.
- **queued execution and explicit synchronization**: each context maps to a
  DRM scheduler entity. Submissions publish job descriptors into the
  host-memory job ring and ring the doorbell; the IRQ handler drains the
  host-memory IH ring and retires each job's fence by its job id, so several
  jobs can be in flight while completions stay unambiguous. Submit accepts
  optional binary `in_syncobj`/`out_syncobj` handles; input and GEM
  dependencies are resolved before launch, while the scheduler finished fence
  is installed in the output syncobj and every written/read reservation
  object. This is DRM interface version 1.3.
- **implicit display synchronization**: the KMS plane extracts the reservation
  fence and DRM atomic helpers wait before programming the scanout bank.
- **flip pacing**: a 60 Hz software vblank source delivers
  `DRM_EVENT_FLIP_COMPLETE` for nonblocking atomic flips after their render
  fence completes and the new scanout has been committed.

`opengpu_drv.c` owns platform lifetime, `opengpu_hw.c` owns MMIO/IRQ/job launch,
`opengpu_memory.c` owns shared buffers, and `opengpu_compute.c` owns the current
execution userspace ABI. See `DRIVER_ARCHITECTURE.md` for the Nova/AMDGPU-based
display and execution separation used by the DRM phase.

The DRM/KMS client registers a virtual connector, CRTC/primary plane and fixed
16x16 mode. It provides GEM DMA dumb buffers and atomic commits in native
`RGBA8888`, then programs the dedicated display register bank; it does not take
ownership of the execution client's bring-up buffers. The DRM driver also
exposes a render node for render-allowed ioctls; KMS remains on the primary
node.

## 6. ARTI integration flow

`scripts/run_arti_gpu.sh` is the project-level entry point. It emits
`GpuHostAxi.sv`, incrementally prepares ARTI's embedded QEMU/Linux environment,
builds `gpu_drv.ko` and a static no-libdrm KMS test against that exact kernel,
then boots the end-to-end draw/display test:

```bash
./scripts/run_arti_gpu.sh
GPU_FRAG_CORE=1 ./scripts/run_arti_gpu.sh
```

The first command emits and tests the fixed-function texture top. The second
emits the 4-lane, 2-warp fragment-core top, runs the trusted shader/kernarg
bring-up draw and executes the validated per-lane RVV shader from the DRM guest. Its
default boot timeout is 180 seconds; set `TIMEOUT` explicitly to override it.

ARTI defaults to the sibling repository `../arti`; override `ARTI_DIR` when it
lives elsewhere. To keep the rendered self-test visible in a macOS window:

```bash
QEMU_DISPLAY=cocoa HOLD_AFTER_TEST=30 ./scripts/run_arti_gpu.sh
```

The underlying integration profile remains `driver/gpu_integration.yaml`.
ARTI infers AXI4 from the `s_axi_*` names, generates the embedded QEMU model
and DT node, then loads the DRM stack and module in the guest. The adaptive
guest queries `CAPABILITIES`: the fixed top binds a texture and verifies the
exact sampled RTL pixel, while the fragment-core top binds shader, kernarg and
texture GEMs, proves missing texture and unsafe vector stores are rejected, and
loads perspective-correct UVs, executes `vtex.sample` and `vquad.dfdx`, writes
shader-generated depth, and takes a structured early exit that discards warp zero.
Each queued core-backed framebuffer must contain exactly 60 live sampled pixels.
Both paths queue two draws with explicit syncobjs, render into two dumb buffers,
perform an atomic modeset and page flip, receive a matching vblank-paced flip
event, prove unbound resources and destroyed contexts cannot be reused, and print
`OPENGPU USERSPACE DRM PASS`.

### Caveat: the memory (master) port

ARTI auto-bridges the AXI **slave/control** port and, in embedded-QEMU mode,
adapts the renderer's memory-client ports to one guest-memory callback ABI.
QEMU services that ABI with `address_space_read/write`, so command buffers,
framebuffers, textures, and core-backed line requests all address the same
guest physical RAM used by the Linux driver. A hardware SoC still attaches
those client ports to its coherent L2 / DRAM hierarchy instead.

### Embedded full-system status

The AArch64 Linux boot path now runs with QEMU, the generated `GpuHostAxi`
model, and the GPU host driver loaded from an initramfs. The AXI control path,
device identification, guest-memory bridge, draw completion, framebuffer
readback, DRM registration, GEM DMA mmap and atomic scanout commits are
functional. The trusted bring-up self-test chooses a fixed-function draw or a
shader/kernarg draw from `CAPABILITIES`; both emitted tops then pass userspace
modeset and a nonblocking page flip with a valid `DRM_EVENT_FLIP_COMPLETE`
through `/dev/dri/card0`.

With `display.source: guest-memory`, ARTI watches the display-domain
`SCANOUT_BASE` (0x44) and `SCANOUT_STRIDE` (0x48), reads the selected framebuffer
through QEMU's guest address space, converts packed RGBA8888 words to the QEMU
surface, and refreshes the graphics console. Render-target programming remains
independent in `COLOR_BASE`/`STRIDE`. The integration profile enables a 16x16
scanout matching the current driver self-test. Run it on macOS with:

```bash
INTEGRATION_CONFIG=/Users/duckdonald/workspace/gpu/driver/gpu_integration.yaml \
  /Users/duckdonald/workspace/arti/examples/linux_arti_driver/run_gpu_display.sh
```
