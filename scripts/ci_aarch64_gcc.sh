#!/usr/bin/env bash
# GCC 13 diagnoses the existing signal-handler write() calls in guest examples
# as warn_unused_result. Keep other -Werror checks while building on Ubuntu 24.
exec aarch64-linux-gnu-gcc "$@" -Wno-error=unused-result
