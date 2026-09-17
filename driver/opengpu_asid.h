/* SPDX-License-Identifier: MIT */
#ifndef OPENGPU_ASID_H
#define OPENGPU_ASID_H

/*
 * GPU address-space ID (ASID) allocator.
 *
 * Sv32 tags TLB entries with a 9-bit ASID (512 values).  ASID 0 is reserved
 * for the driver's global identity map, whose entries are needed by every
 * address space; user VMs allocate from 1..511.  Each VM owns one ASID and
 * one root page table, so a coarse switch (quiesce, then reprogram satp)
 * needs no full flush: entries of other ASIDs stay resident.
 *
 * The allocator only hands out IDs.  A switched-out ASID keeps its TLB
 * entries, so an ASID must be shot down (opengpu_hw_flush_tlb_asid) before it
 * is reused for a different page table; the MMU VM layer does that on create
 * and destroy.
 *
 * Kernel-free so the same logic is exercised by tests/opengpu_asid_test.c.
 */

#ifdef __KERNEL__
#include <linux/types.h>
typedef u32 opengpu_asid_u32;
#else
#include <stdbool.h>
#include <stdint.h>
typedef uint32_t opengpu_asid_u32;
#endif

#define OPENGPU_ASID_COUNT 512u
/* ASID 0 is the global identity map; it is never handed out. */
#define OPENGPU_ASID_RESERVED 0u
#define OPENGPU_ASID_MAX (OPENGPU_ASID_COUNT - 1u)
#define OPENGPU_ASID_WORDS (OPENGPU_ASID_COUNT / 32u)

struct opengpu_asid_pool {
	/* Bit `asid` set means the ASID is in use. */
	opengpu_asid_u32 in_use[OPENGPU_ASID_WORDS];
	/* Round-robin search cursor: the next ASID to consider. */
	opengpu_asid_u32 next;
};

static inline void opengpu_asid_pool_init(struct opengpu_asid_pool *pool)
{
	opengpu_asid_u32 i;

	for (i = 0; i < OPENGPU_ASID_WORDS; i++)
		pool->in_use[i] = 0;
	/* Reserve ASID 0 for the global identity map. */
	pool->in_use[OPENGPU_ASID_RESERVED / 32u] =
		1u << (OPENGPU_ASID_RESERVED % 32u);
	pool->next = 1u;
}

static inline bool opengpu_asid_in_use(const struct opengpu_asid_pool *pool,
				       opengpu_asid_u32 asid)
{
	if (asid >= OPENGPU_ASID_COUNT)
		return false;
	return (pool->in_use[asid / 32u] & (1u << (asid % 32u))) != 0;
}

/* Return 0 and the least-recently-considered free ASID, or -ENOSPC. */
static inline int opengpu_asid_alloc(struct opengpu_asid_pool *pool,
				     opengpu_asid_u32 *out)
{
	opengpu_asid_u32 step;

	for (step = 0; step < OPENGPU_ASID_MAX; step++) {
		opengpu_asid_u32 asid = pool->next;
		opengpu_asid_u32 word, bit;

		if (asid < 1u || asid > OPENGPU_ASID_MAX)
			asid = 1u;
		pool->next = (asid == OPENGPU_ASID_MAX) ? 1u : asid + 1u;
		word = asid / 32u;
		bit = asid % 32u;
		if (!(pool->in_use[word] & (1u << bit))) {
			pool->in_use[word] |= 1u << bit;
			*out = asid;
			return 0;
		}
	}
	return -28; /* -ENOSPC */
}

/* Release `asid`; ASID 0, out-of-range and already-free IDs are rejected. */
static inline int opengpu_asid_free(struct opengpu_asid_pool *pool,
				    opengpu_asid_u32 asid)
{
	opengpu_asid_u32 word, bit;

	if (asid < 1u || asid > OPENGPU_ASID_MAX)
		return -22; /* -EINVAL */
	word = asid / 32u;
	bit = asid % 32u;
	if (!(pool->in_use[word] & (1u << bit)))
		return -22;
	pool->in_use[word] &= ~(1u << bit);
	return 0;
}

#endif /* OPENGPU_ASID_H */
