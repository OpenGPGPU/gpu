#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "$0")/.." && pwd)"
cvfpu_dir="$repo_dir/depends/cvfpu"

verilator --lint-only --Wno-fatal --top-module fp32_cvfpu_wrapper \
  -I"$cvfpu_dir/src" \
  -I"$cvfpu_dir/src/common_cells/include" \
  "$cvfpu_dir/src/common_cells/src/cf_math_pkg.sv" \
  "$cvfpu_dir/src/common_cells/src/lzc.sv" \
  "$cvfpu_dir/src/common_cells/src/rr_arb_tree.sv" \
  "$cvfpu_dir/src/fpnew_pkg.sv" \
  "$cvfpu_dir/src/fpnew_classifier.sv" \
  "$cvfpu_dir/src/fpnew_rounding.sv" \
  "$cvfpu_dir/src/fpnew_fma.sv" \
  "$cvfpu_dir/src/fpnew_fma_multi.sv" \
  "$cvfpu_dir/src/fpnew_noncomp.sv" \
  "$cvfpu_dir/src/fpnew_cast_multi.sv" \
  "$cvfpu_dir/src/fpnew_opgroup_fmt_slice.sv" \
  "$cvfpu_dir/src/fpnew_opgroup_multifmt_slice.sv" \
  "$cvfpu_dir/src/fpnew_opgroup_block.sv" \
  "$cvfpu_dir/src/fpnew_top.sv" \
  "$repo_dir/src/main/resources/fpu/fp32_cvfpu_wrapper.sv"
