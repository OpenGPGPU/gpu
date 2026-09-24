#!/usr/bin/env bash
# Boot the OpenGPU DRM guest with ARTI guest-memory scanout.
#
# Default path is headless but verifies present by dumping the first enabled
# scanout framebuffer to a PPM (ARTI_DISPLAY_DUMP). Set QEMU_DISPLAY=cocoa|gtk
# to also open a window (and optionally ARTI_DISPLAY_REFRESH_HZ=60).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
GPU_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
ARTI_WORK="${ARTI_WORK:-$(cd "$GPU_DIR/.." && pwd)/arti-work}"
mkdir -p "$ARTI_WORK"

: "${HOLD_AFTER_TEST:=1}"
: "${TIMEOUT:=600}"
# Continuous refresh is for interactive windows only; PPM dump is one-shot.
: "${ARTI_DISPLAY_REFRESH_HZ:=0}"
: "${ARTI_DISPLAY_DUMP:=$ARTI_WORK/linux-test/scanout.ppm}"
: "${QEMU_DISPLAY:=none}"
case "$QEMU_DISPLAY" in
    cocoa) QEMU_DISPLAY="cocoa,zoom-to-fit=on" ;;
esac

if [ "$QEMU_DISPLAY" != "none" ] && [ "${ARTI_DISPLAY_REFRESH_HZ}" = "0" ]; then
    ARTI_DISPLAY_REFRESH_HZ=60
    HOLD_AFTER_TEST="${HOLD_AFTER_TEST_WINDOW:-30}"
fi

mkdir -p "$(dirname "$ARTI_DISPLAY_DUMP")"
rm -f "$ARTI_DISPLAY_DUMP"

export QEMU_DISPLAY HOLD_AFTER_TEST TIMEOUT
export ARTI_DISPLAY_REFRESH_HZ ARTI_DISPLAY_DUMP

set +e
"$SCRIPT_DIR/run_arti_gpu.sh"
status=$?
set -e

if [ "$status" -ne 0 ]; then
    echo "FAIL: run_arti_gpu.sh exited $status" >&2
    exit "$status"
fi

if [ ! -f "$ARTI_DISPLAY_DUMP" ]; then
    echo "FAIL: missing scanout dump $ARTI_DISPLAY_DUMP" >&2
    echo "      (rebuild QEMU after ARTI display dump support landed)" >&2
    exit 1
fi

python3 - "$ARTI_DISPLAY_DUMP" "${GPU_WIDTH:-64}" "${GPU_HEIGHT:-64}" <<'PY'
import pathlib
import re
import sys

path = pathlib.Path(sys.argv[1])
expected_size = (int(sys.argv[2]), int(sys.argv[3]))
data = path.read_bytes()
header = re.match(rb"P6\n([0-9]+) ([0-9]+)\n255\n", data)
if header is None:
    sys.exit(f"FAIL: scanout dump is not a P6 PPM ({path})")
size = tuple(map(int, header.groups()))
pixels = data[header.end():]
if size != expected_size or len(pixels) != size[0] * size[1] * 3:
    sys.exit(f"FAIL: scanout dump has wrong dimensions or length ({size}, {len(pixels)} bytes)")
if not any(pixels):
    sys.exit("FAIL: scanout is entirely black; rendered pixels were not presented")
print(f"OPENGPU DISPLAY PRESENT PASS: {path} ({size[0]}x{size[1]}, {len(data)} bytes)")
PY
exit 0
