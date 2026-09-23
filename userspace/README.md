# Small OpenGPU userspace API

`opengpu.h` and `opengpu.c` wrap the public DRM ioctls for contexts, mapped GEM
buffers, resource bindings, compute and render submission, and syncobj fences.
The caller owns the DRM file descriptor and releases each object explicitly.
The library passes command structs through without hiding ABI options; initialize
unused fields to zero. An ioctl returning success means the job was queued;
wait on the output syncobj before reading results. The kernel reports failed
jobs through their fences. See `DRM_IOCTL_OPENGPU_GET_FAULT` for the most recent
hardware fault snapshot.

Build with installed DRM headers:

```sh
make -C userspace
```

In this repository's ARTI workspace, build guest AArch64 binaries using:

```sh
make -C userspace CC=aarch64-linux-gnu-gcc \
    DRM_HEADERS=../../arti-work/linux-headers/include
```

`examples/compute` submits a one-instruction cease kernel through private
shader and kernarg bindings. `examples/triangle` draws to a 16x16 colour GEM
on a fixed-function build. The latter requires a device configured for 16x16
pixels, with no fragment or vertex core. Pass a DRM node path as the first
argument, or use the default `/dev/dri/card0`.

To run both examples in the fixed-function ARTI guest and require a pass marker:

```sh
GPU_USERSPACE_EXAMPLES=1 GPU_USERSPACE_EXAMPLES_ONLY=1 \
  scripts/run_arti_gpu.sh
```

Omit `GPU_USERSPACE_EXAMPLES_ONLY=1` to run the existing DRM guest regression
before the examples. The full release gate remains
`scripts/qualify_functional.sh`.
