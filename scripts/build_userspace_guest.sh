#!/usr/bin/env bash
# Cross-build static aarch64 userspace examples into the ARTI driver output
# directory so build_cloudinit.sh can stage them on the OPENGPU modules ISO.
#
# Default: fixed-function set. With GPU_FRAG_CORE=1 also emit fragment_tint.
set -euo pipefail

GPU_DIR="$(cd "$(dirname "$0")/.." && pwd)"
ARTI_WORK="${ARTI_WORK:-$(cd "$GPU_DIR/.." && pwd)/arti-work}"
DRIVER_OUTPUT="${DRIVER_OUTPUT:-$ARTI_WORK/opengpu-driver}"
LINUX_HEADERS="${LINUX_HEADERS:-$ARTI_WORK/linux-headers}"
CROSS_GCC="${CROSS_GCC:-aarch64-linux-gnu-gcc}"
RISCV_GCC="${RISCV_GCC:-riscv64-unknown-elf-gcc}"
RISCV_OBJCOPY="${RISCV_OBJCOPY:-riscv64-unknown-elf-objcopy}"
GPU_FRAG_CORE="${GPU_FRAG_CORE:-0}"

fail() { echo "FAIL: $*" >&2; exit 1; }

command -v "$CROSS_GCC" >/dev/null || fail "cross compiler not found: $CROSS_GCC"
[ -d "$LINUX_HEADERS/include" ] || fail "Linux headers missing at $LINUX_HEADERS/include"
mkdir -p "$DRIVER_OUTPUT"

CFLAGS=(-static -std=c11 -O2 -Wall -Wextra -Werror
    -I"$LINUX_HEADERS/include" -I"$GPU_DIR/driver" -I"$GPU_DIR/userspace")

echo "=== Build OpenGPU guest userspace → $DRIVER_OUTPUT (FRAG=$GPU_FRAG_CORE) ==="

"$CROSS_GCC" "${CFLAGS[@]}" \
    -o "$DRIVER_OUTPUT/opengpu_kms_present" \
    "$GPU_DIR/userspace/examples/kms_present.c"
"$CROSS_GCC" "${CFLAGS[@]}" \
    -o "$DRIVER_OUTPUT/opengpu_triangle_present" \
    "$GPU_DIR/userspace/opengpu.c" \
    "$GPU_DIR/userspace/examples/triangle_present.c"

"$RISCV_GCC" -march=rv32imv_zicsr -mabi=ilp32 -c \
    -o "$DRIVER_OUTPUT/opengpu_compute_shader.o" \
    "$GPU_DIR/userspace/shaders/round_modes.S"
"$RISCV_OBJCOPY" -O binary --only-section=.text \
    "$DRIVER_OUTPUT/opengpu_compute_shader.o" \
    "$DRIVER_OUTPUT/opengpu_compute_shader.bin"
rm -f "$DRIVER_OUTPUT/opengpu_compute_shader.o"

"$CROSS_GCC" "${CFLAGS[@]}" \
    -o "$DRIVER_OUTPUT/opengpu_compute_example" \
    "$GPU_DIR/userspace/opengpu.c" "$GPU_DIR/userspace/examples/compute.c"
python3 "$GPU_DIR/scripts/validate_shader_corpus.py" \
    --emit fp_unary "$DRIVER_OUTPUT/opengpu_fp_unary.bin"
"$CROSS_GCC" "${CFLAGS[@]}" \
    -o "$DRIVER_OUTPUT/opengpu_fp_unary" \
    "$GPU_DIR/userspace/opengpu.c" "$GPU_DIR/userspace/examples/fp_unary.c"
"$CROSS_GCC" "${CFLAGS[@]}" \
    -o "$DRIVER_OUTPUT/opengpu_triangle_example" \
    "$GPU_DIR/userspace/opengpu.c" "$GPU_DIR/userspace/examples/triangle.c"

for ex in pipe_clear_draw pipe_compute pipe_blit pipe_strided_blit \
          pipe_resolve pipe_texture_draw pipe_depth_pass pipe_msaa_draw \
          pipe_vertex_draw pipe_present; do
    "$CROSS_GCC" "${CFLAGS[@]}" \
        -o "$DRIVER_OUTPUT/opengpu_${ex}" \
        "$GPU_DIR/userspace/opengpu.c" "$GPU_DIR/userspace/pipe_opengpu.c" \
        "$GPU_DIR/userspace/examples/${ex}.c"
done

if [ "$GPU_FRAG_CORE" = "1" ]; then
    python3 "$GPU_DIR/scripts/validate_shader_corpus.py" \
        --emit fragment_tint "$DRIVER_OUTPUT/opengpu_fragment_tint.bin"
    python3 "$GPU_DIR/scripts/validate_shader_corpus.py" \
        --emit fragment_texture "$DRIVER_OUTPUT/opengpu_fragment_texture.bin"
    "$CROSS_GCC" "${CFLAGS[@]}" \
        -o "$DRIVER_OUTPUT/opengpu_fragment_tint" \
        "$GPU_DIR/userspace/opengpu.c" \
        "$GPU_DIR/userspace/examples/fragment_tint.c"
fi

echo "Guest binaries:"
ls -1 "$DRIVER_OUTPUT"/opengpu_kms_present \
    "$DRIVER_OUTPUT"/opengpu_triangle_present \
    "$DRIVER_OUTPUT"/opengpu_pipe_* \
    "$DRIVER_OUTPUT"/opengpu_compute_example \
    "$DRIVER_OUTPUT"/opengpu_fp_unary* \
    "$DRIVER_OUTPUT"/opengpu_triangle_example \
    "$DRIVER_OUTPUT"/opengpu_compute_shader.bin \
    "$DRIVER_OUTPUT"/opengpu_fragment_tint* \
    "$DRIVER_OUTPUT"/opengpu_fragment_texture* 2>/dev/null | sed 's/^/  /' || true
