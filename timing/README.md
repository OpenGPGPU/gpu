# ASAP7 PPA status

Reproducible flow: ChipAgent ASAP7 physical flow (ORFS), Yosys + ABC at
500 ps, no retiming, `sv_frontend=native`. Blocks are emitted with
`sbt -batch 'runMain opengpu.elaboration.EmitPpaRtl <block> <dir>'` and run
with `scripts/run_graphics_ppa.py` (env `GRAPHICS_PPA_TIMING_EFFORT` selects
`closure_no_cts` (default) or `explore`; the output directory suffix follows
the effort). Timing: 1.0 GHz target unless noted, TC corner.

Current state per block, 2026-09-11. Intermediate candidate/attempt history
has been pruned; only the latest closed result per block is kept.

## SharedL2Slice

Not closed at 1 GHz. Two post-route attempts on record:

| Recipe | Core Fmax | Worst setup (all groups) | Worst hold | Area | Power | DRC |
|---|---:|---:|---:|---:|---:|---:|
| LVT closure_no_cts, `syn` engine, pendingEntry fanout split (no explicit io_delay) | 1069.09 MHz | -748.10 ps (1322 viol.) | -17.94 ps (4 viol.) | 20329.2 um^2 | 125.07 mW | 455 |
| LVT closure_no_cts, yosys no-retime, io_delay 20%, margin 50 ps, util 15 / density 0.30 | 986.36 MHz | -378.07 ps (1142 viol.) | -16.20 ps (12 viol.) | 20729.9 um^2 | 129.85 mW | 469 |

The `syn`-engine run closes the reg-to-reg core (+64.62 ps core slack) but
fails the virtual-IO groups wholesale; the yosys rerun fixes most of the IO
gap but its core path degrades (`missEngine.table_0.valid_1 ->
data_1.memory_6`, a miss-table register to SRAM-macro access, core slack
-13.8 ps) and GRT `repair_timing` plateaus at -91.6 ps WNS. Routing DRC is
structural (~455-469 errors both runs) at this util/density. Next levers:
pipeline/register the missEngine SRAM access path in RTL, and revisit the
SRAM macro channel.

Artifacts:

- `generated/ppa_runs/025_shared_l2_slice_lvt_closure_no_cts_pendingentry_tc_lvt_1ghz/`
- `generated/ppa_runs/head_shared_l2_slice_tc_lvt_1ghz_yosys_noretime_closure_u15_d30_margin50/`

The ORFS GDS export step fails in the local image because the KLayout merge
artifact is not produced; DEF/ODB and post-route SPEF STA remain valid.

## ScalarBackend / FPU / Vector pipeline blocks

Per-block closure at 1 GHz / TC / SLVT, Yosys no-retime recipe unless noted.
SRAM macro placement keeps a 10 um routing channel
(`timing/asap7/compute_unit_sram_macro_placement.tcl`).

| Block | Effort | Core Fmax | Worst setup slack | DRC |
|---|---|---:|---:|---:|
| ScalarBackend | closure_no_cts, no retime, util 30, density 0.50, margin 50 ps | 1433.9 MHz | +61.3 ps | 0 |
| Fp32FmaLane | closure, no retime | 1284.9 MHz | +204.9 ps | 0 |
| FpuBackend | closure, no retime, density 0.60 | 1152.3 MHz | +19.7 ps | 0 |
| VectorFmaAlu | closure_no_cts, no retime | 1018.8 MHz | +18.4 ps | 0 |
| VectorFcvtAlu | closure_no_cts, no retime | 1124.1 MHz | +110.4 ps | 0 |

ScalarBackend post-route result: 1433.94 MHz core Fmax, +61.33 ps setup /
+19.29 ps hold (all groups, zero violations), 7297.55 um^2, 63.51 mW,
DRC 0 / antenna 0. This replaces the older LVT `syn`-engine attempt
(`032_scalar_backend_tc_lvt_1ghz_closure`, -28.4 ps, DRC 57), which never
signed off.

Artifact directories:

- `generated/ppa_runs/head_scalar_backend_tc_slvt_1ghz_yosys_noretime_closure_u30_d50_margin50/`
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
| VectorIntegerAlu | closure_no_cts, util 25, density 0.60 | PASS | +5.08 ps |
| VectorDivideAlu | closure_no_cts, density 0.5 | PASS | +296.7 ps |
| VectorMemoryUnit | closure_no_cts, density 0.5 | PASS | +243.5 ps |
| VectorConfigurationUnit | closure_no_cts, density 0.5 | PASS | +435.3 ps |
| VectorExecutionDispatch | - | N/A | combinational only |
| VectorMultiplyAlu | closure_no_cts, util 15, density 0.55 | PASS | +198.9 ps |
| VectorRegisterFile | closure, density 0.6 | PASS | +28.3 ps |
| VectorIssueStage | closure, density 0.6 | PASS | +68.4 ps |
| VectorRegisterManager | closure, util 25, density 0.6, 3x16 grid | PASS | +71.4 ps |

VectorIntegerAlu post-route result: 1005.11 MHz core Fmax, 9826.54 um^2,
83.02 mW, zero hold violations, DRC errors, and antenna violations.
Artifacts: `generated/ppa_runs/head_ppa_scaling_pair_vector_integer_tc_slvt_1ghz_closure_u25_d60/`.

## Graphics blocks

Graphics emitters are available through `EmitPpaRtl`; physical runs use
ASAP7 TC/SLVT, 1 GHz, 25% utilization, density 0.60, no retiming. Emitted RTL
is under `generated/ppa_refresh_head/`, physical artifacts under
`generated/ppa_runs/head_*` (post-route unless noted).

The bounded complete integration tops are `gpu-host-system-fc` and
`gpu-host-system-vc`. They include the AXI host, fixed-function graphics,
shared shader CU, general compute CU, DMA engines and shared L2. Both use a
16x16 render target and reduced cache/L2 capacities so full-top synthesis can
be attempted without changing the product defaults. For example:

```sh
sbt -batch 'runMain opengpu.elaboration.EmitPpaRtl gpu-host-system-vc generated/ppa_refresh_head/gpu_host_system_vc'
scripts/run_graphics_ppa.py generated/ppa_refresh_head/gpu_host_system_vc GpuHostSystemAxi synthesis-only
```

| Block | Status | Result |
|---|---|---|
| CommandBufferStage (scene/scalar) | post-route PASS | 1779.42 MHz, +55.829 ps, 5412.91 um^2, 29.10 mW, DRC 0 |
| CommandBufferStage (vertex) | post-route PASS | 1859.66 MHz, +70.154 ps, 1828.67 um^2, 10.02 mW, DRC 0 |
| TriangleRasterizer | post-route PASS | 1011.01 MHz, +10.895 ps, 8185.10 um^2, 411.18 mW, DRC 0 |
| KernelFragStage | post-route FAIL on timing (explore), DRC clean | 995.2 MHz, core setup -4.9 ps (all groups -4.9 ps, vclk MET), hold -84.6 ps, 27148.8 um^2, 313.62 mW, DRC 0 |

KernelFragStage request-queue registers: sequential cell count 29906
(+6.6% vs the pre-queue state), total instances 870016, IO-virtual-clock
Fmax 1421 MHz (> 1 GHz target, IO/ready boundary register closure holds),
0 DRC / 0 antenna. Two pipeline stages have been added on the staging path
(10/10 KernelFragStageSpec tests pass after each):

1. Producer write pipe: quad + resolved slot + pre-computed lane indices
   captured at accept, slot arrays written one cycle later. Removed the old
   `prodSlot -> fragE0_0_27` critical path (606.2 -> 667.4 MHz,
   `head_kernel_frag_stage_wp_tc_slvt_1ghz_explore_u25_d60/`).
2. Consumer word-request register: a 1-deep queue between the staging FSM
   and the word->line bridge. Removed the `index -> execLane/field mux ->
   bridge -> wordMemReqPipe.ram` critical path (667.4 -> 777.2 MHz, setup
   violations 7594 -> 4977,
   `head_kernel_frag_stage_wp2_tc_slvt_1ghz_explore_u25_d60/`).

The `wp2` critical path was the write-pipe drain (`wpLaneIdx(2) ->
fragE0_0_8`, 1262 ps / 18 cells, ~50% net delay): the per-entry write
decode fans out over 2 slots x 32 entries x 10 arrays spread across the
die.

The 2026-09-09 RTL candidate replaces the binary lane indices with registered
one-hot slot/quad-group write strobes and statically indexed array writes.
The strobes are decoded at quad acceptance, alongside the existing data
registers, preserving the one-cycle producer write latency and one quad per
cycle throughput. Its full `explore` physical result is now available under
`generated/ppa_runs/head_kernel_frag_stage_onehot_tc_slvt_1ghz_explore_u25_d60/`:
724.69 MHz core Fmax, -379.90 ps core setup slack, -78.69 ps hold slack,
24,937 um^2 area, 304.01 mW, and zero DRC/antenna errors. This is 52.47 MHz
slower than the 777.16 MHz `wp2` baseline; the critical path moved to
`index -> wordReqQ.ram[31]`, so the one-hot write change did not address the
dominant post-route path.
Emitted RTL is under `generated/ppa_refresh_head/kernel_frag_stage_onehot/`.
Validation: all 10 existing KernelFragStageSpec cases pass, as does a new
case covering consecutive quad acceptance, full batches, both slots and
slot reuse, stalled memory/output, and per-lane staging/edge preservation.

The packed-record follow-up removes the one-entry `wordReqQ` and stores each
2x2 quad as one producer-side record, while selecting consumer fields
individually to keep FIRRTL legal. Its full physical result is
807.91 MHz, -237.77 ps core setup slack, -83.04 ps hold slack, 26,245 um^2,
324.65 mW, and zero DRC/antenna errors. This improves on the one-hot result
by 83.22 MHz and moves the critical path to `index[2] -> wordReqReg_data[16]`.

The subsequent micro-op register experiment is complete and regresses to
711.60 MHz (core setup -405.29 ps, hold -87.32 ps, 26,228 um^2,
318.35 mW, DRC/antenna 0). Its critical path is `index[3] ->
microReqReg_data[18]` at 1383.94 ps, showing that an extra register only moves
the existing field/index mux cone; it does not reduce the cone. The packed
record version remains the best measured implementation.

The next selector-register experiment keeps separate quad and lane selectors
for the staging request data path, advancing them with each word response so
`index` no longer drives the packed-record muxes. It reaches 846.21 MHz,
-181.75 ps core setup slack, -87.13 ps hold slack, 26,590 um^2, 311.21 mW,
and zero DRC/antenna errors. The critical path is now
`requestQuadIdx[1] -> wordReqReg_data[3]`.

The selector-quad experiment captures the whole 4-lane record (`requestQuad`)
into a register before issuing its words, so the dynamic record-array select
leaves the word-request data path. It measures 864.12 MHz core Fmax, -157.25 ps
core setup slack, 332.43 mW, DRC 0 — above `selectors` but below the
output-register variants described next.

Registering the output port (`outreg`) removes the emit lane-select mux from
the top-level output cone: 898.15 MHz core Fmax, -113.40 ps core setup slack,
-101.63 ps hold, 327.93 mW, DRC 0. Rerunning with the request-record capture
reverted (the register-only `outreg2` variant) reaches 937.11 MHz, -67.11 ps
core setup slack, -100.70 ps hold, 325.40 mW, DRC 0.

The split emit-cache variant (`emitquad`) captures a whole quad of emit payload
into a register selected by the batch-derived index and improves to 928.76 MHz,
but the cache-fill select is still `index`-derived. Replacing that select with a
dedicated registered counter (`emitptr`) keeps the increment and the
`index`-derived mux off the wide cache-fill read and collapses the per-cycle
output selection to a 4:1 mux over the 2-bit lane index. It reaches 951.78 MHz
core Fmax, -109.51 ps setup / -106.98 ps hold (all groups; core
critical-path slack -50.67 ps), 27,538.4 um^2, 320.63 mW, and zero DRC/antenna
errors. The critical path is now `requestQuadIdx[2] ->
requestQuad_lanes_1_depth[6]`. All 11 KernelFragStageSpec cases pass. Emitted
RTL is under `generated/ppa_refresh_head/kernel_frag_stage_emitptr/`.

The `emitptr` critical path exposed the real limit of the staging read: the
binary `requestQuadIdx` bit fanned out to every field mux of every quad entry
(8 quads x 4 lanes x ~10 fields, ~4k loads), so the resizer built a chain of
~10 BUFx16f cells (~856 ps) between the register and the mux. Replacing the
binary read index with a registered one-hot selector (`requestQuadSel`, shifted
once per quad and re-armed per write burst) gives each entry its own select net,
cutting the fanout per net by the entry count; `execSlot` moves after the
one-hot mux so it drives one load per output bit. This `onehotread` variant
reaches 997.72 MHz core Fmax, -2.28 ps core setup slack (all groups -117.08 ps,
the virtual-IO path), -104.81 ps hold, 26,968.6 um^2, 295.52 mW, and zero
DRC/antenna errors. Setup violations drop 56 -> 2 and setup TNS -832 -> -119 ps,
a +45.9 MHz gain that essentially reaches the 1 GHz target on the core clock.
The staging path no longer appears in the timing report at all: the new core
critical path is inside the texture unit
(`texUnit.texWidthReg[4] -> texUnit.gradReg_1[44]`, 37 cells, 84.8% cell delay),
so the remaining core headroom is in the sampler's gradient path, not the
fragment staging. All 11 KernelFragStageSpec cases pass. Emitted RTL is under
`generated/ppa_refresh_head/kernel_frag_stage_onehotread/`.

The `gradsplit` variant pipelines the texture-unit extent multiply in
`TexSampleUnit`, which was the new core critical path after `onehotread`. The
32x14 gradient product (`diffReg(i) * texWidthReg/texHeightReg`) sat in one
cycle; its post-route path ran `texWidthReg[4]` through a 6-deep BUFx16f fanout
chain (~286 ps), a ~7-stage FA carry chain plus one HAxp5 (~440 ps), and a
prefix-adder tail (~200 ps) into `gradReg[44]`. Splitting the 32-bit difference
into two 16-bit halves gives two 16x14 partial products per entry (registered in
`gradLoReg`/`gradHiReg`, exact: `diff = diffLo + diffHi*2^16`) that recombine into
`gradReg` one cycle later in a new `sAdd` FSM state. It reaches 999.82 MHz core
Fmax, -0.18 ps core setup slack (all groups -103.44 ps), -107.67 ps hold,
27,428.4 um^2, 320.31 mW, and zero DRC/antenna errors: setup TNS improves
-119.36 -> -103.62 ps and the virtual-IO all-groups worst path gains 13.6 ps.
Cost is +459.8 um^2 and +24.8 mW for the extra partial-product registers and
recombine adder. The texture gradient path is no longer in the timing report;
the new core critical path is the emit-cache fill mux
(`emitQuadPtr[0] -> emitQuad_3_e2[54]`, 29 cells, ~680 ps of the 978 ps path in
a BUFx16f/BUFx6f chain driven by the binary `emitQuadPtr` fanout). All 11
KernelFragStageSpec and 3 TexSampleUnitSpec cases pass. Emitted RTL is under
`generated/ppa_refresh_head/kernel_frag_stage_gradsplit/`.

The `emitquadsel` variant applies the same one-hot-selector fix to the emit
cache: the binary `emitQuadPtr` selected the quad loaded into the emit cache,
and its bit drove the fill enable of every field register of every quad entry
(~4 lanes x 6 fields x ~40 bits x 8 quads), so the resizer built a ~680 ps
BUFx16f/BUFx6f chain on it. A registered one-hot `emitQuadSel` (shifted once per
loaded quad, re-armed when the walk restarts) gives each entry its own select
net, mirroring `requestQuadSel`. This is the largest single result so far:
worst setup slack across all groups improves -103.44 -> -4.86 ps and setup TNS
-103.62 -> -6.92 ps (5 violations), so the virtual-IO groups now close and the
core clock is the sole remaining setup limiter. It also lowers area
27,428.4 -> 27,148.8 um^2, power 320.31 -> 313.62 mW, and hold -107.67 ->
-84.59 ps; DRC/antenna stay 0. The reported core-clock Fmax dips slightly to
995.17 MHz (from 999.82) because removing the large buffer tree shifted
placement and the new core critical path is `execSlot -> texUnit.sampler.lodFracReg[5]`
(28 cells, 238 ps of BUFx16f), a 2:1 `execSlot` mux into the sampler LOD cone
that was previously hidden behind the emit path. All 11 KernelFragStageSpec and
3 TexSampleUnitSpec cases pass. Emitted RTL is under
`generated/ppa_refresh_head/kernel_frag_stage_emitquadsel/`.

A `texcfg` attempt tried to remove that `execSlot` fanout by registering the
selected sampler configuration (`RegNext(slotTex*(execSlot))` for all seven
fields) so the sampler would not depend on `execSlot` combinationally. It
regressed hard and was reverted: worst setup slack -4.86 -> -119.72 ps, setup
TNS -6.92 -> -16,536.6 ps (399 violations), core Fmax 995.17 -> 893.08 MHz
(area 27,148.8 -> 27,084.8 um^2, power 313.62 -> 311.80 mW). The added
registers shifted placement and exposed a much worse path in the write-pipe
drain (`wpWrite_0_0 -> fragRecords_0_0_v_1[10]`, 1090 ps, 499 ps of BUFx16f and
45% net delay), so the config-register idea is not viable as-is. The
`emitquadsel` RTL remains checked in and is the best measured implementation.

Graphics artifact directories:

- `generated/ppa_runs/head_command_buffer_scalar_tc_slvt_1ghz_closure/`
- `generated/ppa_runs/head_command_buffer_vert_tc_slvt_1ghz_closure/`
- `generated/ppa_runs/raster_quad_incr_edges_1ghz_yosys_noretime_closure_util25_density60/`
- `generated/ppa_runs/head_kernel_frag_stage_wp2_tc_slvt_1ghz_explore_u25_d60/`
- `generated/ppa_runs/head_kernel_frag_stage_onehot_tc_slvt_1ghz_explore_u25_d60/`
- `generated/ppa_runs/head_kernel_frag_stage_packed_tc_slvt_1ghz_explore_u25_d60/`
- `generated/ppa_runs/head_kernel_frag_stage_selectors_tc_slvt_1ghz_explore_u25_d60/`
- `generated/ppa_runs/head_kernel_frag_stage_selected_quad_tc_slvt_1ghz_explore_u25_d60/`
- `generated/ppa_runs/head_kernel_frag_stage_outreg2_tc_slvt_1ghz_explore_u25_d60/`
- `generated/ppa_runs/head_kernel_frag_stage_emitquad_tc_slvt_1ghz_explore_u25_d60/`
- `generated/ppa_runs/head_kernel_frag_stage_emitptr_tc_slvt_1ghz_explore_u25_d60/`
- `generated/ppa_runs/head_kernel_frag_stage_onehotread_tc_slvt_1ghz_explore_u25_d60/`
- `generated/ppa_runs/head_kernel_frag_stage_gradsplit_tc_slvt_1ghz_explore_u25_d60/`
- `generated/ppa_runs/head_kernel_frag_stage_emitquadsel_tc_slvt_1ghz_explore_u25_d60/`
- `generated/ppa_runs/head_kernel_frag_stage_texcfg_tc_slvt_1ghz_explore_u25_d60/` (reverted)

`closure_no_cts` / `closure` note for large blocks: `repair_timing
-repair_tns 100` does not converge on KernelFragStage (~870k instances; WNS
plateaus and the stage spins indefinitely; the Sep-4 closure attempt
`head_kernel_frag_stage_tc_slvt_1ghz_closure` died mid-flow with no metrics).
Use `explore` for this scale.

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
