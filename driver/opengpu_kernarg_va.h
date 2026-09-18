/* SPDX-License-Identifier: MIT */
#ifndef OPENGPU_KERNARG_VA_H
#define OPENGPU_KERNARG_VA_H

/*
 * Plan the VM virtual address for a resource binding.
 *
 * A binding is mapped into its context's VM at a fixed virtual window keyed by
 * resource slot, so two contexts can map different physical buffers at the
 * same VA and stay isolated by their ASID.  The binding's physical base may be
 * unaligned within a page; the plan covers the enclosing pages and records the
 * VA that corresponds to the binding base, so the launch/submission descriptor
 * can add its own offset.
 *
 * Compute kernargs and fixed-function textures use distinct windows so their
 * per-slot mappings cannot collide.
 *
 * Kernel-free so the same arithmetic is exercised by
 * tests/opengpu_kernarg_va_test.c.
 */

#ifdef __KERNEL__
#include <linux/types.h>
typedef u64 opengpu_kernarg_u64;
typedef u32 opengpu_kernarg_u32;
#else
#include <stdint.h>
typedef uint64_t opengpu_kernarg_u64;
typedef uint32_t opengpu_kernarg_u32;
#endif

#define OPENGPU_KERNARG_VA_BASE   0x80000000ull
#define OPENGPU_KERNARG_VA_STRIDE (4ull * 1024ull * 1024ull)
#define OPENGPU_TEXTURE_VA_BASE   0xa0000000ull
#define OPENGPU_TEXTURE_VA_STRIDE (4ull * 1024ull * 1024ull)
#define OPENGPU_COMMAND_VA_BASE   0xb0000000ull
#define OPENGPU_COMMAND_VA_STRIDE (4ull * 1024ull * 1024ull)
#define OPENGPU_RENDER_VA_BASE    0xb4000000ull
#define OPENGPU_RENDER_VA_STRIDE  (4ull * 1024ull * 1024ull)
#define OPENGPU_VERTEX_VA_BASE    0xb8000000ull
#define OPENGPU_VERTEX_VA_STRIDE  (4ull * 1024ull * 1024ull)
#define OPENGPU_FRAMEBUFFER_VA_BASE 0xbc000000ull
#define OPENGPU_FRAMEBUFFER_VA_STRIDE (4ull * 1024ull * 1024ull)

enum opengpu_kernarg_va_status {
	OPENGPU_KERNARG_VA_OK = 0,
	OPENGPU_KERNARG_VA_E_RANGE = -34,	/* -ERANGE */
};

struct opengpu_kernarg_va_plan {
	opengpu_kernarg_u64 va_page;	/* page-aligned VA to map */
	opengpu_kernarg_u64 pa_page;	/* page-aligned PA to map */
	opengpu_kernarg_u64 span;	/* bytes to map, a page multiple */
	opengpu_kernarg_u64 va;		/* VA of the binding base (dma) */
};

static inline int opengpu_resource_va_plan(opengpu_kernarg_u64 dma,
					   opengpu_kernarg_u64 size,
					   opengpu_kernarg_u32 slot,
					   opengpu_kernarg_u32 page_size,
					   opengpu_kernarg_u64 base,
					   opengpu_kernarg_u64 stride,
					   struct opengpu_kernarg_va_plan *out)
{
	opengpu_kernarg_u64 in_page, span, va_page;

	if (!out || !size || !page_size || (page_size & (page_size - 1)))
		return OPENGPU_KERNARG_VA_E_RANGE;
	in_page = dma & (page_size - 1);
	span = (in_page + size + page_size - 1) &
		~((opengpu_kernarg_u64)page_size - 1);
	if (span > stride)
		return OPENGPU_KERNARG_VA_E_RANGE;
	va_page = base + (opengpu_kernarg_u64)slot * stride;
	out->va_page = va_page;
	out->pa_page = dma - in_page;
	out->span = span;
	out->va = va_page + in_page;
	return OPENGPU_KERNARG_VA_OK;
}

static inline int opengpu_kernarg_va_plan(opengpu_kernarg_u64 dma,
					  opengpu_kernarg_u64 size,
					  opengpu_kernarg_u32 slot,
					  opengpu_kernarg_u32 page_size,
					  struct opengpu_kernarg_va_plan *out)
{
	return opengpu_resource_va_plan(dma, size, slot, page_size,
					OPENGPU_KERNARG_VA_BASE,
					OPENGPU_KERNARG_VA_STRIDE, out);
}

static inline int opengpu_texture_va_plan(opengpu_kernarg_u64 dma,
					  opengpu_kernarg_u64 size,
					  opengpu_kernarg_u32 slot,
					  opengpu_kernarg_u32 page_size,
					  struct opengpu_kernarg_va_plan *out)
{
	return opengpu_resource_va_plan(dma, size, slot, page_size,
					OPENGPU_TEXTURE_VA_BASE,
					OPENGPU_TEXTURE_VA_STRIDE, out);
}

static inline int opengpu_command_va_plan(opengpu_kernarg_u64 dma,
					  opengpu_kernarg_u64 size,
					  opengpu_kernarg_u32 slot,
					  opengpu_kernarg_u32 page_size,
					  struct opengpu_kernarg_va_plan *out)
{
	return opengpu_resource_va_plan(dma, size, slot, page_size,
					OPENGPU_COMMAND_VA_BASE,
					OPENGPU_COMMAND_VA_STRIDE, out);
}

static inline int opengpu_render_va_plan(opengpu_kernarg_u64 dma,
					 opengpu_kernarg_u64 size,
					 opengpu_kernarg_u32 slot,
					 opengpu_kernarg_u32 page_size,
					 struct opengpu_kernarg_va_plan *out)
{
	return opengpu_resource_va_plan(dma, size, slot, page_size,
					OPENGPU_RENDER_VA_BASE,
					OPENGPU_RENDER_VA_STRIDE, out);
}

static inline int opengpu_vertex_va_plan(opengpu_kernarg_u64 dma,
					 opengpu_kernarg_u64 size,
					 opengpu_kernarg_u32 slot,
					 opengpu_kernarg_u32 page_size,
					 struct opengpu_kernarg_va_plan *out)
{
	return opengpu_resource_va_plan(dma, size, slot, page_size,
					OPENGPU_VERTEX_VA_BASE,
					OPENGPU_VERTEX_VA_STRIDE, out);
}

static inline int opengpu_framebuffer_va_plan(opengpu_kernarg_u64 dma,
					      opengpu_kernarg_u64 size,
					      opengpu_kernarg_u32 slot,
					      opengpu_kernarg_u32 page_size,
					      struct opengpu_kernarg_va_plan *out)
{
	return opengpu_resource_va_plan(dma, size, slot, page_size,
					OPENGPU_FRAMEBUFFER_VA_BASE,
					OPENGPU_FRAMEBUFFER_VA_STRIDE, out);
}

#endif /* OPENGPU_KERNARG_VA_H */
