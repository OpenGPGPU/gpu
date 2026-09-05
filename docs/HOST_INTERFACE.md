# Host Interface and Driver

## Plan

### SoC interface

OpenGPU is an integrated shared-memory accelerator. An RV64 Linux host accesses
an AXI4 slave control port; the GPU accesses command, shader, texture, colour
and depth buffers in host DRAM through its memory clients and shared L2. There
is no v1 GPU-local VRAM.

`opengpu.graphics.GpuHostAxi` exposes `s_axi_*` AXI4 control signals and the
`m_irq` completion interrupt. The register file accepts 32-bit accesses and
INCR bursts, one read or write transaction at a time.

`opengpu.system.GpuHostSystemAxi` is the integrated successor top. It retains
that AXI ABI, exposes the common `GpuCommand` submission/completion stream, and
routes the graphics host's cache-line client together with compute and DMA
traffic through one shared L2 and one lower-memory line port. The framebuffer,
texture and shader coherence/atomic side ports are still passed through while
their adapters are integrated incrementally.

### Register ABI

`RenderHostRegs` and `driver/gpu_abi.h` must remain synchronized.

| Offset | Name | Access | Meaning |
|---|---|---|---|
| 0x00 | ID | RO | `device_id << 16 \| version` |
| 0x04 | CONTROL | W1P | bit 0 START |
| 0x08 | STATUS | RO/W1C | BUSY, DONE, ERROR and DMA-engine busy bits |
| 0x0C | IRQ | RW | ENABLE and PENDING |
| 0x10 | CMD_BASE | RW | command-buffer byte address |
| 0x14 | CMD_COUNT | RW | draw-record count |
| 0x18 | COLOR_BASE | RW | colour-buffer byte address |
| 0x1C | DEPTH_BASE | RW | depth-buffer byte address |
| 0x20 | STRIDE | RW | framebuffer stride in bytes |
| 0x24 | DEPTH_TEST_ENABLE | RW | depth-test enable |
| 0x28 | DEPTH_FUNC | RW | LESS, LEQUAL, GREATER or ALWAYS |
| 0x2C | DEPTH_WRITE_ENABLE | RW | depth-write enable |
| 0x30 | CULL_MODE | RW | none, back or front |
| 0x34 | TEX_BASE | RW | texture-chain byte address |
| 0x38 | TEX_WIDTH | RW | base width |
| 0x3C | TEX_HEIGHT | RW | base height |
| 0x40 | TEX_CONFIG | RW | clamp, maximum mip and enable |
| 0x44 | SCANOUT_BASE | RW | display framebuffer address |
| 0x48 | SCANOUT_STRIDE | RW | display pitch |
| 0x4C | SCANOUT_WIDTH | RW | active width |
| 0x50 | SCANOUT_HEIGHT | RW | active height |
| 0x54 | SCANOUT_FORMAT | RW | 0 = RGBA8888 |
| 0x58 | SCANOUT_CONTROL | RW | bit 0 ENABLE |
| 0x5C | SCANOUT_STATUS | RO | bit 0 ACTIVE |
| 0x60 | CAPABILITIES | RO | fragment core, job/IH rings, vertex core, clear/blit/strided engines and batch capacity |
| 0x64 | JOB_RING_BASE | RW | job-ring byte address |
| 0x68 | JOB_RING_SIZE | RW | power-of-two entry count |
| 0x6C | JOB_WPTR | RW | host producer pointer/doorbell |
| 0x70 | JOB_RPTR | RO | device consumer pointer |
| 0x74 | JOB_CONTROL | RW | enable, reset, active and pending |
| 0x78 | IH_BASE | RW | completion-ring byte address |
| 0x7C | IH_SIZE | RW | power-of-two record count |
| 0x80 | IH_WPTR | RO | device producer pointer |
| 0x84 | IH_RPTR | RW | host consumer pointer |
| 0x88 | CLEAR_BASE | RW | 64-byte-aligned fill destination |
| 0x8C | CLEAR_BYTES | RW | fill size, multiple of 64 |
| 0x90 | CLEAR_PATTERN | RW | 32-bit fill pattern |
| 0x94 | CLEAR_START | W1P | start fill |
| 0x98 | BLIT_SRC_BASE | RW | 64-byte-aligned copy source |
| 0x9C | BLIT_DST_BASE | RW | 64-byte-aligned copy destination |
| 0xA0 | BLIT_BYTES | RW | copy size, multiple of 64 |
| 0xA4 | BLIT_START | W1P | start non-overlapping copy |
| 0xA8 | STRIDED_SRC_BASE | RW | 64-byte-aligned 2D-copy source |
| 0xAC | STRIDED_DST_BASE | RW | 64-byte-aligned 2D-copy destination |
| 0xB0 | STRIDED_WIDTH | RW | bytes copied per row, multiple of 64 |
| 0xB4 | STRIDED_HEIGHT | RW | row count |
| 0xB8 | STRIDED_SRC_STRIDE | RW | source row stride, aligned and at least width |
| 0xBC | STRIDED_DST_STRIDE | RW | destination row stride, aligned and at least width |
| 0xC0 | STRIDED_START | W1P | start non-overlapping 2D copy |

START snapshots the programmed job state. On queue-capable hardware, the host
writes a 64-byte descriptor to the job ring and advances `JOB_WPTR`. Jobs
execute in order. Completion writes a 16-byte IH record before raising the
interrupt; the driver drains records by job id and retires the matching fence.
Legacy register-programmed START remains supported and is mutually exclusive
with queued execution.

### Shared-memory ABI

The canonical layouts are defined in `driver/gpu_abi.h`.

#### Draw record

Each draw record contains 40 little-endian words. The inline-triangle form is:

| Words | Content |
|---|---|
| 0-11 | three clip-space positions `(x,y,z,w)`, signed Q16.16 |
| 12-20 | three RGB colours |
| 21-23 | three signed fixed-point depths |
| 24 | fragment shader entry PC |
| 25 | fragment kernarg address |
| 26-31 | three UV pairs, unsigned Q16.16 |
| 32 | per-draw depth, cull, texture, mip and blend state |
| 33 | signed LOD bias and minimum mip clamp |
| 34 | optional 64-byte-aligned fragment kernarg bank stride |
| 35-39 | reserved, zero |

On vertex-core hardware the record instead carries vertex-buffer base/count/
stride/format, vertex shader and kernarg fields in words 0-6, fragment shader
and kernarg fields in words 24-25, and the common state in words 32-34. The
fixed vertex format is eight words: Q16.16 position, RGBA8888 colour, depth and
Q16.16 UV. Resource addresses are relocated from validated bindings rather
than trusted from userspace command data.

#### Fragment kernarg

With `stride = 4 * warps * lanes`, fragment data uses structure-of-arrays:

| Slice | Content |
|---|---|
| 0 | x input |
| 1 | y input |
| 2 | depth input |
| 3 | packed colour input |
| 4 | perspective-correct u input |
| 5 | perspective-correct v input |
| 6 | packed colour output |
| 7 | depth output |
| 8 | output-valid/discard output |
| 9+ | uniforms |

Two identical banks may be supplied with a validated aligned bank stride so
raster staging can overlap SIMT execution without aliasing scratch data.

#### Buffers and textures

- Colour is RGBA8888 (`0xRRGGBBAA`); depth is D24 in a 32-bit word.
- Pixel address is `base + (y * stride + x) * 4`.
- Mip levels are tightly packed from `TEX_BASE`, largest to smallest, without
  row padding. The driver validates the full advertised chain.

#### Queued job and completion records

The 16-word job descriptor contains job id/count, command/colour/depth bases,
stride, depth/cull state and texture configuration; unused words are zero. The
four-word IH record contains job id, done/error flags, ring slot and status.

### Linux ownership and synchronization

The driver exposes typed GEM bindings and per-file render contexts. It copies
commands and shaders into immutable per-job DMA storage, validates all resource
ranges and shader accesses, publishes GEM reservation fences, and accepts
optional input/output sync objects. KMS waits on render fences before changing
the dedicated scanout registers.

The boot mode must match the elaborated power-of-two RTL resolution. Device
tree properties provide width, height and stride; builds without those
properties use matching driver defaults.

### ARTI integration

`scripts/run_arti_gpu.sh` emits `GpuHostAxi`, builds the driver and guest test,
and runs the generated device under QEMU/Linux. `GPU_FRAG_CORE=1` selects the
fragment-core top; adding `GPU_VERT_CORE=1` selects vertex-core records.
`GPU_WIDTH` and `GPU_HEIGHT` select a matching RTL and guest mode.

ARTI bridges the AXI slave port and adapts GPU memory clients to guest physical
memory. In silicon, the same clients attach to the SoC L2/DRAM fabric.

`GpuHostSystemAxi` connects `GpuHostAxi.kernelWordMem*` to
`GpuSystem.graphicsHostRequest/graphicsHostResponse`. It remaps the graphics
host's eight local IDs above the CU and DMA ranges and returns responses with
their original IDs. RTL can be emitted with
`runMain opengpu.elaboration.EmitGpuHostSystemAxi [target-dir]`, optionally
adding `--compute-units N`, `--frag-core`, `--vert-core`, `--width N`, and
`--height N`.

## Implemented

- AXI4 register control, interrupt delivery and capability discovery.
- Register and queued submissions with ordered IH completion records.
- Immutable job state, context/resource lifetime tracking and fences.
- Fixed-function, fragment-core and vertex-core draw ABIs.
- Validated shader, kernarg, texture and vertex-buffer bindings.
- Hardware clears, texture mip chains, shader depth/discard and blending state.
- Scheduler-ordered per-job depth clears using the hardware fill engine, with
  a CPU fallback when the capability is absent.
- Scheduler-ordered colour blits with GEM range validation, implicit
  reservation fences and optional input/output sync objects.
- Scheduler-ordered patterned GEM fills using the same validation and
  synchronization model.
- Scheduler-ordered strided GEM copies with independently validated source and
  destination row layouts.
- Separate render-target and KMS scanout programming.
- ARTI-generated QEMU/Linux device, shared guest-memory access and end-to-end
  DRM/KMS execution.

## Next

- Add general compute descriptors to the shared queue; migrate the current
  ordered fill/blit/strided-copy jobs onto that common descriptor path.
- Define ABI-visible fault codes, reset recovery and timeout behavior.
- Expand shader profiles and resource types only with matching hardware and
  validation.
- Stabilize the ABI and performance envelope before a Mesa userspace driver.
