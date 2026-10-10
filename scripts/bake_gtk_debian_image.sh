#!/usr/bin/env bash
# Create a persistent Debian image with GTK3/Epoxy development packages.
set -euo pipefail
GPU_DIR="$(cd "$(dirname "$0")/.." && pwd)"
ARTI_WORK="${ARTI_WORK:-$GPU_DIR/../arti-work}"
ARTI_DIR="${ARTI_DIR:-$GPU_DIR/../arti}"
DISPLAY_WORK="${DISPLAY_WORK:-$ARTI_WORK/debian-640x480}"
BASE="${DEBIAN_BASE:-$ARTI_WORK/debian-arm64-base.qcow2}"
DISK="${GTK_DEBIAN_BASE:-$ARTI_WORK/debian-gtk-arm64.qcow2}"
QEMU_IMG="${QEMU_IMG:-$DISPLAY_WORK/qemu-arti-build/qemu-img}"
if [ -n "${SSH_PORT:-}" ]; then PORT="$SSH_PORT"; else
    PORT="$(python3 - <<'PY_PORT'
import socket
with socket.socket() as sock:
    sock.bind(('127.0.0.1', 0))
    print(sock.getsockname()[1])
PY_PORT
)"
fi
[ -f "$BASE" ] || { echo "missing Debian base image: $BASE" >&2; exit 1; }
[ -x "$QEMU_IMG" ] || { echo "missing qemu-img: $QEMU_IMG" >&2; exit 1; }
if [ -f "$DISK" ]; then echo "GTK image already exists: $DISK"; exit 0; fi
cp "$BASE" "$DISK"
"$QEMU_IMG" resize "$DISK" 10G >/dev/null
log="$ARTI_WORK/debian-gtk-bake.log"
qemu_pid=""
cleanup() { [ -z "$qemu_pid" ] || { kill "$qemu_pid" 2>/dev/null || true; wait "$qemu_pid" 2>/dev/null || true; }; }
trap cleanup EXIT
ARTI_DIR="$ARTI_DIR" ARTI_WORK="$ARTI_WORK" DISPLAY_WORK="$DISPLAY_WORK" \
    GPU_SIM=flashsim GPU_FRAG_CORE=1 GPU_VERT_CORE=1 QEMU_DISPLAY=none \
    OPENGPU_AUTO_DISPLAY=0 BUILD_USERSPACE=0 REBUILD_CLOUDINIT=1 \
    CLOUDINIT_PACKAGES=0 DISK="$DISK" DEBIAN_BASE="$BASE" SSH_PORT="$PORT" \
    bash "$GPU_DIR/scripts/run_arti_debian.sh" > "$log" 2>&1 &
qemu_pid=$!
ready=0
for _ in $(seq 1 180); do
    kill -0 "$qemu_pid" 2>/dev/null || { echo "guest exited; see $log" >&2; exit 1; }
    if "$GPU_DIR/scripts/gtk_coverage_guest.exp" "$PORT" 15 'test -x /root/load_opengpu.sh' >/dev/null 2>&1; then ready=1; break; fi
    sleep 5
done
[ "$ready" = 1 ] || { echo "guest did not become ready; see $log" >&2; exit 1; }
"$GPU_DIR/scripts/gtk_coverage_guest.exp" "$PORT" 900 \
    'apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y kmod build-essential git vim-tiny python3 ca-certificates curl pciutils strace gdb pkg-config libgtk-3-dev libepoxy-dev weston && poweroff -f' || true
sleep 3
printf 'gtk-packages=1\n' > "$DISK.gtk-ready"
echo "GTK Debian image ready: $DISK"
echo "Use GTK_DEBIAN_BASE=$DISK with scripts/run_gtk_coverage_guest.sh."
