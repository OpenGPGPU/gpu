#!/usr/bin/env python3
"""Run checked RTL scenes and save counters plus exact source provenance."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--cases', help='comma-separated workload names; default runs all eleven')
    parser.add_argument('--output', type=Path, default=ROOT / 'generated/qualification/workloads')
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    env = dict(os.environ, GPU_BENCHMARK='1')
    if args.cases:
        env['GPU_BENCHMARK_CASES'] = args.cases
    inputs = sorted(set(subprocess.check_output(['git', 'ls-files', 'src', 'build.sbt'],
        cwd=ROOT, text=True).splitlines()) | {'src/test/scala/opengpu/system/GpuWorkloadSpec.scala',
        'src/test/scala/opengpu/system/GpuHostTestSupport.scala',
        'src/main/scala/opengpu/graphics/GraphicsPerformance.scala'})
    manifest = dict(timestamp=datetime.now(timezone.utc).isoformat(),
        commit=subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip(),
        source_sha256={name: hashlib.sha256((ROOT / name).read_bytes()).hexdigest() for name in inputs},
        configuration=dict(lanes=4, warps=2, l2_sets=8, l2_ways=2, memory_data_bytes=8,
            write_ack_delay_cycles=4, sv32=True, io_instrumentation=True), cases=args.cases or 'all')
    (args.output / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    results = []
    with (args.output / 'simulation.log').open('w') as log:
        process = subprocess.Popen(['sbt', '-batch',
            'testOnly opengpu.system.GpuWorkloadSpec'], cwd=ROOT, env=env,
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        for line in process.stdout:
            log.write(line)
            print(line, end='', flush=True)
            if 'GPU_BENCHMARK_RESULT ' in line:
                results.append(json.loads(line.split('GPU_BENCHMARK_RESULT ', 1)[1]))
        status = process.wait()
    (args.output / 'results.json').write_text(json.dumps(results, indent=2) + '\n')
    expected = len(args.cases.split(',')) if args.cases else 11
    if status or len(results) != expected:
        raise SystemExit(f'Workload qualification failed: exit={status}, results={len(results)}/{expected}')

if __name__ == '__main__':
    main()
