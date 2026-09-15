// SPDX-License-Identifier: MIT
#include <assert.h>
#include <string.h>

#include "../opengpu_resolve_validator.h"

static struct opengpu_resolve_desc desc(uint64_t src, uint64_t dst,
					uint32_t width, uint32_t height,
					uint32_t src_stride, uint32_t dst_stride,
					uint32_t mode)
{
	struct opengpu_resolve_desc d;

	memset(&d, 0, sizeof(d));
	d.source_offset = src;
	d.destination_offset = dst;
	d.width = width;
	d.height = height;
	d.source_stride = src_stride;
	d.destination_stride = dst_stride;
	d.sample_mode = mode;
	return d;
}

static void test_valid_4x(void)
{
	struct opengpu_resolve_desc d =
		desc(0, 0x10000, 16, 8, 16 * 4 * 4, 16 * 4, OPENGPU_RESOLVE_MODE_4X);
	struct opengpu_resolve_layout layout;

	assert(opengpu_resolve_validate(&d, 0x100000, 0x100000,
					OPENGPU_RESOLVE_MAX_MODE, &layout) ==
	       OPENGPU_RESOLVE_OK);
	assert(layout.samples_per_pixel == 4);
	/* Tight source: 7 * 256 + 256 = 2048 = 0x800. */
	assert(layout.source_bytes == 0x800);
	assert(layout.source_end == 0x800);
	/* Tight destination: 7 * 64 + 64 = 512 = 0x200. */
	assert(layout.destination_bytes == 0x200);
	assert(layout.destination_end == 0x10000 + 0x200);
}

static void test_padded_strides(void)
{
	/* Physical strides larger than the row extent: the extent includes the
	 * padding between rows but not after the last row. */
	struct opengpu_resolve_desc d =
		desc(0x40, 0x8000, 16, 8, 320, 96, OPENGPU_RESOLVE_MODE_4X);
	struct opengpu_resolve_layout layout;

	assert(opengpu_resolve_validate(&d, 0x100000, 0x100000,
					OPENGPU_RESOLVE_MAX_MODE, &layout) ==
	       OPENGPU_RESOLVE_OK);
	assert(layout.source_end == 0x40 + 7 * 320 + 256);
	assert(layout.destination_end == 0x8000 + 7 * 96 + 64);
}

static void test_1x_and_2x(void)
{
	struct opengpu_resolve_desc d;

	d = desc(0, 0x100, 4, 4, 16, 16, OPENGPU_RESOLVE_MODE_1X);
	assert(opengpu_resolve_validate(&d, 0x1000, 0x1000, 1, NULL) ==
	       OPENGPU_RESOLVE_OK);
	d = desc(0, 0x100, 4, 4, 32, 16, OPENGPU_RESOLVE_MODE_2X);
	assert(opengpu_resolve_validate(&d, 0x1000, 0x1000, 1, NULL) ==
	       OPENGPU_RESOLVE_OK);
}

static void test_rejects_bad_mode(void)
{
	struct opengpu_resolve_desc d =
		desc(0, 0x1000, 4, 4, 64, 16, 3);

	assert(opengpu_resolve_validate(&d, 0x10000, 0x10000, 2, NULL) ==
	       OPENGPU_RESOLVE_E_MODE);
	/* Mode 2 is valid in general but not above the advertised maximum. */
	d.sample_mode = OPENGPU_RESOLVE_MODE_4X;
	assert(opengpu_resolve_validate(&d, 0x10000, 0x10000, 1, NULL) ==
	       OPENGPU_RESOLVE_E_MODE);
}

static void test_rejects_bad_extent_and_alignment(void)
{
	struct opengpu_resolve_desc d;

	d = desc(0, 0x1000, 0, 4, 0, 16, OPENGPU_RESOLVE_MODE_1X);
	assert(opengpu_resolve_validate(&d, 0x10000, 0x10000, 2, NULL) ==
	       OPENGPU_RESOLVE_E_EXTENT);
	d = desc(0, 0x1000, 4, 0, 16, 16, OPENGPU_RESOLVE_MODE_1X);
	assert(opengpu_resolve_validate(&d, 0x10000, 0x10000, 2, NULL) ==
	       OPENGPU_RESOLVE_E_EXTENT);

	/* Unaligned offset and unaligned physical stride. */
	d = desc(2, 0x1000, 4, 4, 16, 16, OPENGPU_RESOLVE_MODE_1X);
	assert(opengpu_resolve_validate(&d, 0x10000, 0x10000, 2, NULL) ==
	       OPENGPU_RESOLVE_E_ALIGNMENT);
	d = desc(0, 0x1000, 4, 4, 18, 16, OPENGPU_RESOLVE_MODE_1X);
	assert(opengpu_resolve_validate(&d, 0x10000, 0x10000, 2, NULL) ==
	       OPENGPU_RESOLVE_E_ALIGNMENT);
}

static void test_rejects_short_stride(void)
{
	struct opengpu_resolve_desc d =
		desc(0, 0x1000, 16, 4, 16 * 4 * 4 - 4, 64, OPENGPU_RESOLVE_MODE_4X);

	assert(opengpu_resolve_validate(&d, 0x100000, 0x100000, 2, NULL) ==
	       OPENGPU_RESOLVE_E_STRIDE);
	d = desc(0, 0x1000, 16, 4, 256, 16 * 4 - 4, OPENGPU_RESOLVE_MODE_4X);
	assert(opengpu_resolve_validate(&d, 0x100000, 0x100000, 2, NULL) ==
	       OPENGPU_RESOLVE_E_STRIDE);
}

static void test_rejects_out_of_range(void)
{
	/* Exactly one row too tall for the destination allocation. */
	struct opengpu_resolve_desc d =
		desc(0, 0x100, 4, 4, 16, 16, OPENGPU_RESOLVE_MODE_1X);

	assert(opengpu_resolve_validate(&d, 0x1000, 0x1000, 2, NULL) ==
	       OPENGPU_RESOLVE_OK);
	d.height = 5; /* needs 4 * 16 + 16 = 80 bytes */
	assert(opengpu_resolve_validate(&d, 0x1000, 79, 2, NULL) ==
	       OPENGPU_RESOLVE_E_RANGE);
	/* The source is checked against its own allocation too. */
	d = desc(0x100, 0, 4, 4, 16, 16, OPENGPU_RESOLVE_MODE_1X);
	assert(opengpu_resolve_validate(&d, 0x100 + 63, 0x1000, 2, NULL) ==
	       OPENGPU_RESOLVE_E_RANGE);
}

static void test_rejects_overlap(void)
{
	/* Source [0x1000, 0x1040) and destination [0x1020, 0x1060) overlap. */
	struct opengpu_resolve_desc d =
		desc(0x1000, 0x1020, 4, 4, 16, 16, OPENGPU_RESOLVE_MODE_1X);

	assert(opengpu_resolve_validate(&d, 0x10000, 0x10000, 2, NULL) ==
	       OPENGPU_RESOLVE_E_OVERLAP);

	/* Touching but disjoint ranges are accepted: dst == src + span. */
	d = desc(0x1000, 0x1000 + 64, 4, 4, 16, 16, OPENGPU_RESOLVE_MODE_1X);
	assert(opengpu_resolve_validate(&d, 0x10000, 0x10000, 2, NULL) ==
	       OPENGPU_RESOLVE_OK);
	/* Destination before source is also disjoint. */
	d = desc(0x2000, 0x1000, 4, 4, 16, 16, OPENGPU_RESOLVE_MODE_1X);
	assert(opengpu_resolve_validate(&d, 0x10000, 0x10000, 2, NULL) ==
	       OPENGPU_RESOLVE_OK);
}

static void test_rejects_overflow(void)
{
	struct opengpu_resolve_desc d =
		desc(0xfffffffffffffffcull, 0, 1, 0xffffffffu,
		     0xfffffffcu, 16, OPENGPU_RESOLVE_MODE_1X);

	assert(opengpu_resolve_validate(&d, ~0ull, ~0ull, 2, NULL) ==
	       OPENGPU_RESOLVE_E_OVERFLOW);
}

int main(void)
{
	test_valid_4x();
	test_padded_strides();
	test_1x_and_2x();
	test_rejects_bad_mode();
	test_rejects_bad_extent_and_alignment();
	test_rejects_short_stride();
	test_rejects_out_of_range();
	test_rejects_overlap();
	test_rejects_overflow();
	return 0;
}
