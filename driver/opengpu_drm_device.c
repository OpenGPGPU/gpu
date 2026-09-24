// SPDX-License-Identifier: GPL-2.0
/* Platform-owned DRM node, GEM objects, dma-buf synchronization and UAPI. */
#include <linux/module.h>
#include <linux/overflow.h>
#include <linux/dma-buf.h>
#include <drm/drm_drv.h>
#include <drm/clients/drm_client_setup.h>
#include <drm/drm_fourcc.h>
#include <drm/drm_fbdev_dma.h>
#include <drm/drm_gem_dma_helper.h>
#include <drm/drm_ioctl.h>
#include <drm/drm_managed.h>
#include <drm/drm_prime.h>
#include "opengpu_drm_device.h"
#include "opengpu_drm.h"

MODULE_IMPORT_NS("DMA_BUF");

DEFINE_DRM_GEM_DMA_FOPS(opengpu_drm_fops);

static const struct drm_ioctl_desc opengpu_drm_ioctls[] = {
    DRM_IOCTL_DEF_DRV(OPENGPU_SUBMIT, opengpu_compute_drm_ioctl,
                      DRM_RENDER_ALLOW),
    DRM_IOCTL_DEF_DRV(OPENGPU_CONTEXT_CREATE,
                      opengpu_compute_context_create_ioctl,
                      DRM_RENDER_ALLOW),
    DRM_IOCTL_DEF_DRV(OPENGPU_CONTEXT_DESTROY,
                      opengpu_compute_context_destroy_ioctl,
                      DRM_RENDER_ALLOW),
    DRM_IOCTL_DEF_DRV(OPENGPU_RESOURCE_BIND,
                      opengpu_compute_resource_bind_ioctl,
                      DRM_RENDER_ALLOW),
    DRM_IOCTL_DEF_DRV(OPENGPU_RESOURCE_UNBIND,
                      opengpu_compute_resource_unbind_ioctl,
                      DRM_RENDER_ALLOW),
    DRM_IOCTL_DEF_DRV(OPENGPU_GET_PARAM, opengpu_compute_get_param_ioctl,
                      DRM_RENDER_ALLOW),
    DRM_IOCTL_DEF_DRV(OPENGPU_BLIT, opengpu_compute_blit_ioctl,
                      DRM_RENDER_ALLOW),
    DRM_IOCTL_DEF_DRV(OPENGPU_FILL, opengpu_compute_fill_ioctl,
                      DRM_RENDER_ALLOW),
    DRM_IOCTL_DEF_DRV(OPENGPU_STRIDED_BLIT,
                      opengpu_compute_strided_blit_ioctl,
                      DRM_RENDER_ALLOW),
    DRM_IOCTL_DEF_DRV(OPENGPU_RESOLVE, opengpu_compute_resolve_ioctl,
                      DRM_RENDER_ALLOW),
    DRM_IOCTL_DEF_DRV(OPENGPU_INVALIDATE,
                      opengpu_compute_invalidate_ioctl,
                      DRM_RENDER_ALLOW),
    DRM_IOCTL_DEF_DRV(OPENGPU_COMPUTE, opengpu_compute_launch_ioctl,
                      DRM_RENDER_ALLOW),
    DRM_IOCTL_DEF_DRV(OPENGPU_GET_FAULT, opengpu_compute_get_fault_ioctl,
                      DRM_RENDER_ALLOW),
};

/* Drop the shared-L2 lines covering a GEM object.  Called from the dma-buf
 * end_cpu_access hook after the caller wrote to a CPU mapping: the CPU does
 * not write the GPU L2, so a page it cached earlier could otherwise shadow
 * the new contents. */
static int opengpu_gem_invalidate(struct drm_gem_object *obj)
{
    struct opengpu_device *gpu = dev_get_drvdata(obj->dev->dev);
    struct drm_gem_dma_object *dma_obj = to_drm_gem_dma_obj(obj);
    struct dma_fence *fence = NULL;
    u64 start, end;
    u32 bytes;
    long timeout;
    int ret;

    if (!(gpu->hw.capabilities & GPU_CAP_UNIFIED_COMMANDS))
        return 0;
    start = (u64)dma_obj->dma_addr & ~(u64)63;
    if (check_add_overflow((u64)dma_obj->dma_addr, (u64)obj->size, &end) ||
        start > U32_MAX)
        return -ERANGE;
    end = (end + 63ull) & ~(u64)63;
    if (end > (1ull << 32) || end - start > U32_MAX)
        return -ERANGE;
    bytes = (u32)(end - start);
    if (!bytes)
        return 0;
    ret = opengpu_hw_invalidate_async(gpu, (u32)start, bytes, NULL, NULL,
                                      &fence);
    if (ret)
        return ret;
    /* Emulated hardware only advances while the guest touches registers, so
     * wait in slices and tick the device between them. */
    timeout = msecs_to_jiffies(OPENGPU_DRAW_WAIT_MS + 100);
    while (!dma_fence_is_signaled(fence)) {
        long waited = dma_fence_wait_timeout(fence, false,
                                             min(timeout,
                                                 msecs_to_jiffies(4)));
        if (waited < 0) {
            dma_fence_put(fence);
            return waited;
        }
        timeout -= waited ? waited : msecs_to_jiffies(4);
        if (timeout <= 0) {
            dma_fence_put(fence);
            return -ETIMEDOUT;
        }
        opengpu_hw_progress_tick(gpu);
        if (signal_pending(current)) {
            dma_fence_put(fence);
            return -ERESTARTSYS;
        }
    }
    dma_fence_put(fence);
    return 0;
}

static int opengpu_dma_buf_begin_cpu_access(struct dma_buf *dmabuf,
                                            enum dma_data_direction direction)
{
    (void)dmabuf;
    (void)direction;
    return 0;
}

static int opengpu_dma_buf_end_cpu_access(struct dma_buf *dmabuf,
                                          enum dma_data_direction direction)
{
    /* A pure CPU read does not invalidate the GPU's copy. */
    if (direction == DMA_FROM_DEVICE)
        return 0;
    return opengpu_gem_invalidate(dmabuf->priv);
}

static const struct dma_buf_ops opengpu_dmabuf_ops = {
    .attach = drm_gem_map_attach,
    .detach = drm_gem_map_detach,
    .map_dma_buf = drm_gem_map_dma_buf,
    .unmap_dma_buf = drm_gem_unmap_dma_buf,
    .release = drm_gem_dmabuf_release,
    .mmap = drm_gem_dmabuf_mmap,
    .vmap = drm_gem_dmabuf_vmap,
    .vunmap = drm_gem_dmabuf_vunmap,
    .begin_cpu_access = opengpu_dma_buf_begin_cpu_access,
    .end_cpu_access = opengpu_dma_buf_end_cpu_access,
};

static struct dma_buf *opengpu_gem_prime_export(struct drm_gem_object *obj,
                                                int flags)
{
    DEFINE_DMA_BUF_EXPORT_INFO(exp_info);

    exp_info.ops = &opengpu_dmabuf_ops;
    exp_info.size = obj->size;
    exp_info.flags = flags;
    exp_info.priv = obj;
    exp_info.resv = obj->resv;
    /* Take the device and GEM references that drm_gem_dmabuf_release drops,
     * and share the object's reservation; a bare dma_buf_export underflows
     * both refcounts when the export fd is closed. */
    return drm_gem_dmabuf_export(obj->dev, &exp_info);
}

/* The GEM DMA helpers keep their object-function wrappers private, so mirror
 * them here and add the dma-buf export that carries the CPU-access hooks. */
static void opengpu_gem_free(struct drm_gem_object *obj)
{
    drm_gem_dma_free(to_drm_gem_dma_obj(obj));
}

static void opengpu_gem_print_info(struct drm_printer *p, unsigned int indent,
                                   const struct drm_gem_object *obj)
{
    drm_gem_dma_print_info(to_drm_gem_dma_obj(
        (struct drm_gem_object *)obj), p, indent);
}

static struct sg_table *opengpu_gem_get_sg_table(struct drm_gem_object *obj)
{
    return drm_gem_dma_get_sg_table(to_drm_gem_dma_obj(obj));
}

static int opengpu_gem_vmap(struct drm_gem_object *obj, struct iosys_map *map)
{
    return drm_gem_dma_vmap(to_drm_gem_dma_obj(obj), map);
}

static int opengpu_gem_mmap(struct drm_gem_object *obj,
                            struct vm_area_struct *vma)
{
    return drm_gem_dma_mmap(to_drm_gem_dma_obj(obj), vma);
}

static const struct drm_gem_object_funcs opengpu_gem_funcs = {
    .free = opengpu_gem_free,
    .print_info = opengpu_gem_print_info,
    .get_sg_table = opengpu_gem_get_sg_table,
    .vmap = opengpu_gem_vmap,
    .mmap = opengpu_gem_mmap,
    .vm_ops = &drm_gem_dma_vm_ops,
    .export = opengpu_gem_prime_export,
};

static struct drm_gem_object *opengpu_gem_create_object(struct drm_device *drm,
                                                        size_t size)
{
    struct drm_gem_dma_object *dma_obj;

    (void)drm;
    (void)size;
    dma_obj = kzalloc(sizeof(*dma_obj), GFP_KERNEL);
    if (!dma_obj)
        return ERR_PTR(-ENOMEM);
    dma_obj->base.funcs = &opengpu_gem_funcs;
    return &dma_obj->base;
}

static const struct drm_driver opengpu_drm_driver = {
    .driver_features = DRIVER_MODESET | DRIVER_GEM | DRIVER_ATOMIC |
                       DRIVER_RENDER | DRIVER_SYNCOBJ,
    .name = "opengpu",
    .desc = "RISC-V SIMT OpenGPU",
    .major = 1,
    .minor = 9,
    .fops = &opengpu_drm_fops,
    .open = opengpu_compute_drm_open,
    .postclose = opengpu_compute_drm_postclose,
    .ioctls = opengpu_drm_ioctls,
    .num_ioctls = ARRAY_SIZE(opengpu_drm_ioctls),
    .gem_create_object = opengpu_gem_create_object,
    .fbdev_probe = drm_fbdev_dma_driver_fbdev_probe,
    DRM_GEM_DMA_DRIVER_OPS,
};

int opengpu_drm_init(struct opengpu_device *gpu, bool render_only)
{
    struct opengpu_drm *drm;

    drm = devm_drm_dev_alloc(gpu->dev, &opengpu_drm_driver,
                              struct opengpu_drm, drm);
    if (IS_ERR(drm))
        return PTR_ERR(drm);
    drm->gpu = gpu;
    if (render_only)
        drm->drm.driver_features &= ~(DRIVER_MODESET | DRIVER_ATOMIC);
    gpu->drm = drm;
    return 0;
}

int opengpu_drm_register(struct opengpu_device *gpu)
{
    int ret = drm_dev_register(&gpu->drm->drm, 0);

    if (!ret) {
        dev_info(gpu->dev, "OPENGPU DRM PASS: card registered, formats=RGBA8888,XRGB8888\n");
        if (gpu->drm->drm.driver_features & DRIVER_MODESET)
            drm_client_setup(&gpu->drm->drm,
                             drm_format_info(DRM_FORMAT_XRGB8888));
    }
    return ret;
}

void opengpu_drm_unregister(struct opengpu_device *gpu)
{
    drm_dev_unregister(&gpu->drm->drm);
}
