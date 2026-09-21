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
| `gpu-system` | synthesis | 505.13 MHz | -979.70 ps | committed baseline |
| `strided-copy` | post-route | 505.06 MHz | -979.97 ps | committed baseline |
| `strided-copy-pipe9` _(dirty)_ | post-route | 976.34 MHz | -24.24 ps | descriptor cone pipelined; `vclk` output -263.79 ps keeps verdict FAIL |
| `gpu-system-routerpipe` _(dirty)_ | synthesis | 622.85 MHz | -605.53 ps | descriptor + router dispatch pipelined |
| `fma-lane` _(dirty)_ | synthesis | 934.68 MHz | -69.89 ps | FMA lane, four stages, carry-select completion add |
| `gpu-system-fma` _(dirty)_ | synthesis | 634.23 MHz | -576.72 ps | limiter the fill-engine completion path |
| `gpu-system-dmabytes` _(dirty)_ | synthesis | 672.47 MHz | -487.05 ps | + registered DMA byte accumulator |
| `gpu-system-cmdpipe` _(dirty)_ | synthesis | 694.39 MHz | -440.11 ps | + pipelined command-processor dispatch; limiter now L2 fill write data |

`gpu-system` is the bounded integrated top (`GpuHostSystemAxi`).
`strided-copy` routes cleanly (DRC/antenna 0, hold clean); its failing path
is flop-to-flop descriptor address arithmetic, not routing.

The descriptor, router-dispatch, command-processor dispatch and FP32 FMA
cones are pipelined, and the DMA byte accumulator no longer sits behind the
completion arbiter. At the integrated top the binding path has moved to the
L2 fill write-data path, so 1 GHz is not met. `GpuCommandProcessor.queued`
now reports queue occupancy only (commands held in the dispatch pipeline are
not counted).

The 25% IO budget is a placeholder until the enclosing SoC supplies real
parent-interface budgets. Until then, internal `core_clock` is the only
transferable claim — and it is not met at 1 GHz.

## Next

1 GHz is an objective, not a milestone. Next RTL lever: the command-router
completion arbiter grant path (`completionEvents`), which currently binds
the integrated top. Derive real parent IO budgets in parallel.
