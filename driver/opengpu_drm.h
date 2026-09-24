/* SPDX-License-Identifier: MIT */
#ifndef OPENGPU_DRM_H
#define OPENGPU_DRM_H

#include <drm/drm.h>

/* Userspace draw record copied into per-job kernel DMA storage after
 * validation. shader_pc/kernarg are binding-relative offsets, never raw
 * device addresses; core-backed submission remains capability-gated. */
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
    __u32 state;
    __u32 sampler;
    __u32 kernarg_bank_stride;
    __u32 blend_config;
    __u32 stencil_config;
    __u32 stencil_ref;
    __u32 reserved[2];
};

/* Vertex-core form of the same 40-word command record. All addresses are
 * offsets into the matching submission bindings and are relocated only after
 * validation. Vertex format 0 is the fixed 32-byte layout in gpu_abi.h. */
struct drm_opengpu_vertex_draw {
    __u32 vertex_buffer;
    __u32 vertex_count;
    __u32 vertex_stride;
    __u32 vertex_shader_pc;
    __u32 vertex_kernarg;
    __u32 vertex_kernarg_bank_stride;
    __u32 vertex_format;
    __u32 reserved0[17];
    __u32 fragment_shader_pc;
    __u32 fragment_kernarg;
    __u32 reserved1[6];
    __u32 state;
    __u32 sampler;
    __u32 fragment_kernarg_bank_stride;
    __u32 blend_config;
    __u32 stencil_config;
    __u32 stencil_ref;
    __u32 reserved2[2];
};

/* Per-draw state override. Resource addresses and extents remain job-owned. */
#define OPENGPU_DRAW_STATE_OVERRIDE       (1u << 0)
#define OPENGPU_DRAW_STATE_DEPTH_TEST     (1u << 1)
#define OPENGPU_DRAW_STATE_DEPTH_FUNC_SHIFT 4u
#define OPENGPU_DRAW_STATE_DEPTH_FUNC_MASK  (0x7u << 4)
#define OPENGPU_DRAW_STATE_DEPTH_WRITE    (1u << 7)
#define OPENGPU_DRAW_STATE_CULL_SHIFT     8u
#define OPENGPU_DRAW_STATE_CULL_MASK      (0x3u << 8)
#define OPENGPU_DRAW_STATE_TEX_ENABLE     (1u << 10)
#define OPENGPU_DRAW_STATE_TEX_CLAMP      (1u << 11)
#define OPENGPU_DRAW_STATE_MAX_MIP_SHIFT  12u
#define OPENGPU_DRAW_STATE_MAX_MIP_MASK   (0xfu << 12)
#define OPENGPU_DRAW_STATE_BLEND_ENABLE   (1u << 16)
#define OPENGPU_DRAW_STATE_STENCIL_TEST   (1u << 17)
#define OPENGPU_DRAW_STATE_VALID_MASK     0x3ffffu

/* Signed integer LOD bias plus an inclusive minimum-level clamp. */
#define OPENGPU_DRAW_SAMPLER_LOD_BIAS_MASK 0x1fu
#define OPENGPU_DRAW_SAMPLER_MIN_LOD_SHIFT 8u
#define OPENGPU_DRAW_SAMPLER_MIN_LOD_MASK  (0xfu << 8)
#define OPENGPU_DRAW_SAMPLER_VALID_MASK    0x0f1fu

/* Per-draw GL-style blend config (draw-record word 35).  Present overrides
 * the legacy source-over OPENGPU_DRAW_STATE_BLEND_ENABLE. */
#define OPENGPU_DRAW_BLEND_PRESENT        (1u << 0)
#define OPENGPU_DRAW_BLEND_SRC_SHIFT      4u
#define OPENGPU_DRAW_BLEND_SRC_MASK       (0xfu << 4)
#define OPENGPU_DRAW_BLEND_DST_SHIFT      8u
#define OPENGPU_DRAW_BLEND_DST_MASK       (0xfu << 8)
#define OPENGPU_DRAW_BLEND_EQ_SHIFT       12u
#define OPENGPU_DRAW_BLEND_EQ_MASK        (0x7u << 12)
#define OPENGPU_DRAW_BLEND_VALID_MASK     0x7ff1u

/* Per-draw stencil config (draw-record word 36): the func shares the
 * depth-func encoding, ops use the GL 3-bit encoding. */
#define OPENGPU_DRAW_STENCIL_FUNC_SHIFT   0u
#define OPENGPU_DRAW_STENCIL_FUNC_MASK    (0x7u << 0)
#define OPENGPU_DRAW_STENCIL_FAIL_SHIFT   3u
#define OPENGPU_DRAW_STENCIL_FAIL_MASK    (0x7u << 3)
#define OPENGPU_DRAW_STENCIL_ZFAIL_SHIFT  6u
#define OPENGPU_DRAW_STENCIL_ZFAIL_MASK   (0x7u << 6)
#define OPENGPU_DRAW_STENCIL_ZPASS_SHIFT  9u
#define OPENGPU_DRAW_STENCIL_ZPASS_MASK   (0x7u << 9)
#define OPENGPU_DRAW_STENCIL_VALID_MASK   0xfffu

/* Per-draw stencil reference and masks (draw-record word 37). */
#define OPENGPU_DRAW_STENCIL_REF_SHIFT    0u
#define OPENGPU_DRAW_STENCIL_REF_MASK     0xffu
#define OPENGPU_DRAW_STENCIL_RMASK_SHIFT  8u
#define OPENGPU_DRAW_STENCIL_RMASK_MASK   (0xffu << 8)
#define OPENGPU_DRAW_STENCIL_WMASK_SHIFT  16u
#define OPENGPU_DRAW_STENCIL_WMASK_MASK   (0xffu << 16)
#define OPENGPU_DRAW_STENCIL_REF_VALID_MASK 0xffffffu

struct drm_opengpu_context {
    __u32 id;
    __u32 flags;
};

struct drm_opengpu_param {
    __u32 param;
    __u32 pad;
    __u64 value;
};

#define OPENGPU_PARAM_CAPABILITIES 0u
#define OPENGPU_CAP_FRAGMENT_CORE (1u << 0)
/* Bit1 was the host-memory job ring, retired; it is reserved and never set. */
#define OPENGPU_CAP_VERTEX_CORE (1u << 2)
#define OPENGPU_CAP_CLEAR_ENGINE (1u << 3)
#define OPENGPU_CAP_BLIT_ENGINE (1u << 4)
#define OPENGPU_CAP_STRIDED_ENGINE (1u << 5)
#define OPENGPU_CAP_UNIFIED_COMMANDS (1u << 6)
/* MSAA (bit7) is the backend selected by GPU_CAP_FRAGMENT_CORE (bit0): both
 * set is programmable MSAA, bit0 clear is fixed-function MSAA. Bits 17:16
 * carry the max sample mode. */
#define OPENGPU_CAP_MSAA (1u << 7)
#define OPENGPU_CAP_MSAA_MAX_MODE_SHIFT 16u
#define OPENGPU_CAP_MSAA_MAX_MODE_MASK \
    (0x3u << OPENGPU_CAP_MSAA_MAX_MODE_SHIFT)
#define OPENGPU_CAP_FRAGMENT_BATCH_SHIFT 8u
#define OPENGPU_CAP_FRAGMENT_BATCH_MASK \
    (0xffu << OPENGPU_CAP_FRAGMENT_BATCH_SHIFT)
/* Persistent depth attachment (bit19). When advertised, a submission may carry
 * its own depth attachment (depth_handle/depth_offset) and preserve it across
 * submissions with OPENGPU_SUBMIT_DEPTH_LOAD. */
#define OPENGPU_CAP_PERSISTENT_DEPTH (1u << 19)
#define OPENGPU_CAP_HW_VBLANK (1u << 21)

/* Sample mode field width (0 = 1x, 1 = 2x, 2 = 4x). */
#define OPENGPU_MSAA_MODE_MASK 0x3u

/* Device-global snapshot of the most recent unified-command fault. Sequence
 * zero means that no fault has been observed since driver initialization.
 * The status field is the raw GPU_UCMD_* result value. */
struct drm_opengpu_fault {
    __u64 sequence;
    __u64 bytes_processed;
    __u64 expected_bytes;
    __s32 error;
    __u32 command_id;
    __u32 opcode;
    __u32 status;
    __u32 flags;
    __u32 expected_command_id;
    __u32 expected_opcode;
    __u32 pad[2];
};

#define OPENGPU_FAULT_VALID               (1u << 0)
#define OPENGPU_FAULT_TIMEOUT             (1u << 1)
#define OPENGPU_FAULT_ABORTED             (1u << 2)
#define OPENGPU_FAULT_COMPLETION_ERROR    (1u << 3)
#define OPENGPU_FAULT_COMMAND_ID_MISMATCH (1u << 4)
#define OPENGPU_FAULT_OPCODE_MISMATCH     (1u << 5)
#define OPENGPU_FAULT_SUCCESS_MISMATCH    (1u << 6)
#define OPENGPU_FAULT_BYTES_MISMATCH      (1u << 7)
/* A safe unified-command reset was issued to recover from this fault, and
 * the drain never completed within the driver's reset window (the device is
 * wedged until it is reloaded or the fabric is reset). */
#define OPENGPU_FAULT_RESET_ISSUED        (1u << 8)
#define OPENGPU_FAULT_RESET_TIMEOUT       (1u << 9)
#define OPENGPU_FAULT_FLAGS_MASK          0x3ffu

enum drm_opengpu_resource_type {
    OPENGPU_RESOURCE_SHADER = 1,
    OPENGPU_RESOURCE_KERNARG = 2,
    OPENGPU_RESOURCE_TEXTURE = 3,
    OPENGPU_RESOURCE_VERTEX_BUFFER = 4,
    OPENGPU_RESOURCE_VERTEX_SHADER = 5,
    OPENGPU_RESOURCE_VERTEX_KERNARG = 6,
    OPENGPU_RESOURCE_COMPUTE_SHADER = 7,
    OPENGPU_RESOURCE_COMPUTE_KERNARG = 8,
};

struct drm_opengpu_resource {
    __u32 context_id;
    __u32 slot;
    __u32 handle;
    __u32 type;
    __u64 offset;
    __u64 size;
    __u32 width;
    __u32 height;
    __u32 flags;
    __u32 pad;
};

#define OPENGPU_RESOURCE_TEXTURE_CLAMP (1u << 0)
#define OPENGPU_RESOURCE_TEXTURE_MAX_MIP_SHIFT 4u
#define OPENGPU_RESOURCE_TEXTURE_MAX_MIP_MASK \
    (0xfu << OPENGPU_RESOURCE_TEXTURE_MAX_MIP_SHIFT)
/* Request uncached access for translated resources (compute kernarg/texture).
 * Accepted for all resource types; Bare graphics bindings fall back to
 * bind-time invalidation, and shaders are snapshotted. CPU writes to those
 * fallback bindings require rebinding or explicit cache invalidation. */
#define OPENGPU_RESOURCE_UNCACHED (1u << 16)
#define OPENGPU_MAX_RESOURCE_SLOTS 16u

struct drm_opengpu_submit {
    __u32 context_id;
    __u32 command_handle;
    __u32 color_handle;
    __u32 stride;
    __u64 command_offset;
    __u32 command_count;
    __u32 flags;
    __u32 shader_slot;
    __u32 kernarg_slot;
    __u32 texture_slot;
    __u32 in_syncobj;
    __u32 out_syncobj;
    __u32 vertex_buffer_slot;
    __u32 vertex_shader_slot;
    __u32 vertex_kernarg_slot;
    /* bits 1:0 sample mode (OPENGPU_MSAA_MODE_MASK); reserved bits must be 0. */
    __u32 sample_mode;
    /* Persistent depth attachment. Zero depth_handle selects the driver's
     * private depth plane, freshly cleared per submission (legacy behaviour).
     * A nonzero handle binds that GEM's byte range [depth_offset,
     * depth_offset + stride * height) as the depth/stencil plane; it must be
     * word-aligned, large enough, and a different GEM object than the command
     * and colour handles. Without OPENGPU_SUBMIT_DEPTH_LOAD the region is
     * cleared to the D24S8 far value before the draw; with it the stored
     * contents are kept so a render pass can continue across submissions. */
    __u32 depth_handle;
    __u64 depth_offset;
};

/* Ordered whole-cache-line copy. Source and destination ranges are validated
 * GEM-relative offsets and must not overlap. Completion is represented by the
 * same scheduler/syncobj fence model as rendering. */
struct drm_opengpu_blit {
    __u32 context_id;
    __u32 source_handle;
    __u32 destination_handle;
    __u32 flags;
    __u64 source_offset;
    __u64 destination_offset;
    __u64 bytes;
    __u32 in_syncobj;
    __u32 out_syncobj;
    __u32 wait_event;
    __u32 signal_event;
};

/* Ordered whole-cache-line fill. The destination is a validated GEM-relative
 * range. Completion follows the render/blit scheduler and syncobj model. */
struct drm_opengpu_fill {
    __u32 context_id;
    __u32 destination_handle;
    __u32 pattern;
    __u32 flags;
    __u64 destination_offset;
    __u64 bytes;
    __u32 in_syncobj;
    __u32 out_syncobj;
    __u32 wait_event;
    __u32 signal_event;
};

/* Ordered two-dimensional cache-line copy. Width and both strides are
 * multiples of 64; each stride is at least width. Source and destination
 * bounding ranges must not overlap. */
struct drm_opengpu_strided_blit {
    __u32 context_id;
    __u32 source_handle;
    __u32 destination_handle;
    __u32 flags;
    __u64 source_offset;
    __u64 destination_offset;
    __u32 width_bytes;
    __u32 height;
    __u32 source_stride;
    __u32 destination_stride;
    __u32 in_syncobj;
    __u32 out_syncobj;
    __u32 wait_event;
    __u32 signal_event;
};

/* Ordered MSAA resolve. The source is a multisample colour buffer and the
 * destination is a single-sample colour buffer; both are validated
 * GEM-relative ranges that must not overlap. sample_mode is 0/1/2 (1x/2x/4x)
 * and must not exceed the device's advertised maximum. Completion follows the
 * render/blit scheduler and syncobj model, and the destination fence gates
 * scanout. */
struct drm_opengpu_resolve {
    __u32 context_id;
    __u32 source_handle;
    __u32 destination_handle;
    __u32 flags;
    __u64 source_offset;
    __u64 destination_offset;
    __u32 width;
    __u32 height;
    __u32 source_stride;
    __u32 destination_stride;
    __u32 sample_mode;
    __u32 in_syncobj;
    __u32 out_syncobj;
    __u32 wait_event;
    __u32 signal_event;
};

/* Ordered L2 line invalidate over a validated GEM-relative range.  The range is
 * 64-byte aligned and the operation issues no memory traffic: it drops the
 * corresponding shared-L2 lines (and snoops their L1 holders) so a later GPU
 * read observes what a non-coherent agent (the CPU) wrote to the same range.
 * Completion follows the render/blit scheduler and syncobj model. */
struct drm_opengpu_invalidate {
    __u32 context_id;
    __u32 handle;
    __u32 flags;
    __u32 in_syncobj;
    __u64 offset;
    __u64 bytes;
    __u32 out_syncobj;
    __u32 wait_event;
    __u32 signal_event;
    __u32 pad;
};

/* Ordered general-compute launch. The program and kernarg addresses are
 * binding-relative offsets; the kernel validates and relocates both before
 * submitting the launch through the unified command path. */
struct drm_opengpu_compute {
    __u32 context_id;
    __u32 shader_slot;
    __u32 kernarg_slot;
    __u32 flags;
    __u64 shader_offset;
    __u64 kernarg_offset;
    __u32 grid[3];
    __u32 local[3];
    __u32 in_syncobj;
    __u32 out_syncobj;
    __u32 wait_event;
    __u32 signal_event;
};

/* Hardware event controls shared by compute and DMA submissions. Event words
 * encode ID in bits 7:0 and generation in bits 15:8. */
#define OPENGPU_COMMAND_WAIT_EVENT   (1u << 0)
#define OPENGPU_COMMAND_SIGNAL_EVENT (1u << 1)
#define OPENGPU_COMMAND_EVENT(id, generation) \
    (((id) & 0xffu) | (((generation) & 0xffu) << 8))

#define OPENGPU_MAX_COMMANDS 64u

/* Verification-only: defer fence signaling after hardware completion so the
 * KMS implicit-sync wait is deterministically exercised under QEMU. */
#define OPENGPU_SUBMIT_TEST_FENCE_DELAY (1u << 0)
#define OPENGPU_SUBMIT_VERTEX_CORE      (1u << 1)
/* Keep the bound depth attachment's stored contents instead of clearing it at
 * the start of the submission (depth_handle must be nonzero). */
#define OPENGPU_SUBMIT_DEPTH_LOAD       (1u << 2)

#define DRM_OPENGPU_SUBMIT 0x00
#define DRM_OPENGPU_CONTEXT_CREATE 0x01
#define DRM_OPENGPU_CONTEXT_DESTROY 0x02
#define DRM_OPENGPU_RESOURCE_BIND 0x03
#define DRM_OPENGPU_RESOURCE_UNBIND 0x04
#define DRM_OPENGPU_GET_PARAM 0x05
#define DRM_OPENGPU_BLIT 0x06
#define DRM_OPENGPU_FILL 0x07
#define DRM_OPENGPU_STRIDED_BLIT 0x08
#define DRM_OPENGPU_COMPUTE 0x09
#define DRM_OPENGPU_GET_FAULT 0x0a
#define DRM_OPENGPU_RESOLVE 0x0b
#define DRM_OPENGPU_INVALIDATE 0x0c
#define DRM_IOCTL_OPENGPU_SUBMIT \
    DRM_IOWR(DRM_COMMAND_BASE + DRM_OPENGPU_SUBMIT, \
             struct drm_opengpu_submit)
#define DRM_IOCTL_OPENGPU_CONTEXT_CREATE \
    DRM_IOWR(DRM_COMMAND_BASE + DRM_OPENGPU_CONTEXT_CREATE, \
             struct drm_opengpu_context)
#define DRM_IOCTL_OPENGPU_CONTEXT_DESTROY \
    DRM_IOW(DRM_COMMAND_BASE + DRM_OPENGPU_CONTEXT_DESTROY, \
            struct drm_opengpu_context)
#define DRM_IOCTL_OPENGPU_RESOURCE_BIND \
    DRM_IOW(DRM_COMMAND_BASE + DRM_OPENGPU_RESOURCE_BIND, \
            struct drm_opengpu_resource)
#define DRM_IOCTL_OPENGPU_RESOURCE_UNBIND \
    DRM_IOW(DRM_COMMAND_BASE + DRM_OPENGPU_RESOURCE_UNBIND, \
            struct drm_opengpu_resource)
#define DRM_IOCTL_OPENGPU_GET_PARAM \
    DRM_IOWR(DRM_COMMAND_BASE + DRM_OPENGPU_GET_PARAM, \
             struct drm_opengpu_param)
#define DRM_IOCTL_OPENGPU_BLIT \
    DRM_IOWR(DRM_COMMAND_BASE + DRM_OPENGPU_BLIT, \
             struct drm_opengpu_blit)
#define DRM_IOCTL_OPENGPU_FILL \
    DRM_IOWR(DRM_COMMAND_BASE + DRM_OPENGPU_FILL, \
             struct drm_opengpu_fill)
#define DRM_IOCTL_OPENGPU_STRIDED_BLIT \
    DRM_IOWR(DRM_COMMAND_BASE + DRM_OPENGPU_STRIDED_BLIT, \
             struct drm_opengpu_strided_blit)
#define DRM_IOCTL_OPENGPU_COMPUTE \
    DRM_IOWR(DRM_COMMAND_BASE + DRM_OPENGPU_COMPUTE, \
             struct drm_opengpu_compute)
#define DRM_IOCTL_OPENGPU_GET_FAULT \
    DRM_IOWR(DRM_COMMAND_BASE + DRM_OPENGPU_GET_FAULT, \
             struct drm_opengpu_fault)
#define DRM_IOCTL_OPENGPU_RESOLVE \
    DRM_IOWR(DRM_COMMAND_BASE + DRM_OPENGPU_RESOLVE, \
             struct drm_opengpu_resolve)
#define DRM_IOCTL_OPENGPU_INVALIDATE \
    DRM_IOWR(DRM_COMMAND_BASE + DRM_OPENGPU_INVALIDATE, \
             struct drm_opengpu_invalidate)

#endif /* OPENGPU_DRM_H */
