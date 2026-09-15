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
	/* A large row span added to a near-maximum offset overflows u64 even
	 * though the extent and strides are individually valid. */
	struct opengpu_resolve_desc d =
		desc(0xfffffffffffffff0ull, 0, 1, 0xffffu,
		     0xfffffffcu, 16, OPENGPU_RESOLVE_MODE_1X);

	assert(opengpu_resolve_validate(&d, ~0ull, ~0ull, 2, NULL) ==
	       OPENGPU_RESOLVE_E_OVERFLOW);
}

static void test_rejects_large_extent(void)
{
	/* The hardware descriptor holds width/height in 16 bits. */
	struct opengpu_resolve_desc d =
		desc(0, 0x1000, 0x10000, 4, 0x40000, 16, OPENGPU_RESOLVE_MODE_1X);

	assert(opengpu_resolve_validate(&d, ~0ull, ~0ull, 2, NULL) ==
	       OPENGPU_RESOLVE_E_EXTENT);
	d = desc(0, 0x1000, 4, 0x10000, 16, 16, OPENGPU_RESOLVE_MODE_1X);
	assert(opengpu_resolve_validate(&d, ~0ull, ~0ull, 2, NULL) ==
	       OPENGPU_RESOLVE_E_EXTENT);
}

static void test_build_command(void)
{
	struct opengpu_resolve_desc d =
		desc(0x40, 0x100000, 16, 8, 256, 64, OPENGPU_RESOLVE_MODE_4X);
	struct opengpu_resolve_command cmd;

	assert(opengpu_resolve_validate(&d, 0x200000, 0x200000, 2, NULL) ==
	       OPENGPU_RESOLVE_OK);
	assert(opengpu_resolve_build_command(&d, 0x10000000, 0x20000000,
					     &cmd) == OPENGPU_RESOLVE_OK);
	assert(cmd.opcode == OPENGPU_RESOLVE_UCMD_OPCODE);
	assert(cmd.source_address == 0x10000040ull);
	assert(cmd.destination_address == 0x20100000ull);
	assert(cmd.width == 16 && cmd.height == 8);
	assert(cmd.source_stride == 256 && cmd.destination_stride == 64);
	assert(cmd.sample_mode == OPENGPU_RESOLVE_MODE_4X);
}

static void test_build_command_address_limits(void)
{
	struct opengpu_resolve_desc d =
		desc(0x100, 0, 4, 4, 16, 16, OPENGPU_RESOLVE_MODE_1X);
	struct opengpu_resolve_command cmd;

	/* A DMA base whose sum reaches 2^32 is out of the RV32 address space. */
	assert(opengpu_resolve_build_command(&d, 0xffffff00ull, 0, &cmd) ==
	       OPENGPU_RESOLVE_E_RANGE);
	/* An overflowing u64 addition is reported as overflow. */
	d.source_offset = ~0ull - 3;
	assert(opengpu_resolve_build_command(&d, 0x100, 0, &cmd) ==
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
	test_rejects_large_extent();
	test_build_command();
	test_build_command_address_limits();
	return 0;
}
