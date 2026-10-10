# GTK ellipse coverage guest proof

The full 640x480 FlashSim, QEMU, Mesa, and Debian proof runs manually. It
checks the exact inside and outside pixels and the `OPENGPU GL GTK COVERAGE
PASS` marker. The proof is not part of GitHub Actions: full GPU RTL elaboration
exhausted a 6 GiB Java heap on the standard hosted runner.

Prepare the sibling ARTI and FlashSim checkouts, Mesa 22.3.6 under
`depends/mesa`, and AArch64 libdrm under `depends/aarch64-root`. Then run:

```sh
bash scripts/run_gtk_coverage_guest.sh
```

To exercise the experimental tile attachment store and draw binner in the
same proof, run:

```sh
GPU_TILE_BINNING=1 JDK_JAVA_OPTIONS=-Xmx12g bash scripts/run_gtk_coverage_guest.sh
```

This passes `--tile-size 16 --tile-attachments --tile-binning` to RTL
emission. The default remains the existing scanline path. A cached tile-binned
model can be reused with `GPU_TILE_BINNING=1 GTK_PROOF_SKIP_BUILD=1`.
The tile-binned model and logs live under `../arti-work/debian-640x480-tile/`
and `../arti-work/gtk-coverage-proof-tile/`.

To run the multi-draw desktop after the GTK coverage check and capture QEMU's
actual 640x480 scanout, add `GTK_PROOF_DESKTOP=1`. The script verifies the
desktop's panel, window, triangle, and cursor pixels and writes
`desktop-scanout.ppm` in the proof directory. For example, reuse the cached
tile model with:

```sh
GPU_TILE_BINNING=1 GTK_PROOF_DESKTOP=1 GTK_PROOF_SKIP_BUILD=1 \
  bash scripts/run_gtk_coverage_guest.sh
```

To compare the complete tile scanout against a prior scanline proof, set
`GTK_PROOF_SCANOUT_REFERENCE=../arti-work/gtk-coverage-proof/desktop-scanout.ppm`.

To verify scissored GLES drawing in the same 640x480 scanout, use
`GTK_PROOF_SCISSOR=1`. This runs the scissor demo after the GTK coverage check,
checks yellow pixels inside the 16x16 scissor and blue pixels outside it, and
writes `scissor-scanout.ppm`. Compare the complete tile image with a scanline
reference using:

```sh
GPU_TILE_BINNING=1 GTK_PROOF_SCISSOR=1 GTK_PROOF_SKIP_BUILD=1 \
  GTK_PROOF_SCANOUT_REFERENCE=../arti-work/gtk-coverage-proof/scissor-scanout.ppm \
  bash scripts/run_gtk_coverage_guest.sh
```

Run the scanline `GTK_PROOF_SCISSOR=1` proof first to create that reference.
The desktop and scissor options select separate scanout captures.

The script builds the 640x480 GPU model and guest GLES driver, boots a fresh
Debian disk, loads the driver, and checks the coverage result. Once the model
and guest driver are already built, use `GTK_PROOF_SKIP_BUILD=1` to rerun just
the guest proof. Logs are kept in `../arti-work/gtk-coverage-proof/`.

The ordinary `Scala CI` workflow continues to run vector ALU specs and the
backend ellipse regression for relevant changes.

## Minimal GTK window

`userspace/examples/gtk_opengpu_smoke.c` is the first real GTK client. It
creates a `GtkApplication` window with a label and button, renders the panel
through `GtkGLArea` and the OpenGPU GLES driver, and queues a second GPU draw
when the button is clicked. The guest modules ISO now stages the source and a build helper. GTK and the guest development/debug tools are installed once into a dedicated image.
Cloud-init remains package-free during normal boots. Prepare the image with:

```sh
scripts/bake_gtk_debian_image.sh
```

Then point the proof at that image; ordinary boots remain package-free:

```sh
GTK_DEBIAN_BASE=../arti-work/debian-gtk-arm64.qcow2 \
  bash scripts/run_gtk_coverage_guest.sh
```

On that guest, build it with:

```sh
/root/opengpu_gtk_smoke_build.sh
```

Run it under the same environment as the GLES proofs:

```sh
LD_LIBRARY_PATH=/root LIBGL_DRIVERS_PATH=/root \
  /root/opengpu_gtk_smoke.bin
```

The first acceptance point is Weston DRM startup on the `Virtual-1` connector and
GTK/Wayland startup without a display error. The next acceptance point is
`GtkGLArea` realization and one successful frame, followed by the
`OPENGPU GTK REDRAW` line after pressing the button.
The existing ellipse, desktop, and scissor proofs remain the deterministic
scanout checks.

The opt-in smoke gate now starts Weston through the OpenGPU EGL path and reports
`GL vendor: opengpu` and `GL renderer: opengpu`. The TGSI texture shader and
indexed triangle lowering are implemented in the Gallium driver. The remaining
GPU wait budget is selected with `OPENGPU_FENCE_TIMEOUT_MS`; the smoke gate uses
1800000 ms because a 640x480 FlashSim compositor frame can take several
minutes. Fast hardware or small tests can set a shorter value. The kernel
watchdog remains independent and still reports real GPU faults.


## Reusing the GTK-enabled image

The prepared image is a normal Debian ARM64 qcow2 image, so the other ARTI
runners can use it as their base as well:

```sh
DEBIAN_BASE=../arti-work/debian-gtk-arm64.qcow2 \
  scripts/run_arti_debian.sh
```

`DEBIAN_BASE` is only used when the persistent `DISK` does not exist. To start
from the GTK image again, choose a new `DISK` or set `REBUILD_DISK=1`. The
qualification scripts should keep using the clean default image for reproducible
baseline runs; use the GTK image for interactive and application development.
