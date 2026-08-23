#!/usr/bin/env python3
"""Run the whole-block VectorBackend ASAP7 physical flow for candidate 059."""
import json
import sys
from pathlib import Path

sys.path.insert(0, "/Users/duckdonald/workspace/mychipagent")

from chipagent.models import TaskObject
from chipagent.tools.base import ToolContext
from chipagent.tools.phys_flow_asap7 import ASAP7PhysicalFlowTool

repo = Path("/Users/duckdonald/workspace/gpu")
rtl_dir = repo / "generated/ppa_candidate_061_vector_backend_issueskid"
filelist = rtl_dir / "filelist.f"
rtl_files = [(rtl_dir / line.strip()).resolve() for line in filelist.read_text().splitlines() if line.strip()]
for path in rtl_files:
    assert path.is_file(), f"missing RTL file: {path}"

out_dir = repo / "generated/ppa_runs/139_vector_backend_issueskid_flpath_tc_slvt_1ghz_yosys_noretime_closure_util25_density60"

inputs = {
    "reg_code": "",
    "rtl_files": [str(p) for p in rtl_files],
    "module_name": "VectorBackend",
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
    "macro_lefs": [str(repo / "depends/asap7_sram_0p0/generated/LEF/srambank_64x4x64_6t122.lef")],
    "macro_libs": [str(repo / "depends/asap7_sram_0p0/generated/LIB/srambank_64x4x64_6t122.lib")],
    "macro_gds": None,
    "macro_placement_tcl": str(repo / "timing/asap7/compute_unit_sram_macro_placement.tcl"),
    "timing_effort": "closure_no_cts",
    "synthesis_engine": "yosys",
    "sv_frontend": "native",
    "enable_retiming": False,
    "abc_clock_period_ps": 500.0,
    "io_delay_percent": 0.1,
    "io_false_path_ports": [
        "io_unimplemented_valid",
        "io_unimplemented_bits_*",
        "io_scalarReserve_valid",
        "io_scalarReserve_bits_*",
        "io_committedVectorFlags_valid",
        "io_committedVectorFlags_bits_*",
        "io_memoryFault_valid",
        "io_memoryFault_bits_*",
        "io_memoryResponse_valid",
        "io_memoryResponse_ready",
        "io_memoryResponse_bits_*",
    ],
    "synthesis_only": False,
}

tool = ASAP7PhysicalFlowTool()
res = tool.run(ToolContext(
    task=TaskObject(task_type="physical_flow_asap7", module_name="VectorBackend", description=""),
    inputs=inputs,
))

result = res.result
issues = result.get("issues") if isinstance(result, dict) else getattr(res, "issues", None)
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
