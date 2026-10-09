# GTK ellipse coverage in CI

The ordinary `Scala CI` workflow runs the vector ALU specs and the backend
ellipse regression for relevant pull requests. A change under
`src/main/scala/opengpu/core/vector/` selects both groups.

The full 640x480 FlashSim, QEMU, Mesa, and Debian proof runs in
`GTK coverage guest proof` after relevant pushes to `main`, every night at
02:00 UTC, and on manual dispatch. It checks the exact inside and outside
pixels and the `OPENGPU GL GTK COVERAGE PASS` marker. It does not hold up every
pull request while the guest builds.

## Runner

Register a dedicated self-hosted GitHub Actions runner with the
`opengpu-guest` label. It needs at least 16 GB RAM and 25 GB free disk; a
persistent workspace avoids rebuilding the Linux kernel and QEMU from scratch
on every run. Install Java 11, sbt, Python 3, Meson, Ninja, a C/C++ compiler,
`expect`, OpenSSH, xorriso, cpio, pkg-config, AArch64 Linux cross tools, and
RISC-V ELF cross tools. ARTI's `setup_env.sh` checks or installs some host
dependencies, but the runner must provide the cross toolchains.

Before the first run, create these gitignored paths inside the GPU checkout:

- `depends/mesa`: Mesa 22.3.6 source checkout.
- `depends/aarch64-root`: AArch64 libdrm 2.4.120 and expat development
  libraries, including `lib/pkgconfig/libdrm.pc`. The runner must be able to
  link the Mesa guest examples against this prefix.

The workflow checks out the GPU, ARTI, and FlashSim repositories as siblings.
It pins ARTI to `463df439` and FlashSim to `b93a07ff`, the versions used by
the passing local proof. **Those two commits must be published to their
respective repositories before the workflow can fetch them.** Update the
workflow refs when testing newer dependency changes, then dispatch the job.
`ARTI_WORK` defaults to a persistent `arti-work` directory beside the three
checkouts. The script creates a fresh Debian disk for each run and removes it
afterward; build caches and failure logs remain in that work directory.

For a prepared local workspace, run:

```sh
bash scripts/ci_gtk_coverage.sh
```

The full job has not been benchmarked on the CI runner. Its 180-minute timeout
covers a cold setup; measure the first successful run before tightening it.
