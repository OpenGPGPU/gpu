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

The script builds the 640x480 GPU model and guest GLES driver, boots a fresh
Debian disk, loads the driver, and checks the coverage result. Once the model
and guest driver are already built, use `GTK_PROOF_SKIP_BUILD=1` to rerun just
the guest proof. Logs are kept in `../arti-work/gtk-coverage-proof/`.

The ordinary `Scala CI` workflow continues to run vector ALU specs and the
backend ellipse regression for relevant changes.
