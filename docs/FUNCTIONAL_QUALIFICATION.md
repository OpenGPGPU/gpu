# Functional qualification

Run the complete functional gate before calling a change release-ready:

```sh
scripts/qualify_functional.sh
```

The command runs, in order:

1. The full Scala test suite with local parallel test execution enabled. This
   includes the seeded AXI command and fault regression. Set
   `OPENGPU_AXI_SEED` to replay a different seed.
2. All kernel-free host driver tests through `scripts/test_driver.py`.
3. The fixed-function ARTI Linux guest DRM test.
4. The vertex- and fragment-core ARTI Linux guest DRM test.

The gate passes only when every command exits successfully and both guest
runs report `OPENGPU USERSPACE DRM PASS`. `set -e` stops at the first failure;
the final `Functional qualification PASS` line is printed only after all four
stages pass. The guest runner builds the RTL model, QEMU integration, Linux
driver and initramfs as needed, so a first run can take substantially longer
than a cached run.

The guest DRM test also checks that consecutive KMS flip events advance their
sequence, records their interval and rejects events less than 10 ms apart;
emulated MMIO can delay them beyond the nominal 30 Hz period. Rewriting only
`SCANOUT_BASE` for an unchanged mode cut fixed 64x64 guest intervals from
about 2.21 s to 251 ms, and vertex+fragment intervals from 2.69–2.76 s to
293–297 ms. The display check in `scripts/run_arti_display.sh` verifies a
rendered scanout pixel. The userspace example apps, including
`triangle_present` and `pipe_present`,
are opt-in. Run the fixed-function and programmable example paths separately
with `GPU_USERSPACE_EXAMPLES=1 GPU_USERSPACE_EXAMPLES_ONLY=1` and the desired
`GPU_FRAG_CORE` / `GPU_VERT_CORE` settings. The default gate checks DRM flip
events but does not inspect the displayed frame in a QEMU window.

Prerequisites are sbt, a JDK, a host C compiler, the AArch64 cross compiler,
FlashSim (or Verilator), and the sibling ARTI checkout.
`scripts/run_arti_gpu.sh` documents its path overrides and downloads
QEMU/Linux sources into `ARTI_WORK` when needed. The gate uses the FlashSim
backend when `GPU_SIM=flashsim` is set; the runner defaults to Verilator with
eight simulation threads.

The normal CI workflow uses affected-package Scala selection with parallel
test execution disabled to stay within runner memory limits, plus host driver
tests for quicker feedback. This command is the full local gate,
including guest execution; keep its terminal output with the revision being
qualified.
