// SPDX-License-Identifier: GPL-2.0
/* Display client bring-up. DRM/KMS and GEM framebuffer ownership land here. */
#include <linux/overflow.h>

#include "opengpu_device.h"

int opengpu_display_init(struct opengpu_device *gpu,
                         const struct opengpu_buffer *boot_fb)
{
    struct opengpu_display *display = &gpu->display;
    struct opengpu_scanout scanout;
    size_t required;
    int ret;

    if (check_mul_overflow((size_t)gpu->stride, (size_t)gpu->height,
                           &required) || required > boot_fb->size)
        return -EINVAL;

    scanout = (struct opengpu_scanout) {
        .base = boot_fb->dma,
        .stride = gpu->stride,
        .width = gpu->width,
        .height = gpu->height,
        .format = GPU_SCANOUT_FORMAT_RGBA8888,
        .enable = true,
    };
    ret = opengpu_hw_display_commit(gpu, &scanout);
    if (ret)
        return ret;

    display->scanout = scanout.base;
    display->stride = scanout.stride;
    display->width = scanout.width;
    display->height = scanout.height;
    display->format = scanout.format;
    display->enabled = true;
    dev_info(gpu->dev, "display scanout: fb=%pad stride=%u mode=%ux%u\n",
             &display->scanout, display->stride,
             display->width, display->height);
    return 0;
}

void opengpu_display_fini(struct opengpu_device *gpu)
{
    const struct opengpu_scanout disabled = { };

    if (!gpu->display.enabled)
        return;
    opengpu_hw_display_commit(gpu, &disabled);
    gpu->display.enabled = false;
}
