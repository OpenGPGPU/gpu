#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "$0")/.." && pwd)"
cvfpu_dir="$repo_dir/depends/cvfpu"

# CVFPU drives array element zero combinationally and later elements from
# always_ff. Verilator 5 diagnoses the aggregate as BLKANDNBLK even though no
# individual element has two drivers.
verilator --lint-only --Wno-fatal --Wno-BLKANDNBLK \
  --top-module fp32_fma_lane_wrapper \
  -I"$cvfpu_dir/src" \
  -I"$cvfpu_dir/src/common_cells/include" \
  "$cvfpu_dir/src/common_cells/src/cf_math_pkg.sv" \
  "$cvfpu_dir/src/common_cells/src/lzc.sv" \
  "$cvfpu_dir/src/fpnew_pkg.sv" \
  "$cvfpu_dir/src/fpnew_classifier.sv" \
  "$cvfpu_dir/src/fpnew_rounding.sv" \
  "$cvfpu_dir/src/fpnew_fma.sv" \
  "$repo_dir/src/main/resources/fpu/fp32_fma_lane_wrapper.sv"
