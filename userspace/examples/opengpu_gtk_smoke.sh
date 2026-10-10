#!/bin/sh
set -eu
export GDK_BACKEND="${GDK_BACKEND:-wayland,x11}"
export LIBGL_DRIVERS_PATH="${LIBGL_DRIVERS_PATH:-/root}"
export LD_LIBRARY_PATH="${LD_LIBRARY_PATH:-/root}"
exec /root/opengpu_gtk_smoke.bin "$@"
