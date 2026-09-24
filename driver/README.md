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

Loading `gpu_drv.ko` registers the GPU and DRM/KMS device without running a
render self-test. In the Debian guest, run `/root/load_opengpu.sh test` only
when you want the separate DRM regression suite. The legacy triangle self-test
is also available through `OPENGPU_IOCTL_SUBMIT` on `/dev/opengpu0`.

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
cc -std=c11 -O2 -Wall -Wextra -Werror -I. \
    tests/opengpu_tlb_flush_test.c -o /tmp/opengpu_tlb_flush_test
/tmp/opengpu_tlb_flush_test
cc -std=c11 -O2 -Wall -Wextra -Werror -I. \
    tests/opengpu_kernarg_va_test.c -o /tmp/opengpu_kernarg_va_test
/tmp/opengpu_kernarg_va_test
```

See `docs/HOST_INTERFACE.md` for the device ABI and `docs/GRAPHICS_ROADMAP.md`
for the current feature status.

The MMU host test compiles the production mapping implementation against small
kernel dependency stubs. It checks allocation failure rollback, table capacity,
32-bit address bounds, exclusion of submissions during updates, and the per-VM
root-table lifecycle. The ASID allocator has its own kernel-free test. The ARTI
harness runs both; to run them independently from `driver/`:

```sh
cc -std=c11 -O2 -Wall -Wextra -Werror -I. \
    tests/opengpu_asid_test.c -o /tmp/opengpu_asid_test
/tmp/opengpu_asid_test
cc -std=c11 -O2 -Wall -Wextra -Werror -Itests/mmu_stubs \
    tests/opengpu_mmu_test.c -o /tmp/opengpu_mmu_test
/tmp/opengpu_mmu_test
```

## Ownership and tests

`opengpu_scheduler.c` owns shared job scheduling/retirement;
`opengpu_drm_device.c` owns the platform DRM/GEM layer. Display initializes
without an execution-owned boot framebuffer. The optional DT boolean
`opengpu,render-only` disables KMS setup. See
[driver architecture](../docs/DRIVER_ARCHITECTURE.md).

Run all seven host programs from the repository root with
`python3 scripts/test_driver.py`; CI runs this independently of Scala changes.
