# Small OpenGPU userspace API

`opengpu.h` and `opengpu.c` wrap the public DRM ioctls for contexts, mapped GEM
buffers, resource bindings, compute and render submission, and syncobj fences.
The caller owns the DRM file descriptor and releases each object explicitly.
The library passes command structs through without hiding ABI options; initialize
unused fields to zero. An ioctl returning success means the job was queued;
wait on the output syncobj before reading results. The kernel reports failed
jobs through their fences. See `DRM_IOCTL_OPENGPU_GET_FAULT` for the most recent
hardware fault snapshot.

Build with installed DRM headers and a RISC-V GNU assembler/toolchain:

```sh
make -C userspace
```

In this repository's ARTI workspace, build guest AArch64 binaries using:

```sh
make -C userspace CC=aarch64-linux-gnu-gcc \
    DRM_HEADERS=../../arti-work/linux-headers/include
```

`examples/compute` loads `shaders/round_modes.bin`, assembled from symbolic
RISC-V source, through private shader and kernarg bindings. It checks that
the same input rounds to 1 under RNU and 0 under RDN. Pass the shader binary
as the second argument when running outside the guest.

`examples/triangle` draws a native-mode test image into its own colour GEM on
a fixed-function build (64x64 by default). It obtains the mode through
`opengpu_display_size`. Pass a DRM node path as the first argument, or use the
default `/dev/dri/card0`.

`examples/fragment_tint` is the programmable counterpart: it binds the
corpus `fragment_tint` shader and expects `OPENGPU_CAP_FRAGMENT_CORE`. Pass
the DRM node and optional shader binary path (default
`/opengpu_fragment_tint.bin`).

`examples/triangle_present` draws into the native KMS mode buffer (64x64 on
the Debian display profile) and programs the CRTC so ARTI/QEMU scanout shows
the result. Fixed-function builds paint a solid red triangle; fragment-core
builds use the tint shader. Pass `--hold` (or `OPENGPU_PRESENT_HOLD=1`) to
keep the framebuffer live for cocoa. Debian auto-display:

```sh
OPENGPU_AUTO_DISPLAY=triangle QEMU_DISPLAY=cocoa scripts/run_arti_debian.sh
```

`pipe_opengpu.h` / `pipe_opengpu.c` is the Gallium-shaped winsys spike
(`docs/GALLIUM_SPIKE.md`). `examples/pipe_clear_draw` clears via
`opengpu_fill`, then draws one triangle through `pipe_opengpu_draw_vbo`
(fixed-function or fragment-tint by capability). `examples/pipe_compute`
covers `launch_grid`. `examples/pipe_blit` clears a source GEM and copies it
with `pipe_opengpu_blit`. `examples/pipe_strided_blit` invalidates a
CPU-filled source then copies with `pipe_opengpu_strided_blit`.
`examples/pipe_depth_pass` continues a FF render across submissions with a
bound depth GEM and `OPENGPU_SUBMIT_DEPTH_LOAD`.
`examples/pipe_msaa_draw` draws at 2x then resolves.
`examples/pipe_resolve` averages a CPU-filled 2x
MSAA buffer. `examples/pipe_texture_draw` binds a mip chain and draws a textured
triangle: fixed-function uses the HW sampler; fragment-core loads the corpus
`fragment_texture` (`vtex.sample`) binary (default
`/opengpu_fragment_texture.bin`). `examples/pipe_vertex_draw`
exercises vertex+fragment cores (`GPU_VERT_CORE=1`).
`examples/pipe_present` allocates a mode-sized 2D colour GEM, clears, draws,
and presents via `pipe_opengpu_present` (same `--hold` / tint rules as
`triangle_present`).

**ARTI as a GPU** (preferred programmable path):

```sh
GPU_FRAG_CORE=1 GPU_USERSPACE_EXAMPLES=1 GPU_USERSPACE_EXAMPLES_ONLY=1 \
  scripts/run_arti_gpu.sh
```

Fixed-function smoke:

```sh
GPU_USERSPACE_EXAMPLES=1 GPU_USERSPACE_EXAMPLES_ONLY=1 \
  scripts/run_arti_gpu.sh
```

Vertex+fragment smoke:

```sh
GPU_FRAG_CORE=1 GPU_VERT_CORE=1 GPU_USERSPACE_EXAMPLES=1 \
  GPU_USERSPACE_EXAMPLES_ONLY=1 scripts/run_arti_gpu.sh
```

Debian interactive (same binaries on the OPENGPU ISO):

```sh
scripts/run_arti_debian.sh
# guest after /root/load_opengpu.sh:
/root/load_opengpu.sh examples
/root/opengpu_triangle_present --hold
```

See the platform-integration section of
[docs/GRAPHICS_ROADMAP.md](../docs/GRAPHICS_ROADMAP.md). Omit `*_ONLY=1` to
run the DRM guest regression before the apps. The full release gate remains
`scripts/qualify_functional.sh`.

## Shader corpus

Run `python3 scripts/validate_shader_corpus.py` from the repository root. It
assembles `compute_copy.S`, `round_modes.S`, `masked_ops.S`, `fixed_width.S`
(lane-local `vsext`/`vzext`/`vnclip`/`vsmul` with `vxrm`), `widen_alu.S`
(lane-local `vwadd`/`vwsub`/`vwmul`) and `fragment_texture.S` (`vtex.sample`
plus warp discard / `vquad.dfdx`), and compiles three small C shaders with
`riscv64-unknown-elf-gcc`, adapts the C argument base to OpenGPU's direct `x1`
kernarg convention, and replaces the C return with the OpenGPU cease
instruction. The script checks every binary with the production compute,
fragment, fragment+texture or vertex shader validator. It rejects compiler
output with labels or indirect memory operands, rather than assuming arbitrary
C is a valid shader. Set `RISCV_GCC` and `RISCV_OBJCOPY` for another RISC-V
toolchain.
