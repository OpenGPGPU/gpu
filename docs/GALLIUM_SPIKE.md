# Gallium / Mesa spike (scoped)

Evaluate a thin `pipe_opengpu` against the stable DRM ABI already used by
`userspace/opengpu.h`. This is **not** a full Mesa tree yet: land the mapping
and a representative draw path first, then wire a Mesa submodule only if that
path stays green under `scripts/qualify_functional.sh`.

## Goals

1. One `pipe_screen` / `pipe_context` that can clear and draw one triangle.
2. Prefer fragment-core builds with the existing tint/corpus shaders; keep
   fixed-function `examples/triangle` as the Bare smoke path.
3. Reuse private-VM isolation and validated shader admission — do not bypass
   the driver validator.

## Non-goals (first spike)

- Softpipe fallback, NIR lowering, texture multi-sampling, or display scanout.
- Shipping inside upstream Mesa; treat this as an out-of-tree winsys + pipe.
- New ISA work driven by Mesa (add ops only when a spike shader needs them).

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
| flush CPU→GPU visibility | line invalidate or uncached resource flags |

Draw-record and kernarg layouts: [HOST_INTERFACE.md](HOST_INTERFACE.md).
Scheduler / GEM ownership: [DRIVER_ARCHITECTURE.md](DRIVER_ARCHITECTURE.md).
Capability bits: `driver/gpu_abi.h` (`GpuAbiLayoutSpec` guards drift).

## Suggested first code drop

1. Out-of-tree `pipe_opengpu` that links against `userspace/opengpu.c` only
   (no kernel changes).
2. Hard-code one validated fragment binary from `userspace/shaders/` (or the
   guest DRM vector shader) — no NIR yet.
3. Run under ARTI with `OPENGPU_CAP_FRAGMENT_CORE` (extend
   `scripts/run_arti_gpu.sh` userspace examples beyond the fixed-function gate).
4. Gate: guest draw succeeds; `scripts/qualify_functional.sh` still green.

## Stop / continue

- **Continue** if the spike submits through the same ioctl path as
  `examples/triangle` / DRM tests without relaxing validator or VM rules.
- **Stop** and keep Mesa out of tree if NIR or winsys bootstrap dwarfs the
  functional gate; grow `userspace/examples/` instead until a product needs GL.
