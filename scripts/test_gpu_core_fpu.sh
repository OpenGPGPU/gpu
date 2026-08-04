#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "$0")/.." && pwd)"
mkdir -p "$repo_dir/target"
rtl_dir="$(mktemp -d "$repo_dir/target/gpu_core_fpu_rtl.XXXXXX")"
obj_dir="$(mktemp -d "$repo_dir/target/gpu_core_fpu_test.XXXXXX")"

(cd "$repo_dir" && sbt "runMain gpu.elaboration.EmitFpuCoreSimulationRtl $rtl_dir")
rtl_sources=("$rtl_dir"/*.sv)

verilator --cc --exe --build --Wno-fatal --Wno-BLKANDNBLK \
  --Mdir "$obj_dir" --top-module GpuCore \
  "${rtl_sources[@]}" \
  "$repo_dir/src/test/resources/fpu/gpu_core_fpu_test.cpp"

"$obj_dir/VGpuCore"
