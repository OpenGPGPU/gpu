// SPDX-License-Identifier: GPL-2.0
/* Exercise the Sv32 ASID allocator.
 * cc -std=c11 -Wall -Wextra -Werror -I. tests/opengpu_asid_test.c \
 *    -o /tmp/opengpu_asid_test
 */
#include <assert.h>
#include <stdio.h>

#include "opengpu_asid.h"

int main(void)
{
	struct opengpu_asid_pool pool;
	opengpu_asid_u32 asid, first, last;
	unsigned count;

	opengpu_asid_pool_init(&pool);

	/* ASID 0 is reserved for the global identity map. */
	assert(opengpu_asid_in_use(&pool, 0));
	assert(!opengpu_asid_in_use(&pool, 1));
	assert(opengpu_asid_alloc(&pool, &first) == 0);
	assert(first == 1);
	assert(opengpu_asid_in_use(&pool, 1));

	/* Allocation is round-robin: the next fresh IDs follow in order. */
	assert(opengpu_asid_alloc(&pool, &asid) == 0 && asid == 2);
	assert(opengpu_asid_alloc(&pool, &asid) == 0 && asid == 3);

	/* Freeing makes an ID available again.  The cursor has advanced past 2,
	 * so the next allocation takes the following free ID. */
	assert(opengpu_asid_free(&pool, 2) == 0);
	assert(!opengpu_asid_in_use(&pool, 2));
	assert(opengpu_asid_alloc(&pool, &asid) == 0 && asid == 4);
	assert(opengpu_asid_in_use(&pool, 4));

	/* ASID 0 is never allocated and cannot be freed. */
	assert(opengpu_asid_free(&pool, 0) == -22);
	assert(opengpu_asid_in_use(&pool, 0));

	/* Out-of-range and double frees are rejected. */
	assert(opengpu_asid_free(&pool, OPENGPU_ASID_COUNT) == -22);
	assert(opengpu_asid_free(&pool, 1) == 0);
	assert(opengpu_asid_free(&pool, 1) == -22);
	assert(!opengpu_asid_in_use(&pool, OPENGPU_ASID_COUNT));

	/* Exhaust the pool: 511 usable ASIDs, then -ENOSPC. */
	opengpu_asid_pool_init(&pool);
	count = 0;
	last = 0;
	while (opengpu_asid_alloc(&pool, &asid) == 0) {
		assert(asid >= 1 && asid <= OPENGPU_ASID_MAX);
		assert(opengpu_asid_in_use(&pool, asid));
		last = asid;
		count++;
	}
	assert(count == OPENGPU_ASID_MAX);
	assert(opengpu_asid_alloc(&pool, &asid) == -28); /* -ENOSPC */

	/* Every usable ASID was handed out exactly once. */
	for (asid = 1; asid <= OPENGPU_ASID_MAX; asid++)
		assert(opengpu_asid_in_use(&pool, asid));
	assert(last == OPENGPU_ASID_MAX);

	/* Freeing one makes room, and the cursor finds it. */
	assert(opengpu_asid_free(&pool, 7) == 0);
	assert(opengpu_asid_alloc(&pool, &asid) == 0 && asid == 7);
	assert(opengpu_asid_alloc(&pool, &asid) == -28);

	puts("ASID allocator tests passed");
	return 0;
}
