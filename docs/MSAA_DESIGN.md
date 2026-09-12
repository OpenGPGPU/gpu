# MSAA (Multi-Sample Anti-Aliasing) Design

## Status and Goals

This document defines a 1x, 2x, and 4x MSAA path for the existing graphics
pipeline. It is a design proposal; none of the ABI described below is exposed
until the corresponding RTL, Linux driver, and tests land together.

The design has four invariants:

1. A fragment shader runs once per pixel per primitive (plus helper lanes),
   never once per covered sample in this profile.
2. The existing 2x2 pixel-quad lane layout, helper lanes, and derivative
   semantics remain unchanged.
3. Coverage, depth testing, depth writes, and blending operate independently
   for every sample.
4. A render fence covers all multisample writes, and a resolve fence covers the
   resolved output buffer before scanout or reuse.

The initial implementation supports RGBA8888 colour and one 32-bit word per
depth sample. Sample shading, alpha-to-coverage, programmable sample positions,
centroid interpolation, and stencil are out of scope.

## Terminology and Encoding

The programmer-visible value is `sampleMode`, not a literal sample count:

| `sampleMode` | Samples per pixel | Meaning |
|---|---:|---|
| 0 | 1 | MSAA disabled |
| 1 | 2 | 2x MSAA |
| 2 | 4 | 4x MSAA |
| 3 | - | Reserved; submissions are rejected |

Hardware derives `samplesPerPixel = 1 << sampleMode`. Address, allocation, and
loop calculations must use `samplesPerPixel`; they must not multiply by the
encoded `sampleMode` value.

The elaboration-time limit is:

```scala
case class GraphicsConfig(
  screenWidth: Int = 128,
  screenHeight: Int = 128,
  subPixelBits: Int = 8,
  drawFifoDepth: Int = 8,
  maxSampleCount: Int = 4
)
```

`maxSampleCount` must be 1, 2, or 4. Runtime modes above that limit are
rejected by the driver and are not admitted by the hardware queue.

## Sample Coordinate Convention

The current rasterizer evaluates a pixel at the integer fixed-point coordinate
`(x, y)`. For compatibility, that coordinate remains the 1x sample centre.
MSAA positions are therefore defined as offsets from the existing centre, not
as a new `x+0.5, y+0.5` convention.

| Mode | Physical sample index | Centre-relative offset `(dx, dy)` | Equivalent unit-pixel position |
|---|---:|---|---|
| 1x | 0 | `(0, 0)` | `(0.5, 0.5)` |
| 2x | 0 | `(-0.25, -0.25)` | `(0.25, 0.25)` |
| 2x | 1 | `(+0.25, +0.25)` | `(0.75, 0.75)` |
| 4x | 0 | `(-0.25, -0.25)` | `(0.25, 0.25)` |
| 4x | 1 | `(+0.25, -0.25)` | `(0.75, 0.25)` |
| 4x | 2 | `(-0.25, +0.25)` | `(0.25, 0.75)` |
| 4x | 3 | `(+0.25, +0.25)` | `(0.75, 0.75)` |

The 4x pattern is a regular 2x2 grid. The 2x and 4x lookup tables are
mode-specific: 2x sample 1 is bottom-right, while 4x sample 1 is top-right.
These simple quarter-pixel positions are the initial hardware-quality tradeoff;
a rotated pattern may replace them only through a versioned capability.

With `subPixelBits = 8`, the quarter-pixel magnitude is 64. The implementation
must either require `subPixelBits >= 2` or reject configurations that cannot
represent the selected pattern exactly.

The top-left fill rule is applied independently at each lookup-table position.
It determines ownership of a sample on a shared edge; it does not determine the
sample positions themselves.

## Memory Layout

Colour and depth samples of one pixel are interleaved and contiguous. `STRIDE`
always means the physical row stride in bytes; hardware does not scale it a
second time.

```text
samplesPerPixel = 1 << sampleMode

colour(x, y, s) = colourBase + y * stride +
                   (x * samplesPerPixel + s) * 4

depth(x, y, s)  = depthBase + y * stride +
                   (x * samplesPerPixel + s) * 4
```

The initial formats both use four bytes per sample, so one physical stride can
serve both buffers. A future format with different colour and depth bytes per
sample will require separate strides.

For a tightly packed 128x128 4x target:

- Physical stride: `128 * 4 * 4 = 2048` bytes.
- Colour allocation: `2048 * 128 = 256 KiB`.
- Depth allocation: `2048 * 128 = 256 KiB`.
- Resolved RGBA8888 allocation: `512 * 128 = 64 KiB`.

The driver validates `stride >= width * samplesPerPixel * 4`, four-byte
alignment, multiplication overflow, and `stride * height` against both backing
allocations. It clears or loads every sample before a render pass. In
particular, uncovered edge samples must contain the render-pass background
colour and cleared depth rather than uninitialised data.

## ABI

### Capabilities

The existing `CAPABILITIES[15:8]` fragment-batch-capacity field is unchanged.
MSAA uses currently unallocated fields:

| Bits | Meaning |
|---|---|
| 7 | MSAA supported |
| 17:16 | Maximum supported `sampleMode`: 0=1x, 1=2x, 2=4x |

Software tests bit 7 before programming MSAA state and rejects a requested mode
above bits 17:16.

### Register-Programmed Submission

The unified command bank already occupies `0xC4` through `0x130`. MSAA state is
placed after it:

| Offset | Name | Access | Description |
|---|---|---|---|
| 0x134 | MSAA_CONFIG | RW | bits `[1:0]` are `sampleMode`; all other bits reserved-zero |

There is no separate enable bit: mode 0 is the disabled/1x state. There are no
MSAA resolve-PC or resolve-kernarg registers; resolve uses the existing unified
kernel command interface.

Legacy `START` snapshots `MSAA_CONFIG` with the other render state. An invalid
mode or a mode above the advertised maximum reports a submission error rather
than silently falling back.

`GpuHostAxi` currently routes every address at or above `0xC4` to
`GpuCommandMmio`. Adding this register therefore requires bounded routing:
`0xC4 <= address < 0x134` selects the unified-command block, while `0x134`
selects `RenderHost`. The overall mapped end becomes `0x138`. Merely increasing
`RenderHostRegs.END` would shadow or misroute the existing unified registers.

### Job Ring and Linux UAPI

Queue submission must carry the same state as legacy register submission:

- Job descriptor word 9 bits `[1:0]` carry `sampleMode`; bits `[31:2]` are zero.
- `JobConfig`, `DrawRenderState`, and `DrawContext` carry `sampleMode`.
- The render-submit UAPI consumes a reserved field as `sample_mode` in the ABI
  version that advertises MSAA.
- The driver publishes word 9 when it constructs a job descriptor.

The current single-sample check that render stride equals the platform's
logical stride is replaced with the physical-stride validation in the memory
layout section. The private depth allocation and clear size are both
`physicalStride * height`.

## Pipeline

```text
triangle setup
     |
pixel/quad rasterization: centre edge values + coverageMask
     |
pixel-centre interpolation: colour/UV + per-sample depths
     |
fixed-function texture path or one fragment-shader invocation per pixel
     |
shader discard/depth selection
     |
sample expander: one OmFragment for each set coverage bit
     |
per-sample OutputMerger depth/blend/write
```

The sample expander is deliberately after both fragment-shading paths. It is
the boundary between per-pixel work and per-sample work.

### Rasterization and Coverage

`RasterPixel` gains a fixed-width mask sized by the elaboration-time maximum:

```scala
val coverageMask = UInt(maxSampleCount.W)
```

The active low bits correspond to physical sample indices. Bits above
`samplesPerPixel` are zero. In 1x mode bit 0 is exactly the existing `covered`
result, preserving single-sample coverage.

For each pixel, the rasterizer derives sample edge values from the centre edge
value and the edge gradients:

```text
E_sample = E_centre + A * dx + B * dy
```

All arithmetic uses the existing fixed-point scale and top-left tests. The
rasterizer still steps one pixel at a time in scalar mode and one 2x2 pixel quad
at a time in quad mode; it does not scan a 2x or 4x enlarged framebuffer.

The triangle bounding box is conservatively expanded for the largest active
sample offset before screen clamping. Scalar mode emits a pixel when any active
coverage bit is set. Quad mode continues emitting complete aligned 2x2 pixel
quads, including lanes whose mask is zero, so helper-lane and derivative
behaviour is unchanged.

### Interpolation and Depth

Colour, UV, and other varyings are evaluated at the existing pixel centre and
shared by all covered samples. Centroid interpolation is not provided in the
initial implementation, so a centre outside the triangle may extrapolate
varyings when another sample in that pixel is covered.

Depth remains affine in screen space. Triangle setup computes one centre-depth
plane and its one-pixel gradients. For every active sample:

```text
depthSample[s] = depthCentre + depthDx * dx[s] + depthDy * dy[s]
```

The design must specify and test the fixed-point widths, signed rounding,
overflow behaviour, and final D24 clamp/mask before implementation. Gradients
are computed once per triangle, not by adding two multipliers to every
interpolator lane.

`RasterFragment` carries `coverageMask` and the active `depthSample` values
through the fixed-function path and through `KernelFragStage`'s staging
records. Edge values remain centre edge values for pixel-centre UV and colour
interpolation.

The fragment ABI is selected explicitly by submission state, independently of
sample mode. ABI 0 retains the legacy contract: any nonzero output-valid word
emits a covered pixel and the shader depth-output word is used unconditionally.
ABI 0 is supported only with sample mode 0. Existing submissions default to ABI
0, including shaders that write depth without changing output-valid.

ABI 1 interprets the fragment kernarg output-control word as follows:

- bit 0: emit pixel (`0` means discard).
- bit 1: shader depth override is valid.
- bits 31:2: reserved-zero.

ABI 1 shaders write 0 or 1 to select interpolated per-sample
depth. When bit 1 is set, the shader's single depth result is replicated to all
covered samples. This avoids incorrectly adding the original primitive's depth
gradient to a programmatically replaced depth.

### Fragment Shader and Quad Semantics

The core-backed path continues batching four pixel lanes per input beat. A
pixel with four covered samples still occupies one SIMT lane and runs the
fragment shader once. A zero-mask lane remains a helper: it participates in
quad derivatives but its initial output-control word is zero.

After shader completion, effective coverage is:

```text
effectiveMask = outputControl.bit0 ? coverageMask : 0
```

`KernelFragStage` emits one shaded pixel record with that mask; it does not
duplicate the record for samples and does not alter the lane-to-quad mapping.

### Sample Expander

The sample expander accepts a shaded pixel and serially visits the set bits in
`effectiveMask`. Each output contains:

```scala
class OmFragment extends Bundle {
  val x = UInt(16.W)
  val y = UInt(16.W)
  val color = UInt(32.W)
  val depth = UInt(30.W)
  val sampleIndex = UInt(2.W)
}
```

Colour is shared. Depth is selected from `depthSample(sampleIndex)`, unless the
shader-depth-override bit selects the replicated shader result. The input pixel
is held until every set bit has handshaken with the OutputMerger. In 1x mode
this reduces to the current one-input/one-output behaviour.

The draw context must remain at the FIFO head until its last pixel has emitted
its last covered sample. Shader retirement alone is insufficient: the owner
must also wait for the expander to drain. Empty/discard-only draws still retire
once, in order. OM entries snapshot all address and test/blend state on accept.

Render completion requires all producers (including texture sampling and the
expander) to drain, all draw contexts to retire, and all OM writes to receive
acknowledgements. Slot availability (`ready`) is not completion. The word-memory
adapter must acknowledge a write only at the shared visibility point; the
integrated L2 path returns the original requester response after the lower
memory acknowledgement. Same-sample reservations remain held through writes.

### Output Merger

The OutputMerger computes addresses as:

```text
sampleOffset = (x << sampleMode) + sampleIndex
address = base + y * physicalStride + sampleOffset * bytesPerSample
```

Depth test, optional destination-colour read, source-over blend, colour write,
and depth write remain independent for every sample.

Conflict detection compares both latched addresses for the same physical
sample:

```text
conflict = existing.colorAddr == incoming.colorAddr ||
           existing.depthAddr == incoming.depthAddr
```

Different samples of one pixel have different addresses and may occupy
different in-flight entries. Fragments for the same sample remain serialized,
preserving draw order. The initial implementation keeps the current table
depth; increasing it is a measured optimisation, not a correctness dependency.

## Resolve

Resolve averages every pixel's physical colour samples into a separate
single-sample RGBA8888 buffer. Depth resolve is out of scope.

### Trusted Resolve Kernel

The existing user compute profile cannot dereference arbitrary buffer pointers:
it is intentionally limited to one validated kernarg range. Therefore the
initial resolve is a driver-owned trusted kernel, submitted through the
existing unified kernel command engine but exposed through a typed resolve
driver operation rather than as an unvalidated user compute shader.

The typed operation is independent of its execution backend. A dedicated
streaming resolve engine can implement the same validated operation and fence
contract without changing the userspace interface.

The resolve operation takes validated GEM-relative input and output ranges:

| Field | Meaning |
|---|---|
| source | Multisample colour buffer and offset |
| destination | Single-sample colour buffer and offset |
| width, height | Logical extent |
| source stride | Physical multisample row stride |
| destination stride | Single-sample row stride |
| sample mode | 1x, 2x, or 4x encoding |

The driver rejects overlapping source and destination ranges, validates both
two-dimensional bounds, translates them to DMA addresses, and builds a private
kernarg. The built-in program is not supplied by userspace and is audited to
access only those validated ranges.

Conceptually, the kernel performs:

```c
for each logical (x, y) {
    uint32_t *src = (uint32_t *)(source_bytes + y * source_stride +
                                 x * samples_per_pixel * 4);
    uint32_t *dst = (uint32_t *)(destination_bytes +
                                 y * destination_stride + x * 4);

    sum each RGBA channel over src[0 .. samples_per_pixel-1];
    dst[0] = pack((sum + samples_per_pixel / 2) / samples_per_pixel);
}
```

Because supported counts are powers of two, division is implemented with a
rounded right shift. Both programmed strides are used; tight packing is not
assumed. Dispatch may be one-dimensional internally, but every workgroup must
include its group offset when deriving the global pixel index.

### Scheduling and Fences

Resolve is a normal scheduler job with explicit resource ownership:

1. The multisample source BO is added as a read dependency.
2. The resolved destination BO is added as a write dependency.
3. An optional input sync object is honoured.
4. The completion fence is attached to the destination BO and optional output
   sync object.
5. KMS waits for that destination fence before switching `SCANOUT_BASE`.

This makes render-to-resolve and resolve-to-scanout ordering explicit even when
the two jobs use different hardware clients.

Resolve does not modify the multisample target, so later draws in the same
render pass continue using its per-sample contents. Reusing resolved pixels as
new multisample contents requires a separate expand operation that writes the
resolved colour to every sample; an ordinary linear copy is insufficient.

## Driver Behaviour

For an MSAA render submission, the driver:

1. Verifies the MSAA capability and requested `sampleMode`.
2. Validates the physical colour stride and allocation.
3. Allocates and clears a private depth buffer of `stride * height` bytes.
4. Writes `sampleMode` into the legacy snapshot or job descriptor word 9.
5. Submits the render job and publishes the colour-buffer write fence.
6. On request, submits the typed resolve job with the colour BO as input and a
   single-sample scanout BO as output.

The render API does not resolve implicitly after every draw. Applications or a
higher render-pass layer resolve only after the last draw that contributes to
the multisample target. Initially one render submission is one complete render
pass: all contributing draws must be in its command buffer. A later submission
starts with fresh private depth and cannot continue that pass. Cross-submission
continuation requires persistent depth attachments and explicit load/clear
operations, which are not part of the initial implementation.

## Performance

Coverage evaluation grows with the configured elaboration-time maximum, while
shading remains per pixel. Per-sample framebuffer traffic grows approximately
with the active sample count:

- 2x: up to twice the colour/depth RMW traffic.
- 4x: up to four times the colour/depth RMW traffic.

Only set coverage bits are expanded, so edge pixels avoid operations for
uncovered samples. Fully covered interior pixels still perform all sample
writes; framebuffer compression and single-value broadcast are future work.

The in-flight table and single OM memory port are likely throughput limits.
Before increasing table depth, measure slot occupancy, address conflicts, and
memory-port utilisation under 2x and 4x workloads.

## Implementation Phases

### Phase 1: ABI and State Plumbing

Status: landed. `sampleMode` is programmable end-to-end (legacy register and job
ring); mode 0 remains bit-identical on every path.

- [x] Reserve `MSAA_CONFIG` at `0x134`, bound the unified-command address route,
      and extend the overall mapped register end to `0x138`.
- [x] Add capability bit 7 and maximum-mode bits 17:16 without changing the
      fragment-batch field. Advertising is gated to fixed-function builds.
- [x] Add `sampleMode` to the legacy snapshot, job descriptor word 9,
      `JobConfig`, `DrawRenderState`, and `DrawContext`.
- [x] Extend the render UAPI and driver validation for physical stride and
      buffer size.
- [x] Keep mode 0 bit-identical with existing submissions.

### Phase 2: Coverage and Depth

1. Add mode-specific fixed-point sample-position lookup tables.
2. Produce a correctly sized coverage mask from centre edges and gradients.
3. Conservatively expand and clamp the raster bounding box.
4. Compute per-triangle depth gradients and per-sample depths with defined
   precision and rounding.
5. Extend scalar pixels, quad lanes, and fixed-function texturing payloads.

### Phase 3: Programmable Fragment Path

1. Carry coverage masks and sample depths through `KernelFragStage` records.
2. Extend the output-control word with the depth-override bit.
3. Preserve four pixel lanes per quad and one shader invocation per pixel.
4. Verify helper lanes and `vquad.dfdx/dfdy` in every sample mode.

### Phase 4: Sample Expansion and Output Merge

1. Add a backpressured post-shader sample expander.
2. Add `sampleIndex` to `OmFragment`.
3. Use physical stride and decoded sample addressing.
4. Serialize conflicts for the same sample while allowing different samples in
   flight.
5. Verify depth, blending, and draw-order behaviour independently per sample.

### Phase 5: Resolve

1. Add the typed resolve scheduler job and validated source/destination BOs.
2. Add the driver-owned trusted resolve kernel and private kernarg.
3. Attach correct reservation and sync-object fences.
4. Connect resolved output to the existing KMS scanout path.

## Verification

### Unit Tests

1. Exact fixed-point sample LUT values for every mode.
2. Coverage masks for horizontal, vertical, diagonal, shared, and sub-pixel
   edges in both windings.
3. Bounding boxes where the pixel centre is outside but a sample is covered.
4. Per-sample depth against an unbounded-integer software reference, including
   rounding and D24 limits.
5. Sample-expander ordering and backpressure for sparse masks.
6. OM addresses for padded strides and every sample index.
7. Same-sample serialization and different-sample concurrency.
8. Resolve with padded source/destination strides and all channel extrema.
9. Invalid modes, undersized buffers, overflow, and overlapping resolve ranges.

### Integration Tests

1. 1x output remains bit-identical to the current renderer.
2. Shared-edge triangles own every sample exactly once, with no cracks or
   double blending.
3. A fragment shader runs once for a partially covered pixel.
4. Helper lanes and quad derivatives match 1x behaviour.
5. Shader discard clears the complete effective mask.
6. Interpolated sample depth and shader-overridden depth follow their distinct
   rules.
7. Source-over blending occurs once per covered sample.
8. Clear, render, resolve, fence, and scanout work through the queue/DRM path
   under ARTI and QEMU.
9. Switching 1x -> 4x -> 2x -> 1x across queued jobs does not leak state.

Existing single-sample tests remain mandatory and run with `sampleMode = 0`.
