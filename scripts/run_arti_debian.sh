#!/usr/bin/env bash
# Interactive Debian ARM64 development shell against the embedded ARTI GPU.
#
# Unlike run_arti_gpu.sh (one-shot DRM self-test in initramfs), this boots a
# persistent qcow2 so you can apt-install tools, insmod the driver, and iterate.
# Cloud-init stays tiny; DRM .ko files ship on a separate OPENGPU ISO that is
# copied into /root on boot — no manual scp, no 14MB YAML.
#
# This script boots the separately built 64x64 display GPU. Build it once:
#   ./scripts/build_arti_debian_display.sh
# The 16x16 functional qualification binary stays separate.
# Optional side-by-side installs (fallback when the main binary is another backend):
#   $ARTI_WORK/debian-64x64/qemu-arti-build/qemu-system-aarch64.flashsim
#   $ARTI_WORK/debian-64x64/qemu-arti-build/qemu-system-aarch64.verilator
#
# Usage:
#   ./scripts/run_arti_debian.sh
#   GPU_SIM=verilator ./scripts/run_arti_debian.sh
#   GPU_SIM=flashsim QEMU_DISPLAY=cocoa ./scripts/run_arti_debian.sh
#   REBUILD_DISK=1 ./scripts/run_arti_debian.sh   # fresh qcow2 from base image
#   CLOUDINIT_PACKAGES=1 ./scripts/run_arti_debian.sh  # also apt-install tools (slow)
#
# OpenGPU loads at boot and presents a KMS frame by default. In the guest
# (root / arti):
#   systemctl status opengpu-boot-display.service
#   /root/load_opengpu.sh test      # opengpu_drm_test (modeset + flip → ARTI scanout)
#   /root/load_opengpu.sh examples  # pipe/userspace smoke (when staged)
#
# For a visible primary scanout window:
#   QEMU_DISPLAY=cocoa ./scripts/run_arti_debian.sh
# Set OPENGPU_AUTO_DISPLAY=0 to keep the old manual module-loading workflow.
# Headless present check (PPM of first scanout):
#   ./scripts/run_arti_display.sh
set -euo pipefail

GPU_DIR="$(cd "$(dirname "$0")/.." && pwd)"
ARTI_DIR="${ARTI_DIR:-$GPU_DIR/../arti}"
FLASHSIM_DIR="${FLASHSIM_DIR:-$GPU_DIR/../FlashSim}"
GPU_SIM="${GPU_SIM:-verilator}"
ARTI_WORK="${ARTI_WORK:-$(cd "$GPU_DIR/.." && pwd)/arti-work}"
mkdir -p "$ARTI_WORK"
export ARTI_WORK
DISPLAY_WORK="${DISPLAY_WORK:-$ARTI_WORK/debian-64x64}"
INTEGRATION_CONFIG="${INTEGRATION_CONFIG:-$GPU_DIR/driver/gpu_integration_debian.yaml}"

LINUX_BUILD="${LINUX_BUILD:-$ARTI_WORK/arti-linux-build}"
DRIVER_OUTPUT="${DRIVER_OUTPUT:-$DISPLAY_WORK/opengpu-driver}"
QEMU_BUILD="${QEMU_BUILD:-$DISPLAY_WORK/qemu-arti-build}"
KERNEL="${KERNEL:-$LINUX_BUILD/arch/arm64/boot/Image}"
DISK="${DISK:-$ARTI_WORK/arti-dev.qcow2}"
BASE="${DEBIAN_BASE:-$ARTI_WORK/debian-arm64-base.qcow2}"
CIDATA="${CIDATA:-$DISPLAY_WORK/cloud-init.iso}"
MODULES_ISO="${MODULES_ISO:-$DISPLAY_WORK/opengpu-modules.iso}"
DRIVER_KO="${DRIVER_KO:-$DRIVER_OUTPUT/gpu_drv.ko}"
DRIVER_MANIFEST="${DRIVER_MANIFEST:-$DRIVER_OUTPUT/gpu_drv.deps}"
REBUILD_DISK="${REBUILD_DISK:-0}"
REBUILD_CLOUDINIT="${REBUILD_CLOUDINIT:-1}"

# Remember whether the caller forced a specific QEMU path.
QEMU_FROM_ENV=0
if [ -n "${QEMU:-}" ]; then
    QEMU_FROM_ENV=1
fi

if [ -z "${QEMU_DISPLAY:-}" ]; then
    case "$(uname -s)" in
        Darwin) QEMU_DISPLAY="cocoa" ;;
        *)      QEMU_DISPLAY="gtk" ;;
    esac
fi
# Keep the host window resizable when the guest mode changes.
case "$QEMU_DISPLAY" in
    cocoa) QEMU_DISPLAY="cocoa,zoom-to-fit=on" ;;
esac

fail() {
    echo "FAIL: $*" >&2
    exit 1
}

qemu_linked_backend() {
    local bin="$1" syms
    if ! command -v nm >/dev/null 2>&1; then
        echo "unknown"
        return 0
    fi
    # Capture nm output first: under `set -o pipefail`, `nm | grep -q` fails
    # when grep closes early (nm gets SIGPIPE) even on a real match.
    syms="$(nm "$bin" 2>/dev/null || true)"
    # FlashSim emits C++ Dut methods; Verilator emits VGpuHostSystemAxi*.
    case "$syms" in
        *GpuHostSystemAxiDut*) echo "flashsim" ;;
        *VGpuHostSystemAxi*)   echo "verilator" ;;
        *)                     echo "unknown" ;;
    esac
}

resolve_qemu() {
    local cand linked
    case "$GPU_SIM" in
        flashsim|verilator) ;;
        *) fail "GPU_SIM must be verilator or flashsim, got $GPU_SIM" ;;
    esac

    if [ "$QEMU_FROM_ENV" = "1" ]; then
        [ -x "$QEMU" ] || fail "QEMU not executable: $QEMU"
    else
        QEMU=""
        for cand in \
            "$QEMU_BUILD/qemu-system-aarch64" \
            "$QEMU_BUILD/qemu-system-aarch64.$GPU_SIM"
        do
            if [ -x "$cand" ] && \
               [ "$(qemu_linked_backend "$cand")" = "$GPU_SIM" ]; then
                QEMU="$cand"
                break
            fi
        done
        [ -n "$QEMU" ] || fail \
"No QEMU binary linked for GPU_SIM=$GPU_SIM under $QEMU_BUILD.
Build it once (this script never recompiles):
  GPU_SIM=$GPU_SIM ./scripts/build_arti_debian_display.sh
Optional: keep side-by-side binaries as
  $QEMU_BUILD/qemu-system-aarch64.flashsim
  $QEMU_BUILD/qemu-system-aarch64.verilator"
    fi

    linked="$(qemu_linked_backend "$QEMU")"
    if [ "$linked" = "unknown" ]; then
        fail \
"Cannot determine RTL backend linked into:
  $QEMU
Rebuild with: GPU_SIM=$GPU_SIM ./scripts/build_arti_debian_display.sh"
    fi
    if [ "$linked" != "$GPU_SIM" ]; then
        fail \
"GPU_SIM=$GPU_SIM but QEMU is linked as $linked:
  $QEMU
This script does not recompile. Either:
  GPU_SIM=$linked ./scripts/run_arti_debian.sh
or rebuild/switch the binary:
  GPU_SIM=$GPU_SIM ./scripts/build_arti_debian_display.sh
  # optional: cp $QEMU_BUILD/qemu-system-aarch64 \\
  #             $QEMU_BUILD/qemu-system-aarch64.$GPU_SIM"
    fi
}

[ -d "$ARTI_DIR/examples/linux_arti_driver" ] || \
    fail "ARTI repository not found at $ARTI_DIR (set ARTI_DIR)"
[ -f "$INTEGRATION_CONFIG" ] || fail "integration profile not found: $INTEGRATION_CONFIG"
resolve_qemu
[ -f "$KERNEL" ] || fail "kernel Image not found at $KERNEL"
[ -f "$DRIVER_KO" ] || fail "driver not found at $DRIVER_KO — build with scripts/build_arti_debian_display.sh"
[ -f "$ARTI_DIR/examples/linux_arti_driver/build_cloudinit.sh" ] || \
    fail "build_cloudinit.sh missing under $ARTI_DIR"
[ -f "$ARTI_DIR/examples/linux_arti_driver/run_debian.sh" ] || \
    fail "run_debian.sh missing under $ARTI_DIR"

QEMU_IMG="${QEMU_IMG:-}"
if [ -z "$QEMU_IMG" ]; then
    for cand in "$QEMU_BUILD/qemu-img" /tmp/qemu-arti-build/qemu-img "$(command -v qemu-img || true)"; do
        [ -n "$cand" ] && [ -x "$cand" ] || continue
        QEMU_IMG="$cand"
        break
    done
fi

ensure_disk() {
    if [ "$REBUILD_DISK" = "1" ] && [ -f "$DISK" ]; then
        echo "REBUILD_DISK=1: removing $DISK"
        rm -f "$DISK"
    fi
    if [ -f "$DISK" ]; then
        echo "Debian disk : $DISK (persistent)"
        return 0
    fi
    [ -f "$BASE" ] || fail \
        "Debian base image missing at $BASE
Download once, e.g.:
  curl -fL -o $BASE \\
    https://cloud.debian.org/images/cloud/bookworm/latest/debian-12-generic-arm64.qcow2"
    [ -n "$QEMU_IMG" ] || fail "qemu-img not found (needed to create $DISK)"
    echo "Creating 10G working disk from $BASE ..."
    cp "$BASE" "$DISK"
    "$QEMU_IMG" resize "$DISK" 10G
}

echo "=== OpenGPU ARTI Debian dev shell ==="
echo "  GPU_SIM   : $GPU_SIM (selects prebuilt QEMU; no recompile)"
echo "  ARTI_WORK : $ARTI_WORK"
echo "  QEMU      : $QEMU"
echo "  Display   : $QEMU_DISPLAY"
echo "  Driver    : $DRIVER_KO"

ensure_disk

# Stage pipe/userspace binaries next to the driver so the OPENGPU ISO
# ships /root/load_opengpu.sh examples. Skip with BUILD_USERSPACE=0.
# Match GPU_FRAG_CORE to the QEMU binary you boot (this script never
# rebuilds RTL).
if [ "${BUILD_USERSPACE:-1}" = "1" ]; then
    echo "=== Cross-build guest userspace examples (FRAG=${GPU_FRAG_CORE:-0}) ==="
    ARTI_WORK="$ARTI_WORK" DRIVER_OUTPUT="$DRIVER_OUTPUT" \
    GPU_FRAG_CORE="${GPU_FRAG_CORE:-0}" \
        bash "$GPU_DIR/scripts/build_userspace_guest.sh"
fi
if [ "${OPENGPU_AUTO_DISPLAY:-1}" = "1" ]; then
    [ -x "$DRIVER_OUTPUT/opengpu_kms_present" ] || \
        fail "autodisplay needs $DRIVER_OUTPUT/opengpu_kms_present (set BUILD_USERSPACE=1)"
fi

if [ "$REBUILD_CLOUDINIT" = "1" ]; then
    echo "=== Refresh cloud-init + OPENGPU modules ISO ==="
    INTEGRATION_CONFIG="$INTEGRATION_CONFIG" \
    ARTI_DIR="$ARTI_DIR" \
    ARTI_WORK="$ARTI_WORK" \
    LINUX_BUILD="$LINUX_BUILD" \
    DRIVER_KO="$DRIVER_KO" \
    DRIVER_MANIFEST="$DRIVER_MANIFEST" \
    OUTPUT="$CIDATA" \
    MODULES_ISO="$MODULES_ISO" \
    OPENGPU_USERSPACE_DIR="$DRIVER_OUTPUT" \
    OPENGPU_AUTO_DISPLAY="${OPENGPU_AUTO_DISPLAY:-1}" \
        CLOUDINIT_PACKAGES="${CLOUDINIT_PACKAGES:-0}" \
        bash "$ARTI_DIR/examples/linux_arti_driver/build_cloudinit.sh"
fi
[ -f "$CIDATA" ] || fail "cloud-init ISO missing at $CIDATA"
[ -f "$MODULES_ISO" ] || fail "modules ISO missing at $MODULES_ISO"

echo "=== Booting Debian ==="
echo "  Login  : root / arti   (or debian / arti)"
echo "  After cloud-init finishes:"
echo "    systemctl status opengpu-boot-display.service"
echo "    /root/load_opengpu.sh"
echo "    /root/load_opengpu.sh test"
echo "    /root/load_opengpu.sh examples"
echo ""

export ARTI_DIR ARTI_WORK INTEGRATION_CONFIG
export QEMU KERNEL DISK CIDATA MODULES_ISO
export DRIVER_KO DRIVER_MANIFEST
export QEMU_DISPLAY LINUX_BUILD
export OPENGPU_AUTO_DISPLAY="${OPENGPU_AUTO_DISPLAY:-1}"
export PATH="/opt/homebrew/bin:${QEMU_TOOLS:-$ARTI_WORK/qemu-build-tools}/bin:${PATH:-}"

# Prefer firmware next to the work tree when present.
if [ -z "${QEMU_FW_DIR:-}" ]; then
    if [ -f "$ARTI_WORK/qemu-pc-bios/efi-virtio.rom" ]; then
        export QEMU_FW_DIR="$ARTI_WORK/qemu-pc-bios"
    elif [ -n "${QEMU_SRC:-}" ] && [ -f "$QEMU_SRC/pc-bios/efi-virtio.rom" ]; then
        export QEMU_FW_DIR="$QEMU_SRC/pc-bios"
    fi
fi

exec bash "$ARTI_DIR/examples/linux_arti_driver/run_debian.sh"
