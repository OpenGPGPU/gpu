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

/* Find the second-level table for a 4 MiB region, splitting the identity
 * superpage on first use. */
static int mmu_l1_for_region(struct opengpu_device *gpu, u32 region,
                             struct opengpu_buffer **out)
{
    struct opengpu_mmu *mmu = &gpu->mmu;
    u32 *root = mmu->root.cpu;
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
    root[region] = mmu_link_pte(mmu->l1[i].dma);
    mmu->l1_region[i] = region;
    mmu->l1_count++;
    *out = &mmu->l1[i];
    return 0;
}

int opengpu_mmu_set_range_policy(struct opengpu_device *gpu, dma_addr_t base,
                                 size_t size, u32 policy)
{
    struct opengpu_mmu *mmu = &gpu->mmu;
    dma_addr_t start, end, phys;
    int ret = 0;

    if (!mmu->enabled)
        return -EOPNOTSUPP;
    if (policy > OPENGPU_MMU_POLICY_UNCACHED)
        return -EINVAL;
    if (size == 0)
        return 0;

    start = base & ~(dma_addr_t)(MMU_PAGE_SIZE - 1);
    end = (base + size + MMU_PAGE_SIZE - 1) &
        ~(dma_addr_t)(MMU_PAGE_SIZE - 1);

    mutex_lock(&mmu->lock);
    for (phys = start; phys < end; phys += MMU_PAGE_SIZE) {
        struct opengpu_buffer *l1;
        u32 region = (u32)(phys / MMU_SUPERPAGE_SIZE);
        u32 page = (u32)((phys % MMU_SUPERPAGE_SIZE) / MMU_PAGE_SIZE);
        u32 *table;

        ret = mmu_l1_for_region(gpu, region, &l1);
        if (ret)
            break;
        table = l1->cpu;
        table[page] = mmu_leaf_pte(phys, policy);
    }
    mutex_unlock(&mmu->lock);

    if (!ret)
        opengpu_hw_flush_tlbs(gpu);
    return ret;
}

int opengpu_mmu_init(struct opengpu_device *gpu)
{
    struct opengpu_mmu *mmu = &gpu->mmu;
    u32 *root;
    u32 i;
    int ret;

    mutex_init(&mmu->lock);
    if (!(gpu->hw.capabilities & GPU_CAP_UNIFIED_COMMANDS))
        return 0; /* No MMU control path; stay in Bare mode. */

    ret = opengpu_buffer_alloc(gpu, &mmu->root, MMU_PAGE_SIZE);
    if (ret)
        return ret;
    root = mmu->root.cpu;
    for (i = 0; i < MMU_ROOT_ENTRIES; i++)
        root[i] = mmu_leaf_pte((dma_addr_t)i * MMU_SUPERPAGE_SIZE,
                               OPENGPU_MMU_POLICY_CACHED);
    ret = opengpu_hw_enable_mmu(gpu, mmu->root.dma);
    if (ret) {
        opengpu_buffer_free(gpu, &mmu->root);
        return ret;
    }
    mmu->enabled = true;
    return 0;
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
