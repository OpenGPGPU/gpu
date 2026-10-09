#!/usr/bin/env bash
# Build the 640x480 FlashSim GPU, stage the GLES driver, and prove GTK ellipse
# coverage in a fresh Debian guest. Prepare Mesa and the AArch64 cross
# libraries first (see docs/GTK_COVERAGE.md).
set -euo pipefail

GPU_DIR="$(cd "$(dirname "$0")/.." && pwd)"
ARTI_DIR="${ARTI_DIR:-$GPU_DIR/../arti}"
FLASHSIM_DIR="${FLASHSIM_DIR:-$GPU_DIR/../FlashSim}"
ARTI_WORK="${ARTI_WORK:-$GPU_DIR/../arti-work}"
PREFIX="${PREFIX:-$GPU_DIR/depends/aarch64-root}"
MESA_SRC="${MESA_SRC:-$GPU_DIR/depends/mesa}"
MESA_BUILD="${MESA_BUILD:-$GPU_DIR/depends/mesa-build}"
GPU_TILE_BINNING="${GPU_TILE_BINNING:-0}"
INTEGRATION_CONFIG="$GPU_DIR/driver/gpu_integration_debian.yaml"
eval "$(python3 "$GPU_DIR/scripts/gpu_display_config.py" --shell "$INTEGRATION_CONFIG")"
[ "$GPU_MODE" = 640x480 ] || {
    echo "expected the 640x480 coverage profile, got $GPU_MODE" >&2
    exit 1
}
if [ "$GPU_TILE_BINNING" = 1 ]; then
    DISPLAY_WORK="${DISPLAY_WORK:-$ARTI_WORK/debian-${GPU_MODE}-tile}"
    PROOF_WORK="${PROOF_WORK:-$ARTI_WORK/gtk-coverage-proof-tile}"
else
    DISPLAY_WORK="${DISPLAY_WORK:-$ARTI_WORK/debian-$GPU_MODE}"
    PROOF_WORK="${PROOF_WORK:-$ARTI_WORK/gtk-coverage-proof}"
fi
DRIVER_OUTPUT="$DISPLAY_WORK/opengpu-driver"
mkdir -p "$PROOF_WORK"

for tool in expect ssh python3 sbt meson ninja aarch64-linux-gnu-gcc; do
    command -v "$tool" >/dev/null || { echo "missing $tool" >&2; exit 1; }
done
[ -f "$ARTI_DIR/examples/linux_arti_driver/setup_env.sh" ] || {
    echo "ARTI checkout missing at $ARTI_DIR" >&2; exit 1;
}
[ -d "$FLASHSIM_DIR/flashsim" ] || {
    echo "FlashSim checkout missing at $FLASHSIM_DIR" >&2; exit 1;
}
[ -f "$MESA_SRC/meson.build" ] || {
    echo "Mesa 22.3.6 checkout missing at $MESA_SRC" >&2; exit 1;
}
[ -f "$PREFIX/lib/pkgconfig/libdrm.pc" ] || {
    echo "AArch64 libdrm missing under $PREFIX" >&2; exit 1;
}

# Meson needs the current workspace's prefix; the checked-in cross file
# contains a developer-specific pkg_config_libdir.
CROSS="$PROOF_WORK/aarch64-linux-gnu-cross.txt"
python3 "$GPU_DIR/scripts/aarch64_cross_prefix.py" \
    "$GPU_DIR/scripts/aarch64-linux-gnu-cross.txt" "$CROSS" "$PREFIX"

if [ "${GTK_PROOF_SKIP_BUILD:-0}" != 1 ]; then
    echo '=== Build FlashSim ARTI GPU ==='
    (cd "$FLASHSIM_DIR" && python3 -m flashsim setup-circt)
    ARTI_DIR="$ARTI_DIR" FLASHSIM_DIR="$FLASHSIM_DIR" ARTI_WORK="$ARTI_WORK" \
        DISPLAY_WORK="$DISPLAY_WORK" \
        GPU_SIM=flashsim INTEGRATION_CONFIG="$INTEGRATION_CONFIG" \
        "$GPU_DIR/scripts/build_arti_debian_display.sh"

    echo '=== Build and stage guest GLES driver ==='
    PREFIX="$PREFIX" MESA_SRC="$MESA_SRC" MESA_BUILD="$MESA_BUILD" \
        CROSS="$CROSS" CROSS_GCC="$GPU_DIR/scripts/aarch64_gcc_compat.sh" \
        DRIVER_OUTPUT="$DRIVER_OUTPUT" \
        INTEGRATION_CONFIG="$INTEGRATION_CONFIG" \
        "$GPU_DIR/scripts/build_mesa_opengpu.sh"
fi

if [ "${GTK_PROOF_SKIP_BUILD:-0}" = 1 ] && [ "$GPU_TILE_BINNING" = 1 ]; then
    grep -q 'tile_binning=1' "$DISPLAY_WORK/display-mode.txt" 2>/dev/null || {
        echo "cached Debian GPU was not built with GPU_TILE_BINNING=1" >&2
        exit 1
    }
fi

for artifact in "$DISPLAY_WORK/qemu-arti-build/qemu-system-aarch64" \
                "$DRIVER_OUTPUT/gpu_drv.ko" \
                "$DRIVER_OUTPUT/opengpu_gl_gtk_coverage.bin" \
                "$DRIVER_OUTPUT/opengpu_dri.so"; do
    [ -f "$artifact" ] || { echo "missing guest artifact: $artifact" >&2; exit 1; }
done

BASE="$ARTI_WORK/debian-arm64-base.qcow2"
[ -f "$BASE" ] || { echo "Debian base image missing at $BASE" >&2; exit 1; }
QEMU_IMG="${QEMU_IMG:-$DISPLAY_WORK/qemu-arti-build/qemu-img}"
[ -x "$QEMU_IMG" ] || { echo "qemu-img missing at $QEMU_IMG" >&2; exit 1; }
DISK="$PROOF_WORK/guest.qcow2"
rm -f "$DISK"
cp "$BASE" "$DISK"
"$QEMU_IMG" resize "$DISK" 10G >/dev/null

PORT="$(python3 - <<'PY'
import socket
with socket.socket() as sock:
    sock.bind(('127.0.0.1', 0))
    print(sock.getsockname()[1])
PY
)"
SERIAL_LOG="$PROOF_WORK/serial.log"
BOOT_LOG="$PROOF_WORK/boot.log"
PROOF_LOG="$PROOF_WORK/proof.log"
LOAD_LOG="$PROOF_WORK/load.log"
: > "$SERIAL_LOG"
: > "$BOOT_LOG"
: > "$PROOF_LOG"
: > "$LOAD_LOG"
if [ "${GTK_PROOF_DESKTOP:-0}" = 1 ]; then
    SCANOUT="$PROOF_WORK/desktop-scanout.ppm"
    rm -f "$SCANOUT"
fi
qemu_pid=""
cleanup() {
    if [ -n "$qemu_pid" ]; then
        kill "$qemu_pid" 2>/dev/null || true
        wait "$qemu_pid" 2>/dev/null || true
    fi
    rm -f "$DISK"
}
trap cleanup EXIT

echo "=== Boot fresh Debian guest on SSH port $PORT ==="
ARTI_DIR="$ARTI_DIR" ARTI_WORK="$ARTI_WORK" FLASHSIM_DIR="$FLASHSIM_DIR" \
    DISPLAY_WORK="$DISPLAY_WORK" DRIVER_OUTPUT="$DRIVER_OUTPUT" \
    INTEGRATION_CONFIG="$INTEGRATION_CONFIG" GPU_SIM=flashsim \
    QEMU_DISPLAY=none OPENGPU_AUTO_DISPLAY=0 BUILD_USERSPACE=1 \
    REBUILD_CLOUDINIT=1 CLOUDINIT_PACKAGES=0 \
    ARTI_DISPLAY_DUMP="${SCANOUT:-}" ARTI_DISPLAY_DUMP_PIXEL=243044 \
    DISK="$DISK" DEBIAN_BASE="$BASE" SSH_PORT="$PORT" SERIAL_LOG="$SERIAL_LOG" \
    "$GPU_DIR/scripts/run_arti_debian.sh" > "$BOOT_LOG" 2>&1 &
qemu_pid=$!

ready=0
for _ in $(seq 1 120); do
    kill -0 "$qemu_pid" 2>/dev/null || {
        echo "QEMU exited before guest became ready; see $BOOT_LOG" >&2
        exit 1
    }
    if "$GPU_DIR/scripts/gtk_coverage_guest.exp" "$PORT" 15 \
        'test -x /root/opengpu_gl_gtk_coverage.bin' >/dev/null 2>&1; then
        ready=1
        break
    fi
    sleep 5
done
[ "$ready" = 1 ] || { echo "guest did not become ready; see $SERIAL_LOG" >&2; exit 1; }

echo '=== Load guest GPU driver ==='
"$GPU_DIR/scripts/gtk_coverage_guest.exp" "$PORT" 120 \
    '/root/load_opengpu.sh && test -c /dev/dri/card0' | tee "$LOAD_LOG"

echo '=== Check GTK ellipse coverage ==='
"$GPU_DIR/scripts/gtk_coverage_guest.exp" "$PORT" 600 \
    'LD_LIBRARY_PATH=/root LIBGL_DRIVERS_PATH=/root OPENGPU_GL_EXIT=1 /root/opengpu_gl_gtk_coverage.bin' | tee "$PROOF_LOG"
grep -qF 'gl_gtk_coverage: mode 640x480 inside=0xff0000ff outside=0x00000000' "$PROOF_LOG"
grep -qF 'OPENGPU GL GTK COVERAGE PASS' "$PROOF_LOG"
echo 'GTK coverage guest proof: PASS'

if [ "${GTK_PROOF_DESKTOP:-0}" = 1 ]; then
    DESKTOP_LOG="$PROOF_WORK/desktop.log"
    echo '=== Check multi-draw desktop and present ==='
    "$GPU_DIR/scripts/gtk_coverage_guest.exp" "$PORT" 1800 \
        '/root/opengpu_pipe_desktop /dev/dri/card0 /root/opengpu_fragment_tint.bin' \
        | tee "$DESKTOP_LOG"
    grep -qF 'OPENGPU DESKTOP PASS: 640x480 ' "$DESKTOP_LOG"
    python3 - "$SCANOUT" "${GTK_PROOF_SCANOUT_REFERENCE:-}" <<'PY'
import pathlib
import re
import sys

path = pathlib.Path(sys.argv[1])
if not path.is_file():
    sys.exit(f"missing QEMU scanout dump: {path}")
data = path.read_bytes()
header = re.match(rb"P6\n([0-9]+) ([0-9]+)\n255\n", data)
if header is None or header.groups() != (b"640", b"480"):
    sys.exit(f"invalid 640x480 scanout dump: {path}")
pixels = data[header.end():]
if len(pixels) != 640 * 480 * 3:
    sys.exit(f"incomplete scanout dump: {path}")
if pixels[3 * (640 + 1):3 * (640 + 2)] != bytes.fromhex("243044"):
    sys.exit(f"desktop panel missing from scanout: {path}")
colors = {pixels[i:i + 3] for i in range(0, len(pixels), 3)}
for label, color in (("window", "f2efe8"), ("cursor", "ffe14a"),
                     ("triangle", "ffbf40")):
    if bytes.fromhex(color) not in colors:
        sys.exit(f"desktop {label} missing from scanout: {path}")
if sys.argv[2]:
    reference = pathlib.Path(sys.argv[2])
    if not reference.is_file():
        sys.exit(f"missing scanout reference: {reference}")
    if data != reference.read_bytes():
        sys.exit(f"desktop scanout differs from {reference}")
print(f"Desktop scanout proof: PASS ({path})")
PY
    echo 'Desktop guest proof: PASS'
fi
