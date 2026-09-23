/* SPDX-License-Identifier: MIT */
#define _POSIX_C_SOURCE 200809L
#include "opengpu.h"
#include <drm/drm.h>
#include <drm/drm_mode.h>
#include <linux/sync_file.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stddef.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <time.h>
#include <unistd.h>

int opengpu_open(const char *path)
{
    return open(path ? path : "/dev/dri/card0", O_RDWR | O_CLOEXEC);
}

int opengpu_capabilities(int fd, uint64_t *value)
{
    struct drm_opengpu_param param = { .param = OPENGPU_PARAM_CAPABILITIES };
    if (!value) { errno = EINVAL; return -1; }
    if (ioctl(fd, DRM_IOCTL_OPENGPU_GET_PARAM, &param) < 0) return -1;
    *value = param.value;
    return 0;
}

int opengpu_context_create(int fd, uint32_t *id)
{
    struct drm_opengpu_context context = { 0 };
    if (!id) { errno = EINVAL; return -1; }
    if (ioctl(fd, DRM_IOCTL_OPENGPU_CONTEXT_CREATE, &context) < 0) return -1;
    *id = context.id;
    return 0;
}

int opengpu_context_destroy(int fd, uint32_t id)
{
    struct drm_opengpu_context context = { .id = id };
    return ioctl(fd, DRM_IOCTL_OPENGPU_CONTEXT_DESTROY, &context);
}

int opengpu_buffer_create(int fd, uint32_t bytes, struct opengpu_buffer *buffer)
{
    struct drm_mode_create_dumb create = { .width = (bytes + 3u) / 4u,
                                           .height = 1, .bpp = 32 };
    struct drm_mode_map_dumb map = { 0 };
    struct drm_mode_destroy_dumb destroy;
    void *mapping;
    int saved;
    if (!buffer || !bytes || bytes > UINT32_MAX - 3u) {
        errno = EINVAL; return -1;
    }
    memset(buffer, 0, sizeof(*buffer));
    if (ioctl(fd, DRM_IOCTL_MODE_CREATE_DUMB, &create) < 0) return -1;
    map.handle = create.handle;
    if (ioctl(fd, DRM_IOCTL_MODE_MAP_DUMB, &map) < 0) goto fail;
    mapping = mmap(NULL, create.size, PROT_READ | PROT_WRITE, MAP_SHARED,
                   fd, map.offset);
    if (mapping == MAP_FAILED) goto fail;
    buffer->handle = create.handle;
    buffer->size = create.size;
    buffer->map = mapping;
    return 0;
fail:
    saved = errno;
    destroy.handle = create.handle;
    ioctl(fd, DRM_IOCTL_MODE_DESTROY_DUMB, &destroy);
    errno = saved;
    return -1;
}

int opengpu_buffer_destroy(int fd, struct opengpu_buffer *buffer)
{
    struct drm_mode_destroy_dumb destroy;
    int result;
    if (!buffer || !buffer->handle) { errno = EINVAL; return -1; }
    destroy.handle = buffer->handle;
    if (buffer->map) munmap(buffer->map, buffer->size);
    result = ioctl(fd, DRM_IOCTL_MODE_DESTROY_DUMB, &destroy);
    if (!result) memset(buffer, 0, sizeof(*buffer));
    return result;
}

int opengpu_bind(int fd, const struct drm_opengpu_resource *resource)
{
    if (!resource) { errno = EINVAL; return -1; }
    return ioctl(fd, DRM_IOCTL_OPENGPU_RESOURCE_BIND, resource);
}

int opengpu_unbind(int fd, uint32_t context_id, uint32_t slot)
{
    struct drm_opengpu_resource resource = { .context_id = context_id,
                                             .slot = slot };
    return ioctl(fd, DRM_IOCTL_OPENGPU_RESOURCE_UNBIND, &resource);
}

int opengpu_compute(int fd, struct drm_opengpu_compute *command)
{
    if (!command) { errno = EINVAL; return -1; }
    return ioctl(fd, DRM_IOCTL_OPENGPU_COMPUTE, command);
}

int opengpu_render(int fd, struct drm_opengpu_submit *command)
{
    if (!command) { errno = EINVAL; return -1; }
    return ioctl(fd, DRM_IOCTL_OPENGPU_SUBMIT, command);
}

int opengpu_fill(int fd, struct drm_opengpu_fill *command)
{
    if (!command) { errno = EINVAL; return -1; }
    return ioctl(fd, DRM_IOCTL_OPENGPU_FILL, command);
}

int opengpu_blit(int fd, struct drm_opengpu_blit *command)
{
    if (!command) { errno = EINVAL; return -1; }
    return ioctl(fd, DRM_IOCTL_OPENGPU_BLIT, command);
}

int opengpu_strided_blit(int fd, struct drm_opengpu_strided_blit *command)
{
    if (!command) { errno = EINVAL; return -1; }
    return ioctl(fd, DRM_IOCTL_OPENGPU_STRIDED_BLIT, command);
}

int opengpu_resolve(int fd, struct drm_opengpu_resolve *command)
{
    if (!command) { errno = EINVAL; return -1; }
    return ioctl(fd, DRM_IOCTL_OPENGPU_RESOLVE, command);
}

int opengpu_invalidate(int fd, struct drm_opengpu_invalidate *command)
{
    if (!command) { errno = EINVAL; return -1; }
    return ioctl(fd, DRM_IOCTL_OPENGPU_INVALIDATE, command);
}

int opengpu_sync_create(int fd, uint32_t *handle)
{
    struct drm_syncobj_create create = { 0 };
    if (!handle) { errno = EINVAL; return -1; }
    if (ioctl(fd, DRM_IOCTL_SYNCOBJ_CREATE, &create) < 0) return -1;
    *handle = create.handle;
    return 0;
}

int opengpu_sync_destroy(int fd, uint32_t handle)
{
    struct drm_syncobj_destroy destroy = { .handle = handle };
    return ioctl(fd, DRM_IOCTL_SYNCOBJ_DESTROY, &destroy);
}

int opengpu_sync_wait(int fd, uint32_t handle, int64_t timeout_ms)
{
    struct drm_syncobj_wait wait = { .handles = (uintptr_t)&handle,
                                    .count_handles = 1,
                                    .flags = DRM_SYNCOBJ_WAIT_FLAGS_WAIT_ALL |
                                             DRM_SYNCOBJ_WAIT_FLAGS_WAIT_FOR_SUBMIT };
    struct timespec now;
    int64_t seconds, nanoseconds;
    if (timeout_ms < 0 || clock_gettime(CLOCK_MONOTONIC, &now) < 0) {
        if (timeout_ms < 0) errno = EINVAL;
        return -1;
    }
    seconds = timeout_ms / 1000;
    nanoseconds = (timeout_ms % 1000) * INT64_C(1000000);
    if (seconds > (INT64_MAX / INT64_C(1000000000)) - now.tv_sec - 1) {
        errno = EOVERFLOW; return -1;
    }
    wait.timeout_nsec = (now.tv_sec + seconds) * INT64_C(1000000000) +
                        now.tv_nsec + nanoseconds;
    return ioctl(fd, DRM_IOCTL_SYNCOBJ_WAIT, &wait);
}

int opengpu_sync_wait_success(int fd, uint32_t handle, int64_t timeout_ms)
{
    struct drm_syncobj_handle export = {
        .handle = handle,
        .flags = DRM_SYNCOBJ_HANDLE_TO_FD_FLAGS_EXPORT_SYNC_FILE,
    };
    struct sync_file_info info = { 0 };
    int result, saved;
    if (opengpu_sync_wait(fd, handle, timeout_ms) < 0) return -1;
    if (ioctl(fd, DRM_IOCTL_SYNCOBJ_HANDLE_TO_FD, &export) < 0) return -1;
    result = ioctl(export.fd, SYNC_IOC_FILE_INFO, &info);
    saved = errno;
    close(export.fd);
    if (result < 0) { errno = saved; return -1; }
    if (info.status != 1) { errno = EIO; return -1; }
    return 0;
}
