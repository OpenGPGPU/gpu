#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "$0")/.." && pwd)"
cvfpu_dir="$repo_dir/depends/cvfpu"
mkdir -p "$repo_dir/target"
rtl_dir="$(mktemp -d "$repo_dir/target/gpu_core_fpu_rtl.XXXXXX")"
obj_dir="$(mktemp -d "$repo_dir/target/gpu_core_fpu_test.XXXXXX")"

(cd "$repo_dir" && sbt "runMain gpu.elaboration.EmitFpuCoreSimulationRtl $rtl_dir")
rtl_sources=("$rtl_dir"/*.sv)

verilator --cc --exe --build --Wno-fatal --Wno-BLKANDNBLK \
  --Mdir "$obj_dir" --top-module GpuCore \
  -I"$cvfpu_dir/src" \
  -I"$cvfpu_dir/src/common_cells/include" \
  "$cvfpu_dir/src/common_cells/src/cf_math_pkg.sv" \
  "$cvfpu_dir/src/common_cells/src/lzc.sv" \
  "$cvfpu_dir/src/fpnew_pkg.sv" \
  "$cvfpu_dir/src/fpnew_classifier.sv" \
  "$cvfpu_dir/src/fpnew_rounding.sv" \
  "$cvfpu_dir/src/fpnew_fma.sv" \
  "${rtl_sources[@]}" \
  "$repo_dir/src/test/resources/fpu/gpu_core_fpu_test.cpp"

"$obj_dir/VGpuCore"
