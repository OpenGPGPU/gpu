# KernelFragStage LOD / gradient PPA experiment (2026-09-12)

Starting source: `e37318b` (includes MSAA coverage fields). All runs use
ASAP7 TC / SLVT, 1000 ps, Yosys native SV, no retiming, ABC 500 ps,
utilization 25%, placement density 0.60, timing effort `explore`.

## Changes

- `TextureUnit`: replace `min(mipLevel, texMaxLevel) < texMaxLevel` with
  `mipLevel < texMaxLevel` when capturing the fractional blend weight.
  This is exact for every pair of 4-bit unsigned inputs and adds no latency.
- `TexSampleUnit`: take the maximum of the four unsigned U differences and
  the maximum of the four V differences before multiplying by width/height.
  The unsigned 32x14 products fit in 46 bits, so maximum commutes with each
  common nonnegative extent multiplier without rounding or overflow.
  Preserve the two-stage split multiply (16x14 partial products followed by
  recombination), and move the two pairwise-max stages ahead of it. The FSM
  still takes the same number of cycles from request acceptance to sampling.
  Partial multipliers drop from 16 to 4; pipeline register bits drop by 720.

The existing five-bit signed `automaticMip + lodBiasReg` arithmetic is
unchanged, including wraparound for a result above +15. The reference test
models this explicitly; widening the bias arithmetic is a separate fix.

## Controlled synthesis comparison

| Variant | Cell area (um^2) | DFFs | Full adders | Setup slack (ps) | Core Fmax (MHz) |
|---|---:|---:|---:|---:|---:|
| Current MSAA control | 25332.00642 | 34815 | 5693 | +148.474 | 1174.362 |
| LOD comparison only | 25296.78114 | 34815 | 5693 | +123.207 | 1140.520 |
| LOD comparison + max before multiply | 23720.37696 | 34095 | 3707 | +125.353 | 1143.318 |

The combined change saves 1611.62946 um^2 (6.362%) versus the current control.
These are synthesis-only results, not post-route PPA or power estimates.
All three synthesis critical paths are in the sampler's `levelW -> tapAddrs`
address calculation. The LOD comparison alone does not improve synthesis
Fmax; the physical result is needed to assess the historical execSlot path.

The historical `emitquadsel` post-route result predates the current MSAA
fields. Its synthesis DFF count was 34523 versus 34815 in the current control,
so it is not used as the controlled area baseline.

## Artifacts and reproducibility

Emitted RTL is under `generated/ppa_refresh_head/`:

- `kernel_frag_stage_lodcmp_snapshot`: LOD comparison only, original gradients.
- `kernel_frag_stage_control_msaa`: copy of that emitted RTL with the single
  blend predicate restored to `selectedLevel < io_texMaxLevel`, matching HEAD.
  `CONTROL.md` in the directory records this one-line SV control construction.
- `kernel_frag_stage_lodmax_synth`: both source changes.
- `kernel_frag_stage_lodmax_route`: identical copy for full physical flow.
- `kernel_frag_stage_control_msaa_route`: identical control copy for full flow.

Run directory pattern:
`generated/ppa_runs/head_<RTL-directory-name>_tc_slvt_1ghz_explore_u25_d60/`.
Each includes the flow manifest, netlist, logs and reports. The first three
are synthesis-only; the last two are physical runs.

To regenerate the combined candidate from the source:

```sh
sbt -batch 'runMain opengpu.elaboration.EmitPpaRtl kernel-frag-stage generated/ppa_refresh_head/kernel_frag_stage_lodmax'
GRAPHICS_PPA_TIMING_EFFORT=explore /Users/duckdonald/workspace/chipagent/.venv/bin/python scripts/run_graphics_ppa.py generated/ppa_refresh_head/kernel_frag_stage_lodmax KernelFragStage
```

## Validation

- LOD-only: all 23 existing tests in TextureUnitSpec, TexSampleUnitSpec and
  KernelFragStageSpec passed.
- Combined candidate: all 11 KernelFragStageSpec tests passed, including
  ping-pong slot reuse, backpressure, texture sampling and draw retirement.
- TexSampleUnitSpec: all four cases passed after matching the reference's
  bias arithmetic width to the existing implementation. The first new-test
  run failed on reference arithmetic overflow, not the gradient reorder.
- Added full-width gradient test: reference computes all eight full products
  before maximum reduction. Covers U/V maxima, unequal and maximum extents,
  half-product boundaries, zero/full-width differences, inactive lanes,
  consecutive requests, source changes after acceptance, memory stalls and
  held output. Final boundary extension validation is recorded in
  `generated/kernel_frag_stage_gradient_reference.log`.
- TextureUnit trilinear test now includes requested mip 15 above max 2 with
  fraction 255; passes and confirms suppression of the next-level blend.

## Physical results

Pending: candidate and current-MSAA control are running the same full
`explore` recipe. Do not treat synthesis timing or area as post-route closure.
