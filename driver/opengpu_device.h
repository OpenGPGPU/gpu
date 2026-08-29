/* SPDX-License-Identifier: GPL-2.0 */
#ifndef OPENGPU_DEVICE_H
#define OPENGPU_DEVICE_H

#include <linux/atomic.h>
#include <linux/device.h>
#include <linux/dma-mapping.h>
#include <linux/ioport.h>
#include <linux/ioctl.h>
#include <linux/miscdevice.h>
#include <linux/mutex.h>
#include <linux/types.h>
#include <linux/wait.h>

#include "gpu_abi.h"

struct platform_device;
struct opengpu_drm;

#define OPENGPU_NAME            "riscv-simt-opengpu"
#define OPENGPU_COMPUTE_NAME    "opengpu0"
#define OPENGPU_DRAW_WAIT_MS    200
#define OPENGPU_IOCTL_SUBMIT    _IO('G', 0x01)

struct opengpu_buffer {
    void *cpu;
    dma_addr_t dma;
    size_t size;
};

/* Hardware-owned state. Business layers must use opengpu_hw_* APIs instead
 * of accessing the register aperture directly. */
struct opengpu_hw {
    void __iomem *regs;
    resource_size_t regs_phys;
    resource_size_t regs_size;
    int irq;
    wait_queue_head_t irq_wait;
    atomic_t irq_count;
};

/* Bring-up execution client. This becomes the render/compute client as queue
 * and GEM support land; display state deliberately does not live here. */
struct opengpu_compute {
    struct miscdevice misc;
    struct mutex lock;
    struct opengpu_buffer cmd;
    struct opengpu_buffer color;
    struct opengpu_buffer depth;
};

struct opengpu_display {
    struct opengpu_drm *kms;
    dma_addr_t scanout;
    u32 stride;
    u32 width;
    u32 height;
    u32 format;
    bool enabled;
};

struct opengpu_device {
    struct device *dev;
    struct opengpu_hw hw;
    struct opengpu_compute compute;
    struct opengpu_display display;
    u32 width;
    u32 height;
    u32 stride;
};

/* Typed execution descriptor passed across the business/hardware boundary. */
struct opengpu_job {
    dma_addr_t cmd;
    u32 cmd_count;
    dma_addr_t color;
    dma_addr_t depth;
    u32 stride;
    bool depth_test;
    u32 depth_func;
    bool depth_write;
    u32 cull_mode;
};

struct opengpu_scanout {
    dma_addr_t base;
    u32 stride;
    u32 width;
    u32 height;
    u32 format;
    bool enable;
};

int opengpu_hw_init(struct opengpu_device *gpu,
                    struct platform_device *pdev);
int opengpu_hw_submit(struct opengpu_device *gpu,
                      const struct opengpu_job *job);
int opengpu_hw_display_commit(struct opengpu_device *gpu,
                              const struct opengpu_scanout *scanout);

int opengpu_buffer_alloc(struct opengpu_device *gpu,
                         struct opengpu_buffer *buffer, size_t size);
void opengpu_buffer_free(struct opengpu_device *gpu,
                         struct opengpu_buffer *buffer);

int opengpu_compute_init(struct opengpu_device *gpu);
void opengpu_compute_fini(struct opengpu_device *gpu);

int opengpu_display_init(struct opengpu_device *gpu,
                         const struct opengpu_buffer *boot_fb);
void opengpu_display_fini(struct opengpu_device *gpu);

#endif /* OPENGPU_DEVICE_H */
