// SPDX-License-Identifier: GPL-2.0
/* Platform lifetime and subsystem composition for the RISC-V SIMT GPU. */
#include <linux/module.h>
#include <linux/of.h>
#include <linux/platform_device.h>

#include "opengpu_device.h"

static int opengpu_probe(struct platform_device *pdev)
{
    struct opengpu_device *gpu;
    int ret;

    gpu = devm_kzalloc(&pdev->dev, sizeof(*gpu), GFP_KERNEL);
    if (!gpu)
        return -ENOMEM;
    gpu->dev = &pdev->dev;

    of_property_read_u32(pdev->dev.of_node, "opengpu,width", &gpu->width);
    of_property_read_u32(pdev->dev.of_node, "opengpu,height", &gpu->height);
    of_property_read_u32(pdev->dev.of_node, "opengpu,stride", &gpu->stride);
    if (!gpu->width)
        gpu->width = OPENGPU_DEFAULT_WIDTH;
    if (!gpu->height)
        gpu->height = OPENGPU_DEFAULT_HEIGHT;
    if (!gpu->stride)
        gpu->stride = gpu->width * 4;
    if (gpu->stride < gpu->width * 4)
        return dev_err_probe(gpu->dev, -EINVAL,
                             "stride %u is smaller than mode width %u\n",
                             gpu->stride, gpu->width);

    ret = opengpu_hw_init(gpu, pdev);
    if (ret)
        return ret;

    platform_set_drvdata(pdev, gpu);
    ret = opengpu_compute_init(gpu);
    if (ret)
        return dev_err_probe(gpu->dev, ret,
                             "cannot initialize execution client\n");

    /* Temporary bring-up handoff: display receives a shared buffer explicitly
     * from core. DRM/GEM replaces this with framebuffer-object ownership. */
    ret = opengpu_display_init(gpu, &gpu->compute.color);
    if (ret) {
        opengpu_compute_fini(gpu);
        return dev_err_probe(gpu->dev, ret,
                             "cannot initialize display client\n");
    }

    dev_info(gpu->dev, "GPU probe: ctrl=%pa+%pa stride=%u mode=%ux%u\n",
             &gpu->hw.regs_phys, &gpu->hw.regs_size, gpu->stride,
             gpu->width, gpu->height);
    return 0;
}

static void opengpu_remove(struct platform_device *pdev)
{
    struct opengpu_device *gpu = platform_get_drvdata(pdev);

    opengpu_display_fini(gpu);
    opengpu_compute_fini(gpu);
    opengpu_hw_fini(gpu);
}

static const struct of_device_id opengpu_of_match[] = {
    { .compatible = "riscv-simt,opengpu" },
    { }
};
MODULE_DEVICE_TABLE(of, opengpu_of_match);

static struct platform_driver opengpu_platform_driver = {
    .probe = opengpu_probe,
    .remove = opengpu_remove,
    .driver = {
        .name = OPENGPU_NAME,
        .of_match_table = opengpu_of_match,
    },
};
module_platform_driver(opengpu_platform_driver);

MODULE_LICENSE("GPL");
MODULE_DESCRIPTION("RISC-V SIMT GPU layered host driver");
