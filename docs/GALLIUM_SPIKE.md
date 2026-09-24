# Gallium / Mesa spike (scoped)

Evaluate a thin `pipe_opengpu` against the stable DRM ABI already used by
`userspace/opengpu.h`. This is **not** a full Mesa tree yet: land the mapping
and a representative draw path first, then wire a Mesa submodule only if that
path stays green under `scripts/qualify_functional.sh`.

## First code drop (landed)

Out-of-tree winsys in-tree under `userspace/`:

| Piece | Role |
|---|---|
| `pipe_opengpu.h` / `pipe_opengpu.c` | Gallium-shaped `screen` / `context` / `resource` / `fence`; links only `opengpu.c` |
| `opengpu_fill` | Clear maps to `DRM_IOCTL_OPENGPU_FILL` |
| `examples/pipe_clear_draw` | `clear` + `draw_vbo` smoke (fixed or fragment-tint) |
| `examples/pipe_compute` | `bind_cs` + `launch_grid` smoke (`round_modes`) |
| `examples/pipe_blit` | `clear` + `blit` smoke (1024-byte GEM copy) |
| `examples/pipe_strided_blit` | `invalidate` + 2D strided blit (64×4) |
| `examples/pipe_depth_pass` | persistent depth clear + `DEPTH_LOAD` continuation (FF) |
| `examples/pipe_msaa_draw` | 2x MSAA clear/draw + `resolve` (FF) |
| `examples/pipe_resolve` | CPU-fill 2x MSAA GEM + `resolve` average check |
| `examples/pipe_texture_draw` | `bind_texture` + textured `draw_vbo` (fixed-function) |
| `examples/pipe_vertex_draw` | VS/VB bind + `draw_vertex` (vertex+fragment cores) |
| `examples/pipe_present` | mode-sized clear + draw + `pipe_opengpu_present` |

Fixed-function guest apps:

```sh
GPU_USERSPACE_EXAMPLES=1 GPU_USERSPACE_EXAMPLES_ONLY=1 \
  scripts/run_arti_gpu.sh
```

Fragment-core guest apps (tint + pipe clear/draw — preferred “use the GPU”
path under ARTI):

```sh
GPU_FRAG_CORE=1 GPU_USERSPACE_EXAMPLES=1 GPU_USERSPACE_EXAMPLES_ONLY=1 \
  scripts/run_arti_gpu.sh
```

`GPU_PIPE_SPIKE=1` is a compatibility alias for the fragment-core userspace
path. ARTI bring-up commands live in [GRAPHICS_ROADMAP.md](GRAPHICS_ROADMAP.md)
(platform integration).

Emit a single corpus binary without a full corpus validate:

```sh
python3 scripts/validate_shader_corpus.py --emit fragment_tint /tmp/tint.bin
```

## Goals

1. One `pipe_screen` / `pipe_context` that can clear and draw one triangle.
2. Prefer fragment-core builds with the existing tint/corpus shaders; keep
   fixed-function `examples/triangle` as the Bare smoke path.
3. Reuse private-VM isolation and validated shader admission — do not bypass
   the driver validator.

## Non-goals (first spike)

- Softpipe fallback, NIR lowering, or texture multi-sampling.
- Shipping inside upstream Mesa; treat this as an out-of-tree winsys + pipe.
- New ISA work driven by Mesa (add ops only when a spike shader needs them).

KMS present is in-tree via `pipe_opengpu_present` / `examples/pipe_present`
(still no Mesa winsys display integration).

## ABI map

| Gallium / Mesa surface | OpenGPU DRM / userspace |
|---|---|
| `pipe_screen` device open | `opengpu_open` → DRM fd; `opengpu_capabilities` |
| context create/destroy | `opengpu_context_create` / `destroy` |
| resource create/map | `opengpu_buffer_create` (GEM) |
| sampler view / texture | `opengpu_bind` `OPENGPU_RESOURCE_TEXTURE` |
| FS / VS / CS bind | `OPENGPU_RESOURCE_{SHADER,VERTEX_SHADER,COMPUTE_SHADER}` + kernarg slots |
| `pipe_context::draw_vbo` (fixed) | fill `drm_opengpu_draw`, `opengpu_render` |
| `draw_vbo` (vertex core) | `drm_opengpu_vertex_draw` + VB/VS slots |
| `launch_grid` | `opengpu_compute` |
| fence | syncobj via `opengpu_sync_*` |
| clear / blit | `DRM_IOCTL_OPENGPU_FILL` / `BLIT` / `STRIDED_BLIT` |
| MSAA resolve | `DRM_IOCTL_OPENGPU_RESOLVE` |
| present / scanout | `pipe_opengpu_present` → `ADDFB2` + `SETCRTC` |
| flush CPU→GPU visibility | line invalidate or uncached resource flags |

Draw-record and kernarg layouts: [HOST_INTERFACE.md](HOST_INTERFACE.md).
Scheduler / GEM ownership: [DRIVER_ARCHITECTURE.md](DRIVER_ARCHITECTURE.md).
Capability bits: `driver/gpu_abi.h` (`GpuAbiLayoutSpec` guards drift).

## Next

1. Keep the pipe path on the same ioctl + validator rules as
   `examples/triangle` / `fragment_tint` / `opengpu_drm_test`.
2. Optional: real Mesa `pipe_opengpu` that calls this winsys (or inlines it)
   only if NIR/winsys bootstrap stays smaller than growing
   `userspace/examples/`.
3. Gate: guest draw succeeds; `scripts/qualify_functional.sh` still green
   (userspace apps are opt-in via `GPU_USERSPACE_EXAMPLES`, not on the
   default qualify path).

## Stop / continue

- **Continue** if the spike submits through the same ioctl path as
  `examples/triangle` / DRM tests without relaxing validator or VM rules.
- **Stop** and keep Mesa out of tree if NIR or winsys bootstrap dwarfs the
  functional gate; grow `userspace/examples/` instead until a product needs GL.
