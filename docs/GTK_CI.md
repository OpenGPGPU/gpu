# GTK ellipse coverage in CI

The ordinary `Scala CI` workflow runs the vector ALU specs and backend ellipse
regression for relevant pull requests. A change under
`src/main/scala/opengpu/core/vector/` selects both groups.

The full 640x480 FlashSim, QEMU, Mesa, and Debian proof runs in
`GTK coverage guest proof` after relevant pushes to `main`, every night at
02:00 UTC, and on manual dispatch. It checks the exact inside and outside
pixels and the `OPENGPU GL GTK COVERAGE PASS` marker. The job uses a standard
`ubuntu-24.04` GitHub-hosted runner, which is free for this public repository.
It removes unused preinstalled tools to make space for its build. No personal
or self-hosted runner is needed.

The workflow checks out the GPU, ARTI, and FlashSim repositories as siblings.
ARTI is pinned to `463df439`; FlashSim is pinned to `b93a07ff`, the versions
used by the passing local proof. The job downloads checksum-checked Mesa
22.3.6, libdrm 2.4.120, and expat 2.6.4 sources, builds the AArch64
libraries, builds the model and guest driver, then boots a fresh Debian disk.
RTL emission gives sbt a 6 GiB heap, matching the Scala CI shards, because the
default 1 GiB heap cannot elaborate the full GPU.
Update the dependency refs when testing newer ARTI or FlashSim commits.

The first hosted run will establish the actual cold-build time and peak disk
use. The job has a 180-minute timeout and retains guest logs on failure. No
large build cache is uploaded to GitHub Actions.

For an existing local workspace with its dependencies already prepared, run:

```sh
bash scripts/ci_gtk_coverage.sh
```
