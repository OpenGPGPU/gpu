#!/usr/bin/env python3
"""Run ASAP7 1 GHz closure for SRAM-macro blocks (ScalarBackend, SharedL2Slice).

Usage: run_closure_ppa.py <scalar-backend|shared-l2-slice>
Reads emitted RTL from generated/ppa_refresh_head/<block_dir>/filelist.f.
"""
import json
import sys
from pathlib import Path

sys.path.insert(0, "/Users/duckdonald/workspace/chipagent")
from chipagent.models import TaskObject
from chipagent.tools.base import ToolContext
from chipagent.tools.phys_flow_asap7 import ASAP7PhysicalFlowTool

repo = Path(__file__).resolve().parents[1]
sram = repo / "depends/asap7_sram_0p0/generated"

CONFIGS = {
    "scalar-backend": {
        "rtl_dir": "generated/ppa_refresh_head/scalar_backend",
        "module": "ScalarBackend",
        "cell_vt": "SLVT",
        "core_utilization": 30,
        "place_density": 0.5,
        "setup_slack_margin": 50.0,
        "macro_lefs": [str(sram / "LEF/srambank_64x4x32_6t122.lef")],
        "macro_libs": [str(sram / "LIB/srambank_64x4x32_6t122.lib")],
        "macro_placement_tcl": str(repo / "timing/asap7/compute_unit_sram_macro_placement.tcl"),
    },
    "shared-l2-slice": {
        "rtl_dir": "generated/ppa_refresh_head/shared_l2_slice",
        "module": "SharedL2Slice",
        "cell_vt": "LVT",
        "core_utilization": 15,
        "place_density": 0.3,
        "setup_slack_margin": 50.0,
        "macro_lefs": [
            str(sram / "LEF/srambank_64x4x32_6t122.lef"),
            str(sram / "LEF/srambank_64x4x64_6t122.lef"),
        ],
        "macro_libs": [
            str(sram / "LIB/srambank_64x4x32_6t122.lib"),
            str(sram / "LIB/srambank_64x4x64_6t122.lib"),
        ],
        "macro_placement_tcl": str(repo / "timing/asap7/l2_sram_macro_placement.tcl"),
    },
}

if len(sys.argv) != 2 or sys.argv[1] not in CONFIGS:
    raise SystemExit(f"usage: run_closure_ppa.py <{'|'.join(CONFIGS)}>")

cfg = CONFIGS[sys.argv[1]]
rtl_dir = (repo / cfg["rtl_dir"]).resolve()
filelist = rtl_dir / "filelist.f"
rtl_files = [(rtl_dir / line.strip()).resolve()
             for line in filelist.read_text().splitlines()
             if line.strip() and not line.strip().startswith("verification/")]
for path in rtl_files:
    if not path.is_file():
        raise FileNotFoundError(path)

vt_tag = cfg["cell_vt"].lower()
out_dir = repo / "generated/ppa_runs" / (
    f"head_{rtl_dir.name}_tc_{vt_tag}_1ghz_yosys_noretime_closure"
    f"_u{cfg['core_utilization']}_d{int(cfg['place_density'] * 100):02d}_margin50")

inputs = {
    "reg_code": "",
    "rtl_files": [str(p) for p in rtl_files],
    "module_name": cfg["module"],
    "clock_port": "clock",
    "clock_period": 1000.0,
    "core_utilization": cfg["core_utilization"],
    "place_density": cfg["place_density"],
    "corner": "TC",
    "cell_vt": cfg["cell_vt"],
    "output_dir": str(out_dir),
    "timeout": 28800,
    "cache": False,
    "clean": True,
    "generate_gds": False,
    "macro_lefs": cfg["macro_lefs"],
    "macro_libs": cfg["macro_libs"],
    "macro_placement_tcl": cfg["macro_placement_tcl"],
    "timing_effort": "closure_no_cts",
    "synthesis_engine": "yosys",
    "sv_frontend": "native",
    "enable_retiming": False,
    "abc_clock_period_ps": 500.0,
    "setup_slack_margin": cfg["setup_slack_margin"],
    "io_delay_percent": 0.2,
    "synthesis_only": False,
}

tool = ASAP7PhysicalFlowTool()
res = tool.run(ToolContext(
    task=TaskObject(task_type="physical_flow_asap7", module_name=cfg["module"], description=""),
    inputs=inputs,
))
result = res.result
out_dir.mkdir(parents=True, exist_ok=True)
(out_dir / "flow_result.json").write_text(json.dumps(result, indent=2, default=str))
print(json.dumps({k: result.get(k) for k in ("status", "stage", "output_dir", "qor", "overview", "issues")},
                 ensure_ascii=False, indent=2))
