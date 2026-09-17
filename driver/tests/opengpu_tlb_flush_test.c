// SPDX-License-Identifier: GPL-2.0
/* Exercise the scoped TLB-flush register encoding.
 * cc -std=c11 -Wall -Wextra -Werror -I. tests/opengpu_tlb_flush_test.c \
 *    -o /tmp/opengpu_tlb_flush_test
 */
#include <assert.h>
#include <stdint.h>
#include <stdio.h>

#include "opengpu_tlb_flush.h"

int main(void)
{
	opengpu_tlb_u32 word = 0xdeadbeefu;

	assert(opengpu_tlb_flush_full() == GPU_TLB_FLUSH_FULL);

	/* ASID scope sets bit 1 and only the 9-bit ASID field above it. */
	assert(opengpu_tlb_flush_asid(0, &word) == OPENGPU_TLB_FLUSH_OK);
	assert(word == GPU_TLB_FLUSH_ASID);
	assert(opengpu_tlb_flush_asid(GPU_TLB_FLUSH_ASID_MASK, &word) ==
	       OPENGPU_TLB_FLUSH_OK);
	assert((word & GPU_TLB_FLUSH_ASID) != 0);
	assert((word >> GPU_TLB_FLUSH_ASID_SHIFT) == GPU_TLB_FLUSH_ASID_MASK);
	assert((word & ~(GPU_TLB_FLUSH_ASID |
			 (GPU_TLB_FLUSH_ASID_MASK << GPU_TLB_FLUSH_ASID_SHIFT))) == 0);
	word = 0xdeadbeefu;
	assert(opengpu_tlb_flush_asid(GPU_TLB_FLUSH_ASID_MASK + 1, &word) ==
	       OPENGPU_TLB_FLUSH_E_ASID);
	assert(word == 0xdeadbeefu);	/* rejected: untouched */

	/* VPN scope sets bit 2 and only the 20-bit VPN field above it. */
	assert(opengpu_tlb_flush_vpn(0, &word) == OPENGPU_TLB_FLUSH_OK);
	assert(word == GPU_TLB_FLUSH_VPN);
	assert(opengpu_tlb_flush_vpn(GPU_TLB_FLUSH_VPN_MASK, &word) ==
	       OPENGPU_TLB_FLUSH_OK);
	assert(word == (GPU_TLB_FLUSH_VPN |
			(GPU_TLB_FLUSH_VPN_MASK << GPU_TLB_FLUSH_VPN_SHIFT)));
	assert(opengpu_tlb_flush_vpn(GPU_TLB_FLUSH_VPN_MASK + 1, &word) ==
	       OPENGPU_TLB_FLUSH_E_VPN);

	/* The two scopes occupy disjoint fields and can be combined. */
	assert(GPU_TLB_FLUSH_ASID != GPU_TLB_FLUSH_VPN);
	assert((GPU_TLB_FLUSH_ASID_SHIFT + 9) <= GPU_TLB_FLUSH_VPN_SHIFT);

	puts("TLB-flush encoding tests passed");
	return 0;
}
