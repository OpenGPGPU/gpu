# ASAP7 PPA status

Current physical status of the checked-in RTL. Synthesis estimates,
register-only STA and post-route STA are separate claims: a completed tool
run is not timing closure.

## How to run

`scripts/qualify_ppa.py` emits RTL via `EmitPpaRtl`, then runs ChipAgent
ASAP7 (ORFS) with a real `clock` port (1000 ps target, TC, SLVT), explicit
SRAM `.lib`/`.lef`, and a 25% boundary delay budget. Each run directory
keeps `manifest.json`, `result.json` and ORFS artifacts. Exit code 2 means
the flow finished but the verdict is FAIL.

```sh
sbt -batch 'runMain opengpu.elaboration.EmitPpaRtl gpu-system generated/qualification/rtl/gpu-system'
python3 scripts/qualify_ppa.py generated/qualification/rtl/gpu-system GpuSystem \
    --output generated/qualification/ppa/gpu-system --stage synthesis
python3 scripts/qualify_ppa.py generated/qualification/rtl/strided-copy StridedCopyEngine \
    --output generated/qualification/ppa/strided-copy --stage route
```

## Current results

All runs: 1000 ps target, TC, SLVT, 25% boundary-delay budget. `synthesis`
is pre-layout STA on the synthesized netlist; `route` is post-route STA.
Rows marked _(dirty)_ were measured with uncommitted RTL; re-run from a
clean commit before treating them as the baseline.

| Run | Scope | Core Fmax | Worst setup | Note |
|---|---|---:|---:|---|
| `gpu-system-rowbase` | synthesis | 781.65 MHz | -279.34 ps | divide finalize + resolve row bases; limiter FMA `csaSumReg` |
| `gpu-system-csaccum` | synthesis | 760.84 MHz | -314.33 ps | carry-save perf counters (`a882bdb`); limiter vector divide |
| `gpu-system-fma5` | synthesis | 755.96 MHz | -322.83 ps | five-stage FMA; limiter DMA byte counter |
| `gpu-system-20d0732` | synthesis | 701.98 MHz | -424.55 ps | prior baseline (four-stage FMA) |
| `gpu-system` | synthesis | 505.13 MHz | -979.70 ps | older committed baseline |
| `strided-copy` | post-route | 505.06 MHz | -979.97 ps | committed baseline |
| `strided-copy-pipe9` _(dirty)_ | post-route | 976.34 MHz | -24.24 ps | descriptor cone pipelined; `vclk` output -263.79 ps keeps verdict FAIL |
| `gpu-system-routerpipe` _(dirty)_ | synthesis | 622.85 MHz | -605.53 ps | descriptor + router dispatch pipelined |
| `fma-lane` _(dirty)_ | synthesis | 934.68 MHz | -69.89 ps | FMA lane, four stages, carry-select completion add |
| `fma-lane-stage5` | synthesis | 929.70 MHz | -75.62 ps | five stages: add cut from invert/mask-valid |
| `gpu-system-fma` _(dirty)_ | synthesis | 634.23 MHz | -576.72 ps | limiter the fill-engine completion path |
| `gpu-system-dmabytes` _(dirty)_ | synthesis | 672.47 MHz | -487.05 ps | + registered DMA byte accumulator |
| `gpu-system-cmdpipe` _(dirty)_ | synthesis | 694.39 MHz | -440.11 ps | + pipelined command-processor dispatch |

`gpu-system` is the bounded integrated top (`GpuHostSystemAxi`).
`strided-copy` routes cleanly (DRC/antenna 0, hold clean); its failing path
is flop-to-flop descriptor address arithmetic, not routing.

The descriptor, router-dispatch, command-processor dispatch and FP32 FMA
cones are pipelined (FMA now five stages: completion add on `fire_reg0b`,
invert/LZD-mask/mask-valid on `fire_reg0c`). GpuSystem and L2 performance
counters keep carry-save state (`CarrySaveEventCounter` /
`CarrySaveAccumulator`); the DMA byte delta stays registered before the
carry-save add. Signed divide/remainder finalizes one cycle after the last
radix-2 iteration, and MSAA resolve advances addresses with scanline row
bases instead of `y * stride`. Integrated synthesis STA with that RTL
reaches **781.65 MHz** (−279.34 ps). The binding path is again
`computeUnits_*.core.vector.fmaAlu.lanes_*.core.csaSumReg` (FMA). Hold
paths still name `l2.slices_*.fillWriteData` into the data SRAM write
port. `GpuCommandProcessor.queued` reports queue occupancy only (commands
held in the dispatch pipeline are not counted).

The 25% IO budget is a placeholder until the enclosing SoC supplies real
parent-interface budgets. Until then, internal `core_clock` is the only
transferable claim — and it is not met at 1 GHz.

## Next

1 GHz is an objective, not a milestone. Next RTL lever: further cut the
FP32 FMA `csaSumReg` cone that again binds the integrated top at ~782 MHz.
Derive real parent IO budgets in parallel (carry-save observation CPA still
burns `vclk` slack on perf ports).
