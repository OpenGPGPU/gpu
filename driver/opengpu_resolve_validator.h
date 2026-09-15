/* SPDX-License-Identifier: MIT */
#ifndef OPENGPU_RESOLVE_VALIDATOR_H
#define OPENGPU_RESOLVE_VALIDATOR_H

/*
 * Range validation for the typed MSAA resolve operation (GPU_UCMD_OP_RESOLVE).
 *
 * The authority for the operation is the userspace submission: the driver owns
 * the validation.  This header is deliberately free of kernel dependencies so
 * the same rules are exercised by a userspace unit test
 * (tests/opengpu_resolve_validator_test.c), mirroring the shader validator.
 *
 * A resolve averages the interleaved colour samples of a validated source
 * region into a single-sample destination region.  Both buffers are
 * GEM-relative byte ranges; both strides are physical row strides in bytes and
 * may carry row padding.  The source and destination must be non-overlapping
 * and fit inside their backing allocations.
 */

#ifdef __KERNEL__
#include <linux/types.h>
typedef u32 opengpu_resolve_u32;
typedef u64 opengpu_resolve_u64;
#else
#include <stdint.h>
typedef uint32_t opengpu_resolve_u32;
typedef uint64_t opengpu_resolve_u64;
#endif

/* Sample-mode encoding, matching GPU_REG_MSAA_CONFIG and job word 9. */
#define OPENGPU_RESOLVE_MODE_1X 0u
#define OPENGPU_RESOLVE_MODE_2X 1u
#define OPENGPU_RESOLVE_MODE_4X 2u
#define OPENGPU_RESOLVE_MAX_MODE 2u
#define OPENGPU_RESOLVE_BYTES_PER_SAMPLE 4u
#define OPENGPU_RESOLVE_ALIGNMENT 4u

/* Return values are 0 or a negative errno-compatible code. */
enum opengpu_resolve_status {
	OPENGPU_RESOLVE_OK = 0,
	OPENGPU_RESOLVE_E_MODE = -22,		/* -EINVAL */
	OPENGPU_RESOLVE_E_EXTENT = -22,		/* -EINVAL */
	OPENGPU_RESOLVE_E_ALIGNMENT = -22,	/* -EINVAL */
	OPENGPU_RESOLVE_E_STRIDE = -22,		/* -EINVAL */
	OPENGPU_RESOLVE_E_OVERLAP = -22,	/* -EINVAL */
	OPENGPU_RESOLVE_E_RANGE = -34,		/* -ERANGE */
	OPENGPU_RESOLVE_E_OVERFLOW = -75,	/* -EOVERFLOW */
};

struct opengpu_resolve_desc {
	opengpu_resolve_u64 source_offset;
	opengpu_resolve_u64 destination_offset;
	opengpu_resolve_u32 width;		/* logical pixels */
	opengpu_resolve_u32 height;
	opengpu_resolve_u32 source_stride;	/* physical row bytes */
	opengpu_resolve_u32 destination_stride;	/* physical row bytes */
	opengpu_resolve_u32 sample_mode;
};

struct opengpu_resolve_layout {
	opengpu_resolve_u32 samples_per_pixel;
	opengpu_resolve_u64 source_end;		/* exclusive */
	opengpu_resolve_u64 destination_end;	/* exclusive */
	opengpu_resolve_u64 source_bytes;	/* occupied bytes */
	opengpu_resolve_u64 destination_bytes;
};

/* Validate one resolve against its backing allocation sizes and the maximum
 * sample mode advertised by the device.  On success, and when `out` is
 * non-NULL, the computed layout is written for the descriptor builder. */
static inline enum opengpu_resolve_status
opengpu_resolve_validate(const struct opengpu_resolve_desc *desc,
			 opengpu_resolve_u64 source_size,
			 opengpu_resolve_u64 destination_size,
			 opengpu_resolve_u32 max_sample_mode,
			 struct opengpu_resolve_layout *out)
{
	opengpu_resolve_u64 samples, source_row, destination_row;
	opengpu_resolve_u64 source_span, destination_span;
	opengpu_resolve_u64 source_end, destination_end;

	if (desc->sample_mode > OPENGPU_RESOLVE_MAX_MODE ||
	    desc->sample_mode > max_sample_mode)
		return OPENGPU_RESOLVE_E_MODE;

	if (desc->width == 0 || desc->height == 0)
		return OPENGPU_RESOLVE_E_EXTENT;

	if ((desc->source_offset & (OPENGPU_RESOLVE_ALIGNMENT - 1)) != 0 ||
	    (desc->destination_offset & (OPENGPU_RESOLVE_ALIGNMENT - 1)) != 0 ||
	    (desc->source_stride & (OPENGPU_RESOLVE_ALIGNMENT - 1)) != 0 ||
	    (desc->destination_stride & (OPENGPU_RESOLVE_ALIGNMENT - 1)) != 0)
		return OPENGPU_RESOLVE_E_ALIGNMENT;

	samples = 1ull << desc->sample_mode;
	if (__builtin_mul_overflow((opengpu_resolve_u64)desc->width *
				   (opengpu_resolve_u64)OPENGPU_RESOLVE_BYTES_PER_SAMPLE,
				   samples, &source_row) ||
	    __builtin_mul_overflow((opengpu_resolve_u64)desc->width,
				   (opengpu_resolve_u64)OPENGPU_RESOLVE_BYTES_PER_SAMPLE,
				   &destination_row))
		return OPENGPU_RESOLVE_E_OVERFLOW;

	if ((opengpu_resolve_u64)desc->source_stride < source_row ||
	    (opengpu_resolve_u64)desc->destination_stride < destination_row)
		return OPENGPU_RESOLVE_E_STRIDE;

	if (__builtin_mul_overflow((opengpu_resolve_u64)desc->height - 1,
				   (opengpu_resolve_u64)desc->source_stride,
				   &source_span) ||
	    __builtin_add_overflow(source_span, source_row, &source_span) ||
	    __builtin_add_overflow(desc->source_offset, source_span, &source_end) ||
	    __builtin_mul_overflow((opengpu_resolve_u64)desc->height - 1,
				   (opengpu_resolve_u64)desc->destination_stride,
				   &destination_span) ||
	    __builtin_add_overflow(destination_span, destination_row,
				   &destination_span) ||
	    __builtin_add_overflow(desc->destination_offset, destination_span,
				   &destination_end))
		return OPENGPU_RESOLVE_E_OVERFLOW;

	if (source_end > source_size || destination_end > destination_size)
		return OPENGPU_RESOLVE_E_RANGE;

	if (desc->source_offset < destination_end &&
	    desc->destination_offset < source_end)
		return OPENGPU_RESOLVE_E_OVERLAP;

	if (out) {
		out->samples_per_pixel = (opengpu_resolve_u32)samples;
		out->source_end = source_end;
		out->destination_end = destination_end;
		out->source_bytes = source_span;
		out->destination_bytes = destination_span;
	}

	return OPENGPU_RESOLVE_OK;
}

#endif /* OPENGPU_RESOLVE_VALIDATOR_H */
