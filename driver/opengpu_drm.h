/* SPDX-License-Identifier: MIT */
#ifndef OPENGPU_DRM_H
#define OPENGPU_DRM_H

#include <drm/drm.h>

/* Bring-up render submission. The command stream remains driver-owned until
 * command validation and per-file queues replace this fixed test draw. */
struct drm_opengpu_submit {
    __u32 color_handle;
    __u32 stride;
    __u32 flags;
    __u32 pad;
};

/* Verification-only: defer fence signaling after hardware completion so the
 * KMS implicit-sync wait is deterministically exercised under QEMU. */
#define OPENGPU_SUBMIT_TEST_FENCE_DELAY (1u << 0)

#define DRM_OPENGPU_SUBMIT 0x00
#define DRM_IOCTL_OPENGPU_SUBMIT \
    DRM_IOWR(DRM_COMMAND_BASE + DRM_OPENGPU_SUBMIT, \
             struct drm_opengpu_submit)

#endif /* OPENGPU_DRM_H */
