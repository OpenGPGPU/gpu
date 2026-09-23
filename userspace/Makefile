CC ?= cc
CFLAGS ?= -O2 -std=c11 -Wall -Wextra -Werror
DRM_HEADERS ?= /usr/include/libdrm
CPPFLAGS += -I. -I../driver -I$(DRM_HEADERS)

RISCV_GCC ?= riscv64-unknown-elf-gcc
RISCV_OBJCOPY ?= riscv64-unknown-elf-objcopy

all: examples/compute examples/triangle shaders/compute_copy.bin

opengpu.o: opengpu.c opengpu.h ../driver/opengpu_drm.h
	$(CC) $(CPPFLAGS) $(CFLAGS) -c -o $@ $<

examples/%: examples/%.c opengpu.o
	$(CC) $(CPPFLAGS) $(CFLAGS) -o $@ $< opengpu.o

shaders/compute_copy.bin: shaders/compute_copy.S
	$(RISCV_GCC) -march=rv32im_zicsr -mabi=ilp32 -c -o shaders/compute_copy.o $<
	$(RISCV_OBJCOPY) -O binary --only-section=.text shaders/compute_copy.o $@
	rm -f shaders/compute_copy.o

clean:
	rm -f opengpu.o examples/compute examples/triangle shaders/compute_copy.o shaders/compute_copy.bin

.PHONY: all clean
