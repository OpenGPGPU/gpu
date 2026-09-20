# MSAA (Multi-Sample Anti-Aliasing) Design

## Status and Goals

This document defines a 1x, 2x, and 4x MSAA path for the existing graphics
pipeline. The RTL implements sample coverage, depth generation, sample
expansion and per-sample output merging; Linux exposes both fixed-function and
programmable (fragment-core) MSAA submission, physical-stride validation, and a
typed resolve through the unified command path. Both backends advertise
`GPU_CAP_MSAA`; the backend is the one selected by `GPU_FRAGMENT_CORE`. Bit 7 is
a functional claim only - physical timing closure of the programmable fragment
stage is a separate PPA gate, not asserted here. Sections below distinguish the
implemented path from the remaining PPA work.

Source references: `Msaa.scala`, `Rasterizer.scala`,
`FragmentInterpolator.scala`, `KernelFragStage.scala`, `RenderPipeline.scala`,
`OutputMerger.scala` and `RenderHost.scala` under
`src/main/scala/opengpu/graphics/`, plus `driver/opengpu_compute.c`.

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
and centroid interpolation remain out of scope. The depth word is D24S8:
stencil rides bits [31:24] of the same word and ships alongside the GL-style
blend configuration; see [STENCIL_BLEND_DESIGN.md](STENCIL_BLEND_DESIGN.md).

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
rejected by the driver. Legacy START and the raw job queue check the full
sample-mode word against the elaborated maximum, including reserved bits 31:2.
START reports `STATUS.ERROR` without launch. An invalid queued descriptor
produces an ordered DONE|ERROR IH record with status 1 without rendering;
subsequent valid jobs continue after the error record's write acknowledgements.
Programmable-MSAA advertising follows the same validated-mode rule as the
fixed-function build.

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
requires `subPixelBits >= 2` in the depth-gradient module to represent the
selected pattern exactly.

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
allocations. The driver clears every private depth sample to `0x00ffffff`
(stencil 0 under the far 24-bit depth) before rendering.
The caller must initialize the colour samples, including uncovered edge
samples, to the intended render-pass background; render submission does not
implicitly clear the colour buffer.

## ABI

### Capabilities

The existing `CAPABILITIES[15:8]` fragment-batch-capacity field is unchanged.
MSAA uses the following fields:

| Bits | Meaning |
|---|---|
| 7 | MSAA supported on this build's render path |
| 17:16 | Maximum supported `sampleMode`: 0=1x, 1=2x, 2=4x |

Software tests bit 7 before programming MSAA state and rejects a requested mode
above bits 17:16. Bit 7 is backend-parametrised: bit 7 with `FRAGMENT_CORE`
(bit 0) set is programmable MSAA, bit 7 with bit 0 clear is fixed-function MSAA.
Both builds advertise the field, and both advertise the same maximum mode.

### Register-Programmed Submission

The unified command bank already occupies `0xC4` through `0x130`. MSAA state is
placed after it:

| Offset | Name | Access | Description |
|---|---|---|---|
| 0x134 | MSAA_CONFIG | RW | bits `[1:0]` are `sampleMode`; all other bits reserved-zero |

There is no separate enable bit: mode 0 is the disabled/1x state. There are no
MSAA resolve-PC or resolve-kernarg registers; resolve uses the existing unified
kernel command interface.

`MSAA_CONFIG` is a legacy shadow; unified renders take mode from descriptor
word 9. An invalid mode or a mode above the elaborated maximum reports a
submission error rather than falling back.

`GpuHostAxi` routes `[0xC4, 0x134)`, safe-reset at `0x138` and resolve
`UCMD_SAMPLE_MODE` at `0x148` to `GpuCommandMmio`. `0x134` and stencil/blend
at `0x13C`–`0x144` route to `RenderHost`. Mapped end is `0x158`.

### Unified descriptor and Linux UAPI

- Render descriptor word 9 bits `[1:0]` carry `sampleMode`; bits `[31:2]` are zero.
- `JobConfig`, `DrawRenderState`, and `DrawContext` carry `sampleMode`.
- The render-submit UAPI exposes `sample_mode`; reserved bits must be zero.
- The driver publishes word 9 when it builds the unified render descriptor.

The driver uses the physical-stride validation in the memory layout section
instead of requiring the platform’s logical stride. The private depth
allocation and clear size are both
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

`RasterPixel` carries a fixed-width mask sized by the elaboration-time maximum:

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

`DepthGradient` multiplies signed 34-bit plane coefficients by signed 32-bit
depths, sums at the product width, shifts by `subPixelBits - 2`, and divides
by signed triangle area. Division truncates toward zero; the result retains
the low 32 bits. This is a shared combinational calculation from registered
triangle state, not a multiplier pair in every interpolator lane. `SampleDepth`
uses widening signed additions and clamps multisample results to
`[0, 0xffffff]`; 1x retains the centre depth's low 24 bits.

`RasterFragment` carries `coverageMask`. Per-sample depths travel on
`RasterShader.depthSamples` / `quadDepths` sidebands and in `KernelFragStage`
staging records, rather than as a field of `RasterFragment`. Colour and UV
remain evaluated at the pixel centre.

The fragment ABI is deliberately coupled to validated sample mode. The named
profiles in `FragmentShaderAbi.scala` and `GPU_FRAGMENT_ABI_*` in
`driver/gpu_abi.h` define the contract:

| Sample mode | Fragment ABI | Output-control interpretation |
|---|---|---|
| 0 (1x) | 0, legacy | Any nonzero 32-bit word emits; shader depth is always selected |
| 1 or 2 (2x/4x) | 1, multisample | Bit 0 emits; bit 1 selects shader depth; high bits must be zero |

There is no independent selector in MMIO, job records or Linux UAPI. An
independent profile for 1x would require a separately versioned extension;
it is not a prerequisite for the current mode-coupled ABI. Fixed-function
rendering has no fragment output-control word. Programmable MSAA is advertised
on fragment-core builds, so Linux's shader path uses ABI 0 at mode 0 and ABI 1
for the validated nonzero modes.

`KernelFragStage` snapshots the selector with the first accepted quad of each
batch. Later draws and live input changes cannot reinterpret an executing
batch; ping-pong slots retain their own selectors.

ABI 1 interprets the fragment kernarg output-control word as follows:

- bit 0: emit pixel (`0` means discard).
- bit 1: shader depth override is valid.
- bits 31:2: reserved-zero. A nonzero reserved bit discards the pixel, even
  with bit 0 set; the draw still retires normally and no job error is raised.
  This rule is exclusive to ABI 1: ABI 0 preserves all nonzero values,
  including `0x80000000`, as emit requests.

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
abi1Emit = outputControl[31:2] == 0 && outputControl.bit0
emit = ABI1 ? abi1Emit : outputControl != 0
effectiveMask = covered && emit ? coverageMask : 0
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
           existing.depthAddr == incoming.depthAddr ||
           existing.colorAddr == incoming.depthAddr ||
           existing.depthAddr == incoming.colorAddr
```

Different samples of one pixel have different addresses and may occupy
different in-flight entries. Fragments for the same sample remain serialized,
preserving draw order. The initial implementation keeps the current table
depth; increasing it is a measured optimisation, not a correctness dependency.

## Resolve (implemented)

`GPU_UCMD_OP_RESOLVE` carries a `ResolveDescriptor` through `GpuCommandRouter`
to `MsaaResolveEngine`. Unified MMIO stages source/destination, extent,
strides and `UCMD_SAMPLE_MODE` (0x148). Driver range rules live in
`opengpu_resolve_validator.h`; UAPI is `DRM_IOCTL_OPENGPU_RESOLVE` via the
common scheduler with source-read / destination-write reservations. KMS
orders scanout on the destination write fence. Depth resolve is out of scope.
Remaining work is broader guest coverage and physical closure of the
programmable fragment stage (capability bit 7 is functional only).

Resolve averages every pixel's physical colour samples into a separate
single-sample RGBA8888 buffer.

A source normally comes from a GPU-written render target. CPU-initialized
sources are tolerated via host-driven shared-L2 line invalidate before the
engine reads (write-through makes lower memory authoritative).

### Resolve contract

Resolve is a normal scheduler job: source BO read dep, destination BO write
dep, optional input syncobj, completion fence on the destination (and optional
output syncobj). KMS waits on that fence before scanout. Resolve does not
modify the multisample target; expanding resolved colour back to every sample
needs a separate expand, not a linear copy.

The initial backend is the dedicated resolve engine. A trusted kernel with the
same validated ranges and fence contract remains an allowed alternate backend;
userspace does not supply the program.

## Driver Behaviour

For an MSAA render the driver verifies capability and `sampleMode`, validates
physical colour stride, allocates/clears a private depth plane (or binds a
persistent depth attachment), writes mode into descriptor word 9, submits and
publishes the colour write fence, then optionally submits a typed resolve to a
single-sample scanout BO. Resolve is never implicit after every draw.

Persistent depth: `drm_opengpu_submit.depth_handle`/`depth_offset` plus
`OPENGPU_SUBMIT_DEPTH_LOAD` continue a pass across submissions
(`OPENGPU_CAP_PERSISTENT_DEPTH`, bit 19). First submission may clear; later
ones load. FlashSim does not yet expose the prior depth write; Verilator does.

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

## Implementation Status

| Area | Current state | Remaining work |
|---|---|---|
| ABI/state | MSAA register, capabilities on both backends, validated queue word 9 with ordered error completion and Linux physical-stride checks implemented | None functional; bit 7 asserts function only, not timing closure |
| Coverage/depth | Mode-specific LUT, scalar/quad masks, expanded bounds, shared triangle gradients and sample depth implemented | Broaden edge and precision regression coverage |
| Programmable fragment path | Coverage/depth staging, ABI-1 output-control interpretation and per-pixel shading implemented, advertised through bit 7 on fragment-core builds, and exercised by the guest MSAA round trip | Physical timing closure of the fragment stage (separate PPA gate) |
| Expansion/OM | Backpressured expander, sample addresses, address-hazard ordering and acknowledged write drain implemented | Broader integrated multisample regressions |
| Persistent depth | Caller-owned depth attachment (`drm_opengpu_submit.depth_handle`/`depth_offset`), `OPENGPU_SUBMIT_DEPTH_LOAD` cross-submission continuation, driver range validation (`opengpu_depth_validator.h`, unit-tested) and the `OPENGPU_CAP_PERSISTENT_DEPTH` advertisement implemented; `RenderHostSpec` covers a queued two-submission pass and the guest DRM test repeats it under Verilator | Broader ARTI multisample pass-continuation coverage; FlashSim does not yet expose the prior depth write |
| Resolve | `MsaaResolveEngine` backend, the `GPU_UCMD_OP_RESOLVE` router path through the shared L2, unified MMIO staging (`UCMD_SAMPLE_MODE`), the validated range rules and command builder, the `DRM_IOCTL_OPENGPU_RESOLVE` UAPI, the scheduler-backed ioctl with source read / destination write reservations and output syncobj, and KMS ordering via the standard implicit-sync path | Broader ARTI resolve coverage |

## Verification

Covered by `MsaaSpec`, `MsaaResolveSpec`, `FragmentShaderAbiSpec`,
`KernelFragStageSpec`, `RenderPipelineSpec`, `RenderCoreSpec`,
`OutputMergerSpec`, `RenderHostSpec`, `GpuHostAxiSpec`,
`GpuHostSystemAxiSpec`, host resolve/depth validators, and the guest DRM
MSAA render → resolve → scanout path. Focused run:

```sh
sbt 'testOnly opengpu.graphics.FragmentShaderAbiSpec opengpu.graphics.MsaaSpec \
  opengpu.graphics.MsaaResolveSpec opengpu.graphics.OutputMergerSpec \
  opengpu.graphics.KernelFragStageSpec opengpu.graphics.RenderPipelineSpec \
  opengpu.graphics.RenderCoreSpec opengpu.graphics.RenderHostSpec \
  opengpu.graphics.GpuHostAxiSpec'
```
