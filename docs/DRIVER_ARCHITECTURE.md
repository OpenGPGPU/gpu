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
predecessor drains. Compute kernel code is remapped into a private code VA
window at run time (`kernel_pc`), so compute instruction fetch translates
under the context ASID instead of the shared identity map. Fragment and
vertex snapshots occupy separate private code windows, and the shared graphics
shader core uses the same instruction SATP and scoped TLB flushes. Shader
kernarg and vertex-buffer staging share VECTOR_SATP with CU data loads;
texture and other graphics word clients translate independently. Shader traps
retire the faulting warp, fail the kernel and report a render memory fault;
failed batches do not emit pixels or vertex outputs. Faults are not resumable.
For a context with an enabled VM, resource binding and per-job command, DMA,
framebuffer and snapshot mapping failures abort the operation; they never
fall back to the resource's physical address. Bare bring-up jobs still use the
ASID-0 identity map.

DRM is allocated and registered from platform probe. KMS only configures
display objects; it starts disabled and binds caller-owned GEM framebuffers.
It never borrows `gpu->compute.color`. DT `opengpu,render-only` omits KMS and
MODESET/ATOMIC while keeping render, GEM and syncobj.

Probe order: hardware → execution → DRM allocate → optional KMS → DRM
register. Teardown reverses that. Shader snapshots use a validated RV32IMF+V
profile with defined-register tracking; that is separate from GPUVM isolation.
Context VMs still inherit identity mappings, but those leaves are
non-executable; snapshot code runs from private code windows. Graphics TLBs
are ASID-tagged and honour the same full/ASID/VPN-scoped shootdown as the CU
TLBs.

CPU/GPU visibility uses DMA allocation/cache sync, GPU line invalidate and
reservation fences. KMS waits for render/resolve destination write fences.
Virtual vblank is simulation pacing; real scanout/PHY remain external.

Validate with `python3 scripts/test_driver.py`. Guest path:
`scripts/run_arti_gpu.sh`. Status: [GRAPHICS_ROADMAP.md](GRAPHICS_ROADMAP.md).
