#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "$0")/.." && pwd)"
cvfpu_dir="$repo_dir/depends/cvfpu"
build_dir="$repo_dir/target/fp32_fma_dual_equiv_test"
mkdir -p "$build_dir"

verilator --cc --exe --build --Wno-fatal --Wno-BLKANDNBLK \
  --Mdir "$build_dir" --top-module fp32_fma_dual_equiv_wrapper \
  -I"$cvfpu_dir/src" -I"$cvfpu_dir/src/common_cells/include" \
  "$cvfpu_dir/src/common_cells/src/cf_math_pkg.sv" \
  "$cvfpu_dir/src/common_cells/src/lzc.sv" \
  "$cvfpu_dir/src/fpnew_pkg.sv" \
  "$cvfpu_dir/src/fpnew_classifier.sv" \
  "$cvfpu_dir/src/fpnew_rounding.sv" \
  "$cvfpu_dir/src/fpnew_fma.sv" \
  "$repo_dir/src/main/resources/fpu/fp32_fma_lane_wrapper.sv" \
  "$repo_dir/src/test/resources/fpu/fp32_fma_dual_equiv_wrapper.sv" \
  "$repo_dir/src/test/resources/fpu/fp32_fma_dual_equiv_test.cpp"

"$build_dir/Vfp32_fma_dual_equiv_wrapper"
