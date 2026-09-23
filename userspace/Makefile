CC ?= cc
CFLAGS ?= -O2 -std=c11 -Wall -Wextra -Werror
DRM_HEADERS ?= /usr/include/libdrm
CPPFLAGS += -I. -I../driver -I$(DRM_HEADERS)

RISCV_GCC ?= riscv64-unknown-elf-gcc
RISCV_OBJCOPY ?= riscv64-unknown-elf-objcopy

all: examples/compute examples/triangle shaders/round_modes.bin

opengpu.o: opengpu.c opengpu.h ../driver/opengpu_drm.h
	$(CC) $(CPPFLAGS) $(CFLAGS) -c -o $@ $<

examples/%: examples/%.c opengpu.o
	$(CC) $(CPPFLAGS) $(CFLAGS) -o $@ $< opengpu.o

shaders/round_modes.bin: shaders/round_modes.S
	$(RISCV_GCC) -march=rv32imv_zicsr -mabi=ilp32 -c -o shaders/round_modes.o $<
	$(RISCV_OBJCOPY) -O binary --only-section=.text shaders/round_modes.o $@
	rm -f shaders/round_modes.o

clean:
	rm -f opengpu.o examples/compute examples/triangle shaders/round_modes.o shaders/round_modes.bin

.PHONY: all clean
