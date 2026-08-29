#!/usr/bin/env bash
# Emit the GPU RTL, build the embedded ARTI/QEMU model, Linux driver and guest
# KMS test, then boot the end-to-end Linux test. The ARTI setup is incremental
# after the first run, so this remains the normal development entry point.
set -euo pipefail

GPU_DIR="$(cd "$(dirname "$0")/.." && pwd)"
ARTI_DIR="${ARTI_DIR:-$GPU_DIR/../arti}"
INTEGRATION_CONFIG="${INTEGRATION_CONFIG:-$GPU_DIR/driver/gpu_integration.yaml}"
LINUX_BUILD="${LINUX_BUILD:-/tmp/arti-linux-build}"
LINUX_SRC="${LINUX_SRC:-/tmp/linux-src}"
LINUX_HEADERS="${LINUX_HEADERS:-/tmp/arti-linux-headers}"
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
HARNESS_STAGE=""
cleanup() {
    rm -rf "$DRIVER_STAGE"
    [ -z "$HARNESS_STAGE" ] || rm -rf "$HARNESS_STAGE"
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

echo "=== 4/4 Build the guest DRM test and boot Linux ==="
[ -d "$LINUX_SRC" ] || fail "Linux source not found at $LINUX_SRC"
if [ ! -f "$LINUX_HEADERS/include/drm/drm.h" ]; then
    PATH="/opt/homebrew/bin:$PATH" gmake -s -C "$LINUX_SRC" ARCH=arm64 \
        INSTALL_HDR_PATH="$LINUX_HEADERS" headers_install
fi

if [ -n "${CROSS_COMPILE:-}" ]; then
    case "$CROSS_COMPILE" in
        *-) CROSS_GCC="${CROSS_COMPILE}gcc" ;;
        *) CROSS_GCC="${CROSS_COMPILE}-gcc" ;;
    esac
else
    for cross_gcc_candidate in aarch64-linux-gnu-gcc \
                               aarch64-unknown-linux-gnu-gcc \
                               aarch64-none-linux-gnu-gcc; do
        if command -v "$cross_gcc_candidate" >/dev/null 2>&1; then
            CROSS_GCC="$cross_gcc_candidate"
            break
        fi
    done
fi
[ -n "${CROSS_GCC:-}" ] && command -v "$CROSS_GCC" >/dev/null 2>&1 || \
    fail "AArch64 cross compiler is required for the guest DRM test"

GUEST_DRM_TEST="$DRIVER_OUTPUT/opengpu_drm_test"
"$CROSS_GCC" -static -O2 -Wall -Wextra -Werror \
    -I"$LINUX_HEADERS/include" -I"$GPU_DIR/driver" \
    -o "$GUEST_DRM_TEST" "$GPU_DIR/driver/tests/opengpu_drm_test.c"

# ARTI's generic runner deliberately owns initramfs construction. Use a
# temporary harness view with our external-driver init, while keeping ARTI
# itself unmodified and allowing it to stage module dependencies normally.
WORK="${WORK:-/tmp/arti-linux-test}"
mkdir -p "$WORK"
cp "$GUEST_DRM_TEST" "$WORK/opengpu_drm_test"
HARNESS_STAGE="$(mktemp -d "${TMPDIR:-/tmp}/opengpu-harness.XXXXXX")"
ln -s "$ARTI_DIR/examples/linux_arti_driver/run_linux_test.sh" \
    "$HARNESS_STAGE/run_linux_test.sh"
ln -s "$ARTI_DIR/examples/linux_arti_driver/integration_env.sh" \
    "$HARNESS_STAGE/integration_env.sh"
ln -s "$ARTI_DIR/examples/linux_arti_driver/driver_preflight.sh" \
    "$HARNESS_STAGE/driver_preflight.sh"
cp "$GPU_DIR/driver/tests/arti-linux-init.c" "$HARNESS_STAGE/arti-linux-init.c"

export ARTI_DIR INTEGRATION_CONFIG LINUX_BUILD DRIVER_KO DRIVER_MANIFEST WORK
export QEMU_DISPLAY HOLD_AFTER_TEST
"$HARNESS_STAGE/run_linux_test.sh"
