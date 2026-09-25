CC ?= cc
CFLAGS ?= -O2 -std=c11 -Wall -Wextra -Werror
DRM_HEADERS ?= /usr/include/libdrm
CPPFLAGS += -I. -I../driver -I$(DRM_HEADERS)

RISCV_GCC ?= riscv64-unknown-elf-gcc
RISCV_OBJCOPY ?= riscv64-unknown-elf-objcopy

EXAMPLES := examples/compute examples/triangle examples/fragment_tint \
	examples/fp_unary \
	examples/triangle_present \
	examples/pipe_clear_draw examples/pipe_compute examples/pipe_blit \
	examples/pipe_resolve examples/pipe_texture_draw examples/pipe_vertex_draw \
	examples/pipe_strided_blit examples/pipe_depth_pass examples/pipe_msaa_draw \
	examples/pipe_present

all: $(EXAMPLES) shaders/round_modes.bin

opengpu.o: opengpu.c opengpu.h ../driver/opengpu_drm.h
	$(CC) $(CPPFLAGS) $(CFLAGS) -c -o $@ $<

pipe_opengpu.o: pipe_opengpu.c pipe_opengpu.h opengpu.h ../driver/opengpu_drm.h
	$(CC) $(CPPFLAGS) $(CFLAGS) -c -o $@ $<

examples/compute: examples/compute.c opengpu.o
	$(CC) $(CPPFLAGS) $(CFLAGS) -o $@ $< opengpu.o

examples/triangle: examples/triangle.c opengpu.o
	$(CC) $(CPPFLAGS) $(CFLAGS) -o $@ $< opengpu.o

examples/fragment_tint: examples/fragment_tint.c opengpu.o
	$(CC) $(CPPFLAGS) $(CFLAGS) -o $@ $< opengpu.o

examples/fp_unary: examples/fp_unary.c opengpu.o
	$(CC) $(CPPFLAGS) $(CFLAGS) -o $@ $< opengpu.o

examples/triangle_present: examples/triangle_present.c opengpu.o
	$(CC) $(CPPFLAGS) $(CFLAGS) -o $@ $< opengpu.o

examples/pipe_clear_draw: examples/pipe_clear_draw.c pipe_opengpu.o opengpu.o
	$(CC) $(CPPFLAGS) $(CFLAGS) -o $@ $< pipe_opengpu.o opengpu.o

examples/pipe_compute: examples/pipe_compute.c pipe_opengpu.o opengpu.o
	$(CC) $(CPPFLAGS) $(CFLAGS) -o $@ $< pipe_opengpu.o opengpu.o

examples/pipe_blit: examples/pipe_blit.c pipe_opengpu.o opengpu.o
	$(CC) $(CPPFLAGS) $(CFLAGS) -o $@ $< pipe_opengpu.o opengpu.o

examples/pipe_resolve: examples/pipe_resolve.c pipe_opengpu.o opengpu.o
	$(CC) $(CPPFLAGS) $(CFLAGS) -o $@ $< pipe_opengpu.o opengpu.o

examples/pipe_texture_draw: examples/pipe_texture_draw.c pipe_opengpu.o opengpu.o
	$(CC) $(CPPFLAGS) $(CFLAGS) -o $@ $< pipe_opengpu.o opengpu.o

examples/pipe_vertex_draw: examples/pipe_vertex_draw.c pipe_opengpu.o opengpu.o
	$(CC) $(CPPFLAGS) $(CFLAGS) -o $@ $< pipe_opengpu.o opengpu.o

examples/pipe_strided_blit: examples/pipe_strided_blit.c pipe_opengpu.o opengpu.o
	$(CC) $(CPPFLAGS) $(CFLAGS) -o $@ $< pipe_opengpu.o opengpu.o

examples/pipe_depth_pass: examples/pipe_depth_pass.c pipe_opengpu.o opengpu.o
	$(CC) $(CPPFLAGS) $(CFLAGS) -o $@ $< pipe_opengpu.o opengpu.o

examples/pipe_msaa_draw: examples/pipe_msaa_draw.c pipe_opengpu.o opengpu.o
	$(CC) $(CPPFLAGS) $(CFLAGS) -o $@ $< pipe_opengpu.o opengpu.o

examples/pipe_present: examples/pipe_present.c pipe_opengpu.o opengpu.o
	$(CC) $(CPPFLAGS) $(CFLAGS) -o $@ $< pipe_opengpu.o opengpu.o

shaders/round_modes.bin: shaders/round_modes.S
	$(RISCV_GCC) -march=rv32imv_zicsr -mabi=ilp32 -c -o shaders/round_modes.o $<
	$(RISCV_OBJCOPY) -O binary --only-section=.text shaders/round_modes.o $@
	rm -f shaders/round_modes.o

clean:
	rm -f opengpu.o pipe_opengpu.o $(EXAMPLES) \
		shaders/round_modes.o shaders/round_modes.bin

.PHONY: all clean
