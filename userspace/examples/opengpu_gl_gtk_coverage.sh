#!/bin/sh
# The boot desktop holds DRM master, and a second client cannot set the CRTC.
if command -v systemctl >/dev/null 2>&1 &&
   systemctl is-active --quiet opengpu-boot-display.service; then
    echo "opengpu_gl_gtk_coverage: stopping opengpu-boot-display.service for KMS"
    systemctl stop opengpu-boot-display.service
fi
export LD_LIBRARY_PATH="/root${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
export LIBGL_DRIVERS_PATH="/root"
exec /root/opengpu_gl_gtk_coverage.bin "$@"
