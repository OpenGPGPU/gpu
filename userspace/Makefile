CC ?= cc
CFLAGS ?= -O2 -std=c11 -Wall -Wextra -Werror
DRM_HEADERS ?= /usr/include/libdrm
CPPFLAGS += -I. -I../driver -I$(DRM_HEADERS)

RISCV_GCC ?= riscv64-unknown-elf-gcc
RISCV_OBJCOPY ?= riscv64-unknown-elf-objcopy

EXAMPLES := examples/compute examples/triangle examples/fragment_tint \
	examples/pipe_clear_draw

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

examples/pipe_clear_draw: examples/pipe_clear_draw.c pipe_opengpu.o opengpu.o
	$(CC) $(CPPFLAGS) $(CFLAGS) -o $@ $< pipe_opengpu.o opengpu.o

shaders/round_modes.bin: shaders/round_modes.S
	$(RISCV_GCC) -march=rv32imv_zicsr -mabi=ilp32 -c -o shaders/round_modes.o $<
	$(RISCV_OBJCOPY) -O binary --only-section=.text shaders/round_modes.o $@
	rm -f shaders/round_modes.o

clean:
	rm -f opengpu.o pipe_opengpu.o $(EXAMPLES) \
		shaders/round_modes.o shaders/round_modes.bin

.PHONY: all clean
