#!/usr/bin/env bash
# Emit the GPU RTL, build the embedded ARTI/QEMU model and Linux driver, then
# boot the end-to-end Linux test. The ARTI setup is incremental after the first
# run, so this remains the normal entry point for development iterations.
set -euo pipefail

GPU_DIR="$(cd "$(dirname "$0")/.." && pwd)"
ARTI_DIR="${ARTI_DIR:-$GPU_DIR/../arti}"
INTEGRATION_CONFIG="${INTEGRATION_CONFIG:-$GPU_DIR/driver/gpu_integration.yaml}"
LINUX_BUILD="${LINUX_BUILD:-/tmp/arti-linux-build}"
DRIVER_OUTPUT="${DRIVER_OUTPUT:-/tmp/opengpu-arti-driver}"
QEMU_TOOLS="${QEMU_TOOLS:-/tmp/qemu-build-tools}"
QEMU_DISPLAY="${QEMU_DISPLAY:-none}"

if [ -z "${QEMU_SRC:-}" ]; then
    for qemu_candidate in /tmp/qemu-src /tmp/qemu-src/qemu-11.1.0; do
        if [ -f "$qemu_candidate/configure" ]; then
            QEMU_SRC="$qemu_candidate"
            break
        fi
    done
    QEMU_SRC="${QEMU_SRC:-/tmp/qemu-src}"
fi

if [ "$QEMU_DISPLAY" = "none" ]; then
    HOLD_AFTER_TEST="${HOLD_AFTER_TEST:-1}"
else
    HOLD_AFTER_TEST="${HOLD_AFTER_TEST:-30}"
fi

fail() {
    echo "FAIL: $*" >&2
    exit 1
}

[ -d "$ARTI_DIR" ] || fail "ARTI repository not found at $ARTI_DIR (set ARTI_DIR)"
[ -f "$ARTI_DIR/examples/linux_arti_driver/setup_env.sh" ] || \
    fail "ARTI Linux setup script not found under $ARTI_DIR"
[ -f "$INTEGRATION_CONFIG" ] || fail "integration profile not found: $INTEGRATION_CONFIG"
command -v sbt >/dev/null 2>&1 || fail "sbt is required to emit GpuHostAxi RTL"

# ARTI supports an isolated Ninja install under QEMU_TOOLS. Prefer the system
# executable when one is already available; this avoids an unnecessary pip
# download and works around Python installations whose ninja wheel omits the
# bin/ninja launcher under --target.
if [ ! -f "$QEMU_TOOLS/bin/ninja" ] && command -v ninja >/dev/null 2>&1; then
    mkdir -p "$QEMU_TOOLS/bin"
    ln -s "$(command -v ninja)" "$QEMU_TOOLS/bin/ninja"
fi

echo "=== 1/4 Emit GpuHostAxi RTL ==="
(cd "$GPU_DIR" && sbt "runMain opengpu.elaboration.EmitGpuHostAxi generated/host")
[ -f "$GPU_DIR/generated/host/GpuHostAxi.sv" ] || \
    fail "RTL emission did not produce generated/host/GpuHostAxi.sv"
[ -f "$GPU_DIR/generated/host/filelist.f" ] || \
    fail "RTL emission did not produce generated/host/filelist.f"

ARTI_RTL_SOURCE_LIST="$GPU_DIR/generated/host/GpuHostAxi.sv"
while IFS= read -r rtl_source; do
    [ -n "$rtl_source" ] || continue
    [ "$rtl_source" = "GpuHostAxi.sv" ] && continue
    rtl_source="$GPU_DIR/generated/host/$rtl_source"
    [ -f "$rtl_source" ] || fail "RTL dependency from filelist.f is missing: $rtl_source"
    ARTI_RTL_SOURCE_LIST="${ARTI_RTL_SOURCE_LIST:+$ARTI_RTL_SOURCE_LIST,}$rtl_source"
done < "$GPU_DIR/generated/host/filelist.f"
[ -n "$ARTI_RTL_SOURCE_LIST" ] || fail "generated RTL file list is empty"

echo "=== 2/4 Prepare ARTI, QEMU and Linux ==="
# The external module is built in the next step, after setup has prepared the
# exact kernel build tree. Empty overrides prevent setup from expecting a stale
# module from a previous run.
INTEGRATION_CONFIG="$INTEGRATION_CONFIG" \
ARTI_DIR="$ARTI_DIR" \
LINUX_BUILD="$LINUX_BUILD" \
QEMU_TOOLS="$QEMU_TOOLS" \
QEMU_SRC="$QEMU_SRC" \
ARTI_RTL_SOURCE_LIST="$ARTI_RTL_SOURCE_LIST" \
DRIVER_KO= DRIVER_MANIFEST= \
    "$ARTI_DIR/examples/linux_arti_driver/setup_env.sh"

echo "=== 3/4 Build gpu_drv.ko for the QEMU kernel ==="
DRIVER_STAGE="$(mktemp -d "${TMPDIR:-/tmp}/opengpu-driver.XXXXXX")"
cleanup() {
    rm -rf "$DRIVER_STAGE"
}
trap cleanup EXIT
cp "$GPU_DIR/driver/Makefile" "$DRIVER_STAGE/"
cp "$GPU_DIR"/driver/*.c "$GPU_DIR"/driver/*.h "$DRIVER_STAGE/"
LINUX_BUILD="$LINUX_BUILD" \
ARTI_DIR="$ARTI_DIR" \
    "$ARTI_DIR/examples/linux_arti_driver/build_driver.sh" \
        --dir "$DRIVER_STAGE" \
        --module gpu_drv \
        --output "$DRIVER_OUTPUT"

DRIVER_KO="$DRIVER_OUTPUT/gpu_drv.ko"
DRIVER_MANIFEST="$DRIVER_OUTPUT/gpu_drv.deps"
[ -f "$DRIVER_KO" ] || fail "driver build did not produce $DRIVER_KO"

echo "=== 4/4 Boot Linux and run the GPU draw test ==="
export ARTI_DIR INTEGRATION_CONFIG LINUX_BUILD DRIVER_KO DRIVER_MANIFEST
export QEMU_DISPLAY HOLD_AFTER_TEST
"$ARTI_DIR/examples/linux_arti_driver/run_linux_test.sh"
