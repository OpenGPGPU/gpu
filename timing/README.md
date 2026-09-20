# ASAP7 PPA status

Current physical status of the checked-in RTL, refreshed 2026-09-20. Every
figure here names its source manifest and scope. Synthesis estimates,
register-only STA and post-route STA are separate claims: a completed tool run
is not timing closure, and a pre-layout number does not predict a routed one.

The previous per-block campaign (2026-09-15 and earlier, including results
measured before the ChipAgent `clock_port` correction) is archived verbatim in
[history/PPA_2026-09-20.md](history/PPA_2026-09-20.md). Those figures were
produced with a different flow and are not used as baselines.

## Reproducible qualification

`scripts/qualify_ppa.py` emits current RTL with `EmitPpaRtl`, then runs the
ChipAgent ASAP7 (ORFS) flow with:

- a real top-level `clock` port (`--clock clock`) at a 1000 ps target, TC
  corner, SLVT cells, `syn` engine, no retiming;
- explicit SRAM macro `.lib`/`.lef` from `depends/asap7_sram_0p0` for every
  `srambank_*` cell the RTL instantiates, with the adaptive placement TCL;
- an input/output delay budget of 25% of the period on every boundary port;
- an immutable run directory holding `manifest.json` (git commit, dirty flag,
  SHA-256 of every tracked source, emitted RTL and macro file, plus the exact
  flow inputs), `result.json` (verdict, QoR, critical path) and the ORFS run
  artifacts.

```sh
# Emit current RTL, then qualify a block.
sbt -batch 'runMain opengpu.elaboration.EmitPpaRtl gpu-system generated/qualification/rtl/gpu-system'
python3 scripts/qualify_ppa.py generated/qualification/rtl/gpu-system GpuSystem \
    --output generated/qualification/ppa/gpu-system --stage synthesis
python3 scripts/qualify_ppa.py generated/qualification/rtl/strided-copy StridedCopyEngine \
    --output generated/qualification/ppa/strided-copy --stage route
```

The script exits 2 when the flow completes but the physical verdict is FAIL, so
a failing run is recorded as a failure and cannot be mistaken for closure.

## Current results (2026-09-20)

Both current runs use the same RTL revision (`generated/qualification/rtl/`),
the same 1 GHz / TC / SLVT recipe and the same 25% boundary delay budget.

| Run | Scope | Core Fmax | Worst setup | Hold | DRC | Area | Power | Verdict |
|---|---|---:|---:|---:|---:|---:|---:|---|
| `gpu-system` | synthesis estimate | 505.13 MHz | -979.70 ps (TNS -1.47M ps) | n/a | n/a | 155,887 um^2 | 1.021 W | **FAIL** |
| `strided-copy` | post-route, includes IO | 505.06 MHz | -979.97 ps (718 viol., TNS -116.4 kps) | +30.70 ps (0 viol.) | 0 | 3,345 um^2 | 254.3 mW | **FAIL** |

`gpu-system` is the bounded integrated top (`GpuHostSystemAxi`: AXI host,
fixed-function graphics, shared shader CU, general compute CU, DMA engines,
shared L2). Its synthesis gate instantiates 62 SRAM macros and 801,488 cells;
the virtual-IO clock closes at 828.7 MHz but `core_clock` does not. Evidence:
`generated/qualification/ppa/gpu-system/manifest.json`, `flow/orfs-work/logs/base/1_synth.json`,
`flow/orfs-work/reports/base/1_synth.rpt`.

`strided-copy` reaches full detailed routing with DRC 0 / antenna 0 and clean
hold. The failing `core_clock` path is flop-to-flop, 84 cells, 92.65% cell
delay, dominated by `AND2`/`XOR2`/`MAJ` cells — a descriptor address-arithmetic
carry cone, not a routing artifact. The same near-identical core Fmax and WNS
appear in the integrated `gpu-system` synthesis, so this cone is a current
limiter of the integrated top as well. Evidence:
`generated/qualification/ppa/strided-copy/result.json`.

## Parent-interface timing budgets

The 25% boundary budget is a placeholder, not a sign-off: it models a parent
that launches and captures block IO with a quarter-period of external delay. The
reported `vclk_core_clock` groups (input-to-register and register-to-output) are
always weaker than the internal `core_clock`, because the flat flow places the
boundary capture registers far from the die edge. A meaningful top-level sign-off
needs per-interface budgets derived from the enclosing SoC (source clock,
skew, and the actual launch/capture registers), which do not exist yet. Until
then, internal `core_clock` closure is the only transferable claim, and even
that is not met by the current RTL at 1 GHz.

## Status

1 GHz is a design objective, not an achieved milestone. The next measured lever
is RTL pipelining of the strided-copy descriptor decode (the shared
`lastRow * stride` product feeding the bound check), which is the confirmed
critical cone in both runs. That changes accept latency by one cycle and is a
product decision, not a flow tweak.
