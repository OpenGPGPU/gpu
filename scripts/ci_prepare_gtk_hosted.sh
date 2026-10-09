#!/usr/bin/env bash
# Fetch pinned guest libraries and Mesa for a clean GitHub-hosted Ubuntu job.
# The GPU/model build and guest proof are run by ci_gtk_coverage.sh afterward.
set -euo pipefail

GPU_DIR="$(cd "$(dirname "$0")/.." && pwd)"
DEPS="$GPU_DIR/depends"
PREFIX="$DEPS/aarch64-root"
ARTI_WORK="${ARTI_WORK:-$GPU_DIR/../arti-work}"
CI_WORK="$ARTI_WORK/ci-gtk-coverage"
JOBS="${JOBS:-3}"
mkdir -p "$DEPS" "$PREFIX" "$CI_WORK"

[ "$(uname -s)" = Linux ] || {
    echo 'ci_prepare_gtk_hosted.sh requires Linux' >&2
    exit 1
}

fetch_source() {
    local name="$1" url="$2" expected="$3" archive
    archive="$DEPS/$name.tar.xz"
    if [ ! -d "$DEPS/$name" ]; then
        curl -fL --retry 5 --retry-all-errors -o "$archive" "$url"
        printf '%s  %s\n' "$expected" "$archive" | sha256sum -c -
        tar -xJf "$archive" -C "$DEPS"
        rm -f "$archive"
    fi
}

fetch_source expat-2.6.4 \
    'https://github.com/libexpat/libexpat/releases/download/R_2_6_4/expat-2.6.4.tar.xz' \
    a695629dae047055b37d50a0ff4776d1d45d0a4c842cf4ccee158441f55ff7ee
fetch_source libdrm-2.4.120 \
    'https://dri.freedesktop.org/libdrm/libdrm-2.4.120.tar.xz' \
    3bf55363f76c7250946441ab51d3a6cc0ae518055c0ff017324ab76cdefb327a
fetch_source mesa-22.3.6 \
    'https://archive.mesa3d.org/older-versions/22.x/mesa-22.3.6.tar.xz' \
    4ec8ec65dbdb1ee9444dba72970890128a19543a58cf05931bd6f54f124e117f
ln -sfn mesa-22.3.6 "$DEPS/mesa"

echo '=== Cross-build expat ==='
cmake -G Ninja -S "$DEPS/expat-2.6.4" -B "$DEPS/expat-build-ninja" \
    -DCMAKE_SYSTEM_NAME=Linux \
    -DCMAKE_C_COMPILER=aarch64-linux-gnu-gcc \
    -DCMAKE_BUILD_WITH_INSTALL_RPATH=ON \
    -DCMAKE_INSTALL_PREFIX="$PREFIX" \
    -DCMAKE_INSTALL_LIBDIR=lib \
    -DEXPAT_BUILD_DOCS=OFF \
    -DEXPAT_BUILD_EXAMPLES=OFF \
    -DEXPAT_BUILD_TESTS=OFF \
    -DEXPAT_BUILD_TOOLS=OFF
cmake --build "$DEPS/expat-build-ninja" --parallel "$JOBS"
cmake --install "$DEPS/expat-build-ninja"

CROSS="$CI_WORK/aarch64-linux-gnu-cross.txt"
python3 "$GPU_DIR/scripts/ci_aarch64_cross.py" \
    "$GPU_DIR/scripts/aarch64-linux-gnu-cross.txt" "$CROSS" "$PREFIX"

echo '=== Cross-build libdrm ==='
meson setup "$DEPS/libdrm-build" "$DEPS/libdrm-2.4.120" \
    --cross-file "$CROSS" --prefix "$PREFIX" --libdir lib \
    -Dauto_features=disabled -Dtests=false
ninja -C "$DEPS/libdrm-build" -j "$JOBS"
meson install -C "$DEPS/libdrm-build" --no-rebuild

test -f "$PREFIX/lib/pkgconfig/libdrm.pc"
test -f "$PREFIX/lib/libexpat.so.1"
echo 'Hosted guest dependencies: ready'
df -h "$DEPS"
