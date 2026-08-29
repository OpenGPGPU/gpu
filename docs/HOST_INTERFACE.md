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
| 0x40 | TEX_CONFIG | RW | bit0 CLAMP (else REPEAT), bit8 texture enable |
| 0x44 | SCANOUT_BASE | RW | display framebuffer physical byte address |
| 0x48 | SCANOUT_STRIDE | RW | display pitch in bytes |
| 0x4C | SCANOUT_WIDTH | RW | active width in pixels |
| 0x50 | SCANOUT_HEIGHT | RW | active height in pixels |
| 0x54 | SCANOUT_FORMAT | RW | 0 packed RGBA8888 |
| 0x58 | SCANOUT_CONTROL | RW | bit0 ENABLE |
| 0x5C | SCANOUT_STATUS | RO | bit0 ACTIVE |

At START the engine snapshots the configuration so the host can program the
next frame while the current one is in flight (a minimal double-buffered
submission); the completion interrupt (`m_irq`) rises when the draw retires.

## 3. Shared-memory data ABI

All buffers live in host physical memory. The host publishes their addresses in
the registers above, so the ABI is a driver-side concern only (see
`driver/gpu_abi.h`).

### 3.1 Draw record (`CommandBufferStage`, 32 words)

One record per draw call. The 32-word layout is fixed by the RTL:

| word(s) | field |
|---|---|
| 0–11 | v0/v1/v2 clip-space (x,y,z,w), Q16.16 |
| 12–20 | v0/v1/v2 colour (r,g,b), one 32-bit word per component (low 8 bits used) |
| 21–23 | v0/v1/v2 depth (signed 32-bit fixed-point) |
| 24 | shader entry PC (kernel address) |
| 25 | kernarg buffer address |
| 26–31 | v0/v1/v2 texture `(u,v)`, unsigned Q16.16 |

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
| `[4*stride, 5*stride)` | colour outputs (u32) |
| `[5*stride, 6*stride)` | reserved |
| `[6*stride, ...)` | per-draw uniforms |

### 3.3 Colour / depth buffers

Colour is r8g8b8a8 (`0xRRGGBBAA`; little-endian bytes `[AA BB GG RR]`). Depth
is a 24-bit fixed-point value in a 32-bit word. The framebuffer is addressed
`base + (y*stride + x)*4` on integer pixel coordinates.

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
- **submission**: the driver writes the draw record and depth clear into the
  shared buffers, writes the register file (CMD_BASE, COLOR_BASE, DEPTH_BASE,
  STRIDE, depth/cull state), enables the IRQ, writes `CONTROL.START`, and waits
  on `gpu_irq` (with a `STATUS.DONE` polling fallback).
- **readback**: `/dev/opengpu0` exposes the framebuffer; `GPU_IOCTL_SUBMIT` re-runs
  a draw from userspace.

`opengpu_drv.c` owns platform lifetime, `opengpu_hw.c` owns MMIO/IRQ/job launch,
`opengpu_memory.c` owns shared buffers, and `opengpu_compute.c` owns the current
execution userspace ABI. See `DRIVER_ARCHITECTURE.md` for the Nova/AMDGPU-based
display and execution separation used by the DRM phase.

The DRM/KMS client registers a virtual connector, CRTC/primary plane and fixed
16x16 mode. It provides GEM DMA dumb buffers and atomic commits in native
`RGBA8888`, then programs the dedicated display register bank; it does not take
ownership of the execution client's bring-up buffers.

## 6. ARTI integration flow

`scripts/run_arti_gpu.sh` is the project-level entry point. It emits
`GpuHostAxi.sv`, incrementally prepares ARTI's embedded QEMU/Linux environment,
builds `gpu_drv.ko` and a static no-libdrm KMS test against that exact kernel,
then boots the end-to-end draw/display test:

```bash
./scripts/run_arti_gpu.sh
```

ARTI defaults to the sibling repository `../arti`; override `ARTI_DIR` when it
lives elsewhere. To keep the rendered self-test visible in a macOS window:

```bash
QEMU_DISPLAY=cocoa HOLD_AFTER_TEST=30 ./scripts/run_arti_gpu.sh
```

The underlying integration profile remains `driver/gpu_integration.yaml`.
ARTI infers AXI4 from the `s_axi_*` names, generates the embedded QEMU model
and DT node, then loads the DRM stack and module in the guest. The guest test
must create/map two dumb buffers, perform an atomic modeset and page flip, and
print `OPENGPU USERSPACE DRM PASS`.

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
functional. The render self-test still verifies a red triangle; final success
now requires a userspace modeset and page flip through `/dev/dri/card0`.

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
