#!/usr/bin/env python3
"""Run the whole-block M4b incremental rasterizer ASAP7 physical flow.

Mirror of scripts/run_vb_physical.py for the graphics fixed-function block:
emits generated/ppa_raster_quad (EmitPpaRtl "raster-quad", see main.scala), then
drives the ChipAgent ASAP7 flow with the same corner/settings used by the
compute blocks so area/timing deltas against the M1 rasterizer engine are
apples-to-apples.  Frequency target: close the 1 GHz constraint that other
whole-block runs use; the design goal of M4b is that the steady-state scan
path contains no multiplier between edge-value registers and coverage output.
"""
import json
import sys
from pathlib import Path

sys.path.insert(0, "/Users/duckdonald/workspace/mychipagent")

from chipagent.models import TaskObject
from chipagent.tools.base import ToolContext
from chipagent.tools.phys_flow_asap7 import ASAP7PhysicalFlowTool

repo = Path("/Users/duckdonald/workspace/gpu")
rtl_dir = repo / "generated/ppa_raster_quad"
filelist = rtl_dir / "filelist.f"
rtl_files = [(rtl_dir / line.strip()).resolve() for line in filelist.read_text().splitlines() if line.strip()]
for path in rtl_files:
    assert path.is_file(), f"missing RTL file: {path}"

out_dir = repo / ("generated/ppa_runs/"
                  "raster_quad_incr_edges_1ghz_yosys_noretime_closure_util25_density60")

inputs = {
    "reg_code": "",
    "rtl_files": [str(p) for p in rtl_files],
    "module_name": "TriangleRasterizer",
    "clock_port": "clock",
    "clock_period": 1000.0,
    "core_utilization": 25,
    "place_density": 0.6,
    "corner": "TC",
    "cell_vt": "SLVT",
    "output_dir": str(out_dir),
    "timeout": 18000,
    "cache": False,
    "clean": True,
    "generate_gds": False,
    "timing_effort": "closure_no_cts",
    "synthesis_engine": "yosys",
    "sv_frontend": "native",
    "enable_retiming": False,
}

tool = ASAP7PhysicalFlowTool()
res = tool.run(ToolContext(
    task=TaskObject(task_type="physical_flow_asap7", module_name="TriangleRasterizer",
                    description="M4b incremental-edge graphics rasterizer"),
    inputs=inputs,
))

result = res.result
issues = result.get("issues") if isinstance(result, dict) else getattr(res, "issues", None)
(out_dir / "flow_result.json").write_text(
    json.dumps(result, indent=2, default=str))
print(json.dumps({
    "status": result.get("status"),
    "stage": result.get("stage"),
    "output_dir": result.get("output_dir"),
    "qor": result.get("qor"),
    "overview": result.get("overview"),
    "issues": issues,
}, ensure_ascii=False, indent=2))
if result.get("report_tail"):
    print("--- report tail ---")
    print(result["report_tail"])
