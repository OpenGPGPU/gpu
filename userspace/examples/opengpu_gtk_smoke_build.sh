#!/bin/sh
set -eu
command -v pkg-config >/dev/null
pkg-config --exists gtk+-3.0 epoxy || {
    echo 'GTK3/Epoxy development packages are required' >&2
    exit 2
}
gcc -O2 -Wall -Wextra -Werror -o /root/opengpu_gtk_smoke.bin \
    /root/gtk_opengpu_smoke.c $(pkg-config --cflags --libs gtk+-3.0 epoxy)
echo 'Built /root/opengpu_gtk_smoke.bin'
