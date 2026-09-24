# ASAP7 PPA status

Current physical status of the checked-in RTL. Synthesis estimates,
register-only STA and post-route STA are separate claims: a completed tool
run is not timing closure.

## How to run

Emit RTL via `EmitPpaRtl`, then run `scripts/qualify_ppa.py` with ChipAgent
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
| `gpu-system-fsqrtpost` _(dirty, rejected)_ | synthesis | 873.75 MHz | -144.49 ps | Extra sqrt result stage regressed integrated timing; source differs from `wgmul` only in `Fp32SqrtLane` |
| `fma-lane-cspipe` _(dirty)_ | synthesis | **1193.57 MHz** | **+162.17 ps** | FMA CS partials registered before assemble; **PASS** |
| `gpu-system-wgmul` _(dirty)_ | synthesis | 905.83 MHz | -103.97 ps | + workgroup Z mul split; limiter `fsqrtAlu.quotient` → `result` |
| `gpu-system-writeuse` _(dirty)_ | synthesis | **912.80 MHz** | -95.53 ps | + resolve sWriteUse; limiter `taskLocalSize2` → `workgroups.state` |
| `gpu-system-copyack` _(dirty)_ | synthesis | 861.87 MHz | -160.27 ps | + CopyEngine byte-add queue; limiter still `l2.request_address` → `resolveEngine.y` |
| `gpu-system-resolveresp` _(dirty)_ | synthesis | 804.45 MHz | -243.09 ps | resolve resp stage regressed; limiter `l2.request_address` → copy `bytesCopied` |
| `gpu-system-copybytes` _(dirty)_ | synthesis | 894.12 MHz | -118.42 ps | + delayed strided byte add; limiter `l2.request_address` → `resolveEngine.y` |
| `gpu-system-resolveprep` _(dirty)_ | synthesis | 868.65 MHz | -151.22 ps | + resolve sPrep addr; limiter `l2.request_address` → strided `bytesCopied` |
| `gpu-system-stridesum` _(dirty)_ | synthesis | 846.21 MHz | -181.74 ps | + strided sum lo/hi; limiter `resolveEngine.mode` → pending Memory |
| `gpu-system-joblast` _(dirty)_ | synthesis | 898.44 MHz | -113.05 ps | + JobDispatcher atLast flags; limiter `holdDstAddr` → `sumDstLine` |
| `gpu-system-resolveaddr` _(dirty)_ | synthesis | 870.99 MHz | -148.13 ps | + resolve shift addrs; limiter `jobs.command_gridSize_1` → `groupId_2` |
| `gpu-system-dmabytes2` _(dirty)_ | synthesis | 850.35 MHz | -175.98 ps | + cascaded CSA DMA bytes; limiter `resolveEngine.samples` address mul |
| `gpu-system-redfold` _(dirty)_ | synthesis | 824.68 MHz | -212.59 ps | + integerAlu fold stage; limiter `fillBytesFired` → `dmaBytesDelta[63]` |
| `gpu-system-coalpipe` _(dirty)_ | synthesis | 875.27 MHz | -142.51 ps | + coalescer pending; limiter was attributed to `integerAlu.reductionPairs` |
| `gpu-system-smulcs` _(dirty)_ | synthesis | 813.51 MHz | -229.24 ps | + cmdproc localSize pipe + mul CS cuts; limiter `vectorMemoryRouter` `elementSize` |
| `gpu-system-cspipe` _(dirty)_ | synthesis | 800.83 MHz | -248.70 ps | FMA cut; limiter `hold0Cmd_launch_localSize` |
| `gpu-system-cmdmul` _(dirty)_ | synthesis | 754.81 MHz | -324.84 ps | interim; limiter scalar mul CPA |
| `gpu-system-rowbase` | synthesis | 781.65 MHz | -279.34 ps | divide finalize + resolve row bases; limiter FMA `csaSumReg` |
| `gpu-system-csaccum` | synthesis | 760.84 MHz | -314.33 ps | carry-save perf counters (`a882bdb`); limiter vector divide |
| `gpu-system-fma5` | synthesis | 755.96 MHz | -322.83 ps | five-stage FMA; limiter DMA byte counter |
| `gpu-system-20d0732` | synthesis | 701.98 MHz | -424.55 ps | prior baseline (four-stage FMA) |
| `gpu-system` | synthesis | 505.13 MHz | -979.70 ps | older committed baseline |
| `strided-copy` | post-route | 505.06 MHz | -979.97 ps | committed baseline |
| `strided-copy-pipe9` _(dirty)_ | post-route | 976.34 MHz | -24.24 ps | descriptor cone pipelined; `vclk` output -263.79 ps keeps verdict FAIL |
| `gpu-system-routerpipe` _(dirty)_ | synthesis | 622.85 MHz | -605.53 ps | descriptor + router dispatch pipelined |
| `fma-lane` _(dirty)_ | synthesis | 934.68 MHz | -69.89 ps | FMA lane, four stages, carry-select completion add |
| `fma-lane-stage5` | synthesis | 929.70 MHz | -75.62 ps | five stages: add cut from invert/LZD-mask/mask-valid |
| `gpu-system-fma` _(dirty)_ | synthesis | 634.23 MHz | -576.72 ps | limiter the fill-engine completion path |
| `gpu-system-dmabytes` _(dirty)_ | synthesis | 672.47 MHz | -487.05 ps | + registered DMA byte accumulator |
| `gpu-system-cmdpipe` _(dirty)_ | synthesis | 694.39 MHz | -440.11 ps | + pipelined command-processor dispatch |

`gpu-system` is the bounded integrated top (`GpuHostSystemAxi`).
`strided-copy` routes cleanly (DRC/antenna 0, hold clean); its failing path
is flop-to-flop descriptor address arithmetic, not routing.

Standalone FP32 FMA now **meets 1 GHz** at synthesis (`fma-lane-cspipe`:
1193.57 MHz, +162.17 ps) after registering carry-select completion-add
partials on `fire_reg0b` and mux-assembling on `fire_reg0c` (stops Yosys
from flattening the CS structure into one long CPA).

Integrated `GpuSystem` still fails. The retained `gpu-system-wgmul` RTL is
905.83 MHz (−103.97 ps); the best measured intermediate is `writeuse` at
**912.80 MHz**. Hierarchical STA on `wgmul` names `fsqrtAlu.quotient`
through rounding into `result`. A post-iteration register was tested in
`gpu-system-fsqrtpost`, but it reduced integrated Fmax to 873.75 MHz and was
reverted. The two run manifests differ only in `Fp32SqrtLane.scala`.
Hold paths still name `l2.slices_*.fillWriteData`
into the data SRAM write port. `GpuCommandProcessor.queued` reports queue
occupancy only (commands held in the dispatch pipeline are not counted).

The 25% IO budget is a placeholder until the enclosing SoC supplies real
parent-interface budgets. Until then, internal `core_clock` is the only
transferable claim — and the integrated top is not met at 1 GHz.
Carry-save observation CPA still burns `vclk` slack on perf ports.

## Next

1 GHz remains the objective for the integrated top. Inspect the sqrt
quotient-to-result cone before trying another cut, then re-measure the
integrated top. Derive real parent IO budgets in parallel. These dirty-run
synthesis estimates need a clean committed baseline and post-route
qualification before making a physical closure claim.
