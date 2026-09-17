# OpenGPU kernel driver (`gpu_drv.ko`)

Out-of-tree Kbuild module for the RISC-V SIMT GPU host. Sources live here
(`opengpu_drv.c`, `opengpu_hw.c`, `opengpu_memory.c`, `opengpu_compute.c`,
`opengpu_display.c`), alongside the kernel-free `opengpu_*_validator.h` headers
that are shared with the userspace unit tests in `tests/`.

## Where the Linux source tree is

The kernel source the module and the QEMU guest are built against lives **next
to this repository**, in the ARTI workspace:

    ../arti-work/linux-src          # Linux source tree (currently 7.2.0)

`ARTI_WORK` defaults to `<repo>/../arti-work`, so the full default set is:

| What | Default | Override |
|---|---|---|
| Linux source tree | `../arti-work/linux-src` | `LINUX_SRC` / `ARTI_WORK` |
| Configured kernel build dir | `../arti-work/arti-linux-build` | `LINUX_BUILD` |
| ARTI repo (build scripts) | `../arti` | `ARTI_DIR` |
| Driver build output | `../arti-work/opengpu-driver` | `DRIVER_OUTPUT` |

`scripts/run_arti_gpu.sh` probes `$ARTI_WORK/linux-src` and downloads the tree
only if it is missing or incomplete.

## Building

The supported path is the ARTI/QEMU harness, which builds the module against
the same kernel the guest boots and drops `gpu_drv.ko` in `$DRIVER_OUTPUT`:

```sh
scripts/run_arti_gpu.sh
```

To build by hand against the prepared tree instead:

```sh
make -C ../arti-work/arti-linux-build M="$PWD" ARCH=arm64 modules
```

(The prepared build dir is required: the raw `linux-src` tree is unconfigured.)

## Validator unit tests

The validator headers have no kernel dependencies, so their tests are plain C
and run on the host. They are also wired into `scripts/run_arti_gpu.sh`:

```sh
cd driver
for v in shader resolve depth; do
    cc -std=c11 -O2 -Wall -Wextra -Werror -I. \
        -o /tmp/opengpu_${v}_validator_test tests/opengpu_${v}_validator_test.c
    /tmp/opengpu_${v}_validator_test
done
```

See `docs/HOST_INTERFACE.md` for the device ABI and `docs/GRAPHICS_ROADMAP.md`
for the current feature status.

The MMU host test compiles the production mapping implementation against small
kernel dependency stubs. It checks allocation failure rollback, table capacity,
32-bit address bounds, and exclusion of submissions during updates. The ARTI
harness runs it too; to run it independently from `driver/`:

```sh
cc -std=c11 -O2 -Wall -Wextra -Werror -Itests/mmu_stubs \
    tests/opengpu_mmu_test.c -o /tmp/opengpu_mmu_test
/tmp/opengpu_mmu_test
```
