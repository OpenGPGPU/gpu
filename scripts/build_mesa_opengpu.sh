#!/usr/bin/env bash
# Cross-build Mesa 22.3 with the out-of-tree OpenGPU Gallium driver and the
# GLES clear+triangle guest program. Mesa stays in the gitignored depends/
# checkout; this script does not fork it.
set -euo pipefail

GPU_DIR="$(cd "$(dirname "$0")/.." && pwd)"
PREFIX="${PREFIX:-$GPU_DIR/depends/aarch64-root}"
MESA_SRC="${MESA_SRC:-$GPU_DIR/depends/mesa}"
MESA_BUILD="${MESA_BUILD:-$GPU_DIR/depends/mesa-build}"
CROSS="${CROSS:-$GPU_DIR/scripts/aarch64-linux-gnu-cross.txt}"
CROSS_GCC="${CROSS_GCC:-aarch64-linux-gnu-gcc}"

fail() { echo "FAIL: $*" >&2; exit 1; }

command -v meson >/dev/null || fail "meson not found"
command -v ninja >/dev/null || fail "ninja not found"
command -v "$CROSS_GCC" >/dev/null || fail "cross compiler not found: $CROSS_GCC"
[ -f "$MESA_SRC/meson.build" ] || fail "Mesa checkout missing at $MESA_SRC"
[ -f "$PREFIX/lib/pkgconfig/libdrm.pc" ] || \
    fail "aarch64 libdrm missing under $PREFIX (build libdrm into that prefix first)"

bash "$GPU_DIR/scripts/stage_mesa_opengpu.sh"

if [ ! -f "$MESA_BUILD/build.ninja" ]; then
    meson setup "$MESA_BUILD" "$MESA_SRC" \
        --cross-file "$CROSS" \
        --prefix "$PREFIX" \
        -Dgallium-drivers=opengpu \
        -Dvulkan-drivers= \
        -Dplatforms= \
        -Degl-native-platform=drm \
        -Dgbm=enabled \
        -Degl=enabled \
        -Dgles1=disabled \
        -Dgles2=enabled \
        -Dopengl=true \
        -Dglx=disabled \
        -Dglvnd=false \
        -Dllvm=disabled \
        -Dshared-glapi=enabled \
        -Dgallium-vdpau=disabled \
        -Dgallium-va=disabled \
        -Dgallium-xa=disabled \
        -Dgallium-nine=false \
        -Dgallium-omx=disabled \
        -Dshader-cache=disabled \
        -Dzlib=disabled \
        -Dzstd=disabled
fi

ninja -C "$MESA_BUILD"
meson install -C "$MESA_BUILD" --no-rebuild

mkdir -p "$PREFIX/bin"
"$CROSS_GCC" -std=c11 -O2 -Wall -Wextra -Werror \
    -I"$PREFIX/include" -I"$PREFIX/include/libdrm" \
    -o "$PREFIX/bin/opengpu_gl_triangle" \
    "$GPU_DIR/userspace/examples/gl_triangle.c" \
    -L"$PREFIX/lib" -Wl,-rpath-link,"$PREFIX/lib" \
    -lEGL -lGLESv2 -lgbm -ldrm
"$CROSS_GCC" -std=c11 -O2 -Wall -Wextra -Werror \
    -I"$PREFIX/include" -I"$PREFIX/include/libdrm" \
    -o "$PREFIX/bin/opengpu_gl_texture" \
    "$GPU_DIR/userspace/examples/gl_texture.c" \
    -L"$PREFIX/lib" -Wl,-rpath-link,"$PREFIX/lib" \
    -lEGL -lGLESv2 -lgbm -ldrm
"$CROSS_GCC" -std=c11 -O2 -Wall -Wextra -Werror \
    -I"$PREFIX/include" -I"$PREFIX/include/libdrm" \
    -o "$PREFIX/bin/opengpu_gl_blend" \
    "$GPU_DIR/userspace/examples/gl_blend.c" \
    -L"$PREFIX/lib" -Wl,-rpath-link,"$PREFIX/lib" \
    -lEGL -lGLESv2 -lgbm -ldrm
"$CROSS_GCC" -std=c11 -O2 -Wall -Wextra -Werror \
    -I"$PREFIX/include" -I"$PREFIX/include/libdrm" \
    -o "$PREFIX/bin/opengpu_gl_scissor" \
    "$GPU_DIR/userspace/examples/gl_scissor.c" \
    -L"$PREFIX/lib" -Wl,-rpath-link,"$PREFIX/lib" \
    -lEGL -lGLESv2 -lgbm -ldrm
"$CROSS_GCC" -std=c11 -O2 -Wall -Wextra -Werror \
    -I"$PREFIX/include" -I"$PREFIX/include/libdrm" \
    -o "$PREFIX/bin/opengpu_gl_transform" \
    "$GPU_DIR/userspace/examples/gl_transform.c" \
    -L"$PREFIX/lib" -Wl,-rpath-link,"$PREFIX/lib" \
    -lEGL -lGLESv2 -lgbm -ldrm
"$CROSS_GCC" -std=c11 -O2 -Wall -Wextra -Werror \
    -I"$PREFIX/include" -I"$PREFIX/include/libdrm" \
    -o "$PREFIX/bin/opengpu_gl_textransform" \
    "$GPU_DIR/userspace/examples/gl_textransform.c" \
    -L"$PREFIX/lib" -Wl,-rpath-link,"$PREFIX/lib" \
    -lEGL -lGLESv2 -lgbm -ldrm
"$CROSS_GCC" -std=c11 -O2 -Wall -Wextra -Werror \
    -I"$PREFIX/include" -I"$PREFIX/include/libdrm" \
    -o "$PREFIX/bin/opengpu_gl_modulate" \
    "$GPU_DIR/userspace/examples/gl_modulate.c" \
    -L"$PREFIX/lib" -Wl,-rpath-link,"$PREFIX/lib" \
    -lEGL -lGLESv2 -lgbm -ldrm
"$CROSS_GCC" -std=c11 -O2 -Wall -Wextra -Werror \
    -I"$PREFIX/include" -I"$PREFIX/include/libdrm" \
    -o "$PREFIX/bin/opengpu_gl_tint" \
    "$GPU_DIR/userspace/examples/gl_tint.c" \
    -L"$PREFIX/lib" -Wl,-rpath-link,"$PREFIX/lib" \
    -lEGL -lGLESv2 -lgbm -ldrm
"$CROSS_GCC" -std=c11 -O2 -Wall -Wextra -Werror \
    -I"$PREFIX/include" -I"$PREFIX/include/libdrm" \
    -o "$PREFIX/bin/opengpu_gl_opacity" \
    "$GPU_DIR/userspace/examples/gl_opacity.c" \
    -L"$PREFIX/lib" -Wl,-rpath-link,"$PREFIX/lib" \
    -lEGL -lGLESv2 -lgbm -ldrm
"$CROSS_GCC" -std=c11 -O2 -Wall -Wextra -Werror \
    -I"$PREFIX/include" -I"$PREFIX/include/libdrm" \
    -o "$PREFIX/bin/opengpu_gl_sprite" \
    "$GPU_DIR/userspace/examples/gl_sprite.c" \
    -L"$PREFIX/lib" -Wl,-rpath-link,"$PREFIX/lib" \
    -lEGL -lGLESv2 -lgbm -ldrm
"$CROSS_GCC" -std=c11 -O2 -Wall -Wextra -Werror \
    -I"$PREFIX/include" -I"$PREFIX/include/libdrm" \
    -o "$PREFIX/bin/opengpu_gl_fill" \
    "$GPU_DIR/userspace/examples/gl_fill.c" \
    -L"$PREFIX/lib" -Wl,-rpath-link,"$PREFIX/lib" \
    -lEGL -lGLESv2 -lgbm -ldrm
"$CROSS_GCC" -std=c11 -O2 -Wall -Wextra -Werror \
    -I"$PREFIX/include" -I"$PREFIX/include/libdrm" \
    -o "$PREFIX/bin/opengpu_gl_icon" \
    "$GPU_DIR/userspace/examples/gl_icon.c" \
    -L"$PREFIX/lib" -Wl,-rpath-link,"$PREFIX/lib" \
    -lEGL -lGLESv2 -lgbm -ldrm
"$CROSS_GCC" -std=c11 -O2 -Wall -Wextra -Werror \
    -I"$PREFIX/include" -I"$PREFIX/include/libdrm" \
    -o "$PREFIX/bin/opengpu_gl_shade" \
    "$GPU_DIR/userspace/examples/gl_shade.c" \
    -L"$PREFIX/lib" -Wl,-rpath-link,"$PREFIX/lib" \
    -lEGL -lGLESv2 -lgbm -ldrm

echo "Built $PREFIX/bin/opengpu_gl_triangle"

# Flat /root layout used by the OPENGPU modules ISO. The wrapper is the
# command; the ELF and the DRI driver sit beside it.
DRIVER_OUTPUT="${DRIVER_OUTPUT:-$GPU_DIR/../arti-work/debian-320x240/opengpu-driver}"
if [ -d "$DRIVER_OUTPUT" ]; then
    copy_lib() {
        cp -L "$PREFIX/lib/$1" "$DRIVER_OUTPUT/$1"
    }
    copy_lib libEGL.so.1
    copy_lib libGLESv2.so.2
    copy_lib libgbm.so.1
    copy_lib libglapi.so.0
    copy_lib libdrm.so.2
    copy_lib libexpat.so.1
    cp -L "$PREFIX/lib/dri/opengpu_dri.so" "$DRIVER_OUTPUT/opengpu_dri.so"
    cp -L "$PREFIX/bin/opengpu_gl_triangle" "$DRIVER_OUTPUT/opengpu_gl_triangle.bin"
    cp -L "$PREFIX/bin/opengpu_gl_texture" "$DRIVER_OUTPUT/opengpu_gl_texture.bin"
    cp -L "$PREFIX/bin/opengpu_gl_blend" "$DRIVER_OUTPUT/opengpu_gl_blend.bin"
    cp -L "$PREFIX/bin/opengpu_gl_scissor" "$DRIVER_OUTPUT/opengpu_gl_scissor.bin"
    cp -L "$PREFIX/bin/opengpu_gl_transform" "$DRIVER_OUTPUT/opengpu_gl_transform.bin"
    cp -L "$PREFIX/bin/opengpu_gl_textransform" "$DRIVER_OUTPUT/opengpu_gl_textransform.bin"
    cp -L "$PREFIX/bin/opengpu_gl_modulate" "$DRIVER_OUTPUT/opengpu_gl_modulate.bin"
    cp -L "$PREFIX/bin/opengpu_gl_tint" "$DRIVER_OUTPUT/opengpu_gl_tint.bin"
    cp -L "$PREFIX/bin/opengpu_gl_opacity" "$DRIVER_OUTPUT/opengpu_gl_opacity.bin"
    cp -L "$PREFIX/bin/opengpu_gl_sprite" "$DRIVER_OUTPUT/opengpu_gl_sprite.bin"
    cp -L "$PREFIX/bin/opengpu_gl_fill" "$DRIVER_OUTPUT/opengpu_gl_fill.bin"
    cp -L "$PREFIX/bin/opengpu_gl_icon" "$DRIVER_OUTPUT/opengpu_gl_icon.bin"
    cp -L "$PREFIX/bin/opengpu_gl_shade" "$DRIVER_OUTPUT/opengpu_gl_shade.bin"
    aarch64-linux-gnu-strip "$DRIVER_OUTPUT"/libEGL.so.1 \
        "$DRIVER_OUTPUT"/libGLESv2.so.2 "$DRIVER_OUTPUT"/libgbm.so.1 \
        "$DRIVER_OUTPUT"/libglapi.so.0 "$DRIVER_OUTPUT"/libdrm.so.2 \
        "$DRIVER_OUTPUT"/libexpat.so.1 "$DRIVER_OUTPUT"/opengpu_dri.so \
        "$DRIVER_OUTPUT"/opengpu_gl_triangle.bin \
        "$DRIVER_OUTPUT"/opengpu_gl_texture.bin \
        "$DRIVER_OUTPUT"/opengpu_gl_blend.bin \
        "$DRIVER_OUTPUT"/opengpu_gl_scissor.bin \
        "$DRIVER_OUTPUT"/opengpu_gl_transform.bin \
        "$DRIVER_OUTPUT"/opengpu_gl_textransform.bin \
        "$DRIVER_OUTPUT"/opengpu_gl_modulate.bin \
        "$DRIVER_OUTPUT"/opengpu_gl_tint.bin \
        "$DRIVER_OUTPUT"/opengpu_gl_opacity.bin \
        "$DRIVER_OUTPUT"/opengpu_gl_sprite.bin \
        "$DRIVER_OUTPUT"/opengpu_gl_fill.bin \
        "$DRIVER_OUTPUT"/opengpu_gl_icon.bin \
        "$DRIVER_OUTPUT"/opengpu_gl_shade.bin
    cp "$GPU_DIR/userspace/examples/opengpu_gl_triangle.sh" \
        "$DRIVER_OUTPUT/opengpu_gl_triangle"
    cp "$GPU_DIR/userspace/examples/opengpu_gl_texture.sh" \
        "$DRIVER_OUTPUT/opengpu_gl_texture"
    cp "$GPU_DIR/userspace/examples/opengpu_gl_blend.sh" \
        "$DRIVER_OUTPUT/opengpu_gl_blend"
    cp "$GPU_DIR/userspace/examples/opengpu_gl_scissor.sh" \
        "$DRIVER_OUTPUT/opengpu_gl_scissor"
    cp "$GPU_DIR/userspace/examples/opengpu_gl_transform.sh" \
        "$DRIVER_OUTPUT/opengpu_gl_transform"
    cp "$GPU_DIR/userspace/examples/opengpu_gl_textransform.sh" \
        "$DRIVER_OUTPUT/opengpu_gl_textransform"
    cp "$GPU_DIR/userspace/examples/opengpu_gl_modulate.sh" \
        "$DRIVER_OUTPUT/opengpu_gl_modulate"
    cp "$GPU_DIR/userspace/examples/opengpu_gl_tint.sh" \
        "$DRIVER_OUTPUT/opengpu_gl_tint"
    cp "$GPU_DIR/userspace/examples/opengpu_gl_opacity.sh" \
        "$DRIVER_OUTPUT/opengpu_gl_opacity"
    cp "$GPU_DIR/userspace/examples/opengpu_gl_sprite.sh" \
        "$DRIVER_OUTPUT/opengpu_gl_sprite"
    cp "$GPU_DIR/userspace/examples/opengpu_gl_fill.sh" \
        "$DRIVER_OUTPUT/opengpu_gl_fill"
    cp "$GPU_DIR/userspace/examples/opengpu_gl_icon.sh" \
        "$DRIVER_OUTPUT/opengpu_gl_icon"
    cp "$GPU_DIR/userspace/examples/opengpu_gl_shade.sh" \
        "$DRIVER_OUTPUT/opengpu_gl_shade"
    chmod +x "$DRIVER_OUTPUT/opengpu_gl_triangle" \
        "$DRIVER_OUTPUT/opengpu_gl_triangle.bin" \
        "$DRIVER_OUTPUT/opengpu_gl_texture" \
        "$DRIVER_OUTPUT/opengpu_gl_texture.bin" \
        "$DRIVER_OUTPUT/opengpu_gl_blend" \
        "$DRIVER_OUTPUT/opengpu_gl_blend.bin" \
        "$DRIVER_OUTPUT/opengpu_gl_scissor" \
        "$DRIVER_OUTPUT/opengpu_gl_scissor.bin" \
        "$DRIVER_OUTPUT/opengpu_gl_transform" \
        "$DRIVER_OUTPUT/opengpu_gl_transform.bin" \
        "$DRIVER_OUTPUT/opengpu_gl_textransform" \
        "$DRIVER_OUTPUT/opengpu_gl_textransform.bin" \
        "$DRIVER_OUTPUT/opengpu_gl_modulate" \
        "$DRIVER_OUTPUT/opengpu_gl_modulate.bin" \
        "$DRIVER_OUTPUT/opengpu_gl_tint" \
        "$DRIVER_OUTPUT/opengpu_gl_tint.bin" \
        "$DRIVER_OUTPUT/opengpu_gl_opacity" \
        "$DRIVER_OUTPUT/opengpu_gl_opacity.bin" \
        "$DRIVER_OUTPUT/opengpu_gl_sprite" \
        "$DRIVER_OUTPUT/opengpu_gl_sprite.bin" \
        "$DRIVER_OUTPUT/opengpu_gl_fill" \
        "$DRIVER_OUTPUT/opengpu_gl_fill.bin" \
        "$DRIVER_OUTPUT/opengpu_gl_icon" \
        "$DRIVER_OUTPUT/opengpu_gl_icon.bin" \
        "$DRIVER_OUTPUT/opengpu_gl_shade" \
        "$DRIVER_OUTPUT/opengpu_gl_shade.bin"
    echo "Staged GL triangle into $DRIVER_OUTPUT"
fi
