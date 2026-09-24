#!/usr/bin/env bash
# Release-style functional gate. Every stage must pass; the guest runner checks
# the userspace DRM PASS marker before reporting success.
set -euo pipefail

GPU_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$GPU_DIR"

echo '=== Functional qualification: full Scala suite ==='
# sbt's XML listener expects this path to exist when each suite finishes.
mkdir -p target/test-reports
sbt -batch test

echo '=== Functional qualification: host driver tests ==='
python3 scripts/test_driver.py

echo '=== Functional qualification: fixed-function ARTI guest ==='
GPU_FRAG_CORE=0 GPU_VERT_CORE=0 TIMEOUT=900 HOLD_AFTER_TEST=1 \
  scripts/run_arti_gpu.sh

echo '=== Functional qualification: vertex and fragment ARTI guest ==='
GPU_FRAG_CORE=1 GPU_VERT_CORE=1 TIMEOUT=1500 HOLD_AFTER_TEST=1 \
  scripts/run_arti_gpu.sh

echo '=== Functional qualification PASS ==='
