/* SPDX-License-Identifier: MIT */
#ifndef OPENGPU_DRM_H
#define OPENGPU_DRM_H

#include <drm/drm.h>

/* Userspace draw record copied into per-context kernel DMA storage after
 * validation. External shader and kernarg addresses are not accepted yet. */
struct drm_opengpu_draw {
    __s32 v0[4];
    __s32 v1[4];
    __s32 v2[4];
    __u32 c0[3];
    __u32 c1[3];
    __u32 c2[3];
    __s32 d0;
    __s32 d1;
    __s32 d2;
    __u32 shader_pc;
    __u32 kernarg;
    __u32 uv0[2];
    __u32 uv1[2];
    __u32 uv2[2];
};

struct drm_opengpu_context {
    __u32 id;
    __u32 flags;
};

struct drm_opengpu_submit {
    __u32 context_id;
    __u32 command_handle;
    __u32 color_handle;
    __u32 stride;
    __u64 command_offset;
    __u32 command_count;
    __u32 flags;
};

#define OPENGPU_MAX_COMMANDS 64u

/* Verification-only: defer fence signaling after hardware completion so the
 * KMS implicit-sync wait is deterministically exercised under QEMU. */
#define OPENGPU_SUBMIT_TEST_FENCE_DELAY (1u << 0)

#define DRM_OPENGPU_SUBMIT 0x00
#define DRM_OPENGPU_CONTEXT_CREATE 0x01
#define DRM_OPENGPU_CONTEXT_DESTROY 0x02
#define DRM_IOCTL_OPENGPU_SUBMIT \
    DRM_IOWR(DRM_COMMAND_BASE + DRM_OPENGPU_SUBMIT, \
             struct drm_opengpu_submit)
#define DRM_IOCTL_OPENGPU_CONTEXT_CREATE \
    DRM_IOWR(DRM_COMMAND_BASE + DRM_OPENGPU_CONTEXT_CREATE, \
             struct drm_opengpu_context)
#define DRM_IOCTL_OPENGPU_CONTEXT_DESTROY \
    DRM_IOW(DRM_COMMAND_BASE + DRM_OPENGPU_CONTEXT_DESTROY, \
            struct drm_opengpu_context)

#endif /* OPENGPU_DRM_H */
