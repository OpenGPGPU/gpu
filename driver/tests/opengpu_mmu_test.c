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
#define GPU_CAP_UNIFIED_COMMANDS 1u
#define OPENGPU_MMU_MAX_TABLES 16u
#define OPENGPU_MMU_POLICY_CACHED 0u
#define OPENGPU_MMU_POLICY_UNCACHED 2u
struct opengpu_buffer { void *cpu; dma_addr_t dma; size_t size; };
struct opengpu_mmu {
    struct mutex lock;
    struct opengpu_buffer root;
    struct opengpu_buffer l1[OPENGPU_MMU_MAX_TABLES];
    u32 l1_region[OPENGPU_MMU_MAX_TABLES];
    u32 l1_count;
    bool enabled;
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
    b->cpu = calloc(1, size);
    assert(b->cpu);
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
    assert((((u32 *)gpu.mmu.l1[0].cpu)[1023] & 0x300) == 0x200);
    assert((((u32 *)gpu.mmu.l1[1].cpu)[0] & 0x300) == 0x200);
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
    assert(!gpu.hw.submit_lock.held && !gpu.mmu.lock.held);
    opengpu_mmu_fini(&gpu);
    assert(!live_allocations);
    puts("MMU allocation, rollback, range and lock-scope tests passed");
    return 0;
}
