#!/usr/bin/env bash
# Build an isolated 64x64 ARTI GPU/QEMU/driver for the interactive Debian guest.
# The 16x16 qualification binary and generated RTL remain available.
set -euo pipefail

GPU_DIR="$(cd "$(dirname "$0")/.." && pwd)"
ARTI_WORK="${ARTI_WORK:-$(cd "$GPU_DIR/.." && pwd)/arti-work}"
DISPLAY_WORK="${DISPLAY_WORK:-$ARTI_WORK/debian-64x64}"

export ARTI_WORK
export WORK_DIR="$DISPLAY_WORK"
export QEMU_BUILD="${QEMU_BUILD:-$DISPLAY_WORK/qemu-arti-build}"
export DRIVER_OUTPUT="${DRIVER_OUTPUT:-$DISPLAY_WORK/opengpu-driver}"
export GPU_RTL_DIR="${GPU_RTL_DIR:-$GPU_DIR/generated/debian-64x64}"
export INTEGRATION_CONFIG="${INTEGRATION_CONFIG:-$GPU_DIR/driver/gpu_integration_debian.yaml}"
export GPU_SIM="${GPU_SIM:-verilator}"
export ARTI_VERILATOR_THREADS="${ARTI_VERILATOR_THREADS:-8}"
export ARTI_VERILATOR_BUILD_JOBS="${ARTI_VERILATOR_BUILD_JOBS:-4}"
# ARTI's setup looks under WORK_DIR by default. Reuse the existing Debian
# image in ARTI_WORK; setup only checks that DEBIAN_QCOW2 exists at this step.
export DEBIAN_BASE="${DEBIAN_BASE:-$ARTI_WORK/debian-arm64-base.qcow2}"
if [ -f "$ARTI_WORK/arti-dev.qcow2" ]; then
    export DEBIAN_QCOW2="${DEBIAN_QCOW2:-$ARTI_WORK/arti-dev.qcow2}"
else
    export DEBIAN_QCOW2="${DEBIAN_QCOW2:-$DISPLAY_WORK/arti-dev.qcow2}"
fi
export GPU_WIDTH=64 GPU_HEIGHT=64 BUILD_ONLY=1

"$GPU_DIR/scripts/run_arti_gpu.sh"
printf '64x64 backend=%s verilator_threads=%s\n' "$GPU_SIM" "$ARTI_VERILATOR_THREADS" > "$DISPLAY_WORK/display-mode.txt"
echo "64x64 Debian GPU ready. Boot with scripts/run_arti_debian.sh"
