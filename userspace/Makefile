CC ?= cc
CFLAGS ?= -O2 -std=c11 -Wall -Wextra -Werror
DRM_HEADERS ?= /usr/include/libdrm
CPPFLAGS += -I. -I../driver -I$(DRM_HEADERS)

all: examples/compute examples/triangle

opengpu.o: opengpu.c opengpu.h ../driver/opengpu_drm.h
	$(CC) $(CPPFLAGS) $(CFLAGS) -c -o $@ $<

examples/%: examples/%.c opengpu.o
	$(CC) $(CPPFLAGS) $(CFLAGS) -o $@ $< opengpu.o

clean:
	rm -f opengpu.o examples/compute examples/triangle

.PHONY: all clean
