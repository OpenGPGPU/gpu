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
"$CROSS_GCC" -std=c11 -O2 -Wall -Wextra -Werror \
    -I"$PREFIX/include" -I"$PREFIX/include/libdrm" \
    -o "$PREFIX/bin/opengpu_gl_fade" \
    "$GPU_DIR/userspace/examples/gl_fade.c" \
    -L"$PREFIX/lib" -Wl,-rpath-link,"$PREFIX/lib" \
    -lEGL -lGLESv2 -lgbm -ldrm
"$CROSS_GCC" -std=c11 -O2 -Wall -Wextra -Werror \
    -I"$PREFIX/include" -I"$PREFIX/include/libdrm" \
    -o "$PREFIX/bin/opengpu_gl_glass" \
    "$GPU_DIR/userspace/examples/gl_glass.c" \
    -L"$PREFIX/lib" -Wl,-rpath-link,"$PREFIX/lib" \
    -lEGL -lGLESv2 -lgbm -ldrm
"$CROSS_GCC" -std=c11 -O2 -Wall -Wextra -Werror \
    -I"$PREFIX/include" -I"$PREFIX/include/libdrm" \
    -o "$PREFIX/bin/opengpu_gl_quad" \
    "$GPU_DIR/userspace/examples/gl_quad.c" \
    -L"$PREFIX/lib" -Wl,-rpath-link,"$PREFIX/lib" \
    -lEGL -lGLESv2 -lgbm -ldrm
"$CROSS_GCC" -std=c11 -O2 -Wall -Wextra -Werror \
    -I"$PREFIX/include" -I"$PREFIX/include/libdrm" \
    -o "$PREFIX/bin/opengpu_gl_pane" \
    "$GPU_DIR/userspace/examples/gl_pane.c" \
    -L"$PREFIX/lib" -Wl,-rpath-link,"$PREFIX/lib" \
    -lEGL -lGLESv2 -lgbm -ldrm
"$CROSS_GCC" -std=c11 -O2 -Wall -Wextra -Werror \
    -I"$PREFIX/include" -I"$PREFIX/include/libdrm" \
    -o "$PREFIX/bin/opengpu_gl_wash" \
    "$GPU_DIR/userspace/examples/gl_wash.c" \
    -L"$PREFIX/lib" -Wl,-rpath-link,"$PREFIX/lib" \
    -lEGL -lGLESv2 -lgbm -ldrm
"$CROSS_GCC" -std=c11 -O2 -Wall -Wextra -Werror \
    -I"$PREFIX/include" -I"$PREFIX/include/libdrm" \
    -o "$PREFIX/bin/opengpu_gl_ramp" \
    "$GPU_DIR/userspace/examples/gl_ramp.c" \
    -L"$PREFIX/lib" -Wl,-rpath-link,"$PREFIX/lib" \
    -lEGL -lGLESv2 -lgbm -ldrm
"$CROSS_GCC" -std=c11 -O2 -Wall -Wextra -Werror \
    -I"$PREFIX/include" -I"$PREFIX/include/libdrm" \
    -o "$PREFIX/bin/opengpu_gl_veil" \
    "$GPU_DIR/userspace/examples/gl_veil.c" \
    -L"$PREFIX/lib" -Wl,-rpath-link,"$PREFIX/lib" \
    -lEGL -lGLESv2 -lgbm -ldrm
"$CROSS_GCC" -std=c11 -O2 -Wall -Wextra -Werror \
    -I"$PREFIX/include" -I"$PREFIX/include/libdrm" \
    -o "$PREFIX/bin/opengpu_gl_sheet" \
    "$GPU_DIR/userspace/examples/gl_sheet.c" \
    -L"$PREFIX/lib" -Wl,-rpath-link,"$PREFIX/lib" \
    -lEGL -lGLESv2 -lgbm -ldrm
"$CROSS_GCC" -std=c11 -O2 -Wall -Wextra -Werror \
    -I"$PREFIX/include" -I"$PREFIX/include/libdrm" \
    -o "$PREFIX/bin/opengpu_gl_grade" \
    "$GPU_DIR/userspace/examples/gl_grade.c" \
    -L"$PREFIX/lib" -Wl,-rpath-link,"$PREFIX/lib" \
    -lEGL -lGLESv2 -lgbm -ldrm
"$CROSS_GCC" -std=c11 -O2 -Wall -Wextra -Werror \
    -I"$PREFIX/include" -I"$PREFIX/include/libdrm" \
    -o "$PREFIX/bin/opengpu_gl_haze" \
    "$GPU_DIR/userspace/examples/gl_haze.c" \
    -L"$PREFIX/lib" -Wl,-rpath-link,"$PREFIX/lib" \
    -lEGL -lGLESv2 -lgbm -ldrm
"$CROSS_GCC" -std=c11 -O2 -Wall -Wextra -Werror \
    -I"$PREFIX/include" -I"$PREFIX/include/libdrm" \
    -o "$PREFIX/bin/opengpu_gl_qt_flatcolor" \
    "$GPU_DIR/userspace/examples/gl_qt_flatcolor.c" \
    -L"$PREFIX/lib" -Wl,-rpath-link,"$PREFIX/lib" \
    -lEGL -lGLESv2 -lgbm -ldrm
"$CROSS_GCC" -std=c11 -O2 -Wall -Wextra -Werror \
    -I"$PREFIX/include" -I"$PREFIX/include/libdrm" \
    -o "$PREFIX/bin/opengpu_gl_qt_texture" \
    "$GPU_DIR/userspace/examples/gl_qt_texture.c" \
    -L"$PREFIX/lib" -Wl,-rpath-link,"$PREFIX/lib" \
    -lEGL -lGLESv2 -lgbm -ldrm
"$CROSS_GCC" -std=c11 -O2 -Wall -Wextra -Werror \
    -I"$PREFIX/include" -I"$PREFIX/include/libdrm" \
    -o "$PREFIX/bin/opengpu_gl_qt_opacity" \
    "$GPU_DIR/userspace/examples/gl_qt_opacity.c" \
    -L"$PREFIX/lib" -Wl,-rpath-link,"$PREFIX/lib" \
    -lEGL -lGLESv2 -lgbm -ldrm
"$CROSS_GCC" -std=c11 -O2 -Wall -Wextra -Werror \
    -I"$PREFIX/include" -I"$PREFIX/include/libdrm" \
    -o "$PREFIX/bin/opengpu_gl_qt_vertexcolor" \
    "$GPU_DIR/userspace/examples/gl_qt_vertexcolor.c" \
    -L"$PREFIX/lib" -Wl,-rpath-link,"$PREFIX/lib" \
    -lEGL -lGLESv2 -lgbm -ldrm
"$CROSS_GCC" -std=c11 -O2 -Wall -Wextra -Werror \
    -I"$PREFIX/include" -I"$PREFIX/include/libdrm" \
    -o "$PREFIX/bin/opengpu_gl_qt_stencilclip" \
    "$GPU_DIR/userspace/examples/gl_qt_stencilclip.c" \
    -L"$PREFIX/lib" -Wl,-rpath-link,"$PREFIX/lib" \
    -lEGL -lGLESv2 -lgbm -ldrm
"$CROSS_GCC" -std=c11 -O2 -Wall -Wextra -Werror \
    -I"$PREFIX/include" -I"$PREFIX/include/libdrm" \
    -o "$PREFIX/bin/opengpu_gl_qt_rendernode" \
    "$GPU_DIR/userspace/examples/gl_qt_rendernode.c" \
    -L"$PREFIX/lib" -Wl,-rpath-link,"$PREFIX/lib" \
    -lEGL -lGLESv2 -lgbm -ldrm
"$CROSS_GCC" -std=c11 -O2 -Wall -Wextra -Werror \
    -I"$PREFIX/include" -I"$PREFIX/include/libdrm" \
    -o "$PREFIX/bin/opengpu_gl_gtk_color" \
    "$GPU_DIR/userspace/examples/gl_gtk_color.c" \
    -L"$PREFIX/lib" -Wl,-rpath-link,"$PREFIX/lib" \
    -lEGL -lGLESv2 -lgbm -ldrm
"$CROSS_GCC" -std=c11 -O2 -Wall -Wextra -Werror \
    -I"$PREFIX/include" -I"$PREFIX/include/libdrm" \
    -o "$PREFIX/bin/opengpu_gl_gtk_coverage" \
    "$GPU_DIR/userspace/examples/gl_gtk_coverage.c" \
    -L"$PREFIX/lib" -Wl,-rpath-link,"$PREFIX/lib" \
    -lEGL -lGLESv2 -lgbm -ldrm

echo "Built $PREFIX/bin/opengpu_gl_triangle"

# Flat /root layout used by the OPENGPU modules ISO. The wrapper is the
# command; the ELF and the DRI driver sit beside it.
eval "$(python3 "$GPU_DIR/scripts/gpu_display_config.py" --shell \
    "${INTEGRATION_CONFIG:-$GPU_DIR/driver/gpu_integration_debian.yaml}")"
DRIVER_OUTPUT="${DRIVER_OUTPUT:-$GPU_DIR/../arti-work/debian-${GPU_MODE}/opengpu-driver}"
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
    cp -L "$PREFIX/bin/opengpu_gl_fade" "$DRIVER_OUTPUT/opengpu_gl_fade.bin"
    cp -L "$PREFIX/bin/opengpu_gl_glass" "$DRIVER_OUTPUT/opengpu_gl_glass.bin"
    cp -L "$PREFIX/bin/opengpu_gl_quad" "$DRIVER_OUTPUT/opengpu_gl_quad.bin"
    cp -L "$PREFIX/bin/opengpu_gl_pane" "$DRIVER_OUTPUT/opengpu_gl_pane.bin"
    cp -L "$PREFIX/bin/opengpu_gl_wash" "$DRIVER_OUTPUT/opengpu_gl_wash.bin"
    cp -L "$PREFIX/bin/opengpu_gl_ramp" "$DRIVER_OUTPUT/opengpu_gl_ramp.bin"
    cp -L "$PREFIX/bin/opengpu_gl_veil" "$DRIVER_OUTPUT/opengpu_gl_veil.bin"
    cp -L "$PREFIX/bin/opengpu_gl_sheet" "$DRIVER_OUTPUT/opengpu_gl_sheet.bin"
    cp -L "$PREFIX/bin/opengpu_gl_grade" "$DRIVER_OUTPUT/opengpu_gl_grade.bin"
    cp -L "$PREFIX/bin/opengpu_gl_haze" "$DRIVER_OUTPUT/opengpu_gl_haze.bin"
    cp -L "$PREFIX/bin/opengpu_gl_qt_flatcolor" "$DRIVER_OUTPUT/opengpu_gl_qt_flatcolor.bin"
    cp -L "$PREFIX/bin/opengpu_gl_qt_texture" "$DRIVER_OUTPUT/opengpu_gl_qt_texture.bin"
    cp -L "$PREFIX/bin/opengpu_gl_qt_opacity" "$DRIVER_OUTPUT/opengpu_gl_qt_opacity.bin"
    cp -L "$PREFIX/bin/opengpu_gl_qt_vertexcolor" "$DRIVER_OUTPUT/opengpu_gl_qt_vertexcolor.bin"
    cp -L "$PREFIX/bin/opengpu_gl_qt_stencilclip" "$DRIVER_OUTPUT/opengpu_gl_qt_stencilclip.bin"
    cp -L "$PREFIX/bin/opengpu_gl_qt_rendernode" "$DRIVER_OUTPUT/opengpu_gl_qt_rendernode.bin"
    cp -L "$PREFIX/bin/opengpu_gl_gtk_color" "$DRIVER_OUTPUT/opengpu_gl_gtk_color.bin"
    cp -L "$PREFIX/bin/opengpu_gl_gtk_coverage" "$DRIVER_OUTPUT/opengpu_gl_gtk_coverage.bin"
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
        "$DRIVER_OUTPUT"/opengpu_gl_shade.bin \
        "$DRIVER_OUTPUT"/opengpu_gl_fade.bin \
        "$DRIVER_OUTPUT"/opengpu_gl_glass.bin \
        "$DRIVER_OUTPUT"/opengpu_gl_quad.bin \
        "$DRIVER_OUTPUT"/opengpu_gl_pane.bin \
        "$DRIVER_OUTPUT"/opengpu_gl_wash.bin \
        "$DRIVER_OUTPUT"/opengpu_gl_ramp.bin \
        "$DRIVER_OUTPUT"/opengpu_gl_veil.bin \
        "$DRIVER_OUTPUT"/opengpu_gl_sheet.bin \
        "$DRIVER_OUTPUT"/opengpu_gl_grade.bin \
        "$DRIVER_OUTPUT"/opengpu_gl_haze.bin \
        "$DRIVER_OUTPUT"/opengpu_gl_qt_flatcolor.bin \
        "$DRIVER_OUTPUT"/opengpu_gl_qt_texture.bin \
        "$DRIVER_OUTPUT"/opengpu_gl_qt_opacity.bin \
        "$DRIVER_OUTPUT"/opengpu_gl_qt_vertexcolor.bin \
        "$DRIVER_OUTPUT"/opengpu_gl_qt_stencilclip.bin \
        "$DRIVER_OUTPUT"/opengpu_gl_qt_rendernode.bin \
        "$DRIVER_OUTPUT"/opengpu_gl_gtk_color.bin \
        "$DRIVER_OUTPUT"/opengpu_gl_gtk_coverage.bin
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
    cp "$GPU_DIR/userspace/examples/opengpu_gl_fade.sh" \
        "$DRIVER_OUTPUT/opengpu_gl_fade"
    cp "$GPU_DIR/userspace/examples/opengpu_gl_glass.sh" \
        "$DRIVER_OUTPUT/opengpu_gl_glass"
    cp "$GPU_DIR/userspace/examples/opengpu_gl_quad.sh" \
        "$DRIVER_OUTPUT/opengpu_gl_quad"
    cp "$GPU_DIR/userspace/examples/opengpu_gl_pane.sh" \
        "$DRIVER_OUTPUT/opengpu_gl_pane"
    cp "$GPU_DIR/userspace/examples/opengpu_gl_wash.sh" \
        "$DRIVER_OUTPUT/opengpu_gl_wash"
    cp "$GPU_DIR/userspace/examples/opengpu_gl_ramp.sh" \
        "$DRIVER_OUTPUT/opengpu_gl_ramp"
    cp "$GPU_DIR/userspace/examples/opengpu_gl_veil.sh" \
        "$DRIVER_OUTPUT/opengpu_gl_veil"
    cp "$GPU_DIR/userspace/examples/opengpu_gl_sheet.sh" \
        "$DRIVER_OUTPUT/opengpu_gl_sheet"
    cp "$GPU_DIR/userspace/examples/opengpu_gl_grade.sh" \
        "$DRIVER_OUTPUT/opengpu_gl_grade"
    cp "$GPU_DIR/userspace/examples/opengpu_gl_haze.sh" \
        "$DRIVER_OUTPUT/opengpu_gl_haze"
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
        "$DRIVER_OUTPUT/opengpu_gl_shade.bin" \
        "$DRIVER_OUTPUT/opengpu_gl_fade" \
        "$DRIVER_OUTPUT/opengpu_gl_fade.bin" \
        "$DRIVER_OUTPUT/opengpu_gl_glass" \
        "$DRIVER_OUTPUT/opengpu_gl_glass.bin" \
        "$DRIVER_OUTPUT/opengpu_gl_quad" \
        "$DRIVER_OUTPUT/opengpu_gl_quad.bin" \
        "$DRIVER_OUTPUT/opengpu_gl_pane" \
        "$DRIVER_OUTPUT/opengpu_gl_pane.bin" \
        "$DRIVER_OUTPUT/opengpu_gl_wash" \
        "$DRIVER_OUTPUT/opengpu_gl_wash.bin" \
        "$DRIVER_OUTPUT/opengpu_gl_ramp" \
        "$DRIVER_OUTPUT/opengpu_gl_ramp.bin" \
        "$DRIVER_OUTPUT/opengpu_gl_veil" \
        "$DRIVER_OUTPUT/opengpu_gl_veil.bin" \
        "$DRIVER_OUTPUT/opengpu_gl_sheet" \
        "$DRIVER_OUTPUT/opengpu_gl_sheet.bin" \
        "$DRIVER_OUTPUT/opengpu_gl_grade" \
        "$DRIVER_OUTPUT/opengpu_gl_grade.bin" \
        "$DRIVER_OUTPUT/opengpu_gl_haze" \
        "$DRIVER_OUTPUT/opengpu_gl_haze.bin" \
        "$DRIVER_OUTPUT/opengpu_gl_qt_flatcolor.bin" \
        "$DRIVER_OUTPUT/opengpu_gl_qt_texture.bin" \
        "$DRIVER_OUTPUT/opengpu_gl_qt_opacity.bin" \
        "$DRIVER_OUTPUT/opengpu_gl_qt_vertexcolor.bin" \
        "$DRIVER_OUTPUT/opengpu_gl_qt_stencilclip.bin" \
        "$DRIVER_OUTPUT/opengpu_gl_qt_rendernode.bin" \
        "$DRIVER_OUTPUT/opengpu_gl_gtk_color.bin" \
        "$DRIVER_OUTPUT/opengpu_gl_gtk_coverage.bin"
    echo "Staged GL triangle into $DRIVER_OUTPUT"
fi
