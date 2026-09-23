// SPDX-License-Identifier: GPL-2.0
/* Exercise the production MMU implementation with allocation/idle failures.
 * cc -std=c11 -Wall -Wextra -Werror -Itests/mmu_stubs \
 *    tests/opengpu_mmu_test.c -o /tmp/opengpu_mmu_test
 */
#include <assert.h>
#include <errno.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <linux/mutex.h>

/* Replace kernel/device dependencies, not the MMU algorithm under test. */
#define OPENGPU_DEVICE_H
typedef uint32_t u32;
typedef uint64_t u64;
typedef uint64_t dma_addr_t;
#include "../opengpu_asid.h"
#define GPU_CAP_UNIFIED_COMMANDS 1u
#define OPENGPU_MMU_MAX_TABLES 16u
#define OPENGPU_MMU_POLICY_CACHED 0u
#define OPENGPU_MMU_POLICY_UNCACHED 2u
struct opengpu_buffer { void *cpu; dma_addr_t dma; size_t size; };
struct opengpu_vm;
struct opengpu_mmu {
    struct mutex lock;
    struct opengpu_buffer root;
    struct opengpu_buffer l1[OPENGPU_MMU_MAX_TABLES];
    u32 l1_region[OPENGPU_MMU_MAX_TABLES];
    u32 l1_count;
    struct opengpu_asid_pool asids;
    bool enabled;
};
struct opengpu_vm {
    struct opengpu_buffer root;
    u32 asid;
    bool enabled;
    struct opengpu_buffer l1[OPENGPU_MMU_MAX_TABLES];
    u32 l1_region[OPENGPU_MMU_MAX_TABLES];
    u32 l1_count;
};
struct opengpu_device {
    struct { struct mutex submit_lock; u32 capabilities; } hw;
    struct opengpu_mmu mmu;
};
static int allocations_until_failure = -1, live_allocations, idle_error;
static unsigned flushes, barriers;
static dma_addr_t next_dma = 0x100000;
static int opengpu_buffer_alloc(struct opengpu_device *gpu,
                               struct opengpu_buffer *b, size_t size)
{
    (void)gpu;
    if (!allocations_until_failure)
        return -ENOMEM;
    if (allocations_until_failure > 0)
        allocations_until_failure--;
    b->cpu = malloc(size);
    assert(b->cpu);
    memset(b->cpu, 0xa5, size);
    b->size = size;
    b->dma = next_dma;
    next_dma += size;
    live_allocations++;
    return 0;
}
static void opengpu_buffer_free(struct opengpu_device *gpu,
                               struct opengpu_buffer *b)
{
    (void)gpu;
    if (b->cpu) {
        free(b->cpu);
        b->cpu = NULL;
        live_allocations--;
    }
}
static int opengpu_hw_wait_idle_locked(struct opengpu_device *gpu)
{
    assert(gpu->hw.submit_lock.held);
    return idle_error;
}
static int opengpu_hw_flush_tlbs(struct opengpu_device *gpu)
{
    assert(gpu->hw.submit_lock.held && gpu->mmu.lock.held);
    assert(barriers >= 2);
    flushes++;
    return 0;
}
static unsigned asid_flushes, vm_activations;
static dma_addr_t last_satp_root;
static u32 last_satp_asid;
static int opengpu_hw_flush_tlb_asid(struct opengpu_device *gpu, u32 asid)
{
    assert(gpu->hw.submit_lock.held && gpu->mmu.lock.held);
    assert(barriers >= 2);
    (void)asid;
    asid_flushes++;
    return 0;
}
static int opengpu_hw_activate_vm(struct opengpu_device *gpu,
                                  const struct opengpu_vm *vm)
{
    /* The production helper acquires submit_lock itself. */
    assert(!gpu->hw.submit_lock.held);
    last_satp_root = vm ? vm->root.dma : 0;
    last_satp_asid = vm ? vm->asid : 0;
    vm_activations++;
    return 0;
}
static int opengpu_hw_enable_mmu(struct opengpu_device *gpu, dma_addr_t root)
{
    (void)gpu;
    assert(root && barriers);
    return 0;
}
#define dma_wmb() (++barriers)
#include "../opengpu_mmu.c"

int main(void)
{
    struct opengpu_device gpu = { .hw.capabilities = GPU_CAP_UNIFIED_COMMANDS };
    u32 root_before[1024], leaf_before[1024];
    u32 i;

    assert(!opengpu_mmu_init(&gpu));
    assert((((u32 *)gpu.mmu.root.cpu)[0] & MMU_PTE_V) != 0);
    assert((((u32 *)gpu.mmu.root.cpu)[0] & 0x20) == 0); /* ASID-tagged identity */
    /* Identity leaves are read/write but never executable. */
    assert((((u32 *)gpu.mmu.root.cpu)[0] & MMU_PTE_X) == 0);
    memcpy(root_before, gpu.mmu.root.cpu, sizeof(root_before));
    idle_error = -ETIMEDOUT;
    assert(opengpu_mmu_set_range_policy(&gpu, 0, 4096, 2) == -ETIMEDOUT);
    assert(!gpu.hw.submit_lock.held && !gpu.mmu.l1_count && !flushes);
    idle_error = 0;

    /* Fail after allocating the first of two regions: no live link changed. */
    allocations_until_failure = 1;
    assert(opengpu_mmu_set_range_policy(&gpu, 0x3ff000, 8192, 2) == -ENOMEM);
    assert(!memcmp(root_before, gpu.mmu.root.cpu, sizeof(root_before)));
    assert(!gpu.mmu.l1_count && live_allocations == 1 && !flushes);
    allocations_until_failure = -1;
    assert(!opengpu_mmu_set_range_policy(&gpu, 0x3ff000, 8192, 2));
    assert(gpu.mmu.l1_count == 2 && flushes == 1);
    assert((((u32 *)gpu.mmu.l1[0].cpu)[1023] & 0x320) == 0x200);
    assert((((u32 *)gpu.mmu.l1[1].cpu)[0] & 0x320) == 0x200);
    assert((((u32 *)gpu.mmu.l1[0].cpu)[1022] & 0x300) == 0);

    /* Existing PTEs must also survive allocation failure in a later region. */
    memcpy(leaf_before, gpu.mmu.l1[1].cpu, sizeof(leaf_before));
    allocations_until_failure = 0;
    assert(opengpu_mmu_set_range_policy(&gpu, 0x400000, 0x401000, 2) == -ENOMEM);
    assert(!memcmp(leaf_before, gpu.mmu.l1[1].cpu, sizeof(leaf_before)));
    assert(gpu.mmu.l1_count == 2 && flushes == 1);
    allocations_until_failure = -1;

    /* Capacity exhaustion rolls back all newly reserved tables. */
    memcpy(root_before, gpu.mmu.root.cpu, sizeof(root_before));
    assert(opengpu_mmu_set_range_policy(&gpu, 0x800000, 15 * 0x400000, 2) == -ENOSPC);
    assert(gpu.mmu.l1_count == 2 && live_allocations == 3);
    assert(!memcmp(root_before, gpu.mmu.root.cpu, sizeof(root_before)));
    assert(flushes == 1);

    assert(opengpu_mmu_set_range_policy(&gpu, UINT64_MAX - 1, 8, 2) == -ERANGE);
    assert(opengpu_mmu_set_range_policy(&gpu, 0xffffffff, 2, 2) == -ERANGE);
    assert(!opengpu_mmu_set_range_policy(&gpu, 0xffffffff, 1, 2));
    i = gpu.mmu.l1_count - 1;
    assert(gpu.mmu.l1_region[i] == 1023);
    assert((((u32 *)gpu.mmu.l1[i].cpu)[1023] >> 10) == 0xfffff);

    /* Per-VM roots start empty; activation reprograms satp. */
    {
        struct opengpu_vm vm, vm2;
        int base_live = live_allocations;
        unsigned base_asid = asid_flushes;

        assert(!opengpu_mmu_vm_create(&gpu, &vm));
        assert(vm.enabled && vm.asid == 1);
        assert(live_allocations == base_live + 1);
        /* Creation does not shoot down a freshly allocated ASID. */
        assert(asid_flushes == base_asid);
        for (i = 0; i < MMU_ROOT_ENTRIES; i++)
            assert(((u32 *)vm.root.cpu)[i] == 0);

        assert(!opengpu_mmu_vm_activate(&gpu, &vm));
        assert(vm_activations == 1);
        assert(last_satp_root == vm.root.dma && last_satp_asid == vm.asid);

        /* A second VM takes the next ASID; destroy evicts each ASID once. */
        assert(!opengpu_mmu_vm_create(&gpu, &vm2));
        assert(vm2.enabled && vm2.asid == 2);
        assert(asid_flushes == base_asid);
        opengpu_mmu_vm_destroy(&gpu, &vm2);
        assert(asid_flushes == base_asid + 1);

        opengpu_mmu_vm_destroy(&gpu, &vm);
        assert(!vm.enabled);
        assert(asid_flushes == base_asid + 2);
        assert(live_allocations == base_live);
    }

    /* ASID-0 policy updates never grant access to a live context. */
    {
        struct opengpu_vm vm;
        u32 region = 500;
        int live_before = live_allocations;
        unsigned full_before = flushes;

        assert(!opengpu_mmu_vm_create(&gpu, &vm));
        assert(((u32 *)vm.root.cpu)[1023] == 0);
        assert(!opengpu_mmu_set_range_policy(
            &gpu, (dma_addr_t)region * MMU_SUPERPAGE_SIZE, MMU_PAGE_SIZE, 2));
        assert(((u32 *)vm.root.cpu)[region] == 0);
        assert(((u32 *)gpu.mmu.root.cpu)[region] & MMU_PTE_V);
        assert(flushes == full_before + 1);
        opengpu_mmu_vm_destroy(&gpu, &vm);
        /* Only the new L1 table stays allocated after the VM is gone. */
        assert(live_allocations == live_before + 1);
    }

    /* Private mappings expose only their explicitly bound pages. */
    {
        struct opengpu_vm vm;
        dma_addr_t va = 0x20000000;            /* region 128, page 0 */
        dma_addr_t pa = 0x12345000;
        u32 region = (u32)(va / MMU_SUPERPAGE_SIZE);
        u32 *table;
        int live_before = live_allocations;
        unsigned full_before, asid_before;

        assert(!opengpu_mmu_vm_create(&gpu, &vm));
        full_before = flushes;
        asid_before = asid_flushes;
        assert(!opengpu_mmu_vm_map(&gpu, &vm, va, pa, MMU_PAGE_SIZE, 2));
        assert(flushes == full_before);
        assert(asid_flushes == asid_before + 1);
        assert(vm.l1_count == 1);
        assert((((u32 *)vm.root.cpu)[region] & 1) != 0);
        assert((((u32 *)vm.root.cpu)[region] >> 10) ==
               (u32)(vm.l1[0].dma >> 12));
        table = vm.l1[0].cpu;
        /* The mapped page points at the requested PA, without the G bit, with
         * the requested policy, and executable (private code/data). */
        assert((table[0] >> 10) == 0x12345);
        assert((table[0] & 0x20) == 0);
        assert((table[0] & 0x300) == 0x200);
        assert((table[0] & MMU_PTE_X) != 0);
        /* An untouched sibling has no valid translation. */
        assert(table[1] == 0);
        /* Validation: alignment, size, policy and the 32-bit window. */
        assert(opengpu_mmu_vm_map(&gpu, &vm, va + 1, pa, MMU_PAGE_SIZE, 0) ==
               -EINVAL);
        assert(opengpu_mmu_vm_map(&gpu, &vm, va, pa, MMU_PAGE_SIZE + 1, 0) ==
               -EINVAL);
        assert(opengpu_mmu_vm_map(&gpu, &vm, va, pa, 0, 0) == -EINVAL);
        assert(opengpu_mmu_vm_map(&gpu, &vm, va, pa, MMU_PAGE_SIZE, 3) ==
               -EINVAL);
        assert(opengpu_mmu_vm_map(&gpu, &vm, 0xfffff000, pa, 0x2000, 0) ==
               -ERANGE);
        /* An ASID-0 split of the same region cannot clobber the private table. */
        assert(!opengpu_mmu_set_range_policy(
            &gpu, (dma_addr_t)region * MMU_SUPERPAGE_SIZE, MMU_PAGE_SIZE, 0));
        assert((((u32 *)vm.root.cpu)[region] >> 10) ==
               (u32)(vm.l1[0].dma >> 12));
        /* Unbind revokes the containing page, leaving siblings invalid. */
        asid_before = asid_flushes;
        assert(!opengpu_mmu_vm_unmap(&gpu, &vm, va + 123, 100));
        assert(table[0] == 0);
        assert(table[1] == 0);
        assert(asid_flushes == asid_before + 1);
        assert(opengpu_mmu_vm_unmap(
                   &gpu, &vm, 0x30000000, MMU_PAGE_SIZE) == -ENOENT);
        assert(asid_flushes == asid_before + 1);
        assert(opengpu_mmu_vm_unmap(&gpu, &vm, va, 0) == -EINVAL);
        assert(opengpu_mmu_vm_unmap(
                   &gpu, &vm, 0xfffff000, 0x2000) == -ERANGE);
        opengpu_mmu_vm_destroy(&gpu, &vm);
        /* Only the global split table stays allocated. */
        assert(live_allocations == live_before + 1);
    }

    /* Two contexts may use the same private VA without sharing translations.
     * Revoking one context's leaf must not change the other context's mapping. */
    {
        struct opengpu_vm first, second, recycled;
        dma_addr_t va = 0x24000000;
        int live_before = live_allocations;
        unsigned asid_before = asid_flushes;
        u32 recycled_asid;
        u32 *first_table, *second_table;

        assert(!opengpu_mmu_vm_create(&gpu, &first));
        assert(!opengpu_mmu_vm_create(&gpu, &second));
        assert(first.asid != second.asid);
        assert(!opengpu_mmu_vm_map(
            &gpu, &first, va, 0x11111000, MMU_PAGE_SIZE, 0));
        assert(!opengpu_mmu_vm_map(
            &gpu, &second, va, 0x22222000, MMU_PAGE_SIZE, 0));
        first_table = first.l1[0].cpu;
        second_table = second.l1[0].cpu;
        assert((first_table[0] >> 10) == 0x11111);
        assert((second_table[0] >> 10) == 0x22222);
        assert(first_table[1] == 0 && second_table[1] == 0);
        assert(((u32 *)first.root.cpu)[0] == 0);
        assert(((u32 *)second.root.cpu)[0] == 0);
        assert(!opengpu_mmu_vm_unmap(
            &gpu, &first, va, MMU_PAGE_SIZE));
        assert(first_table[0] == 0);
        assert((second_table[0] >> 10) == 0x22222);
        assert(asid_flushes == asid_before + 3);
        recycled_asid = first.asid;
        opengpu_mmu_vm_destroy(&gpu, &first);
        opengpu_mmu_vm_destroy(&gpu, &second);
        assert(asid_flushes == asid_before + 5);

        /* Force the allocator cursor to the released ID. Destroy already
         * flushed it, so the new owner starts from a fresh root and cannot
         * observe either prior translation. */
        gpu.mmu.asids.next = recycled_asid;
        assert(!opengpu_mmu_vm_create(&gpu, &recycled));
        assert(recycled.asid == recycled_asid);
        assert(((u32 *)recycled.root.cpu)[va / MMU_SUPERPAGE_SIZE] == 0);
        assert(!opengpu_mmu_vm_map(
            &gpu, &recycled, va, 0x33333000, MMU_PAGE_SIZE, 0));
        assert((((u32 *)recycled.l1[0].cpu)[0] >> 10) == 0x33333);
        opengpu_mmu_vm_destroy(&gpu, &recycled);
        assert(asid_flushes == asid_before + 7);
        assert(live_allocations == live_before);
    }
    assert(!gpu.hw.submit_lock.held && !gpu.mmu.lock.held);
    opengpu_mmu_fini(&gpu);
    assert(!live_allocations);
    puts("MMU allocation, rollback, range and lock-scope tests passed");
    return 0;
}
