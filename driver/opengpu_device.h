/* SPDX-License-Identifier: GPL-2.0 */
#ifndef OPENGPU_DEVICE_H
#define OPENGPU_DEVICE_H

#include <linux/device.h>
#include <linux/dma-fence.h>
#include <linux/dma-mapping.h>
#include <linux/ioport.h>
#include <linux/ioctl.h>
#include <linux/miscdevice.h>
#include <linux/mutex.h>
#include <linux/spinlock.h>
#include <linux/types.h>
#include <linux/workqueue.h>

#include <drm/gpu_scheduler.h>

#include "gpu_abi.h"
#include "opengpu_asid.h"

struct platform_device;
struct drm_device;
struct drm_file;
struct opengpu_drm;

#define OPENGPU_NAME            "riscv-simt-opengpu"
#define OPENGPU_COMPUTE_NAME    "opengpu0"
/* Embedded RTL simulation executes every memory handshake cycle-by-cycle;
 * textured batches are intentionally given a bounded but generous watchdog. */
/* Verilated quad dispatch executes covered and helper lanes. A full 16x16
 * fragment-core draw can exceed ten seconds under instruction-level QEMU. */
#ifndef OPENGPU_DRAW_WAIT_MS
#define OPENGPU_DRAW_WAIT_MS    30000
#endif
/* How long the driver waits for a requested safe unified-command reset to
 * drain before declaring the device wedged. */
#ifndef OPENGPU_RESET_WAIT_MS
#define OPENGPU_RESET_WAIT_MS   5000
#endif
/* Boot-time render mode used when the device-tree node carries no
 * opengpu,width/height/stride properties (the ARTI-generated node does not).
 * Must match the elaborated RTL resolution; the runner passes
 * -DOPENGPU_DEFAULT_WIDTH/HEIGHT to keep them in lockstep. */
#ifndef OPENGPU_DEFAULT_WIDTH
#define OPENGPU_DEFAULT_WIDTH   16
#endif
#ifndef OPENGPU_DEFAULT_HEIGHT
#define OPENGPU_DEFAULT_HEIGHT  16
#endif
#define OPENGPU_IOCTL_SUBMIT    _IO('G', 0x01)

struct opengpu_buffer {
    void *cpu;
    dma_addr_t dma;
    size_t size;
};

/* Host-memory job ring / IH ring sizing (entries; powers of two). */
#define OPENGPU_JOB_RING_ENTRIES 8u
#define OPENGPU_IH_RING_ENTRIES  16u

/* One in-flight submission tracked per job-ring slot. */
struct opengpu_pending_job {
    struct dma_fence *fence;
    u32 id;
    u32 delay_ms;
};

struct opengpu_fault_snapshot {
    u64 sequence;
    u64 bytes_processed;
    u64 expected_bytes;
    int error;
    u32 command_id;
    u32 opcode;
    u32 status;
    u32 flags;
    u32 expected_command_id;
    u32 expected_opcode;
};

/* Hardware-owned state. Business layers must use opengpu_hw_* APIs instead
 * of accessing the register aperture directly. */
struct opengpu_hw {
    void __iomem *regs;
    resource_size_t regs_phys;
    resource_size_t regs_size;
    int irq;
    struct mutex submit_lock;
    spinlock_t fence_lock;
    struct dma_fence *active_fence;
    struct dma_fence *unified_fence;
    u32 unified_command_id;
    u32 unified_opcode;
    u64 unified_expected_bytes;
    struct opengpu_fault_snapshot last_fault;
    struct delayed_work timeout_work;
    struct delayed_work completion_work;
    struct delayed_work poll_work;
    struct delayed_work reset_work;
    /* Safe unified-command reset state.  `reset_pending` blocks new unified
     * submissions with -EBUSY until the hardware drain acknowledges;
     * `wedged` means the drain never completed, so the device keeps failing
     * unified submissions with -EIO until it is reloaded. */
    bool reset_pending;
    bool wedged;
    u32 active_completion_delay_ms;
    u64 fence_context;
    u64 fence_seqno;
    u32 capabilities;
    /* Host-memory job queue + IH ring (AMDGPU-style), when the device
     * advertises GPU_CAP_JOB_QUEUE.  Submissions publish descriptors into
     * the ring and ring the doorbell; the IRQ handler drains IH records and
     * retires the fence named by the job id. */
    bool queue_ready;
    struct opengpu_buffer job_ring;
    struct opengpu_buffer ih_ring;
    u32 job_mask;               /* OPENGPU_JOB_RING_ENTRIES - 1 */
    u32 ih_mask;                /* OPENGPU_IH_RING_ENTRIES - 1 */
    u32 job_wptr;               /* next free ring slot (free-running) */
    u32 job_done;               /* oldest job not yet retired (free-running) */
    u32 ih_rptr;                /* next IH record to drain */
    u32 job_seqno;              /* last allocated job id */
    /* Fence awaiting its simulated slow completion (test hook). */
    struct dma_fence *delayed_fence;
    struct opengpu_pending_job pending[OPENGPU_JOB_RING_ENTRIES];
};

/* Bring-up execution client. This becomes the render/compute client as queue
 * and GEM support land; display state deliberately does not live here. */
struct opengpu_compute {
    struct miscdevice misc;
    struct mutex lock;
    struct opengpu_buffer cmd;
    struct opengpu_buffer color;
    struct opengpu_buffer depth;
    struct opengpu_buffer shader;
    struct opengpu_buffer kernarg;
    struct drm_gpu_scheduler scheduler;
};

struct opengpu_display {
    struct opengpu_drm *kms;
    dma_addr_t scanout;
    u32 stride;
    u32 width;
    u32 height;
    u32 format;
    bool enabled;
};

/* Sv32 per-page cache policy, matching CachePolicy in the RTL and the Sv32
 * PTE bits [9:8]. */
#define OPENGPU_MMU_POLICY_CACHED       0u
#define OPENGPU_MMU_POLICY_WRITE_THROUGH 1u
#define OPENGPU_MMU_POLICY_UNCACHED     2u

/* Sv32 page size; must match MMU_PAGE_SIZE in opengpu_mmu.c. */
#define OPENGPU_MMU_PAGE_SIZE 4096u

/* Up to this many 4 MiB regions may carry a second-level table (a region is
 * split only when a page inside it needs a non-default policy). */
#define OPENGPU_MMU_MAX_TABLES 16u

/* Forward declaration: `struct opengpu_mmu` holds a VM registry below. */
struct opengpu_vm;

/** Identity-map GPU MMU: one root table of 4 MiB superpages, split into
  * second-level tables on demand so individual 4 KiB pages can carry a
  * non-default cache policy.  `asids` hands out Sv32 ASIDs to per-VM root
  * tables; ASID 0 is the driver's global identity map.  `vm_by_asid`
  * registers live VMs so a policy update can propagate a newly split L1 link
  * into every VM root. */
struct opengpu_mmu {
    struct mutex lock;
    struct opengpu_buffer root;
    struct opengpu_buffer l1[OPENGPU_MMU_MAX_TABLES];
    u32 l1_region[OPENGPU_MMU_MAX_TABLES];
    u32 l1_count;
    struct opengpu_asid_pool asids;
    struct opengpu_vm *vm_by_asid[OPENGPU_ASID_COUNT];
    bool enabled;
};

/** One GPU address space: a root page table reached through `asid`.
  *
  * ASID 0 is the driver's global identity map, whose mappings are needed by
  * every address space.  A VM owns an ASID in 1..511 and its own root table,
  * so a coarse switch (quiesce, then reprogram satp) needs no full TLB flush:
  * other ASIDs stay resident.  The scoped TLB flush evicts this VM's entries
  * when it is destroyed or when its ASID is recycled.  A new root starts as a
  * full identity map; per-VM cache-policy mappings are added once the
  * scheduler selects VMs. */
struct opengpu_vm {
    struct opengpu_buffer root;
    u32 asid;
    bool enabled;
    /* Private second-level tables for regions this VM maps itself.  Their
     * leaves are non-global, so a translation is only valid under this VM's
     * ASID. */
    struct opengpu_buffer l1[OPENGPU_MMU_MAX_TABLES];
    u32 l1_region[OPENGPU_MMU_MAX_TABLES];
    u32 l1_count;
};

struct opengpu_device {
    struct device *dev;
    struct opengpu_hw hw;
    struct opengpu_compute compute;
    struct opengpu_display display;
    struct opengpu_mmu mmu;
    u32 width;
    u32 height;
    u32 stride;
};

/* Typed execution descriptor passed across the business/hardware boundary. */
struct opengpu_job {
    dma_addr_t cmd;
    u32 cmd_count;
    dma_addr_t color;
    dma_addr_t depth;
    u32 stride;
    bool depth_test;
    u32 depth_func;
    bool depth_write;
    u32 cull_mode;
    u32 sample_mode;
    /* Global stencil/blend defaults (register/job-record encodings; per-draw
     * records override them). stencil_config uses the draw-record word-36
     * layout; stencil_test is the enable bit. */
    bool stencil_test;
    u32 stencil_config;
    u32 stencil_ref_masks;
    u32 blend_config;
    dma_addr_t texture;
    u32 texture_width;
    u32 texture_height;
    u32 texture_config;
    u32 completion_delay_ms;
};

struct opengpu_kernel_launch {
    dma_addr_t kernel_pc;
    dma_addr_t kernarg;
    u32 grid[3];
    u32 local[3];
};

struct opengpu_command_events {
    u32 flags;
    u32 wait_event;
    u32 signal_event;
};

struct opengpu_scanout {
    dma_addr_t base;
    u32 stride;
    u32 width;
    u32 height;
    u32 format;
    bool enable;
};

int opengpu_hw_init(struct opengpu_device *gpu,
                    struct platform_device *pdev);
void opengpu_hw_fini(struct opengpu_device *gpu);
int opengpu_hw_submit(struct opengpu_device *gpu,
                      const struct opengpu_job *job);
int opengpu_hw_submit_async(struct opengpu_device *gpu,
                            const struct opengpu_job *job,
                            const struct opengpu_vm *vm,
                            struct dma_fence **fence);
int opengpu_hw_clear_and_submit_async(struct opengpu_device *gpu,
                                      const struct opengpu_job *job,
                                      u32 clear_base, u32 clear_bytes,
                                      u32 clear_pattern,
                                      const struct opengpu_vm *vm,
                                      struct dma_fence **fence);
void opengpu_hw_abort(struct opengpu_device *gpu, int error);
void opengpu_hw_progress_tick(struct opengpu_device *gpu);
void opengpu_hw_get_fault(struct opengpu_device *gpu,
                          struct opengpu_fault_snapshot *fault);
int opengpu_hw_clear(struct opengpu_device *gpu, u32 base, u32 bytes,
                     u32 pattern);
int opengpu_hw_blit(struct opengpu_device *gpu, u32 source, u32 destination,
                    u32 bytes);
int opengpu_hw_strided_blit(struct opengpu_device *gpu, u32 source,
                            u32 destination, u32 width, u32 height,
                            u32 source_stride, u32 destination_stride);
/* The optional `vm` selects the address space a submission runs in.  NULL (or
 * a disabled VM) means the driver's global ASID-0 identity map.  The switch is
 * programmed inside the hardware submit lock, so it cannot race another
 * submission; all mappings are global, so it needs no TLB flush. */
int opengpu_hw_clear_async(struct opengpu_device *gpu, u32 base, u32 bytes,
                           u32 pattern,
                           const struct opengpu_command_events *events,
                           const struct opengpu_vm *vm,
                           struct dma_fence **fence);
int opengpu_hw_blit_async(struct opengpu_device *gpu, u32 source,
                          u32 destination, u32 bytes,
                          const struct opengpu_command_events *events,
                          const struct opengpu_vm *vm,
                          struct dma_fence **fence);
int opengpu_hw_strided_blit_async(struct opengpu_device *gpu, u32 source,
                                  u32 destination, u32 width, u32 height,
                                  u32 source_stride,
                                  u32 destination_stride,
                                  const struct opengpu_command_events *events,
                                  const struct opengpu_vm *vm,
                                  struct dma_fence **fence);
int opengpu_hw_resolve_async(struct opengpu_device *gpu, u32 source,
                             u32 destination, u32 width, u32 height,
                             u32 source_stride, u32 destination_stride,
                             u32 sample_mode,
                             const struct opengpu_command_events *events,
                             const struct opengpu_vm *vm,
                             struct dma_fence **fence);
int opengpu_hw_invalidate_async(struct opengpu_device *gpu, u32 address,
                                u32 bytes,
                                const struct opengpu_command_events *events,
                                const struct opengpu_vm *vm,
                                struct dma_fence **fence);
int opengpu_hw_enable_mmu(struct opengpu_device *gpu, dma_addr_t root_table);
/* Program both CU `satp` registers with `root_table` and `asid`, without a
 * TLB flush.  The caller quiesces execution and shoots down a recycled ASID. */
int opengpu_hw_set_satp(struct opengpu_device *gpu, dma_addr_t root_table,
                        u32 asid);
/* Switch the active address space outside a submission: quiesce, then set
 * `satp`.  `vm == NULL` restores the global ASID-0 identity map. */
int opengpu_hw_activate_vm(struct opengpu_device *gpu,
                           const struct opengpu_vm *vm);
int opengpu_hw_flush_tlbs(struct opengpu_device *gpu);
/* Scoped shootdown: drop only the entries for one ASID or one VPN.  Global
 * mappings and other address spaces stay warm; the texture translator is
 * flushed by the same pulse. */
int opengpu_hw_flush_tlb_asid(struct opengpu_device *gpu, u32 asid);
int opengpu_hw_flush_tlb_vpn(struct opengpu_device *gpu, u32 vpn);
/* Caller holds submit_lock until its page-table update and flush finish. */
int opengpu_hw_wait_idle_locked(struct opengpu_device *gpu);

int opengpu_mmu_init(struct opengpu_device *gpu);
void opengpu_mmu_fini(struct opengpu_device *gpu);
int opengpu_mmu_set_range_policy(struct opengpu_device *gpu, dma_addr_t base,
                                 size_t size, u32 policy);
/* Per-VM root tables.  Create allocates an ASID and clones the global identity
 * map into a root table; activate switches both CU `satp` registers to it;
 * destroy evicts its TLB entries and releases it. */
int opengpu_mmu_vm_create(struct opengpu_device *gpu, struct opengpu_vm *vm);
int opengpu_mmu_vm_activate(struct opengpu_device *gpu,
                            const struct opengpu_vm *vm);
/* Map `size` bytes of 4 KiB-aligned VA (`va`) to PA (`pa`) in this VM's private
 * address space with `policy`.  Mapped leaves are non-global, so only this
 * VM's ASID resolves them, and a scoped ASID flush drops any prior
 * translation.  Untouched pages keep the shared global identity mapping. */
int opengpu_mmu_vm_map(struct opengpu_device *gpu, struct opengpu_vm *vm,
                       dma_addr_t va, dma_addr_t pa, size_t size, u32 policy);
void opengpu_mmu_vm_destroy(struct opengpu_device *gpu, struct opengpu_vm *vm);
int opengpu_hw_compute_async(struct opengpu_device *gpu,
                             const struct opengpu_kernel_launch *launch,
                             const struct opengpu_command_events *events,
                             const struct opengpu_vm *vm,
                             struct dma_fence **fence);
int opengpu_hw_display_commit(struct opengpu_device *gpu,
                              const struct opengpu_scanout *scanout);

int opengpu_buffer_alloc(struct opengpu_device *gpu,
                         struct opengpu_buffer *buffer, size_t size);
void opengpu_buffer_free(struct opengpu_device *gpu,
                         struct opengpu_buffer *buffer);

int opengpu_compute_init(struct opengpu_device *gpu);
void opengpu_compute_fini(struct opengpu_device *gpu);
int opengpu_compute_drm_ioctl(struct drm_device *drm, void *data,
                              struct drm_file *file);
int opengpu_compute_drm_open(struct drm_device *drm, struct drm_file *file);
void opengpu_compute_drm_postclose(struct drm_device *drm,
                                   struct drm_file *file);
int opengpu_compute_context_create_ioctl(struct drm_device *drm, void *data,
                                         struct drm_file *file);
int opengpu_compute_get_param_ioctl(struct drm_device *drm, void *data,
                                    struct drm_file *file);
int opengpu_compute_get_fault_ioctl(struct drm_device *drm, void *data,
                                    struct drm_file *file);
int opengpu_compute_context_destroy_ioctl(struct drm_device *drm, void *data,
                                          struct drm_file *file);
int opengpu_compute_resource_bind_ioctl(struct drm_device *drm, void *data,
                                        struct drm_file *file);
int opengpu_compute_resource_unbind_ioctl(struct drm_device *drm, void *data,
                                          struct drm_file *file);
int opengpu_compute_blit_ioctl(struct drm_device *drm, void *data,
                               struct drm_file *file);
int opengpu_compute_fill_ioctl(struct drm_device *drm, void *data,
                               struct drm_file *file);
int opengpu_compute_strided_blit_ioctl(struct drm_device *drm, void *data,
                                       struct drm_file *file);
int opengpu_compute_resolve_ioctl(struct drm_device *drm, void *data,
                                  struct drm_file *file);
int opengpu_compute_invalidate_ioctl(struct drm_device *drm, void *data,
                                     struct drm_file *file);
int opengpu_compute_launch_ioctl(struct drm_device *drm, void *data,
                                 struct drm_file *file);

int opengpu_display_init(struct opengpu_device *gpu,
                         const struct opengpu_buffer *boot_fb);
void opengpu_display_fini(struct opengpu_device *gpu);

#endif /* OPENGPU_DEVICE_H */
