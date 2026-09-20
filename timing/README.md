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

| Run | Scope | Core Fmax | Worst setup | Hold | DRC | Verdict |
|---|---|---:|---:|---:|---:|---|
| `gpu-system` | synthesis | 505.13 MHz | -979.70 ps | n/a | n/a | **FAIL** |
| `strided-copy` | post-route | 505.06 MHz | -979.97 ps | clean | 0 | **FAIL** |

`gpu-system` is the bounded integrated top (`GpuHostSystemAxi`).
`strided-copy` routes cleanly (DRC/antenna 0, hold clean); the failing path
is flop-to-flop descriptor address arithmetic, not routing. The same cone
limits the integrated top.

The 25% IO budget is a placeholder until the enclosing SoC supplies real
parent-interface budgets. Until then, internal `core_clock` is the only
transferable claim — and it is not met at 1 GHz.

## Next

1 GHz is an objective, not a milestone. Next RTL lever: pipeline the
strided-copy descriptor decode (`lastRow * stride` into the bound check).
That adds one cycle of accept latency and is a product decision, not a flow
tweak.
