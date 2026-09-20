# Linux driver architecture

Platform owns device and DRM lifetime. Execution, scheduling, memory and KMS
are separate:

| File | Responsibility |
|---|---|
| `opengpu_drv.c` | Probe/remove, subsystem order, render-only selection |
| `opengpu_drm_device.c` | DRM allocation/registration, ioctl table, GEM and dma-buf |
| `opengpu_hw.c` | MMIO, IRQ, unified submission, fences, reset, recovery |
| `opengpu_memory.c` | Coherent DMA buffer allocation/free |
| `opengpu_mmu.c` | Page tables, ASIDs, VA windows, shootdown |
| `opengpu_compute.c` | Contexts, binding validation, job preparation, opcodes |
| `opengpu_scheduler.c` | One scheduler, reservation/syncobj deps, common retirement |
| `opengpu_display.c` | Connector, pipe, atomic modeset, page flips, virtual vblank |

Validation happens before scheduler submission. On success the scheduler owns
the job and retained GEM references; the common free callback releases staged
buffers. All opcodes share one dependency/fence path and one-credit policy.
Per-job VM remapping runs when the scheduler starts the job after its
predecessor drains.

DRM is allocated and registered from platform probe. KMS only configures
display objects; it starts disabled and binds caller-owned GEM framebuffers.
It never borrows `gpu->compute.color`. DT `opengpu,render-only` omits KMS and
MODESET/ATOMIC while keeping render, GEM and syncobj.

Probe order: hardware → execution → DRM allocate → optional KMS → DRM
register. Teardown reverses that. Shader snapshots use a validated RV32IMF+V
profile with defined-register tracking; that is separate from GPUVM isolation.
Context VMs still inherit identity mappings. Graphics TLBs are ASID-tagged
but flush all entries on any shootdown pulse.

CPU/GPU visibility uses DMA allocation/cache sync, GPU line invalidate and
reservation fences. KMS waits for render/resolve destination write fences.
Virtual vblank is simulation pacing; real scanout/PHY remain external.

Validate with `python3 scripts/test_driver.py`. Guest path:
`scripts/run_arti_gpu.sh`. Status: [GRAPHICS_ROADMAP.md](GRAPHICS_ROADMAP.md).
