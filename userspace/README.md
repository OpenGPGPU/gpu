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

`examples/triangle` draws to a 16x16 colour GEM on a fixed-function
build. It requires a device configured for 16x16 pixels, with no fragment or vertex core. Pass a DRM node path as the first
argument, or use the default `/dev/dri/card0`.

`examples/fragment_tint` is the programmable counterpart: it binds the
corpus `fragment_tint` shader and expects `OPENGPU_CAP_FRAGMENT_CORE`. Pass
the DRM node and optional shader binary path (default
`/opengpu_fragment_tint.bin`). It is the userspace path a Gallium
`draw_vbo` spike would call; see [docs/GALLIUM_SPIKE.md](../docs/GALLIUM_SPIKE.md).

To run both examples in the fixed-function ARTI guest and require a pass marker:

```sh
GPU_USERSPACE_EXAMPLES=1 GPU_USERSPACE_EXAMPLES_ONLY=1 \
  scripts/run_arti_gpu.sh
```

Omit `GPU_USERSPACE_EXAMPLES_ONLY=1` to run the existing DRM guest regression
before the examples. The full release gate remains
`scripts/qualify_functional.sh`.

## Shader corpus

Run `python3 scripts/validate_shader_corpus.py` from the repository root. It
assembles `compute_copy.S`, `round_modes.S`, `masked_ops.S`, `fixed_width.S`
(lane-local `vsext`/`vzext`/`vnclip`/`vsmul` with `vxrm`) and `widen_alu.S`
(lane-local `vwadd`/`vwsub`/`vwmul`), and
compiles three small C shaders with `riscv64-unknown-elf-gcc`, adapts the C
argument base to OpenGPU's direct `x1` kernarg convention, and replaces the C
return with the OpenGPU cease instruction. The script checks every binary with
the production compute, fragment or vertex shader validator. It rejects
compiler output with labels or indirect memory operands, rather than assuming
arbitrary C is a valid shader. Set `RISCV_GCC` and `RISCV_OBJCOPY` for another
RISC-V toolchain.
