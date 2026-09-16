// SPDX-License-Identifier: MIT
#include <assert.h>
#include <string.h>

#include "../opengpu_depth_validator.h"

static struct opengpu_depth_desc desc(uint64_t offset, uint32_t stride,
				      uint32_t height)
{
	struct opengpu_depth_desc d;

	memset(&d, 0, sizeof(d));
	d.offset = offset;
	d.stride = stride;
	d.height = height;
	return d;
}

static void test_valid_tight(void)
{
	struct opengpu_depth_desc d = desc(0, 16 * 4, 8);
	struct opengpu_depth_layout layout;

	assert(opengpu_depth_validate(&d, 16 * 4 * 8, &layout) ==
	       OPENGPU_DEPTH_OK);
	assert(layout.required == 16 * 4 * 8);
	assert(layout.end == 16 * 4 * 8);
}

static void test_valid_padded_offset_and_slack(void)
{
	struct opengpu_depth_desc d = desc(0x40, 16 * 4, 8);
	struct opengpu_depth_layout layout;

	/* Allocation is larger than the occupied range. */
	assert(opengpu_depth_validate(&d, 0x1000, &layout) ==
	       OPENGPU_DEPTH_OK);
	assert(layout.required == 512);
	assert(layout.end == 0x40 + 512);
}

static void test_optional_layout(void)
{
	struct opengpu_depth_desc d = desc(0x40, 16 * 4, 8);

	/* The driver passes no layout; the rule set must still accept/reject. */
	assert(opengpu_depth_validate(&d, 0x1000, NULL) == OPENGPU_DEPTH_OK);
	assert(opengpu_depth_validate(&d, 0x40 + 512 - 1, NULL) ==
	       OPENGPU_DEPTH_E_RANGE);
}

static void test_rejects_unaligned(void)
{
	struct opengpu_depth_desc d = desc(2, 16 * 4, 8);
	struct opengpu_depth_layout layout;

	assert(opengpu_depth_validate(&d, 0x1000, &layout) ==
	       OPENGPU_DEPTH_E_ALIGNMENT);
	d = desc(0, 16 * 4 + 2, 8);
	assert(opengpu_depth_validate(&d, 0x1000, &layout) ==
	       OPENGPU_DEPTH_E_ALIGNMENT);
}

static void test_rejects_out_of_range(void)
{
	struct opengpu_depth_desc d = desc(0, 16 * 4, 8);
	struct opengpu_depth_layout layout;

	/* One byte short of the required range. */
	assert(opengpu_depth_validate(&d, 16 * 4 * 8 - 1, &layout) ==
	       OPENGPU_DEPTH_E_RANGE);
	/* Offset pushes the end past the allocation. */
	d = desc(0x1000, 16 * 4, 8);
	assert(opengpu_depth_validate(&d, 0x1000 + 512 - 1, &layout) ==
	       OPENGPU_DEPTH_E_RANGE);
}

static void test_rejects_zero_height(void)
{
	struct opengpu_depth_desc d = desc(0, 16 * 4, 0);
	struct opengpu_depth_layout layout;

	assert(opengpu_depth_validate(&d, 0x1000, &layout) ==
	       OPENGPU_DEPTH_E_RANGE);
}

static void test_rejects_overflow(void)
{
	struct opengpu_depth_desc d = desc(~0ull - 3, 16 * 4, 8);
	struct opengpu_depth_layout layout;

	assert(opengpu_depth_validate(&d, ~0ull, &layout) ==
	       OPENGPU_DEPTH_E_OVERFLOW);
}

int main(void)
{
	test_valid_tight();
	test_valid_padded_offset_and_slack();
	test_optional_layout();
	test_rejects_unaligned();
	test_rejects_out_of_range();
	test_rejects_zero_height();
	test_rejects_overflow();
	return 0;
}
