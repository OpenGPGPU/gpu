/* SPDX-License-Identifier: MIT */
#ifndef OPENGPU_DEPTH_VALIDATOR_H
#define OPENGPU_DEPTH_VALIDATOR_H

/*
 * Range validation for a user-supplied persistent depth attachment.
 *
 * Render submissions may carry their own depth/stencil plane instead of the
 * driver's private cleared plane.  The attachment is a GEM-relative byte range
 * and the depth render target occupies exactly `stride * height` bytes at
 * `offset`.  Rows share the colour render target's physical stride (the MSAA
 * layout interleaves samples within a row), so only the range and alignment
 * rules live here; the stride-versus-width and sample-mode admission checks
 * stay with the render submission path.
 *
 * This header is deliberately free of kernel dependencies so the same rules
 * are exercised by a userspace unit test
 * (tests/opengpu_depth_validator_test.c), mirroring the resolve and shader
 * validators.
 */

#ifdef __KERNEL__
#include <linux/types.h>
typedef u32 opengpu_depth_u32;
typedef u64 opengpu_depth_u64;
#else
#include <stdint.h>
typedef uint32_t opengpu_depth_u32;
typedef uint64_t opengpu_depth_u64;
#endif

/* One 32-bit D24S8 depth/stencil word per pixel sample. */
#define OPENGPU_DEPTH_BYTES_PER_PIXEL 4u
/* The RV32 hardware addresses words, so both offsets and strides are 4-byte
 * aligned (matching the colour attachment's stride requirement). */
#define OPENGPU_DEPTH_ALIGNMENT 4u

/* Return values are 0 or a negative errno-compatible code. */
enum opengpu_depth_status {
	OPENGPU_DEPTH_OK = 0,
	OPENGPU_DEPTH_E_ALIGNMENT = -22,	/* -EINVAL */
	OPENGPU_DEPTH_E_RANGE = -34,		/* -ERANGE */
	OPENGPU_DEPTH_E_OVERFLOW = -75,		/* -EOVERFLOW */
};

struct opengpu_depth_desc {
	opengpu_depth_u64 offset;	/* GEM-relative byte offset */
	opengpu_depth_u32 stride;	/* physical row stride, bytes */
	opengpu_depth_u32 height;	/* render target height, rows */
};

struct opengpu_depth_layout {
	opengpu_depth_u64 required;	/* stride * height */
	opengpu_depth_u64 end;		/* offset + required, exclusive */
};

static inline enum opengpu_depth_status
opengpu_depth_validate(const struct opengpu_depth_desc *desc,
		       opengpu_depth_u64 attachment_bytes,
		       struct opengpu_depth_layout *layout)
{
	opengpu_depth_u64 required;
	opengpu_depth_u64 end;

	if (!desc || !desc->height)
		return OPENGPU_DEPTH_E_RANGE;
	if ((desc->offset % OPENGPU_DEPTH_ALIGNMENT) ||
	    (desc->stride % OPENGPU_DEPTH_ALIGNMENT))
		return OPENGPU_DEPTH_E_ALIGNMENT;

	/* stride * height, both operands are u32 so the product fits in u64. */
	required = (opengpu_depth_u64)desc->stride * desc->height;
	/* offset + required must not wrap. */
	if (desc->offset > ~(opengpu_depth_u64)0 - required)
		return OPENGPU_DEPTH_E_OVERFLOW;
	end = desc->offset + required;
	if (end > attachment_bytes)
		return OPENGPU_DEPTH_E_RANGE;

	if (layout) {
		layout->required = required;
		layout->end = end;
	}
	return OPENGPU_DEPTH_OK;
}

#endif /* OPENGPU_DEPTH_VALIDATOR_H */
