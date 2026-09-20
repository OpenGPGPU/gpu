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

/* Sv32 PTE flag bits.  Mappings are global (G): every VM root is a copy of
 * the driver's identity map, so a translation stays valid across an ASID
 * switch and the TLB keeps it resident. */
#define MMU_PTE_V 0x001u
#define MMU_PTE_R 0x002u
#define MMU_PTE_W 0x004u
#define MMU_PTE_X 0x008u
#define MMU_PTE_G 0x020u
#define MMU_PTE_A 0x040u
#define MMU_PTE_D 0x080u
#define MMU_PTE_LEAF_FLAGS (MMU_PTE_V | MMU_PTE_R | MMU_PTE_W | MMU_PTE_X | \
                            MMU_PTE_G | MMU_PTE_A | MMU_PTE_D)
#define MMU_PTE_POLICY_SHIFT 8u

static u32 mmu_leaf_pte(dma_addr_t phys, u32 policy)
{
    u32 ppn = (u32)(phys >> 12);

    return ((ppn & 0xfffffu) << 10) | MMU_PTE_LEAF_FLAGS |
        ((policy & 0x3u) << MMU_PTE_POLICY_SHIFT);
}

/* A VM-private leaf: the same PTE without the global bit, so only the owning
 * ASID resolves it. */
static u32 mmu_leaf_pte_private(dma_addr_t phys, u32 policy)
{
    return mmu_leaf_pte(phys, policy) & ~(u32)MMU_PTE_G;
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

/* Clone a root table.  A new VM shares the driver's global identity map,
 * including any split L1 link entries, so a switch reuses the same
 * translations; the G bit keeps them resident across ASIDs. */
static void mmu_copy_root(u32 *destination, const u32 *source)
{
    u32 i;

    for (i = 0; i < MMU_ROOT_ENTRIES; i++)
        destination[i] = source[i];
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

/* Look up an already-split global region; never allocates. */
static const struct opengpu_buffer *mmu_l1_find(const struct opengpu_mmu *mmu,
                                                u32 region)
{
    u32 i;

    for (i = 0; i < mmu->l1_count; i++)
        if (mmu->l1_region[i] == region)
            return &mmu->l1[i];
    return NULL;
}

static struct opengpu_buffer *vm_l1_find(struct opengpu_vm *vm, u32 region)
{
    u32 i;

    for (i = 0; i < vm->l1_count; i++)
        if (vm->l1_region[i] == region)
            return &vm->l1[i];
    return NULL;
}

/* Seed a VM's private L1 table with the region's current effective mapping:
 * the shared global leaves (preserving a split region's policies) when the
 * region is split globally, or the cached identity superpage leaves
 * otherwise.  Untouched leaves therefore stay global. */
static void vm_l1_init(const struct opengpu_mmu *mmu, u32 region, u32 *table)
{
    const struct opengpu_buffer *global = mmu_l1_find(mmu, region);
    u32 j;

    if (global) {
        const u32 *source = global->cpu;

        for (j = 0; j < MMU_L1_ENTRIES; j++)
            table[j] = source[j];
        return;
    }
    for (j = 0; j < MMU_L1_ENTRIES; j++)
        table[j] = mmu_leaf_pte(
            (dma_addr_t)region * MMU_SUPERPAGE_SIZE +
                (dma_addr_t)j * MMU_PAGE_SIZE,
            OPENGPU_MMU_POLICY_CACHED);
}

/* Allocate (but do not publish) a VM-private L1 table for `region`. */
static int vm_l1_for_region(struct opengpu_device *gpu, struct opengpu_vm *vm,
                            struct opengpu_mmu *mmu, u32 region,
                            struct opengpu_buffer **out)
{
    struct opengpu_buffer *l1 = vm_l1_find(vm, region);
    int ret;

    if (l1) {
        *out = l1;
        return 0;
    }
    if (vm->l1_count >= OPENGPU_MMU_MAX_TABLES)
        return -ENOSPC;
    l1 = &vm->l1[vm->l1_count];
    ret = opengpu_buffer_alloc(gpu, l1, MMU_PAGE_SIZE);
    if (ret)
        return ret;
    vm_l1_init(mmu, region, l1->cpu);
    vm->l1_region[vm->l1_count] = region;
    vm->l1_count++;
    *out = l1;
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
    for (i = old_count; i < mmu->l1_count; i++) {
        u32 region = mmu->l1_region[i];
        u32 link = mmu_link_pte(mmu->l1[i].dma);
        u32 j;

        ((u32 *)mmu->root.cpu)[region] = link;
        /* A VM created before this split must see the same L1 table, or it
         * would keep using the cached identity superpage.  A VM that maps the
         * region privately keeps its own table.  Updates exclude submissions
         * (submit_lock held, execution drained). */
        for (j = 1; j < OPENGPU_ASID_COUNT; j++) {
            struct opengpu_vm *vm = mmu->vm_by_asid[j];

            if (vm && !vm_l1_find(vm, region))
                ((u32 *)vm->root.cpu)[region] = link;
        }
    }
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
    vm->l1_count = 0;

    ret = opengpu_buffer_alloc(gpu, &vm->root, MMU_PAGE_SIZE);
    if (ret)
        return ret;

    mutex_lock(&mmu->lock);
    /* Clone the live root under the lock so a concurrent policy update cannot
     * tear it, and register the VM so later splits propagate into it. */
    mmu_copy_root(vm->root.cpu, mmu->root.cpu);
    ret = opengpu_asid_alloc(&mmu->asids, &asid);
    if (!ret) {
        vm->asid = asid;
        mmu->vm_by_asid[asid] = vm;
    }
    mutex_unlock(&mmu->lock);
    if (ret)
        goto err_free_root;
    dma_wmb();

    /* A recycled ASID is clean: vm_destroy shoots the ASID down before it is
     * returned to the pool (and leaks it on failure), so a new owner never
     * inherits another VM's private entries. */
    vm->enabled = true;
    return 0;

err_free_root:
    opengpu_buffer_free(gpu, &vm->root);
    return ret;
}

int opengpu_mmu_vm_activate(struct opengpu_device *gpu,
                            const struct opengpu_vm *vm)
{
    if (!vm || !vm->enabled)
        return -EINVAL;
    if (!gpu->mmu.enabled)
        return -EOPNOTSUPP;
    /* opengpu_hw_activate_vm quiesces, then programs satp; all VM roots share
     * the global identity map, so no flush is needed.  The graphics address
     * translator follows the vector satp and tags its entries with the filling
     * ASID (global pages hit any address space), so a switch needs no graphics
     * flush either. */
    return opengpu_hw_activate_vm(gpu, vm);
}

int opengpu_mmu_vm_map(struct opengpu_device *gpu, struct opengpu_vm *vm,
                       dma_addr_t va, dma_addr_t pa, size_t size, u32 policy)
{
    struct opengpu_mmu *mmu = &gpu->mmu;
    dma_addr_t p, q;
    u64 va_end, pa_end;
    u32 old_count, i;
    int ret = 0;

    if (!vm || !vm->enabled)
        return -EINVAL;
    if (!mmu->enabled)
        return -EOPNOTSUPP;
    if (policy > OPENGPU_MMU_POLICY_UNCACHED)
        return -EINVAL;
    if (!size || (va & (MMU_PAGE_SIZE - 1)) ||
        (pa & (MMU_PAGE_SIZE - 1)) || (size & (MMU_PAGE_SIZE - 1)))
        return -EINVAL;
    if (check_add_overflow((u64)va, (u64)size, &va_end) ||
        check_add_overflow((u64)pa, (u64)size, &pa_end) ||
        va_end > (1ull << 32) || pa_end > (1ull << 32))
        return -ERANGE;

    mutex_lock(&gpu->hw.submit_lock);
    ret = opengpu_hw_wait_idle_locked(gpu);
    if (ret)
        goto out_submit;
    mutex_lock(&mmu->lock);
    old_count = vm->l1_count;

    /* Reserve every private L1 table before touching a live mapping. */
    for (p = va; (u64)p < va_end;) {
        u32 region = (u32)(p / MMU_SUPERPAGE_SIZE);
        struct opengpu_buffer *l1;

        ret = vm_l1_for_region(gpu, vm, mmu, region, &l1);
        if (ret)
            goto rollback;
        p = ((dma_addr_t)region + 1) * MMU_SUPERPAGE_SIZE;
    }
    /* Install the private (non-global) leaves. */
    for (p = va, q = pa; (u64)p < va_end;
         p += MMU_PAGE_SIZE, q += MMU_PAGE_SIZE) {
        u32 region = (u32)(p / MMU_SUPERPAGE_SIZE);
        u32 page = (u32)((p % MMU_SUPERPAGE_SIZE) / MMU_PAGE_SIZE);
        struct opengpu_buffer *l1 = vm_l1_find(vm, region);

        ((u32 *)l1->cpu)[page] = mmu_leaf_pte_private(q, policy);
    }
    dma_wmb();
    for (i = old_count; i < vm->l1_count; i++)
        ((u32 *)vm->root.cpu)[vm->l1_region[i]] =
            mmu_link_pte(vm->l1[i].dma);
    dma_wmb();
    /* A mapping change must evict any cached translation for the range,
     * including a global identity entry that would otherwise shadow the new
     * private leaf: an ASID-scoped flush deliberately keeps globals, so a VA
     * this VM had resolved through the shared identity map would keep hitting
     * the identity PA.  Full-flush, as the global policy split does. */
    ret = opengpu_hw_flush_tlbs(gpu);
    goto out_mmu;

rollback:
    while (vm->l1_count > old_count)
        opengpu_buffer_free(gpu, &vm->l1[--vm->l1_count]);
out_mmu:
    mutex_unlock(&mmu->lock);
out_submit:
    mutex_unlock(&gpu->hw.submit_lock);
    return ret;
}

void opengpu_mmu_vm_destroy(struct opengpu_device *gpu, struct opengpu_vm *vm)
{
    struct opengpu_mmu *mmu = &gpu->mmu;
    u32 i;
    int ret;

    if (!vm || !vm->enabled)
        return;
    /* Evict this address space before releasing the ASID for reuse.  Private
     * (non-global) entries must not survive into the next owner, so if the
     * shootdown fails the ASID is leaked rather than handed out dirty. */
    ret = mmu_shootdown_asid(gpu, vm->asid);
    mutex_lock(&mmu->lock);
    if (vm->asid < OPENGPU_ASID_COUNT &&
        mmu->vm_by_asid[vm->asid] == vm)
        mmu->vm_by_asid[vm->asid] = NULL;
    if (!ret)
        opengpu_asid_free(&mmu->asids, vm->asid);
    mutex_unlock(&mmu->lock);
    for (i = 0; i < vm->l1_count; i++)
        opengpu_buffer_free(gpu, &vm->l1[i]);
    vm->l1_count = 0;
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
