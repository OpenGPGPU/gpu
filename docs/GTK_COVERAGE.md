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

The script builds the 640x480 GPU model and guest GLES driver, boots a fresh
Debian disk, loads the driver, and checks the coverage result. Once the model
and guest driver are already built, use `GTK_PROOF_SKIP_BUILD=1` to rerun just
the guest proof. Logs are kept in `../arti-work/gtk-coverage-proof/`.

The ordinary `Scala CI` workflow continues to run vector ALU specs and the
backend ellipse regression for relevant changes.
