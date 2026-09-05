# ASAP7 PPA status

Reproducible flow: ChipAgent ASAP7 physical flow (ORFS), Yosys + ABC at
500 ps, no retiming, `sv_frontend=native`. Blocks are emitted with
`sbt -batch 'runMain opengpu.elaboration.EmitPpaRtl <block> <dir>'` and run
with `scripts/run_graphics_ppa.py` (env `GRAPHICS_PPA_TIMING_EFFORT` selects
`closure_no_cts` (default) or `explore`; the output directory suffix follows
the effort). Timing: 1.0 GHz target unless noted, TC corner.

Current state per block, 2026-09-04. Intermediate candidate/attempt history
has been pruned; only the latest closed result per block is kept.

## SharedL2Slice

1 GHz target met: LVT cells, `closure_no_cts` timing repair, targeted fanout
splitting of `storeTable.pendingEntry`.

| VT / effort | Core Fmax | Core slack | Area | Power |
|---|---:|---:|---:|---:|
| LVT closure_no_cts | 1069.09 MHz | +64.62 ps | 20329.2 um^2 | 125.067 mW |

Artifacts: `generated/ppa_runs/025_shared_l2_slice_lvt_closure_no_cts_pendingentry_tc_lvt_1ghz/`
(SUMMARY.md, flow_manifest.json, 6_final.odb/def). The ORFS GDS export step
fails in the local image because the KLayout merge artifact is not produced;
DEF/ODB and post-route SPEF STA remain valid.

## ScalarBackend / FPU / Vector pipeline blocks

Per-block closure at 1 GHz / TC / SLVT, Yosys no-retime recipe unless noted.
SRAM macro placement keeps a 10 um routing channel
(`timing/asap7/compute_unit_sram_macro_placement.tcl`).

| Block | Effort | Core Fmax | Worst setup slack | DRC |
|---|---|---:|---:|---:|
| ScalarBackend | LVT closure_no_cts | 1023.3 MHz | -28.4 ps | 57 |
| Fp32FmaLane | closure, no retime | 1284.9 MHz | +204.9 ps | 0 |
| FpuBackend | closure, no retime, density 0.60 | 1152.3 MHz | +19.7 ps | 0 |
| VectorFmaAlu | closure_no_cts, no retime | 1018.8 MHz | +18.4 ps | 0 |
| VectorFcvtAlu | closure_no_cts, no retime | 1124.1 MHz | +110.4 ps | 0 |

Artifact directories:

- `generated/ppa_runs/095_vectorfmaalu_tc_slvt_1ghz_yosys_noretime_closure/`
- `generated/ppa_runs/100_vectorfcvtalu_tc_slvt_1ghz_yosys_noretime_pipe5_closure/`
- `generated/ppa_runs/113_fp32fmalane_tc_slvt_1ghz_yosys_noretime_infsign_closure/`
- `generated/ppa_runs/114_fpu_backend_tc_slvt_1ghz_yosys_noretime_pipe5_infsign_closure_density60/`

## Vector submodules

Synthesis gate (SLVT, TC, no-retime, `abc_clock_period_ps=500`): all nine
submodules pass 1 GHz pre-layout.

Physical closure, closure/`closure_no_cts` with the square-grid SRAM layout
and the noted util/density, no retime:

| Block | Effort | Status | Worst setup slack |
|---|---|---|---:|
| VectorIntegerAlu | closure_no_cts, density 0.5 | PASS | +330.7 ps |
| VectorDivideAlu | closure_no_cts, density 0.5 | PASS | +296.7 ps |
| VectorMemoryUnit | closure_no_cts, density 0.5 | PASS | +243.5 ps |
| VectorConfigurationUnit | closure_no_cts, density 0.5 | PASS | +435.3 ps |
| VectorExecutionDispatch | - | N/A | combinational only |
| VectorMultiplyAlu | closure_no_cts, util 15, density 0.55 | PASS | +198.9 ps |
| VectorRegisterFile | closure, density 0.6 | PASS | +28.3 ps |
| VectorIssueStage | closure, density 0.6 | PASS | +68.4 ps |
| VectorRegisterManager | closure, util 25, density 0.6, 3x16 grid | PASS | +71.4 ps |

## Graphics blocks

Graphics emitters are available through `EmitPpaRtl`; physical runs use
ASAP7 TC/SLVT, 1 GHz, 25% utilization, density 0.60, no retiming. Emitted RTL
is under `generated/ppa_refresh_head/`, physical artifacts under
`generated/ppa_runs/head_*` (post-route unless noted).

| Block | Status | Result |
|---|---|---|
| CommandBufferStage (scene/scalar) | post-route PASS | 1779.42 MHz, +55.829 ps, 5412.91 um^2, 29.10 mW, DRC 0 |
| CommandBufferStage (vertex) | post-route PASS | 1859.66 MHz, +70.154 ps, 1828.67 um^2, 10.02 mW, DRC 0 |
| KernelFragStage | post-route PASS (explore) | 606.2 MHz, -783 ps, 39318.6 um^2, 383.27 mW, DRC 0 |

KernelFragStage request-queue registers: sequential cell count 29906
(+6.6% vs the pre-queue state), total instances 870016, IO-virtual-clock
Fmax 1421 MHz (> 1 GHz target, IO/ready boundary register closure holds),
0 DRC / 0 antenna. The critical path is now intra-core
(`prodSlot -> fragE0_0_27`), so the request/ready boundary registers are not
critical. Core clock is below the 1 GHz target; the next lever is splitting
the `prodSlot -> fragE0_0_27` data path.

`closure_no_cts` / `closure` note for large blocks: `repair_timing
-repair_tns 100` does not converge on KernelFragStage (~870k instances; WNS
plateaus and the stage spins indefinitely). Use `explore` for this scale.

## Whole-block physical-flow limit (current)

Whole-block `VectorBackend` (~404k cells, 48 SRAM macros) cannot close in the
flat local ORFS image: the worst path is always a virtual input port to a
block-internal capture register; the flat placer puts the capture register
near its consumer, far from the die edge, so the port wire stays long no
matter how many boundary/skid registers are added. This is a hierarchy /
top-level integration concern, not something closable by RTL pipelining
inside the block. Per-block closure (tables above) is the correct and
essentially complete methodology. Tooling supplied for this: the physical
flow supports `io_delay_percent` (relaxed boundary assumption) and
`io_false_path_ports` (emits `set_false_path -from` and `-to`).
