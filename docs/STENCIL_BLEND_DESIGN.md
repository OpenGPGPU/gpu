# Stencil (D24S8) and GL-Style Blend Design

## Status and Goals

This document defines the single-sided stencil test and the GL-style blend
factor/equation path for the graphics output merger. Both ship together with
the D24S8 depth/stencil packing: stencil occupies bits [31:24] of the existing
32-bit depth word, so there is no new buffer, no new base/stride registers and
no new capability bit. The feature is implemented end to end — RTL, register
file, job record, Linux UAPI, driver validation and integration tests — and
ships in all builds (fixed-function, fragment-core and vertex-core).

Source references: `OutputMerger.scala`, `Msaa.scala`, `RenderPipeline.scala`,
`CommandBufferStage.scala`, `KernelVertStage.scala`, `DrawContextFifo.scala`,
`RenderHost.scala`, `JobQueue.scala` under
`src/main/scala/opengpu/graphics/`, plus `driver/gpu_abi.h`,
`driver/opengpu_drm.h`, `driver/opengpu_compute.c` and `driver/opengpu_hw.c`.

The design has four invariants:

1. Legacy streams stay bit-identical: absent/zero config words decode to
   "blend config not present, stencil disabled", and the legacy
   `BLEND_ENABLE` (source-over) path is untouched.
2. A plain depth write preserves the stored stencil byte; stencil-disabled
   draws never corrupt stencil counters.
3. The stencil test runs before the depth test (GL order); fail/zfail/zpass
   ops are independent, and the z-pass op applies even when depth write is
   disabled.
4. A present blend config overrides the legacy source-over enable; MIN/MAX
   ignore the factors (GL semantics).

Deferred: front/back stencil split (the pipeline has no per-face concept), a
constant blend colour, dual-source blending, and per-face/op distinctions.

## Terminology and Encoding

### Depth/stencil word

One 32-bit word per pixel sample:

```text
bits[23:0]  depth   (D24)
bits[31:24] stencil
```

Depth comparisons narrow to bits [23:0] (the func shares the existing
depth-func encoding). All upstream depth clamps saturate to 24 bits
(`SampleDepth.maxDepth`), and the driver's depth-plane clear is `0x00ffffff`
(stencil 0, far depth) so stencil users start from a defined zero.

### Stencil func and ops

The stencil func reuses the hardware depth-func comparator encoding, so
`stencilPass = depthCompare(ref & readMask, stored & readMask, func)`:

| Value | Func | Value | Op |
|---|---|---|---|
| 0 | LESS | 0 | KEEP |
| 1 | LEQUAL | 1 | ZERO |
| 2 | GREATER | 2 | REPLACE |
| 3 | GEQUAL | 3 | INCR (saturate) |
| 4 | EQUAL | 4 | DECR (saturate) |
| 5 | NOTEQUAL | 5 | INVERT |
| 6 | ALWAYS | 6 | INCRWRAP |
| 7 | NEVER | 7 | DECRWRAP |

Ops apply to the stored byte and are write-masked:

```text
out = (opResult & writeMask) | (stored & ~writeMask)
```

### Blend factors and equations

Factors (4 bits, GL order): 0=ZERO, 1=ONE, 2=SRC_COLOR,
3=ONE_MINUS_SRC_COLOR, 4=SRC_ALPHA, 5=ONE_MINUS_SRC_ALPHA, 6=DST_COLOR,
7=ONE_MINUS_DST_COLOR, 8=DST_ALPHA, 9=ONE_MINUS_DST_ALPHA,
10=SRC_ALPHA_SATURATE (`min(srcA, 255-dstA)`); 11–15 are reserved and
rejected by the driver.

Equations (3 bits, GL order): 0=ADD, 1=SUB, 2=REV_SUB, 3=MIN, 4=MAX; 5–7 are
reserved and rejected by the driver. MIN/MAX ignore the factors. All four
channels (including alpha) use the same factor pair and equation; *_COLOR
factors are per-channel and alpha factors uniform, which falls out naturally
from treating the alpha position as its own channel.

Per channel:

```text
m     = factor-selected 8-bit multiplier
scaled = round(c * m / 255)          // exact rounded mul255
result = equation(scaledSrc, scaledDst)  // ADD/SUB/REV_SUB saturate to [0,255]
```

The legacy source-over path keeps its own arithmetic and remains
bit-identical; the general path's `mul255` is a separate helper.

## Memory Layout

No new buffer. The stencil byte shares the depth word, so colour and
depth/stencil addressing is exactly the MSAA layout:

```text
depthStencil(x, y, s) = depthBase + y * stride +
                        (x * samplesPerPixel + s) * 4
```

The MSAA per-sample depth gradient saturates to the 24-bit D24 range; wire
widths stay 30/32 bits and only the clamps and masks narrowed.

## ABI

### Registers

The unified command bank owns `0xC4`–`0x130` plus `UCMD_RESET` at `0x138`, so
the stencil/blend registers live immediately after it and are snapshotted at
START exactly like `DEPTH_FUNC`:

| Offset | Name | Meaning |
|---|---|---|
| 0x13C | STENCIL_CONFIG | bit 0 enable; bits 6:4 func; bits 9:7 fail op; bits 12:10 z-fail op; bits 15:13 z-pass op |
| 0x140 | STENCIL_REF_MASKS | bits 7:0 ref; bits 15:8 read mask; bits 23:16 write mask |
| 0x144 | BLEND_CONFIG | bit 0 present; bits 7:4 src factor; bits 11:8 dst factor; bits 14:12 equation |

The register stencil layout is the draw-record word-36 layout shifted up one
nibble with the enable at bit 0; the ref/mask and blend words are
register-compatible with job words 11/12. `RenderHostRegs.END` is `0x148`.

### Draw record words

Both draw forms (inline triangle and vertex-core) decode:

| Word | Content |
|---|---|
| 32 bit 17 | stencil test enable (per-draw state override) |
| 35 | blend config: bit 0 present, bits 7:4 src factor, bits 11:8 dst factor, bits 14:12 equation |
| 36 | stencil config: bits 2:0 func, bits 5:3 fail op, bits 8:6 z-fail op, bits 11:9 z-pass op |
| 37 | stencil ref bits 7:0, read mask bits 15:8, write mask bits 23:16 |

Words 38–39 stay reserved-zero. Absent/zero words decode to "blend config not
present, stencil disabled", so legacy command streams are bit-identical.

A present blend config (bit 0) overrides the legacy source-over
`BLEND_ENABLE`. Stencil words are only meaningful with the word-32 enable set;
the driver rejects them otherwise.

### Job record

Job words 10/11/12 mirror words 36/37/35, and job state word 5 bit 17 carries
the stencil enable. Linux publishes zeros in words 10–12 (its per-draw state
rides the command records), but the fields are defined and the raw queue can
use them as global defaults for draws without a per-draw override.

### Linux UAPI

`struct drm_opengpu_draw` and `struct drm_opengpu_vertex_draw` expose
`blend_config`, `stencil_config` and `stencil_ref` (previously reserved), with
`OPENGPU_DRAW_STATE_STENCIL_TEST (1u << 17)` in the state word
(`OPENGPU_DRAW_STATE_VALID_MASK` is now `0x3ffff`). Each word has its own
valid mask mirroring the sampler word.

## Pipeline

Per depth-word read response, the output merger FSM (states unchanged) does:

1. **Stencil test** (when enabled): compare `ref & readMask` against
   `stored & readMask` with the func. On fail, apply the fail op to the
   stencil byte; if the updated word differs from stored, write it (depth bits
   preserved) and skip colour entirely, otherwise retire.
2. **Depth compare** on bits [23:0]. On fail, apply the z-fail op the same
   stencil-only way (write or retire).
3. **Depth pass**: apply the z-pass op (even when depth write is disabled) and
   latch the full write word `Cat(zpassStencil, fragDepth[23:0])` — or
   `Cat(zpassStencil, storedDepth[23:0])` when depth write is off. The
   effective depth-write flag becomes `depthWriteEnable || stencilChanged`, so
   a stencil-only update still writes the depth word. Colour then proceeds
   (blended or not).
4. **Colour read response**: `blendedColor = blendGeneral(src, dst, srcFactor,
   dstFactor, eq)` when the blend config is present, else the legacy
   source-over result. `sWriteDepth` writes the latched word rather than the
   raw fragment depth.

When the depth test is disabled but stencil is enabled, the draw still reads
the D24S8 word and runs the stencil test with an implicit depth pass (GL
semantics). When both are disabled, no depth-plane traffic occurs (legacy
behaviour).

Every entry latches the complete stencil/blend configuration at fragment
acceptance, so per-draw state may change while earlier entries are in flight.

## Driver Behaviour

For a render submission the driver:

1. Validates the state word against `0x3ffff` and the three new words against
   their valid masks.
2. Rejects reserved encodings: blend factors > 10, equations > 4, a blend
   config without the present bit, and stencil words without the stencil-test
   enable. Func/op fields are 3-bit and cover 0–7 by construction.
3. Relocates and copies the records unchanged; the hardware decodes words
   35–37 per draw.
4. Clears the private depth plane to `0x00ffffff` (hardware clear engine or a
   per-word CPU fallback).
5. Builds job words 10–12 from the (zero) global defaults and sets state bit
   17 only if the global stencil test is enabled; the legacy MMIO path writes
   registers 0x13C/0x140/0x144.

The self-test record carries a pass-through blend config
(`ONE/ZERO/ADD`) so the word-35 decode is exercised on every boot without
changing the rendered colour.

## Implementation Status

| Area | State |
|---|---|
| RTL | OM stencil FSM + general blend, D24S8 packing, 24-bit clamps: implemented |
| State plumbing | Draw records, vertex pass-through, draw state, contexts, host regs, job record: implemented |
| Driver | UAPI words, validation, job build, clear value, MMIO path: implemented |
| Tests | OM factor/equation sweep, stencil func/op/mask/order matrix, pipeline stencil gating, negative UAPI cases: implemented |

Deferred: front/back stencil, constant blend colour, dual-source factors, and
a per-channel colour/alpha factor split.

## Verification

From the repository root:

```sh
sbt 'testOnly opengpu.graphics.OutputMergerSpec opengpu.graphics.RenderCoreSpec opengpu.graphics.CommandBufferStageSpec opengpu.graphics.JobQueueSpec opengpu.graphics.RenderHostSpec'
```

Key cases:

1. Every blend factor pair and equation against a software reference
   (11 × 11 × 5 sweep), plus a present-config-overrides-source-over case.
2. Every stencil func before the depth test with the z-pass op applied on
   pass and colour skipped on fail.
3. Every stencil op with saturation, 8-bit wrap, and partial write masks.
4. z-fail on a depth fail; stencil-only write with depth write disabled;
   stencil test with the depth test disabled.
5. Pipeline-level stencil gating in one command buffer: a stamped byte
   admits one later draw and blocks another; the winning sample carries the
   stamped stencil byte under the new depth.
6. Negative UAPI cases: reserved factor, reserved equation, stencil words
   without the enable bit (`-EINVAL`).
7. Legacy bit-identity: existing source-over and depth-write expectations are
   unchanged, and the D24S8 clear keeps stencil at 0.
