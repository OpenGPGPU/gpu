# Host interface

The product top is `GpuHostSystemAxi`: one AXI4 control slave, one AXI4 memory
master, one IRQ and one clock/reset domain. CPU and GPU have separate L2
caches. Shared DRAM ownership uses driver cache maintenance and fences;
GPU-internal invalidation is not CPU cache snooping.

## Submission and completion

All renders use `GPU_UCMD_OP_RENDER` (6). Software writes the descriptor VA to
`UCMD_SOURCE`, stages the ID/byte count, then writes `UCMD_SUBMIT`. The immutable
16-word descriptor is fetched through the translated command client. Its
command, colour/depth and texture addresses resolve under the selected VM.
`UCMD_BYTES` is completion accounting, not a variable descriptor length.

`GpuHostAxi` dispatches render locally and other commands to `GpuSystem`.
Their results share an arbitrated completion slot and IRQ. Driver scheduler
jobs retain GEM references and reservation/syncobj fences until completion.
Render dependency ordering is provided by the driver scheduler; hardware
wait/signal events are implemented by the system router for compute/DMA.
The render demultiplexer does not implement those event fields.

Render status is opcode-specific: 0 success, 1 memory fault, 2 invalid sample
mode. The entire descriptor sample word must be within the build's supported
mode range. Reserved bits, mode 3 and modes above capacity complete with error
without launching the renderer. Descriptor faults stop the fetch; execution
faults drain the active render before error completion. Failed rendering may
leave partial output. Clearing STATUS.ERROR does not erase the owning job's
fault, and the next accepted job starts clean.

Reset blocks new bridge submissions, discards queued bridge commands and
waits for graphics descriptor fetch, launch, execution, pending completion,
system engines and shared-L2 transactions. Empty L2 alone is insufficient.
Old completions are discarded during reset; RESET_BUSY clears only after
retirement. A hung client leaves reset draining; the driver reports bounded
recovery failure rather than claiming that memory has stopped.

Register offsets 0x64–0x84 are unmapped/reserved. Execution shadow registers
remain readable/writable for compatibility but do not configure unified
renders. Dedicated legacy DMA registers remain supported.

## Registers

The C definitions in `driver/gpu_abi.h` and Scala definitions are compared by
`GpuAbiLayoutSpec`. The mapped end of an integrated build is 0x158.

| Offset | Name | Access | Meaning |
|---|---|---|---|
| 0x00 | ID | RO | `device_id << 16 \| version` |
| 0x04 | CONTROL | W1P | legacy START bit is inert; does not launch rendering |
| 0x08 | STATUS | RO/W1C | BUSY, DONE, ERROR and DMA-engine busy bits |
| 0x0C | IRQ | RW/W1C | bit 0 ENABLE; bit 1 PENDING for graphics or unified-command completion |
| 0x10 | CMD_BASE | RW | command-buffer byte address |
| 0x14 | CMD_COUNT | RW | draw-record count |
| 0x18 | COLOR_BASE | RW | colour-buffer byte address |
| 0x1C | DEPTH_BASE | RW | depth-buffer byte address |
| 0x20 | STRIDE | RW | framebuffer stride in bytes |
| 0x24 | DEPTH_TEST_ENABLE | RW | depth-test enable |
| 0x28 | DEPTH_FUNC | RW | 0 LESS, 1 LEQUAL, 2 GREATER, 3 GEQUAL, 4 EQUAL, 5 NOTEQUAL, 6 ALWAYS, 7 NEVER |
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
| 0x60 | CAPABILITIES | RO | fragment core, vertex core, clear/blit/strided engines, unified commands, MSAA, batch capacity and safe unified reset |
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
| 0xC8 | UCMD_OPCODE | RW | kernel, copy, fill, strided-copy, resolve, invalidate or render |
| 0xCC | UCMD_KERNEL_PC | RW | compute kernel entry PC |
| 0xD0 | UCMD_KERNARG | RW | compute kernarg byte address |
| 0xD4-0xDC | UCMD_GRID_X/Y/Z | RW | compute grid dimensions |
| 0xE0-0xE8 | UCMD_LOCAL_X/Y/Z | RW | compute workgroup dimensions |
| 0xEC | UCMD_FLAGS | RW | wait-DMA, wait-event and signal-event enables |
| 0xF0 | UCMD_DMA_DEPENDENCY | RW | DMA source `[1:0]` and descriptor ID `[15:8]` |
| 0xF4 | UCMD_SOURCE | RW | copy source address or render descriptor VA |
| 0xF8 | UCMD_DESTINATION | RW | copy/fill destination byte address |
| 0xFC | UCMD_BYTES | RW | linear copy/fill byte count |
| 0x100 | UCMD_PATTERN | RW | fill pattern; for resolve, physical source base for the pre-resolve L2 invalidate when SOURCE is a private VA (0 = invalidate SOURCE) |
| 0x104 | UCMD_WIDTH | RW | strided-copy width in bytes |
| 0x108 | UCMD_HEIGHT | RW | strided-copy row count |
| 0x10C | UCMD_SOURCE_STRIDE | RW | strided-copy source stride |
| 0x110 | UCMD_DEST_STRIDE | RW | strided-copy destination stride |
| 0x114 | UCMD_WAIT_EVENT | RW | event ID `[7:0]` and generation `[15:8]` |
| 0x118 | UCMD_SIGNAL_EVENT | RW | event ID `[7:0]` and generation `[15:8]` |
| 0x11C | UCMD_SUBMIT | W1P | snapshot and enqueue when bit 0 is written |
| 0x120 | UCMD_STATUS | RO/W1C | ready, completion-valid and submit-overflow; bit 2 clears overflow; bits 3 RESET_BUSY and 4 RESET_REJECTED; bit 4 clears RESET_REJECTED |
| 0x124 | UCMD_COMPLETION | RO | ID `[7:0]`, opcode `[10:8]`, status `[14:11]`, success `[15]` |
| 0x128-0x12C | UCMD_COMPLETION_BYTES | RO | processed byte count, low then high word |
| 0x130 | UCMD_COMPLETION_POP | W1P | consume completion when bit 0 is written |
| 0x134 | MSAA_CONFIG | RW | bits 1:0 sample mode (0 = 1x, 1 = 2x, 2 = 4x), legacy shadow; render mode comes from descriptor word 9 |
| 0x138 | UCMD_RESET | W1P | request a safe unified-command reset when bit 0 is written |
| 0x13C | STENCIL_CONFIG | RW | enable bit 0; func 6:4; fail op 9:7; depth-fail op 12:10; depth-pass op 15:13 |
| 0x140 | STENCIL_REF_MASKS | RW | reference 7:0, read mask 15:8, write mask 23:16 |
| 0x144 | BLEND_CONFIG | RW | present bit 0, source factor 7:4, destination factor 11:8, equation 14:12 |
| 0x148 | UCMD_SAMPLE_MODE | RW | bits 1:0 sample mode staged for a unified `GPU_UCMD_OP_RESOLVE` |
| 0x14C | UCMD_VECTOR_SATP | RW | Sv32 `satp` for the vector/data MMU: bit 31 enable, ASID `[30:22]`, root PPN `[19:0]` |
| 0x150 | UCMD_INSTRUCTION_SATP | RW | Sv32 `satp` for the instruction MMU (same layout as VECTOR_SATP) |
| 0x154 | UCMD_TLB_FLUSH | W1P | bit 0 full flush; bit 1 ASID-scoped flush (ASID `[11:3]`); bit 2 VPN-scoped flush (VPN `[31:12]`); no bit set is a no-op |

## Translation and ownership

`satp` changes require quiescence. Distinct ASIDs preserve CU and graphics TLB
entries on a plain VM switch. Both the CU TLBs and the graphics word clients
implement the same scoped invalidation: a full flush, an ASID-scoped or a
VPN-scoped shootdown, so a scoped flush does not evict unrelated warm entries.
ASID reuse and private mapping changes take ASID-scoped shootdowns. Context
roots start empty and contain only explicit mappings. The ASID-0 identity
leaves are read/write, non-executable and non-global, so their cached entries
cannot match a context ASID. Compute, fragment and vertex code
use private executable windows. The shared graphics shader core consumes
UCMD_INSTRUCTION_SATP and UCMD_TLB_FLUSH; Bare mode still fetches physical
instructions. Shader instruction faults fail render completion with a memory
fault after draining. Shader kernarg, vertex-buffer staging and scalar/vector
data accesses translate under VECTOR_SATP alongside the graphics word clients
(Bare mode keeps physical addressing). Resolve engine traffic translates under
VECTOR_SATP through the private DMA VA window when the context VM is enabled;
UCMD_PATTERN carries the physical source base for the pre-resolve L2
invalidate. Line-invalidate commands also use physical addresses (the L2
host-invalidate port ignores satp) but keep the submitting context's VM active
rather than restoring ASID-0. Fill/blit/strided DMA translate under
VECTOR_SATP.

The command client remains uncached. Texture/framebuffer clients use the
PTE cache policy. CPU writes still require the appropriate CPU cache/DMA
synchronization; uncached GPU mappings only bypass GPU caches.

## Shared-memory layouts

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
| 35 | RTL blend config: present bit 0, source factor 7:4, destination factor 11:8, equation 14:12 |
| 36 | RTL stencil func 2:0, fail op 5:3, depth-fail op 8:6, depth-pass op 11:9 |
| 37 | RTL stencil reference 7:0, read mask 15:8, write mask 23:16 |
| 38-39 | reserved, zero |

On vertex-core hardware the record instead carries vertex-buffer base/count/
stride/format, vertex shader and kernarg fields in words 0-6, fragment shader
and kernarg fields in words 24-25, and the common state in words 32-34.
Both record forms decode words 35–37. Word 32 bit 17 enables stencil when
per-draw state is selected. Linux defines words 35–37 in
`struct drm_opengpu_draw` / `struct drm_opengpu_vertex_draw`
(`blend_config`, `stencil_config`, `stencil_ref`), validates their layout and
value ranges (blend factors 0–10, equations 0–4, func/op fields 0–7), and
rejects stencil words without the stencil-enable bit; words 38–39 stay
reserved-zero.

The fixed vertex format is eight words: Q16.16 position, RGBA8888 colour, depth and
Q16.16 UV. Resource addresses are relocated from validated bindings rather
than trusted from userspace command data.

#### Shader vector rounding

Validated compute, vertex and fragment shaders may use `csrwi vxrm, mode`
(`csrrwi x0, 0x00a, mode`) with immediate modes 0=RNU, 1=RNE, 2=RDN and
3=ROD. Every launched warp starts at RNU, including reuse of a hardware warp
by a later job. The write is ordered before the next instruction from that
warp. Other CSR forms remain unsupported and are rejected by the shader
validator; unvalidated execution reports an illegal-instruction trap. The
fixed-point vector rounding operations consume the selected per-warp mode.

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

The fragment profile is coupled to validated sample mode: mode 0 selects
`GPU_FRAGMENT_ABI_LEGACY` (0), modes 1/2 select
`GPU_FRAGMENT_ABI_MULTISAMPLE` (1). There is no independent selector. ABI 0
emits for any nonzero 32-bit slice-8 word and uses slice 7 as shader depth.
ABI 1 requires bits 31:2 to be zero, bit 0 to emit, and bit 1 to override
raster sample depths with the shader depth. A reserved-bit violation discards
the pixel without failing the job; helpers never emit under either profile.
`GPU_FRAGMENT_CONTROL_*` defines the ABI-1 masks in `driver/gpu_abi.h`.
The selector is snapshotted per batch, so overlapping draws keep their own
interpretation. Programmable MSAA is advertised on fragment-core builds through
`CAPABILITIES[7]`, so Linux's fragment path uses ABI 0 at mode 0 and ABI 1 for
the validated nonzero modes.

Two identical banks may be supplied with a validated aligned bank stride so
raster staging can overlap SIMT execution without aliasing scratch data.

#### Buffers and textures

- Colour is RGBA8888 (`0xRRGGBBAA`); depth is D24S8 in a 32-bit word
  (depth bits 23:0, stencil bits 31:24).
- `stride` is in bytes: a 1x pixel address is `base + y * stride + x * 4`.
  MSAA uses `base + y * stride + ((x << sampleMode) + sampleIndex) * 4`.
- Depth comparison and writes use bits 23:0; a plain depth write preserves the
  stored stencil byte. Stencil testing precedes depth testing. The driver
  clears every private depth plane to `0x00ffffff` (stencil 0, far depth).
- Mip levels are tightly packed from `TEX_BASE`, largest to smallest, without
  row padding. The driver validates the full advertised chain.

### Unified render descriptor

The `gpu_job_record` C name is retained for the 16-word layout; it no longer
implies a job ring. Unified command ID owns completion identification.

| Words | Content |
|---|---|
| 0 | bits 15:0 job id, bits 31:16 command record count |
| 1 | command buffer base |
| 2 | colour buffer base |
| 3 | depth buffer base |
| 4 | framebuffer stride (bytes, physically padded for the sample mode) |
| 5 | bit 0 depth test, bits 6:4 depth func, bit 7 depth write, bits 9:8 cull mode, bit 17 stencil test |
| 6 | texture base |
| 7 | bits 13:0 texture width, bits 29:16 texture height |
| 8 | TEX_CONFIG (bit 0 clamp, bits 5:2 max mip level, bit 8 enable) |
| 9 | bits 1:0 sample mode (0 = 1x, 1 = 2x, 2 = 4x), bits 31:2 reserved |
| 10 | RTL stencil func 2:0, fail op 5:3, depth-fail op 8:6, depth-pass op 11:9 |
| 11 | RTL stencil reference 7:0, read mask 15:8, write mask 23:16 |
| 12 | RTL blend config, same layout as draw word 35 |
| 13-15 | reserved, zero |

## Linux and display

The platform owns DRM allocation/registration. Execution owns contexts and
job preparation; the common scheduler owns dependency publication and
retirement. KMS owns connector/pipe state and programs scanout from GEM
framebuffers after their write fences. Display starts disabled and does not
borrow the execution self-test buffer. `opengpu,render-only` omits KMS setup
while retaining GEM, render and syncobj services.

Under ARTI/QEMU the chosen display model is guest-memory scanout: the
embedded device watches `SCANOUT_BASE` / `STRIDE` / `CONTROL` / `WIDTH` /
`HEIGHT` and refreshes the QEMU console from guest RAM (`source:
guest-memory` in `gpu_integration.yaml`). Soft/timer vblank remains in the
Linux driver; a hardware vblank IRQ is future work.

Host validation: `python3 scripts/test_driver.py`. Linux integration:
`bash scripts/run_arti_gpu.sh` (FlashSim by default; `GPU_SIM=verilator` for
Verilator). Visual scanout: `bash scripts/run_arti_display.sh`. Status:
[GRAPHICS_ROADMAP.md](GRAPHICS_ROADMAP.md).
