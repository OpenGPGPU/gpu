/* SPDX-License-Identifier: MIT */
#ifndef OPENGPU_TLB_FLUSH_H
#define OPENGPU_TLB_FLUSH_H

/*
 * Encoding for the write-1 GPU_REG_UCMD_TLB_FLUSH register.
 *
 * The register carries a one-shot TLB shootdown.  A full flush invalidates
 * every entry of both CU translation caches and the three graphics word
 * clients.  A scoped flush drops only the entries that match an ASID, a
 * virtual page number, or both, so other address spaces stay warm; the
 * graphics clients are ASID-tagged and honour the same scope.
 * A set full-flush bit dominates the scope bits.
 *
 * This header is deliberately free of kernel dependencies so the encoding is
 * exercised by a userspace unit test (tests/opengpu_tlb_flush_test.c),
 * mirroring the resolve, depth and shader validators.
 */

#include "gpu_abi.h"

#ifdef __KERNEL__
#include <linux/types.h>
typedef u32 opengpu_tlb_u32;
#else
#include <stdint.h>
typedef uint32_t opengpu_tlb_u32;
#endif

/* Return values are 0 or a negative errno-compatible code. */
enum opengpu_tlb_flush_status {
	OPENGPU_TLB_FLUSH_OK = 0,
	OPENGPU_TLB_FLUSH_E_ASID = -22,	/* -EINVAL */
	OPENGPU_TLB_FLUSH_E_VPN = -22,	/* -EINVAL */
};

static inline opengpu_tlb_u32 opengpu_tlb_flush_full(void)
{
	return GPU_TLB_FLUSH_FULL;
}

static inline enum opengpu_tlb_flush_status
opengpu_tlb_flush_asid(opengpu_tlb_u32 asid, opengpu_tlb_u32 *word)
{
	if (asid > GPU_TLB_FLUSH_ASID_MASK)
		return OPENGPU_TLB_FLUSH_E_ASID;
	*word = GPU_TLB_FLUSH_ASID | (asid << GPU_TLB_FLUSH_ASID_SHIFT);
	return OPENGPU_TLB_FLUSH_OK;
}

static inline enum opengpu_tlb_flush_status
opengpu_tlb_flush_vpn(opengpu_tlb_u32 vpn, opengpu_tlb_u32 *word)
{
	if (vpn > GPU_TLB_FLUSH_VPN_MASK)
		return OPENGPU_TLB_FLUSH_E_VPN;
	*word = GPU_TLB_FLUSH_VPN | (vpn << GPU_TLB_FLUSH_VPN_SHIFT);
	return OPENGPU_TLB_FLUSH_OK;
}

#endif /* OPENGPU_TLB_FLUSH_H */
