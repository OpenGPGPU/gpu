#!/usr/bin/env bash
# Emit the GPU RTL, build the embedded ARTI/QEMU model, Linux driver and guest
# KMS test, then boot the end-to-end Linux test. The ARTI setup is incremental
# after the first run, so this remains the normal development entry point.
set -euo pipefail

GPU_DIR="$(cd "$(dirname "$0")/.." && pwd)"
ARTI_DIR="${ARTI_DIR:-$GPU_DIR/../arti}"
INTEGRATION_CONFIG="${INTEGRATION_CONFIG:-$GPU_DIR/driver/gpu_integration.yaml}"
LINUX_BUILD="${LINUX_BUILD:-/tmp/arti-linux-build}"
LINUX_HEADERS="${LINUX_HEADERS:-/tmp/arti-linux-headers}"
DRIVER_OUTPUT="${DRIVER_OUTPUT:-/tmp/opengpu-arti-driver}"
QEMU_TOOLS="${QEMU_TOOLS:-/tmp/qemu-build-tools}"
QEMU_DISPLAY="${QEMU_DISPLAY:-none}"
GPU_FRAG_CORE="${GPU_FRAG_CORE:-0}"
GPU_VERT_CORE="${GPU_VERT_CORE:-0}"
GPU_WIDTH="${GPU_WIDTH:-16}"
GPU_HEIGHT="${GPU_HEIGHT:-16}"
# Emulated quad shading is roughly linear in covered+helper lanes, so scale
# the draw watchdog with the pixel count (60 s at the 16x16 baseline).
ARTI_GPU_DRAW_WAIT_MS="${ARTI_GPU_DRAW_WAIT_MS:-$((60000 * GPU_WIDTH * GPU_HEIGHT / 256))}"
QEMU_VERSION="${QEMU_VERSION:-11.1.0}"
LINUX_VERSION="${LINUX_VERSION:-7.2}"
BUSYBOX_DIR="${BUSYBOX_DIR:-/tmp/busybox-1.36.1}"
SLIRP_INSTALL="${SLIRP_INSTALL:-/tmp/slirp-install}"

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
[ "$GPU_FRAG_CORE" = "0" ] || [ "$GPU_FRAG_CORE" = "1" ] || \
    fail "GPU_FRAG_CORE must be 0 or 1"
[ "$GPU_VERT_CORE" = "0" ] || [ "$GPU_VERT_CORE" = "1" ] || \
    fail "GPU_VERT_CORE must be 0 or 1"
[ "$GPU_VERT_CORE" = "0" ] || [ "$GPU_FRAG_CORE" = "1" ] || \
    fail "GPU_VERT_CORE=1 requires GPU_FRAG_CORE=1"
pow2() { [ "$1" -ge 1 ] && [ "$(($1 & ($1 - 1)))" = "0" ]; }
pow2 "$GPU_WIDTH" && pow2 "$GPU_HEIGHT" || \
    fail "GPU_WIDTH/GPU_HEIGHT must be powers of two"
[ "$GPU_WIDTH" -ge 16 ] && [ "$GPU_HEIGHT" -ge 16 ] || \
    fail "GPU_WIDTH/GPU_HEIGHT must be at least 16"

# ARTI supports an isolated Ninja install under QEMU_TOOLS. Prefer the system
# executable when one is already available; this avoids an unnecessary pip
# download and works around Python installations whose ninja wheel omits the
# bin/ninja launcher under --target.
if [ ! -f "$QEMU_TOOLS/bin/ninja" ] && command -v ninja >/dev/null 2>&1; then
    mkdir -p "$QEMU_TOOLS/bin"
    ln -s "$(command -v ninja)" "$QEMU_TOOLS/bin/ninja"
fi

# ---------------------------------------------------------------------------
# Dependency cache validation
#
# Cached artifacts are validated with real markers (configure script,
# top-level Makefile) instead of plain directory existence, so a mangled or
# partial extraction no longer passes a `-d` check yet still forces a fresh
# download on every run. Downloads extract into a temporary directory first
# and only move into place once complete, so an interrupted fetch never
# poisons the canonical cache path.
# ---------------------------------------------------------------------------
qemu_src_valid() {
    [ -n "$1" ] && [ -f "$1/configure" ] && [ -f "$1/meson.build" ]
}

if [ -z "${QEMU_SRC:-}" ]; then
    for qemu_candidate in "/tmp/qemu-${QEMU_VERSION}" \
                          "/tmp/qemu-src/qemu-${QEMU_VERSION}" \
                          "/tmp/qemu-src"; do
        if qemu_src_valid "$qemu_candidate"; then
            QEMU_SRC="$qemu_candidate"
            break
        fi
    done
    QEMU_SRC="${QEMU_SRC:-/tmp/qemu-${QEMU_VERSION}}"
fi
if ! qemu_src_valid "$QEMU_SRC"; then
    command -v curl >/dev/null 2>&1 || fail "curl is required to download the QEMU source"
    echo "=== 0/4 Downloading QEMU v$QEMU_VERSION source (missing or invalid at $QEMU_SRC) ==="
    QEMU_DL_STAGE="$(mktemp -d "${TMPDIR:-/tmp}/arti-qemu-dl.XXXXXX")"
    curl -sSL "https://download.qemu.org/qemu-${QEMU_VERSION}.tar.xz" | tar xJ -C "$QEMU_DL_STAGE"
    qemu_src_valid "$QEMU_DL_STAGE/qemu-${QEMU_VERSION}" || fail "QEMU source download failed"
    rm -rf "$QEMU_SRC"
    mv "$QEMU_DL_STAGE/qemu-${QEMU_VERSION}" "$QEMU_SRC"
    rm -rf "$QEMU_DL_STAGE"
fi
echo "QEMU source : $QEMU_SRC (valid)"

linux_src_valid() {
    [ -n "$1" ] && [ -f "$1/Makefile" ]
}

if [ -z "${LINUX_SRC:-}" ]; then
    for linux_candidate in "/tmp/linux-src/linux-${LINUX_VERSION}" \
                           "/tmp/linux-src"; do
        if linux_src_valid "$linux_candidate"; then
            LINUX_SRC="$linux_candidate"
            break
        fi
    done
    LINUX_SRC="${LINUX_SRC:-/tmp/linux-src/linux-${LINUX_VERSION}}"
fi
if ! linux_src_valid "$LINUX_SRC"; then
    command -v curl >/dev/null 2>&1 || fail "curl is required to download the Linux source"
    echo "=== 0/4 Downloading Linux v$LINUX_VERSION source (missing or invalid at $LINUX_SRC) ==="
    LINUX_DL_STAGE="$(mktemp -d "${TMPDIR:-/tmp}/arti-linux-dl.XXXXXX")"
    curl -sSL "https://cdn.kernel.org/pub/linux/kernel/v${LINUX_VERSION%%.*}.x/linux-${LINUX_VERSION}.tar.xz" \
        | tar xJ -C "$LINUX_DL_STAGE"
    linux_src_valid "$LINUX_DL_STAGE/linux-${LINUX_VERSION}" || fail "Linux source download failed"
    rm -rf "$LINUX_SRC"
    mv "$LINUX_DL_STAGE/linux-${LINUX_VERSION}" "$LINUX_SRC"
    rm -rf "$LINUX_DL_STAGE"
fi
echo "Linux source: $LINUX_SRC (valid)"

echo "=== 1/4 Emit GpuHostAxi RTL ==="
if [ "$GPU_VERT_CORE" = "1" ]; then
    (cd "$GPU_DIR" && \
        sbt "runMain opengpu.elaboration.EmitGpuHostAxi generated/host --frag-core --vert-core --width $GPU_WIDTH --height $GPU_HEIGHT")
    TIMEOUT="${TIMEOUT:-120}"
elif [ "$GPU_FRAG_CORE" = "1" ]; then
    (cd "$GPU_DIR" && \
        sbt "runMain opengpu.elaboration.EmitGpuHostAxi generated/host --frag-core --width $GPU_WIDTH --height $GPU_HEIGHT")
    TIMEOUT="${TIMEOUT:-120}"
else
    (cd "$GPU_DIR" && \
        sbt "runMain opengpu.elaboration.EmitGpuHostAxi generated/host --width $GPU_WIDTH --height $GPU_HEIGHT")
    TIMEOUT="${TIMEOUT:-180}"
fi
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
LINUX_SRC="$LINUX_SRC" \
BUSYBOX_DIR="$BUSYBOX_DIR" \
SLIRP_INSTALL="$SLIRP_INSTALL" \
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
KCFLAGS="${KCFLAGS:-} -DOPENGPU_DRAW_WAIT_MS=$ARTI_GPU_DRAW_WAIT_MS \
    -DOPENGPU_DEFAULT_WIDTH=$GPU_WIDTH -DOPENGPU_DEFAULT_HEIGHT=$GPU_HEIGHT" \
    "$ARTI_DIR/examples/linux_arti_driver/build_driver.sh" \
        --dir "$DRIVER_STAGE" \
        --module gpu_drv \
        --output "$DRIVER_OUTPUT"

DRIVER_KO="$DRIVER_OUTPUT/gpu_drv.ko"
DRIVER_MANIFEST="$DRIVER_OUTPUT/gpu_drv.deps"
[ -f "$DRIVER_KO" ] || fail "driver build did not produce $DRIVER_KO"

echo "=== 4/4 Build the guest DRM test and boot Linux ==="
linux_src_valid "$LINUX_SRC" || \
    fail "Linux source not found (no Makefile) at $LINUX_SRC"
HOST_CC="${HOST_CC:-cc}"
command -v "$HOST_CC" >/dev/null 2>&1 || \
    fail "host C compiler is required for the shader validator test"
VALIDATOR_TEST="$DRIVER_OUTPUT/opengpu_shader_validator_test"
"$HOST_CC" -std=c11 -O2 -Wall -Wextra -Werror -I"$GPU_DIR/driver" \
    -o "$VALIDATOR_TEST" \
    "$GPU_DIR/driver/tests/opengpu_shader_validator_test.c"
"$VALIDATOR_TEST"
if [ ! -f "$LINUX_HEADERS/include/drm/drm.h" ]; then
    HEADER_TOOLS_PATH="/opt/homebrew/bin:$PATH"
    if [ -x /opt/homebrew/opt/gnu-sed/libexec/gnubin/sed ]; then
        HEADER_TOOLS_PATH="/opt/homebrew/opt/gnu-sed/libexec/gnubin:$HEADER_TOOLS_PATH"
    fi
    PATH="$HEADER_TOOLS_PATH" gmake -s -C "$LINUX_SRC" ARCH=arm64 \
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
    -DTEST_WIDTH="$GPU_WIDTH" -DTEST_HEIGHT="$GPU_HEIGHT" \
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
export QEMU_DISPLAY HOLD_AFTER_TEST TIMEOUT
export QEMU_SRC QEMU_FW_DIR="$QEMU_SRC/pc-bios"
"$HARNESS_STAGE/run_linux_test.sh"
