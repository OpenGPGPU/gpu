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

At START the engine snapshots the configuration so the host can program the
next frame while the current one is in flight (a minimal double-buffered
submission); the completion interrupt (`m_irq`) rises when the draw retires.

## 3. Shared-memory data ABI

All buffers live in host physical memory. The host publishes their addresses in
the registers above, so the ABI is a driver-side concern only (see
`driver/gpu_abi.h`).

### 3.1 Draw record (`CommandBufferStage`, 26 words)

One record per draw call. The 26-word layout is fixed by the RTL:

| word(s) | field |
|---|---|
| 0–11 | v0/v1/v2 clip-space (x,y,z,w), Q16.16 |
| 12–20 | v0/v1/v2 colour (r,g,b) 8-bit |
| 21–23 | v0/v1/v2 depth (signed 32-bit fixed-point) |
| 24 | shader entry PC (kernel address) |
| 25 | kernarg buffer address |

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
| `[6*stride, ...)` | per-draw uniforms |

### 3.3 Colour / depth buffers

Colour is a8r8g8b8 (`0xAARRGGBB` in a little-endian word). Depth is a 24-bit
fixed-point value in a 32-bit word. The framebuffer is addressed
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

`driver/gpu_drv.c` is a **platform driver** binding to `riscv-simt,opengpu`
(character device first, DRM later, per the roadmap):

- **probe**: map the `ctrl` resource, verify the device ID, allocate the
  command/colour/depth buffers with `dma_alloc_coherent`, request the
  completion IRQ, programme the register file, run a self-test draw and print
  `OPENGPU DRIVER PASS` (the ARTI harness success marker).
- **submission**: the driver writes the draw record and depth clear into the
  shared buffers, writes the register file (CMD_BASE, COLOR_BASE, DEPTH_BASE,
  STRIDE, depth/cull state), enables the IRQ, writes `CONTROL.START`, and waits
  on `gpu_irq` (with a `STATUS.DONE` polling fallback).
- **readback**: `/dev/opengpu0` exposes the framebuffer; `GPU_IOCTL_SUBMIT` re-runs
  a draw from userspace.

The DRM/KMS controller is a later milestone (M7). The scanout path there points
at the same shared framebuffer the renderer writes, mirroring ARTI's
`simple-framebuffer` -> real-driver handoff.

## 6. ARTI integration flow

1. Emit the RTL: `sbt "runMain opengpu.elaboration.EmitGpuHostAxi"` produces
   `GpuHostAxi.sv`.
2. Point `driver/gpu_integration.yaml` at it: ARTI infers AXI4 from the
   `s_axi_*` names, generates the embedded QEMU model `arti-rtl.c`, and the DT
   node.
3. Build the driver module: `driver/Makefile` produces `gpu_drv.ko`.
4. Run the harness: `run_linux_test.sh` with `INTEGRATION_CONFIG=` pointing at
   `driver/gpu_integration.yaml`; the guest `insmod`s the module, which
   submits a draw over the bus and prints `OPENGPU DRIVER PASS`.

### Caveat: the memory (master) port

ARTI auto-bridges **slave/peripheral** RTL. The GPU's memory client (the
coalesced L2 line port behind `RenderCoreL2`) is a **master** that reads the
command buffer and writes the framebuffer in the host's physical RAM. That port
is exposed through `GpuHostAxi` and must be attached to the SoC's coherent L2 /
DRAM by the platform — it is **not** something ARTI's MMIO bridge drives. In
the embedded-QEMU model the framebuffer the driver allocates is the host's own
RAM, so the renderer and the host CPU observe the same pixels with
driver-driven coherence (cache-clean before submission, fence on completion).
