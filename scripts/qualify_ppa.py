#!/usr/bin/env python3
"""Measure emitted RTL with explicit clocks, SRAM libraries and an input manifest.

Use ChipAgent's Python environment. Set CHIPAGENT_DIR for a non-sibling checkout.
Outputs are immutable run directories; no historical result is overwritten.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
from datetime import datetime, timezone

ROOT = Path(__file__).resolve().parents[1]

def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('rtl', type=Path)
    parser.add_argument('module')
    parser.add_argument('--stage', choices=['synthesis', 'route'], default='synthesis')
    parser.add_argument('--clock', default='clock')
    parser.add_argument('--period-ps', type=float, default=1000)
    parser.add_argument('--io-delay', type=float, default=0.25)
    parser.add_argument('--utilization', type=int, default=25)
    parser.add_argument('--density', type=float, default=0.6)
    parser.add_argument('--timeout', type=int, default=7200)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=False)
    rtl = args.rtl.resolve()
    filelist = rtl / 'filelist.f'
    files = ([rtl / line.strip() for line in filelist.read_text().splitlines()
              if line.strip() and not line.startswith('verification/')]
             if filelist.exists() else sorted(rtl.glob('*.sv')))
    if not files:
        raise SystemExit('No emitted RTL files')
    source = '\n'.join(path.read_text() for path in files)
    top = re.search(r'\bmodule\s+' + re.escape(args.module) + r'\b(.*?);', source, re.S)
    if not top or not re.search(r'\b' + re.escape(args.clock) + r'\b', top[1]):
        raise SystemExit('Requested clock is absent from the top-level ports')
    sram = ROOT / 'depends/asap7_sram_0p0/generated'
    macros = sorted(set(re.findall(r'\bsrambank_\w+', source)))
    libs = [sram / 'LIB' / (name + '.lib') for name in macros]
    lefs = [sram / 'LEF' / (name + '.lef') for name in macros]
    for path in files + libs + lefs:
        if not path.is_file():
            raise SystemExit(f'Missing input: {path}')
    chipagent = Path(os.environ.get('CHIPAGENT_DIR', ROOT.parent / 'chipagent'))
    sys.path.insert(0, str(chipagent))
    from chipagent.models import TaskObject
    from chipagent.tools.base import ToolContext
    from chipagent.tools.phys_flow_asap7 import ASAP7PhysicalFlowTool
    inputs = dict(reg_code='', rtl_files=[str(p) for p in files],
        module_name=args.module, clock_port=args.clock, clock_period=args.period_ps,
        io_delay_percent=args.io_delay, io_false_path_ports=None,
        cell_vt='SLVT', corner='TC', synthesis_engine='syn',
        enable_retiming=False, sv_frontend='native',
        core_utilization=args.utilization, place_density=args.density,
        output_dir=str(args.output.resolve() / 'flow'), timeout=args.timeout,
        synthesis_only=args.stage == 'synthesis', cache=False, clean=False,
        generate_gds=False, timing_effort='explore',
        macro_libs=[str(p) for p in libs] or None,
        macro_lefs=[str(p) for p in lefs] or None,
        macro_placement_tcl=str(ROOT / 'timing/asap7/compute_unit_sram_macro_placement.tcl') if macros else None)
    tracked = subprocess.check_output(['git', 'ls-files', 'src/main', 'build.sbt',
        'scripts/qualify_ppa.py', 'timing/asap7'], cwd=ROOT, text=True).splitlines()
    manifest = dict(timestamp=datetime.now(timezone.utc).isoformat(),
        commit=subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip(),
        dirty=bool(subprocess.check_output(['git', 'status', '--porcelain'], cwd=ROOT)),
        chipagent_commit=subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=chipagent, text=True).strip(),
        source_sha256={name:sha(ROOT / name) for name in tracked},
        emitted_sha256={str(p.relative_to(rtl)):sha(p) for p in files},
        macro_sha256={str(p.relative_to(ROOT)):sha(p) for p in libs + lefs},
        scope='synthesis estimate' if args.stage == 'synthesis' else 'post-route; includes IO timing',
        inputs=inputs)
    (args.output / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    result = ASAP7PhysicalFlowTool().run(ToolContext(
        task=TaskObject(task_type='physical_flow_asap7', module_name=args.module, description='Current RTL qualification'),
        inputs=inputs))
    (args.output / 'result.json').write_text(json.dumps(result.result, indent=2, default=str) + '\n')
    print(json.dumps({k: result.result.get(k) for k in ('status', 'qor', 'overview', 'diagnosis')}, indent=2, default=str), flush=True)
    status = result.result.get('status')
    if status in ('error', 'missing_tool'):
        raise SystemExit(1)
    if result.result.get('overview', {}).get('verdict') == 'FAIL':
        raise SystemExit(2)  # Flow completed, but physical qualification failed.
    if status == 'failed':
        raise SystemExit(1)

if __name__ == '__main__':
    main()
