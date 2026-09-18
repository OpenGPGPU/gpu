// SPDX-License-Identifier: GPL-2.0
/* Exercise the compute-kernarg VA planner.
 * cc -std=c11 -Wall -Wextra -Werror -I. tests/opengpu_kernarg_va_test.c \
 *    -o /tmp/opengpu_kernarg_va_test
 */
#include <assert.h>
#include <stdio.h>

#include "opengpu_kernarg_va.h"

#define PAGE 4096u

int main(void)
{
	struct opengpu_kernarg_va_plan plan, other;

	/* A page-aligned binding maps exactly its page at the slot window. */
	assert(opengpu_kernarg_va_plan(0x12345000, 64, 1, PAGE, &plan) ==
	       OPENGPU_KERNARG_VA_OK);
	assert(plan.va_page == OPENGPU_KERNARG_VA_BASE + OPENGPU_KERNARG_VA_STRIDE);
	assert(plan.va == plan.va_page);
	assert(plan.pa_page == 0x12345000);
	assert(plan.span == PAGE);

	/* An unaligned binding keeps its in-page offset on the VA side and maps
	 * the enclosing page. */
	assert(opengpu_kernarg_va_plan(0x22222040, 128, 1, PAGE, &plan) ==
	       OPENGPU_KERNARG_VA_OK);
	assert(plan.pa_page == 0x22222000);
	assert(plan.va_page == OPENGPU_KERNARG_VA_BASE + OPENGPU_KERNARG_VA_STRIDE);
	assert(plan.va == plan.va_page + 0x40);
	assert(plan.span == PAGE);

	/* A binding that crosses a page boundary maps both pages. */
	assert(opengpu_kernarg_va_plan(0x33333f00, 0x200, 2, PAGE, &plan) ==
	       OPENGPU_KERNARG_VA_OK);
	assert(plan.pa_page == 0x33333000);
	assert(plan.span == 2 * PAGE);

	/* Different slots get disjoint windows, so two contexts can share a VA. */
	assert(opengpu_kernarg_va_plan(0x1000, 64, 3, PAGE, &plan) ==
	       OPENGPU_KERNARG_VA_OK);
	assert(opengpu_kernarg_va_plan(0x9000, 64, 4, PAGE, &other) ==
	       OPENGPU_KERNARG_VA_OK);
	assert(other.va_page == plan.va_page + OPENGPU_KERNARG_VA_STRIDE);

	/* A range larger than the window is rejected (the caller falls back). */
	assert(opengpu_kernarg_va_plan(0, OPENGPU_KERNARG_VA_STRIDE + 1, 1, PAGE,
				       &plan) == OPENGPU_KERNARG_VA_E_RANGE);
	assert(opengpu_kernarg_va_plan(0xf100, OPENGPU_KERNARG_VA_STRIDE, 1, PAGE,
				       &plan) == OPENGPU_KERNARG_VA_E_RANGE);
	/* A full window with no in-page offset is the largest accepted range. */
	assert(opengpu_kernarg_va_plan(0, OPENGPU_KERNARG_VA_STRIDE, 1, PAGE,
				       &plan) == OPENGPU_KERNARG_VA_OK);
	assert(plan.span == OPENGPU_KERNARG_VA_STRIDE);

	/* Degenerate inputs are rejected. */
	assert(opengpu_kernarg_va_plan(0x1000, 0, 1, PAGE, &plan) ==
	       OPENGPU_KERNARG_VA_E_RANGE);
	assert(opengpu_kernarg_va_plan(0x1000, 64, 1, 0, &plan) ==
	       OPENGPU_KERNARG_VA_E_RANGE);
	assert(opengpu_kernarg_va_plan(0x1000, 64, 1, 1000, &plan) ==
	       OPENGPU_KERNARG_VA_E_RANGE);
	assert(opengpu_kernarg_va_plan(0x1000, 64, 1, PAGE, NULL) ==
	       OPENGPU_KERNARG_VA_E_RANGE);

	/* The texture planner uses its own window, disjoint from the kernarg
	 * window, and shares the same arithmetic. */
	assert(opengpu_texture_va_plan(0x55555000, 0x3000, 1, PAGE, &plan) ==
	       OPENGPU_KERNARG_VA_OK);
	assert(plan.va_page == OPENGPU_TEXTURE_VA_BASE + OPENGPU_TEXTURE_VA_STRIDE);
	assert(plan.pa_page == 0x55555000);
	assert(plan.va == plan.va_page);
	assert(plan.span == 0x3000);
	assert(plan.va_page >= OPENGPU_KERNARG_VA_BASE +
	       OPENGPU_KERNARG_VA_STRIDE);
	assert(opengpu_texture_va_plan(0, OPENGPU_TEXTURE_VA_STRIDE + 1, 1, PAGE,
				      &plan) == OPENGPU_KERNARG_VA_E_RANGE);
	assert(opengpu_texture_va_plan(0, OPENGPU_TEXTURE_VA_STRIDE, 1, PAGE,
				      &plan) == OPENGPU_KERNARG_VA_OK);

	/* The command planner is its own window too, so a per-job command
	 * snapshot never aliases a texture or kernarg window. */
	assert(opengpu_command_va_plan(0x66666000, 0x4000, 1, PAGE, &plan) ==
	       OPENGPU_KERNARG_VA_OK);
	assert(plan.va_page == OPENGPU_COMMAND_VA_BASE + OPENGPU_COMMAND_VA_STRIDE);
	assert(plan.pa_page == 0x66666000);
	assert(plan.va == plan.va_page);
	assert(plan.va_page >= OPENGPU_TEXTURE_VA_BASE +
	       OPENGPU_TEXTURE_VA_STRIDE);
	assert(opengpu_command_va_plan(0, OPENGPU_COMMAND_VA_STRIDE + 1, 1, PAGE,
				       &plan) == OPENGPU_KERNARG_VA_E_RANGE);

	/* The render-descriptor window is disjoint from the command window. */
	assert(opengpu_render_va_plan(0x77777000, 64, 1, PAGE, &plan) ==
	       OPENGPU_KERNARG_VA_OK);
	assert(plan.va_page == OPENGPU_RENDER_VA_BASE + OPENGPU_RENDER_VA_STRIDE);
	assert(plan.pa_page == 0x77777000);
	assert(plan.span == PAGE);
	assert(plan.va_page >= OPENGPU_COMMAND_VA_BASE +
	       OPENGPU_COMMAND_VA_STRIDE);
	assert(opengpu_render_va_plan(0, OPENGPU_RENDER_VA_STRIDE + 1, 1, PAGE,
				      &plan) == OPENGPU_KERNARG_VA_E_RANGE);

	/* The vertex-buffer window is disjoint from the render window. */
	assert(opengpu_vertex_va_plan(0x88888000, 0x600, 1, PAGE, &plan) ==
	       OPENGPU_KERNARG_VA_OK);
	assert(plan.va_page == OPENGPU_VERTEX_VA_BASE + OPENGPU_VERTEX_VA_STRIDE);
	assert(plan.pa_page == 0x88888000);
	assert(plan.span == PAGE);
	assert(plan.va_page >= OPENGPU_RENDER_VA_BASE +
	       OPENGPU_RENDER_VA_STRIDE);

	/* The framebuffer window (colour slot then depth slot) is its own. */
	assert(opengpu_framebuffer_va_plan(0x99999000, 0x4000, 1, PAGE, &plan) ==
	       OPENGPU_KERNARG_VA_OK);
	assert(plan.va_page ==
	       OPENGPU_FRAMEBUFFER_VA_BASE + OPENGPU_FRAMEBUFFER_VA_STRIDE);
	assert(plan.pa_page == 0x99999000);
	assert(plan.va_page >= OPENGPU_VERTEX_VA_BASE +
	       OPENGPU_VERTEX_VA_STRIDE);
	assert(opengpu_framebuffer_va_plan(0, OPENGPU_FRAMEBUFFER_VA_STRIDE + 1,
					  1, PAGE, &plan) ==
	       OPENGPU_KERNARG_VA_E_RANGE);

	puts("kernarg VA planner tests passed");
	return 0;
}
