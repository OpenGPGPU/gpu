#!/usr/bin/env python3
"""Run a reproducible graphics block ASAP7 PPA flow for current emitted RTL."""
import json
import os
import sys
from pathlib import Path

sys.path.insert(0, "/Users/duckdonald/workspace/chipagent")
from chipagent.models import TaskObject
from chipagent.tools.base import ToolContext
from chipagent.tools.phys_flow_asap7 import ASAP7PhysicalFlowTool

if len(sys.argv) not in (3, 4):
    raise SystemExit("usage: run_graphics_ppa.py <block-dir> <top-module> [synthesis-only]")

repo = Path(__file__).resolve().parents[1]
rtl_dir = (repo / sys.argv[1]).resolve()
module = sys.argv[2]
filelist = rtl_dir / "filelist.f"
rtl_files = [(rtl_dir / line.strip()).resolve()
             for line in filelist.read_text().splitlines()
             if line.strip() and not line.strip().startswith("verification/")]
for path in rtl_files:
    if not path.is_file():
        raise FileNotFoundError(path)

name = rtl_dir.name
timing_effort = os.environ.get("GRAPHICS_PPA_TIMING_EFFORT", "closure_no_cts")
effort_tag = "closure" if timing_effort.startswith("closure") else timing_effort
out_dir = repo / "generated/ppa_runs" / f"head_{name}_tc_slvt_1ghz_{effort_tag}"
inputs = {
    "reg_code": "",
    "rtl_files": [str(p) for p in rtl_files],
    "module_name": module,
    "clock_port": "clock",
    "clock_period": 1000.0,
    "core_utilization": 25,
    "place_density": 0.6,
    "corner": "TC",
    "cell_vt": "SLVT",
    "output_dir": str(out_dir),
    "timeout": 28800,
    "cache": False,
    "clean": True,
    "generate_gds": False,
    "timing_effort": timing_effort,
    "synthesis_engine": "yosys",
    "sv_frontend": "native",
    "enable_retiming": False,
    "abc_clock_period_ps": 500.0,
    # Large integration blocks can supply a tighter parent-register boundary
    # budget without changing the reproducible default used by the small
    # standalone blocks.
    "io_delay_percent": float(os.environ.get("GRAPHICS_PPA_IO_DELAY_PERCENT", "0.2")),
    "synthesis_only": len(sys.argv) == 4 and sys.argv[3] == "synthesis-only",
}
tool = ASAP7PhysicalFlowTool()
res = tool.run(ToolContext(
    task=TaskObject(task_type="physical_flow_asap7", module_name=module, description=""),
    inputs=inputs,
))
result = res.result
out_dir.mkdir(parents=True, exist_ok=True)
(out_dir / "flow_result.json").write_text(json.dumps(result, indent=2, default=str))
print(json.dumps({k: result.get(k) for k in ("status", "stage", "output_dir", "qor", "overview", "issues")},
                 ensure_ascii=False, indent=2))
