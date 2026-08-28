# Kbuild out-of-tree module for the RISC-V SIMT GPU host driver.
#
# Build against a configured kernel tree, e.g.
#   make -C /tmp/linux-build M=$(pwd) ARCH=arm64 CROSS_COMPILE=aarch64-linux-gnu-
# which produces gpu_drv.ko.

obj-m += gpu_drv.o
gpu_drv-y := opengpu_drv.o opengpu_hw.o opengpu_memory.o opengpu_compute.o

ccflags-y := -I$(src)
