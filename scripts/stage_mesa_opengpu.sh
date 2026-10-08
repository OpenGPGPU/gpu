#!/usr/bin/env bash
# Copy the out-of-tree Gallium driver into the pinned Mesa 22.3 tree and
# register it as the opengpu DRI driver. depends/mesa is a local checkout,
# not a fork: re-run this after replacing that checkout.
set -euo pipefail

GPU_DIR="$(cd "$(dirname "$0")/.." && pwd)"
MESA="${MESA:-$GPU_DIR/depends/mesa}"
DEST="$MESA/src/gallium/drivers/opengpu"

[ -d "$MESA/src/gallium" ] || {
    echo "FAIL: Mesa checkout missing at $MESA" >&2
    echo "Clone mesa-22.3.6 there, then re-run." >&2
    exit 1
}

mkdir -p "$DEST"
cp "$GPU_DIR/userspace/gallium/"*.c "$GPU_DIR/userspace/gallium/"*.h "$DEST/"
cp "$GPU_DIR/userspace/pipe_opengpu.c" "$GPU_DIR/userspace/pipe_opengpu.h" \
    "$GPU_DIR/userspace/opengpu.c" "$GPU_DIR/userspace/opengpu.h" \
    "$GPU_DIR/driver/opengpu_drm.h" "$DEST/"
python3 - "$DEST/opengpu.h" <<'PY'
import pathlib, sys
path = pathlib.Path(sys.argv[1])
text = path.read_text()
text = text.replace('#include "../driver/opengpu_drm.h"', '#include "opengpu_drm.h"')
path.write_text(text)
PY

python3 - "$MESA" <<'PY'
import pathlib, sys
mesa = pathlib.Path(sys.argv[1])

def insert_once(path, needle, addition, after=True):
    file = mesa / path
    text = file.read_text()
    if addition.strip() in text or "GALLIUM_OPENGPU" in text and path.endswith("drm_helper.h") and "opengpu/opengpu_public.h" in text:
        if addition.strip() in text:
            return
    if needle not in text:
        raise SystemExit(f"anchor missing in {path}")
    if after:
        text = text.replace(needle, needle + addition, 1)
    else:
        text = text.replace(needle, addition + needle, 1)
    file.write_text(text)

gbm = mesa / "src/gbm/backends/dri/gbm_dri.c"
gbm_text = gbm.read_text()
old_cap = """   switch (cap) {
   case DRI_LOADER_CAP_FP16:
      return 1;
   default:
      return 0;
   }"""
new_cap = """   switch (cap) {
   case DRI_LOADER_CAP_FP16:
   case DRI_LOADER_CAP_RGBA_ORDERING:
      /* OpenGPU scanout stores R8G8B8A8. Without this cap Mesa hides that
       * format and the screen has no EGL config. */
      return 1;
   default:
      return 0;
   }"""
if "DRI_LOADER_CAP_RGBA_ORDERING" not in gbm_text:
    if old_cap not in gbm_text:
        raise SystemExit("gbm capability switch not found")
    gbm.write_text(gbm_text.replace(old_cap, new_cap, 1))

insert_once(
    "src/gallium/auxiliary/target-helpers/drm_helper.h",
    "#endif /* DRM_HELPER_H */\n",
    """
#ifdef GALLIUM_OPENGPU
#include "opengpu/opengpu_public.h"

static struct pipe_screen *
pipe_opengpu_create_screen(int fd, const struct pipe_screen_config *config)
{
   struct pipe_screen *screen;

   screen = opengpu_drm_screen_create(fd, config);
   return screen ? debug_screen_wrap(screen) : NULL;
}
DRM_DRIVER_DESCRIPTOR(opengpu, NULL, 0)
#else
DRM_DRIVER_DESCRIPTOR_STUB(opengpu)
#endif

""",
    after=False,
)

pub = mesa / "src/gallium/auxiliary/target-helpers/drm_helper_public.h"
pub_text = pub.read_text()
if "opengpu_driver_descriptor" not in pub_text:
    pub.write_text(pub_text.replace(
        "#endif /* _DRM_HELPER_PUBLIC_H */",
        "extern const struct drm_driver_descriptor opengpu_driver_descriptor;\n\n#endif /* _DRM_HELPER_PUBLIC_H */",
    ))

loader = mesa / "src/gallium/auxiliary/pipe-loader/pipe_loader_drm.c"
loader_text = loader.read_text()
if "opengpu_driver_descriptor" not in loader_text:
    loader.write_text(loader_text.replace(
        "   &zink_driver_descriptor,\n",
        "   &zink_driver_descriptor,\n   &opengpu_driver_descriptor,\n",
        1,
    ))

target = mesa / "src/gallium/targets/dri/target.c"
target_text = target.read_text()
if "GALLIUM_OPENGPU" not in target_text:
    target.write_text(target_text.replace(
        "#if defined(GALLIUM_LIMA)\n",
        "#if defined(GALLIUM_OPENGPU)\nDEFINE_LOADER_DRM_ENTRYPOINT(opengpu)\n#endif\n\n#if defined(GALLIUM_LIMA)\n",
        1,
    ))

dri = mesa / "src/gallium/targets/dri/meson.build"
dri_text = dri.read_text()
if "driver_opengpu" not in dri_text:
    dri_text = dri_text.replace(
        "    driver_asahi, driver_crocus\n",
        "    driver_asahi, driver_crocus, driver_opengpu\n",
        1,
    )
    dri_text = dri_text.replace(
        "             [with_gallium_asahi, 'asahi_dri.so']]\n",
        "             [with_gallium_asahi, 'asahi_dri.so'],\n"
        "             [with_gallium_opengpu, 'opengpu_dri.so']]\n",
        1,
    )
    dri.write_text(dri_text)

root = mesa / "meson.build"
root_text = root.read_text()
if "with_gallium_opengpu" not in root_text:
    root_text = root_text.replace(
        "with_gallium_v3d = gallium_drivers.contains('v3d')\n",
        "with_gallium_v3d = gallium_drivers.contains('v3d')\n"
        "with_gallium_opengpu = gallium_drivers.contains('opengpu')\n",
        1,
    )
    root.write_text(root_text)

options = mesa / "meson_options.txt"
opt_text = options.read_text()
if "'opengpu'" not in opt_text:
    opt_text = opt_text.replace(
        "'panfrost', 'iris', 'lima', 'zink', 'd3d12', 'asahi', 'crocus'\n",
        "'panfrost', 'iris', 'lima', 'zink', 'd3d12', 'asahi', 'crocus', 'opengpu'\n",
        1,
    )
    options.write_text(opt_text)

gallium = mesa / "src/gallium/meson.build"
gallium_text = gallium.read_text()
if "with_gallium_opengpu" not in gallium_text:
    gallium_text = gallium_text.replace(
        "if with_gallium_v3d\n",
        "if with_gallium_opengpu\n"
        "  subdir('drivers/opengpu')\n"
        "else\n"
        "  driver_opengpu = declare_dependency()\n"
        "endif\n"
        "if with_gallium_v3d\n",
        1,
    )
    gallium.write_text(gallium_text)
PY

cat > "$DEST/meson.build" <<'EOF'
# Generated by scripts/stage_mesa_opengpu.sh. The sources live in the OpenGPU
# repo; this file only exists inside the pinned Mesa checkout.
libopengpu_gallium = static_library(
  'opengpu_gallium',
  files(
    'opengpu_screen.c',
    'opengpu_context.c',
    'opengpu_fs_emit.c',
    'pipe_opengpu.c',
    'opengpu.c',
  ),
  include_directories : [
    inc_include, inc_src, inc_gallium, inc_gallium_aux,
    include_directories('.'),
  ],
  c_args : ['-DHAVE_ENDIAN_H'],
  gnu_symbol_visibility : 'hidden',
  dependencies : [dep_libdrm, idep_mesautil],
)

driver_opengpu = declare_dependency(
  compile_args : '-DGALLIUM_OPENGPU',
  link_with : libopengpu_gallium,
)
EOF

echo "Staged OpenGPU Gallium driver into $DEST"
