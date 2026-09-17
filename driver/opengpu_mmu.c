// SPDX-License-Identifier: GPL-2.0
/* Identity-map GPU MMU with per-page Sv32 cache policy.
 *
 * The CU MMUs are enabled with a root table of 4 MiB identity superpages, so
 * every existing physical-address binding keeps working.  When a 4 KiB page
 * needs a non-default cache policy (for example an uncached buffer that the CPU
 * writes), the containing superpage is split into a second-level table and only
 * that page's PTE changes.  Page tables live in coherent DMA memory and are
 * fetched uncached by the hardware walker, so CPU writes to them are visible.
 */
#include <linux/mutex.h>
#include <linux/overflow.h>

#include "opengpu_device.h"

#define MMU_PAGE_SIZE       4096u
#define MMU_SUPERPAGE_SIZE  (4u * 1024u * 1024u)
#define MMU_L1_ENTRIES      1024u
#define MMU_ROOT_ENTRIES    1024u

/* Sv32 PTE flag bits. */
#define MMU_PTE_V 0x001u
#define MMU_PTE_R 0x002u
#define MMU_PTE_W 0x004u
#define MMU_PTE_X 0x008u
#define MMU_PTE_A 0x040u
#define MMU_PTE_D 0x080u
#define MMU_PTE_LEAF_FLAGS (MMU_PTE_V | MMU_PTE_R | MMU_PTE_W | MMU_PTE_X | \
                            MMU_PTE_A | MMU_PTE_D)
#define MMU_PTE_POLICY_SHIFT 8u

static u32 mmu_leaf_pte(dma_addr_t phys, u32 policy)
{
    u32 ppn = (u32)(phys >> 12);

    return ((ppn & 0xfffffu) << 10) | MMU_PTE_LEAF_FLAGS |
        ((policy & 0x3u) << MMU_PTE_POLICY_SHIFT);
}

static u32 mmu_link_pte(dma_addr_t table)
{
    return ((((u32)(table >> 12)) & 0xfffffu) << 10) | MMU_PTE_V;
}

/* Fill a root table with 4 MiB identity superpages.  The whole physical
 * address space is reachable this way, so every existing physical-address
 * binding keeps working. */
static void mmu_fill_identity(u32 *root, u32 policy)
{
    u32 i;

    for (i = 0; i < MMU_ROOT_ENTRIES; i++)
        root[i] = mmu_leaf_pte((dma_addr_t)i * MMU_SUPERPAGE_SIZE, policy);
}

/* Find the second-level table for a 4 MiB region, splitting the identity
 * superpage on first use. */
static int mmu_l1_for_region(struct opengpu_device *gpu, u32 region,
                             struct opengpu_buffer **out)
{
    struct opengpu_mmu *mmu = &gpu->mmu;
    u32 i;
    int ret;

    for (i = 0; i < mmu->l1_count; i++) {
        if (mmu->l1_region[i] == region) {
            *out = &mmu->l1[i];
            return 0;
        }
    }
    if (mmu->l1_count >= OPENGPU_MMU_MAX_TABLES)
        return -ENOSPC;

    i = mmu->l1_count;
    ret = opengpu_buffer_alloc(gpu, &mmu->l1[i], MMU_PAGE_SIZE);
    if (ret)
        return ret;
    {
        u32 *l1 = mmu->l1[i].cpu;
        u32 j;

        for (j = 0; j < MMU_L1_ENTRIES; j++)
            l1[j] = mmu_leaf_pte((dma_addr_t)region * MMU_SUPERPAGE_SIZE +
                                 (dma_addr_t)j * MMU_PAGE_SIZE,
                                 OPENGPU_MMU_POLICY_CACHED);
    }
    /* The caller publishes the link only after every allocation succeeds. */
    mmu->l1_region[i] = region;
    mmu->l1_count++;
    *out = &mmu->l1[i];
    return 0;
}

int opengpu_mmu_set_range_policy(struct opengpu_device *gpu, dma_addr_t base,
                                 size_t size, u32 policy)
{
    struct opengpu_mmu *mmu = &gpu->mmu;
    u64 start, end, phys;
    u32 old_count, i;
    int ret = 0;

    if (!mmu->enabled)
        return -EOPNOTSUPP;
    if (policy > OPENGPU_MMU_POLICY_UNCACHED)
        return -EINVAL;
    if (size == 0)
        return 0;

    if (check_add_overflow((u64)base, (u64)size, &end) ||
        end > (1ull << 32))
        return -ERANGE;
    start = (u64)base & ~(u64)(MMU_PAGE_SIZE - 1);
    end = (end + MMU_PAGE_SIZE - 1) & ~(u64)(MMU_PAGE_SIZE - 1);

    mutex_lock(&gpu->hw.submit_lock);
    ret = opengpu_hw_wait_idle_locked(gpu);
    if (ret)
        goto out_submit;
    mutex_lock(&mmu->lock);
    old_count = mmu->l1_count;
    /* Reserve all tables before touching any live mapping. */
    for (phys = start; phys < end;) {
        struct opengpu_buffer *l1;
        u32 region = phys / MMU_SUPERPAGE_SIZE;

        ret = mmu_l1_for_region(gpu, region, &l1);
        if (ret)
            goto rollback;
        phys = ((u64)region + 1) * MMU_SUPERPAGE_SIZE;
    }
    for (phys = start; phys < end; phys += MMU_PAGE_SIZE) {
        struct opengpu_buffer *l1;
        u32 region = (u32)(phys / MMU_SUPERPAGE_SIZE);
        u32 page = (u32)((phys % MMU_SUPERPAGE_SIZE) / MMU_PAGE_SIZE);
        u32 *table;

        /* All regions were reserved above; this lookup cannot allocate. */
        mmu_l1_for_region(gpu, region, &l1);
        table = l1->cpu;
        table[page] = mmu_leaf_pte(phys, policy);
    }
    dma_wmb();
    for (i = old_count; i < mmu->l1_count; i++)
        ((u32 *)mmu->root.cpu)[mmu->l1_region[i]] =
            mmu_link_pte(mmu->l1[i].dma);
    dma_wmb();
    ret = opengpu_hw_flush_tlbs(gpu);
    goto out_mmu;

rollback:
    while (mmu->l1_count > old_count)
        opengpu_buffer_free(gpu, &mmu->l1[--mmu->l1_count]);
out_mmu:
    mutex_unlock(&mmu->lock);
out_submit:
    mutex_unlock(&gpu->hw.submit_lock);
    return ret;
}

int opengpu_mmu_init(struct opengpu_device *gpu)
{
    struct opengpu_mmu *mmu = &gpu->mmu;
    int ret;

    mutex_init(&mmu->lock);
    opengpu_asid_pool_init(&mmu->asids);
    if (!(gpu->hw.capabilities & GPU_CAP_UNIFIED_COMMANDS))
        return 0; /* No MMU control path; stay in Bare mode. */

    ret = opengpu_buffer_alloc(gpu, &mmu->root, MMU_PAGE_SIZE);
    if (ret)
        return ret;
    mmu_fill_identity(mmu->root.cpu, OPENGPU_MMU_POLICY_CACHED);
    dma_wmb();
    ret = opengpu_hw_enable_mmu(gpu, mmu->root.dma);
    if (ret) {
        opengpu_buffer_free(gpu, &mmu->root);
        return ret;
    }
    mmu->enabled = true;
    return 0;
}

/* Quiesce execution and drop every TLB entry tagged `asid`.  A recycled ASID
 * must be shot down before it names a different page table, and destroying a
 * VM must evict its entries so a later address space cannot hit them. */
static int mmu_shootdown_asid(struct opengpu_device *gpu, u32 asid)
{
    struct opengpu_mmu *mmu = &gpu->mmu;
    int ret;

    mutex_lock(&gpu->hw.submit_lock);
    ret = opengpu_hw_wait_idle_locked(gpu);
    if (!ret) {
        mutex_lock(&mmu->lock);
        ret = opengpu_hw_flush_tlb_asid(gpu, asid);
        mutex_unlock(&mmu->lock);
    }
    mutex_unlock(&gpu->hw.submit_lock);
    return ret;
}

int opengpu_mmu_vm_create(struct opengpu_device *gpu, struct opengpu_vm *vm)
{
    struct opengpu_mmu *mmu = &gpu->mmu;
    u32 asid;
    int ret;

    if (!vm)
        return -EINVAL;
    if (!mmu->enabled)
        return -EOPNOTSUPP;
    vm->asid = 0;
    vm->enabled = false;

    ret = opengpu_buffer_alloc(gpu, &vm->root, MMU_PAGE_SIZE);
    if (ret)
        return ret;
    mmu_fill_identity(vm->root.cpu, OPENGPU_MMU_POLICY_CACHED);
    dma_wmb();

    mutex_lock(&mmu->lock);
    ret = opengpu_asid_alloc(&mmu->asids, &asid);
    mutex_unlock(&mmu->lock);
    if (ret)
        goto err_free_root;

    /* An ASID freed by an earlier VM may still have TLB entries. */
    ret = mmu_shootdown_asid(gpu, asid);
    if (ret)
        goto err_free_asid;

    vm->asid = asid;
    vm->enabled = true;
    return 0;

err_free_asid:
    mutex_lock(&mmu->lock);
    opengpu_asid_free(&mmu->asids, asid);
    mutex_unlock(&mmu->lock);
err_free_root:
    opengpu_buffer_free(gpu, &vm->root);
    return ret;
}

int opengpu_mmu_vm_activate(struct opengpu_device *gpu,
                            const struct opengpu_vm *vm)
{
    int ret;

    if (!vm || !vm->enabled)
        return -EINVAL;
    if (!gpu->mmu.enabled)
        return -EOPNOTSUPP;

    /* The fixed-function texture translator follows the vector satp but tags
     * no entries with an ASID.  All VM roots are identity maps today, so a
     * switch leaves its cached translations valid; a future non-identity VM
     * must flush the graphics clients when it is activated. */
    mutex_lock(&gpu->hw.submit_lock);
    ret = opengpu_hw_wait_idle_locked(gpu);
    if (!ret)
        ret = opengpu_hw_set_satp(gpu, vm->root.dma, vm->asid);
    mutex_unlock(&gpu->hw.submit_lock);
    return ret;
}

void opengpu_mmu_vm_destroy(struct opengpu_device *gpu, struct opengpu_vm *vm)
{
    struct opengpu_mmu *mmu = &gpu->mmu;

    if (!vm || !vm->enabled)
        return;
    /* Evict this address space before releasing the ASID for reuse. */
    mmu_shootdown_asid(gpu, vm->asid);
    mutex_lock(&mmu->lock);
    opengpu_asid_free(&mmu->asids, vm->asid);
    mutex_unlock(&mmu->lock);
    opengpu_buffer_free(gpu, &vm->root);
    vm->enabled = false;
}

void opengpu_mmu_fini(struct opengpu_device *gpu)
{
    struct opengpu_mmu *mmu = &gpu->mmu;
    u32 i;

    for (i = 0; i < mmu->l1_count; i++)
        opengpu_buffer_free(gpu, &mmu->l1[i]);
    mmu->l1_count = 0;
    opengpu_buffer_free(gpu, &mmu->root);
    mmu->enabled = false;
}
