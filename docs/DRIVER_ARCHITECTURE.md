# Linux driver architecture

The platform owns device and DRM lifetime. Execution, scheduling, memory and
KMS are separate responsibilities:

| File | Responsibility |
|---|---|
| `opengpu_drv.c` | Probe/remove, subsystem order, render-only selection |
| `opengpu_drm_device.c` | DRM allocation/registration, ioctl table, GEM and dma-buf hooks |
| `opengpu_hw.c` | MMIO, IRQ, unified submission, fences, reset and bounded recovery |
| `opengpu_memory.c` | Coherent DMA buffer allocation/free |
| `opengpu_mmu.c` | Page tables, ASIDs, VA windows and shootdown |
| `opengpu_compute.c` | Contexts, binding validation, immutable job preparation, opcode execution |
| `opengpu_scheduler.c` | One scheduler, reservation/syncobj dependencies and common job retirement |
| `opengpu_display.c` | Connector, display pipe, atomic modeset, page flips and virtual vblank |

`opengpu_scheduler.h` defines the private context/job ownership shared by
preparation and scheduling. Validation occurs before scheduler submission.
On successful submission the scheduler owns the job and retained GEM
references; its common free callback releases every staged buffer. All
opcodes use the same dependency/fence publication path and one-credit policy.
Per-job VM remapping occurs when the scheduler runs the job after its
predecessor has drained.

The DRM device is allocated and registered from platform probe. KMS only
configures display objects on that device. Display starts disabled, then
atomic modesets bind caller-owned GEM framebuffers; it no longer receives
`gpu->compute.color`. The self-test buffers remain execution-owned and are
never borrowed by KMS. A DT `opengpu,render-only` boolean omits KMS setup and
removes MODESET/ATOMIC from that device's feature mask while retaining render,
GEM and syncobj services.

Probe initializes hardware and execution, allocates DRM, optionally creates
KMS objects and finally registers DRM. Teardown unregisters DRM, shuts down
KMS, tears down execution and releases hardware services. Managed DRM objects
remain owned by the platform device. Probe errors unwind initialized services.

Shader snapshots use an explicit validated RV32IMF+V profile. Defined-register
tracking and bounded memory provenance constrain accesses; this is separate
from GPUVM isolation. Context VMs still inherit compatibility identity
mappings. Quiescence precedes satp switches; scoped shootdown handles mapping
changes and ASID reuse. Graphics TLBs are ASID-tagged but flush all entries on
any shootdown pulse.

CPU/GPU visibility uses DMA allocation/cache synchronization, GPU line
invalidation and reservation fences. KMS waits for render/resolve destination
write fences. The virtual vblank timer is simulation pacing; actual scanout,
display timing and PHY remain external.

Validation entry points:

- `python3 scripts/test_driver.py`: seven host programs, including the
  production MMU implementation with dependency stubs.
- `scripts/run_arti_gpu.sh`: build the exact driver and boot its DRM submission,
  persistent-depth, resolve and scanout tests under ARTI/QEMU.
- `docs/QUALIFICATION.md`: the current evidence matrix and limits.
