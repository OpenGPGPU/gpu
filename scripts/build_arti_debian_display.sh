#!/usr/bin/env bash
# Build an isolated ARTI GPU/QEMU/driver for the interactive Debian guest at a
# given resolution. The 16x16 qualification binary and the generated RTL for
# other sizes remain available.
set -euo pipefail

GPU_DIR="$(cd "$(dirname "$0")/.." && pwd)"
ARTI_WORK="${ARTI_WORK:-$(cd "$GPU_DIR/.." && pwd)/arti-work}"
INTEGRATION_CONFIG="${INTEGRATION_CONFIG:-$GPU_DIR/driver/gpu_integration_debian.yaml}"
[ -f "$INTEGRATION_CONFIG" ] || {
    echo "FAIL: integration profile not found: $INTEGRATION_CONFIG" >&2; exit 1; }
# The profile owns the resolution; these names follow from it.
eval "$(python3 "$GPU_DIR/scripts/gpu_display_config.py" --shell \
    "$INTEGRATION_CONFIG")"
# Each size needs its own work tree: the generated RTL, the embedded model and
# the QEMU build are all sized from it.
DISPLAY_WORK="${DISPLAY_WORK:-$ARTI_WORK/debian-${GPU_MODE}}"

export ARTI_WORK
export GPU_WIDTH GPU_HEIGHT
export WORK_DIR="$DISPLAY_WORK"
export QEMU_BUILD="${QEMU_BUILD:-$DISPLAY_WORK/qemu-arti-build}"
export DRIVER_OUTPUT="${DRIVER_OUTPUT:-$DISPLAY_WORK/opengpu-driver}"
export GPU_RTL_DIR="${GPU_RTL_DIR:-$GPU_DIR/generated/debian-${GPU_MODE}}"
export INTEGRATION_CONFIG
export GPU_SIM="${GPU_SIM:-verilator}"
# Same defaults as run_arti_gpu.sh: both shader cores, unless the caller
# turned them off. GPU_FRAG_CORE=0 implies no vertex core.
export GPU_FRAG_CORE="${GPU_FRAG_CORE:-1}"
if [ -z "${GPU_VERT_CORE:-}" ]; then
    if [ "$GPU_FRAG_CORE" = "1" ]; then
        GPU_VERT_CORE=1
    else
        GPU_VERT_CORE=0
    fi
fi
export GPU_VERT_CORE
export ARTI_VERILATOR_BUILD_JOBS="${ARTI_VERILATOR_BUILD_JOBS:-4}"
# ARTI's setup looks under WORK_DIR by default. Reuse the existing Debian
# image in ARTI_WORK; setup only checks that DEBIAN_QCOW2 exists at this step.
export DEBIAN_BASE="${DEBIAN_BASE:-$ARTI_WORK/debian-arm64-base.qcow2}"
if [ -f "$ARTI_WORK/arti-dev.qcow2" ]; then
    export DEBIAN_QCOW2="${DEBIAN_QCOW2:-$ARTI_WORK/arti-dev.qcow2}"
else
    export DEBIAN_QCOW2="${DEBIAN_QCOW2:-$DISPLAY_WORK/arti-dev.qcow2}"
fi
export BUILD_ONLY=1

"$GPU_DIR/scripts/run_arti_gpu.sh"
printf 'mode=%s backend=%s frag=%s vert=%s\n' \
    "$GPU_MODE" "$GPU_SIM" "$GPU_FRAG_CORE" "$GPU_VERT_CORE" \
    > "$DISPLAY_WORK/display-mode.txt"
echo "$GPU_MODE Debian GPU ready. Boot with scripts/run_arti_debian.sh"
