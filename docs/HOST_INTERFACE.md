# Host Interface and Driver

## Plan

### SoC interface

OpenGPU is an integrated shared-memory accelerator. An RV64 Linux host accesses
an AXI4 slave control port; the GPU accesses command, shader, texture, colour
and depth buffers in shared DRAM through an AXI4 master port behind its GPU
L2. The CPU and GPU have separate L2 caches and share the SoC fabric and DRAM.
There is no v1 GPU-local VRAM or CPU access port into the GPU L2.

`opengpu.graphics.GpuHostAxi` exposes `s_axi_*` AXI4 control signals and the
`m_irq` completion interrupt. The register file accepts 32-bit accesses and
INCR bursts, one read or write transaction at a time.

`opengpu.system.GpuHostSystemAxi` is the product integration top. Its external
surface is only one clock/reset pair, a 32-bit `s_axi_*` AXI4 control slave, an
`m_axi_*` AXI4 memory master and `m_irq`. Graphics, compute and DMA commands are
submitted through MMIO; no command, cache-line or performance-counter bundle is
exported as a product pin.

The memory master defaults to a 64-bit data bus. A 64-byte cache-line request
becomes an eight-beat INCR burst; a page-table word becomes one four-byte AXI
narrow transfer with the correct byte lane and strobe. `BRESP` and `RRESP`
errors are returned to the internal requester as faults. The data width is
elaboration-time configurable to 4, 8, 16, 32 or 64 bytes with
`--memory-axi-data-bytes`; the current bridge serializes lower-memory
transactions while preserving their AXI IDs.

Internally, the graphics host's line and word clients, compute units and DMA
engines all converge on one GPU-internal shared L2. The graphics shader is an
additional coherent L2 client, including private-cache invalidation and global
atomics.
Private `ComputeMemoryRequest/Response` bundles stop at the AXI master adapter
and are not part of the SoC ABI.

The memory master exposes the GPU L2's lower-memory traffic; the control slave
provides MMIO, not a CPU data path into that L2. GPU-internal cache coherence
does not extend to CPU caches. CPU/GPU buffer handoff uses driver-managed cache
maintenance, job fences and interrupts; v1 has no CPU/GPU hardware snooping
protocol.

### Register ABI

`RenderHostRegs` and `driver/gpu_abi.h` must remain synchronized.

| Offset | Name | Access | Meaning |
|---|---|---|---|
| 0x00 | ID | RO | `device_id << 16 \| version` |
| 0x04 | CONTROL | W1P | bit 0 START |
| 0x08 | STATUS | RO/W1C | BUSY, DONE, ERROR and DMA-engine busy bits |
| 0x0C | IRQ | RW/W1C | bit 0 ENABLE; bit 1 PENDING for graphics or unified-command completion |
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
| 0x60 | CAPABILITIES | RO | fragment core, job/IH rings, vertex core, clear/blit/strided engines, unified commands, MSAA and batch capacity |
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
| 0xC4 | UCMD_ID | RW | unified command ID, low 8 bits |
| 0xC8 | UCMD_OPCODE | RW | kernel, copy, fill or strided-copy opcode |
| 0xCC | UCMD_KERNEL_PC | RW | compute kernel entry PC |
| 0xD0 | UCMD_KERNARG | RW | compute kernarg byte address |
| 0xD4-0xDC | UCMD_GRID_X/Y/Z | RW | compute grid dimensions |
| 0xE0-0xE8 | UCMD_LOCAL_X/Y/Z | RW | compute workgroup dimensions |
| 0xEC | UCMD_FLAGS | RW | wait-DMA, wait-event and signal-event enables |
| 0xF0 | UCMD_DMA_DEPENDENCY | RW | DMA source `[1:0]` and descriptor ID `[15:8]` |
| 0xF4 | UCMD_SOURCE | RW | copy source byte address |
| 0xF8 | UCMD_DESTINATION | RW | copy/fill destination byte address |
| 0xFC | UCMD_BYTES | RW | linear copy/fill byte count |
| 0x100 | UCMD_PATTERN | RW | fill pattern |
| 0x104 | UCMD_WIDTH | RW | strided-copy width in bytes |
| 0x108 | UCMD_HEIGHT | RW | strided-copy row count |
| 0x10C | UCMD_SOURCE_STRIDE | RW | strided-copy source stride |
| 0x110 | UCMD_DEST_STRIDE | RW | strided-copy destination stride |
| 0x114 | UCMD_WAIT_EVENT | RW | event ID `[7:0]` and generation `[15:8]` |
| 0x118 | UCMD_SIGNAL_EVENT | RW | event ID `[7:0]` and generation `[15:8]` |
| 0x11C | UCMD_SUBMIT | W1P | snapshot and enqueue when bit 0 is written |
| 0x120 | UCMD_STATUS | RO/W1C | ready, completion-valid and submit-overflow; bit 2 clears overflow |
| 0x124 | UCMD_COMPLETION | RO | ID `[7:0]`, opcode `[10:8]`, status `[14:11]`, success `[15]` |
| 0x128-0x12C | UCMD_COMPLETION_BYTES | RO | processed byte count, low then high word |
| 0x130 | UCMD_COMPLETION_POP | W1P | consume completion when bit 0 is written |
| 0x134 | MSAA_CONFIG | RW | bits 1:0 sample mode (0 = 1x, 1 = 2x, 2 = 4x), snapshotted at START |

START snapshots the programmed job state. On queue-capable hardware, the host
writes a 64-byte descriptor to the job ring and advances `JOB_WPTR`. Jobs
execute in order. Completion writes a 16-byte IH record before raising the
interrupt; the driver drains records by job id and retires the matching fence.
Legacy register-programmed START remains supported and is mutually exclusive
with queued execution.

Integrated hosts advertise `CAPABILITIES[6]` when the unified command bank is
present. Software must test this bit before accessing `UCMD_*`. A command ID
remains reserved from submission until its completion is popped; duplicate IDs
are not accepted. The staging FIFO preserves submission order, while engines
may finish independently and report their opcode and status in the common
completion format.

`CAPABILITIES[7]` advertises MSAA and is set only on fixed-function builds
(`!fragCore`); bits 17:16 carry the maximum supported sample mode
(`log2Ceil(maxSampleCount)`). `MSAA_CONFIG[1:0]` selects 1x/2x/4x for the next
START; an invalid or over-maximum mode is rejected with `STATUS.ERROR` and no
launch. The same mode travels in queued job-record word 9. In multi-sample
modes a pixel's samples are contiguous words at
`base + y*stride + ((x << mode) + sample)*4`, so the physical stride must
cover `width << mode` pixels.

Linux exposes the kernel opcode through `DRM_IOCTL_OPENGPU_COMPUTE`.
Applications bind separate `OPENGPU_RESOURCE_COMPUTE_SHADER` and
`OPENGPU_RESOURCE_COMPUTE_KERNARG` resources to a render context, then submit
binding-relative entry/kernarg offsets and three-dimensional grid and local
sizes. The initial sandbox accepts bounded forward control flow and the
supported scalar subset plus masked and unmasked lane-local RVV integer ALU,
saturating, multiply, divide and remainder operations. Comparisons,
single-width reductions, gather and slide retain their unmasked profile.
Masked arithmetic requires defined source, v0 and old destination registers,
rejects destination v0, and preserves masked-off and inactive lanes.
The sandbox admits `vssrl.vv/vx/vi` and `vssra.vv/vx/vi` scaling right shifts
with rounding. They use one 32-bit vector source and the low five shift-amount
bits, support source/destination overlap, and do not set `vxsat`. Masked
forms follow the defined-register and destination-v0 restrictions above.
Hardware honors `vxrm`; the current shader interface uses reset RNU. Scaling
results invalidate trusted byte-index provenance.
The sandbox also supports masked and unmasked fixed-profile
`vsext/vzext.vf2/vf4/vf8`.
These extensions use the low 16/8/4 bits of each 32-bit source lane. Masked
forms require defined v0 and destination registers and preserve disabled
lanes. Source/destination overlap and masked writes to v0 are rejected, in
line with the [RVV register overlap rules](https://docs.riscv.org/reference/isa/unpriv/v-st-ext).
The sandbox also admits `vnsrl.wv/wx/wi` and `vnsra.wv/wx/wi` using a
fixed lane-local 64-bit source `{v[vs2+1][lane], v[vs2][lane]}` and a 32-bit
result. Vector/scalar shift amounts use six bits; immediates are unsigned
five-bit values. Both source registers must be defined, `vs2` must be even,
and `vd` must be disjoint from the pair. Masked forms additionally require
defined v0 and old `vd`, reject destination v0, and preserve disabled lanes.
Narrowing results cannot retain trusted byte-index provenance. Software must
prepare separate low/high word vectors; this pair layout differs from RVV's
general double-width register-group layout.
`vnclipu.wv/wx/wi` and `vnclip.wv/wx/wi` use the same pair layout and
validation rules. They round the shifted unsigned/signed 64-bit source
before saturating to 32 bits. Enabled saturated lanes set the warp's sticky
`vxsat` at commit; masked-off and inactive lanes preserve old `vd` and do
not raise saturation. Hardware supports all four `vxrm` modes, while the
current shader interface uses reset RNU and does not admit rounding-CSR
writes. Clip results also discard trusted byte-index provenance.
This fixed lane layout does not implement general RVV register groups or
variable-SEW extension semantics. The sandbox permits memory
access only inside the bound kernarg range and limits a workgroup to the fixed 32
resident work-items. `vrgather.vv/vx/vi` selects within the fixed per-warp
VLMAX and returns zero for an out-of-range index. Vector-vector and
vector-scalar forms require proven defined VGPR or SGPR sources; immediate
forms do not consume a source register.
`vslideup.vx/vi` preserves destination elements below its unsigned offset;
`vslidedown.vx/vi` returns zero when the selected source is outside VLMAX.
The validator rejects the architecturally reserved `vslideup`
destination/source overlap and requires its partially preserved destination
register to be defined before use.
The `vredsum/and/or/xor/minu/min/maxu/max.vs` family combines active source
lanes with the always-included `vs1[0]` seed and writes `vd[0]`. Because this
implementation preserves the remaining destination elements, the validator
requires the destination VGPR to be defined before a reduction.
Unit-stride `vle32.v` and `vse32.v` may use `v0.t`. Masked loads require both
`v0` and the partially preserved destination VGPR to be defined, and cannot
target `v0`; masked stores require a defined `v0` and source VGPR. Bounds are
proved for the complete vector range, so masking can only remove accesses from
an already safe range.
The RTL LSU additionally implements scalar-stride `vlse8/16/32.v` and
`vsse8/16/32.v`, including sparse cache-line transactions and elements that
cross a line boundary. The Linux shader sandbox admits `vlse32.v` and
`vsse32.v` when `rs2` is an aligned signed constant created directly with
`addi rd,x0,imm` and proves each lane address independently. The base must be a
fixed kernarg-relative pointer; lane-relative bases remain excluded. Loads must
stay inside the kernarg object and stores must also stay inside its writable
output range. The same defined-mask and preserved-destination rules apply to
masked strided operations.
The RTL also accepts ordered and unordered 32-bit indexed word operations
`vluxei32/vloxei32/vsuxei32/vsoxei32`; each `vs2` lane is an unsigned byte
offset from the scalar base and overlapping ordered stores resolve in element
order. The Linux validator admits these instructions when the index vector is
derived from trusted launch-time local IDs by an unmasked `vsll.vi ...,2`.
Masked shifts cannot establish this proof because disabled lanes retain old
destination values. The validator then
proves the fixed kernarg-relative base plus the complete batch byte span,
including the writable output interval for stores; other index provenance is
rejected.
The driver snapshots and validates the program, tracks the kernarg GEM object
for both reads and writes, and publishes completion through the context's
normal scheduler and optional input/output sync objects.

The compute, fill, blit and strided-copy ioctls also expose hardware events.
`OPENGPU_COMMAND_WAIT_EVENT` makes a command wait for the event encoded by
`wait_event`; `OPENGPU_COMMAND_SIGNAL_EVENT` publishes `signal_event` with the
command's success state. Each event word stores its ID in bits 7:0 and its
generation in bits 15:8. Generations let users reuse an event ID without
accidentally consuming an older completion. Event fields must be zero unless
their corresponding flag is set, and DMA ioctls reject event flags on hardware
without the unified-command capability. A failed waited event completes the
dependent command with an error fence instead of dispatching it.

`DRM_IOCTL_OPENGPU_GET_FAULT` returns one atomic, device-global snapshot of the
most recent unified-command failure. Sequence zero and a clear
`OPENGPU_FAULT_VALID` bit mean that the driver has not observed a fault. Each
new hardware completion error, completion-protocol mismatch or watchdog abort
advances the nonzero sequence and records the Linux errno, raw hardware status,
observed and expected command ID/opcode, and actual and expected byte counts.
Reason flags distinguish hardware completion errors, timeouts, aborts, ID or
opcode mismatches, inconsistent success metadata and short/long successful
transfers. For an abort without a completion, raw status and processed bytes
are zero. The snapshot intentionally contains no GPU addresses and remains
available after the failing fence is consumed, so render clients can diagnose
asynchronous failures without racing completion handling. Reserved input words
must be zero.

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

The 16-word job descriptor is:

| Words | Content |
|---|---|
| 0 | bits 15:0 job id, bits 31:16 command record count |
| 1 | command buffer base |
| 2 | colour buffer base |
| 3 | depth buffer base |
| 4 | framebuffer stride (bytes, physically padded for the sample mode) |
| 5 | bit 0 depth test, bits 6:4 depth func, bit 7 depth write, bits 9:8 cull mode |
| 6 | texture base |
| 7 | bits 13:0 texture width, bits 29:16 texture height |
| 8 | TEX_CONFIG (bit 0 clamp, bits 5:2 max mip level, bit 8 enable) |
| 9 | bits 1:0 sample mode (0 = 1x, 1 = 2x, 2 = 4x), bits 31:2 reserved |
| 10-15 | reserved, zero |

Unused words are zero. The four-word IH record contains job id, done/error
flags, ring slot and status.

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

`scripts/run_arti_gpu.sh` emits `GpuHostSystemAxi`, builds the driver and guest
test, and runs the generated device under QEMU/Linux. `GPU_SIM=verilator`
(default) embeds a Verilator model; `GPU_SIM=flashsim` embeds FlashSim instead
(`FLASHSIM_DIR` defaults to `../FlashSim`). `GPU_FRAG_CORE=1` selects the
fragment-core configuration; adding `GPU_VERT_CORE=1` selects vertex-core
records. `GPU_WIDTH` and `GPU_HEIGHT` select a matching RTL and guest mode.

For an interactive Debian rootfs (persistent qcow2, apt, manual `insmod`), use
`scripts/run_arti_debian.sh`. It builds a tiny cloud-init ISO plus a separate
`opengpu-modules.iso` (DRM deps + `/root/load_opengpu.sh`), then boots
`run_debian.sh`. Large modules are not embedded in cloud-init YAML. The script
does not re-run `setup_env.sh`, so a FlashSim-linked QEMU is left alone. In the
guest: `/root/load_opengpu.sh` or `/root/load_opengpu.sh test`
(login `root` / `arti`).

ARTI drives the AXI control slave and implements an AXI memory slave for the
GPU's `m_axi_*` master. AXI masters are discovered from channel shape and port
direction, not from GPU-specific names such as `kernelMemReq` or
`memoryRequest`. In silicon, `m_axi_*` attaches to the SoC L2/DRAM fabric.

`GpuHostSystemAxi` connects `GpuHostAxi.kernelWordMem*` to
`GpuSystem.graphicsHostRequest/graphicsHostResponse`. It remaps the graphics
host's eight local IDs above the CU and DMA ranges and returns responses with
their original IDs. Three four-entry word-to-line bridges occupy separate
command-buffer, framebuffer and texture ID ranges in the same 32-ID graphics
namespace. `GpuHostAxi.kernelMem*`, invalidate and atomic channels occupy one
additional CU-sized coherent range and connect directly to the L2 directory.
RTL can be emitted with
`runMain opengpu.elaboration.EmitGpuHostSystemAxi [target-dir]`, optionally
adding `--compute-units N`, `--frag-core`, `--vert-core`, `--width N`, and
`--height N`, and `--memory-axi-data-bytes N`.

The integrated top also feeds `GpuSystem.gpuCompletion.valid` into the same
sticky IRQ pending bit used by graphics jobs. Software consumes the structured
`gpuCompletion` result and clears IRQ.PENDING through the existing AXI W1C
write. A completion held under backpressure keeps reasserting pending, so the
result must be consumed before the interrupt is acknowledged.

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
- Shared `m_irq` delivery for graphics and unified-command completions.
- Capability discovery and a synchronized register ABI for unified compute and
  DMA command submission/completion.
- Linux fill/blit/strided-copy ioctls use unified commands when capability bit
  6 is present and fall back to their dedicated register banks otherwise.
- Unified DMA submission returns an asynchronous DRM scheduler fence. The IRQ
  handler validates completion ID, opcode, status and processed byte count
  before signaling it; timeout and abort paths signal an error fence. A 5 ms
  progress poll is retained for MMIO-driven emulators, not for synchronous job
  execution.
- Linux general-compute submission uses distinct shader/kernarg bindings,
  immutable validated program snapshots and the same scheduler-owned unified
  completion fence.
- The validator exposes the implemented RVV integer ALU, comparison,
  saturation, reduction, gather, slide, multiply, divide and remainder families
  with exact operand-form and defined-register checks.
- Unit-, constant-stride and trusted-local-index word vector loads and stores
  support validated `v0.t` predication, including preserved-destination checks
  for masked loads.
- Compute and DMA ioctls expose generation-tagged wait/signal hardware events;
  event-dependency failures propagate as scheduler fence errors.
- `DRM_IOCTL_OPENGPU_GET_FAULT` exposes an atomic retained snapshot of the most
  recent unified completion error, protocol mismatch or watchdog abort.

## Next

- Add a safe command-level reset that drains or invalidates in-flight memory
  transactions, then expose explicit reset recovery semantics through DRM.
- Expand shader profiles and resource types only with matching hardware and
  validation.
- Stabilize the ABI and performance envelope before a Mesa userspace driver.
