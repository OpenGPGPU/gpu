/* SPDX-License-Identifier: MIT */
#ifndef RISCV_SIMT_GPU_ABI_H
#define RISCV_SIMT_GPU_ABI_H

/*
 * RISC-V SIMT GPU host ABI.
 *
 * This file is the single source of truth shared between the hardware (the
 * AXI4 control port, see GpuHostAxi.scala) and the Linux driver.  The GPU is
 * a software-defined engine: the host owns all buffers in shared physical
 * memory and only writes control registers over the bus.  The hardware never
 * sizes or owns a buffer; it computes addresses and issues reads/writes.
 *
 * Register offsets are BYTE offsets into the AXI4-M mapped control region
 * (32-bit words, little-endian, matching RenderHostRegs in GpuHostAxi.scala).
 */

/* ---- Device identification -------------------------------------------- */
#define GPU_REG_ID            0x000
#define GPU_REG_CONTROL       0x004
#define GPU_REG_STATUS        0x008
#define GPU_REG_IRQ           0x00c
#define GPU_REG_CMD_BASE      0x010
#define GPU_REG_CMD_COUNT     0x014
#define GPU_REG_COLOR_BASE    0x018
#define GPU_REG_DEPTH_BASE    0x01c
#define GPU_REG_STRIDE        0x020
#define GPU_REG_DEPTH_TEST    0x024
#define GPU_REG_DEPTH_FUNC    0x028
#define GPU_REG_DEPTH_WRITE   0x02c
#define GPU_REG_CULL_MODE     0x030
#define GPU_REG_TEX_BASE      0x034
#define GPU_REG_TEX_WIDTH     0x038
#define GPU_REG_TEX_HEIGHT    0x03c
#define GPU_REG_TEX_CONFIG    0x040

/* Display scanout domain, independent of execution COLOR_BASE/STRIDE. */
#define GPU_REG_SCANOUT_BASE    0x044
#define GPU_REG_SCANOUT_STRIDE  0x048
#define GPU_REG_SCANOUT_WIDTH   0x04c
#define GPU_REG_SCANOUT_HEIGHT  0x050
#define GPU_REG_SCANOUT_FORMAT  0x054
#define GPU_REG_SCANOUT_CONTROL 0x058
#define GPU_REG_SCANOUT_STATUS  0x05c
#define GPU_REG_CAPABILITIES    0x060

/* Hardware job queue + interrupt history (IH) ring, both in host memory.
 * The device fetches job descriptors from the ring behind JOB_RING_BASE and
 * rings the completion interrupt only after recording the interrupt details
 * (job id, ring slot, status) into the IH ring behind IH_BASE — AMDGPU-style
 * interrupt history that the IRQ handler drains instead of guessing. */
#define GPU_REG_JOB_RING_BASE   0x064
#define GPU_REG_JOB_RING_SIZE   0x068
#define GPU_REG_JOB_WPTR        0x06c
#define GPU_REG_JOB_RPTR        0x070
#define GPU_REG_JOB_CONTROL     0x074
#define GPU_REG_IH_BASE         0x078
#define GPU_REG_IH_SIZE         0x07c
#define GPU_REG_IH_WPTR         0x080
#define GPU_REG_IH_RPTR         0x084
/* Hardware clear (FillEngine): 64-byte-aligned destination, byte count a
 * multiple of 64, 32-bit fill pattern.  Write 1 to CLEAR_START to run one
 * clear of the programmed range; STATUS.CLEAR_BUSY reports completion. */
#define GPU_REG_CLEAR_BASE      0x088
#define GPU_REG_CLEAR_BYTES     0x08c
#define GPU_REG_CLEAR_PATTERN   0x090
#define GPU_REG_CLEAR_START     0x094

#define GPU_CAP_FRAGMENT_CORE   (1u << 0)
#define GPU_CAP_JOB_QUEUE       (1u << 1)
#define GPU_CAP_VERTEX_CORE     (1u << 2)
#define GPU_CAP_CLEAR_ENGINE    (1u << 3)
#define GPU_CAP_FRAGMENT_BATCH_SHIFT 8u
#define GPU_CAP_FRAGMENT_BATCH_MASK  (0xffu << GPU_CAP_FRAGMENT_BATCH_SHIFT)

/* JOB_CONTROL: bit0 ENABLE (RW), bit1 RESET (w1p, idle only),
 * bit8 ACTIVE (ro: a job is running), bit9 PENDING (ro: descriptor staged). */
#define GPU_JOB_ENABLE          (1u << 0)
#define GPU_JOB_RESET           (1u << 1)
#define GPU_JOB_STATUS_ACTIVE   (1u << 8)
#define GPU_JOB_STATUS_PENDING  (1u << 9)

#define GPU_SCANOUT_FORMAT_RGBA8888 0u
#define GPU_SCANOUT_ENABLE          (1u << 0)
#define GPU_SCANOUT_ACTIVE          (1u << 0)

/* TEX_CONFIG: bit0 CLAMP, bits[5:2] max mip level, bit8 sampling enable. */
#define GPU_TEX_WRAP_CLAMP    (1u << 0)
#define GPU_TEX_MAX_MIP_SHIFT 2u
#define GPU_TEX_MAX_MIP_MASK  (0xfu << GPU_TEX_MAX_MIP_SHIFT)
#define GPU_TEX_ENABLE        (1u << 8)

#define GPU_ID                ((GPU_DEVICE_ID << 16) | GPU_VERSION)
#define GPU_DEVICE_ID          0x4755u   /* 'GU' */
#define GPU_VERSION            0x0001u

/* ---- CONTROL (write-1 pulse on START) --------------------------------- */
#define GPU_CTRL_START         (1u << 0)

/* ---- STATUS (bit0 BUSY, bit1 DONE, bit2 ERROR; w1c on DONE/ERROR) ------ */
#define GPU_STATUS_BUSY        (1u << 0)
#define GPU_STATUS_DONE        (1u << 1)
#define GPU_STATUS_ERROR       (1u << 2)
#define GPU_STATUS_CLEAR_BUSY  (1u << 3)

/* ---- IRQ (bit0 ENABLE, bit1 PENDING w1c) ------------------------------- */
#define GPU_IRQ_ENABLE         (1u << 0)
#define GPU_IRQ_PENDING        (1u << 1)

/* ---- Depth function / cull modes (must match hardware encoding) -------- */
#define GPU_DEPTH_FUNC_LESS    0u
#define GPU_DEPTH_FUNC_LEQUAL  1u
#define GPU_DEPTH_FUNC_GREATER 2u
#define GPU_DEPTH_FUNC_ALWAYS  3u
#define GPU_CULL_NONE          0u
#define GPU_CULL_BACK          1u
#define GPU_CULL_FRONT         2u

/* ---- Pixel format: packed r8g8b8a8 (word 0xRRGGBBAA; little-endian bytes
 *      [AA BB GG RR]), matching RenderPipeline and TextureUnit. ------------ */
#define GPU_PIXEL_RGBA(r, g, b, a) \
    ((((u32)(r)) << 24) | (((u32)(g)) << 16) | (((u32)(b)) << 8) | ((u32)(a)))

/* ---------------------------------------------------------------------------
 * Draw-call record (CommandBufferStage), 40 32-bit words, little-endian.
 *                                                                   word idx
 *   v0/v1/v2 clip-space (x,y,z,w) as Q16.16                           [0..11]
 *   v0/v1/v2 colour (r,g,b) as 8-bit                                   [12..20]
 *   v0/v1/v2 depth as signed 32-bit (fixed-point, compared as-is)      [21..23]
 *   shader entry PC (shader descriptor)                                [24]
 *   kernarg buffer address                                             [25]
 *   per-vertex texture coords u,v (unsigned Q16.16, u0v0u1v1u2v2)      [26..31]
 *   optional per-draw depth/cull/texture state override                      [32]
 *   signed integer LOD bias [4:0], minimum mip clamp [11:8]                  [33]
  *   optional two-bank kernarg stride in bytes (zero = legacy single bank)   [34]
  *   reserved, must be zero                                               [35..39]
  *
  * Consecutive batches must not re-read lines a previous batch's kernel also
  * loaded (the kernel's loads hit the CU's L1 while staging writes go to
  * memory directly).  The host therefore programs a non-zero two-bank stride
  * (the driver always does) or gives draws disjoint kernarg buffers; a zero
  * stride is only safe for single-batch draws or uniform per-fragment
  * inputs.
 *
 * The colour/depth registers are the fixed-function interpolation inputs on
 * `fragCore = false`; the shader descriptor is used only on the core-backed
 * path, where it selects a compiled RV32 kernel launched on the SIMT lanes.
 * ------------------------------------------------------------------------ */
#define GPU_DRAW_WORDS 40u
struct gpu_draw_record {
    /* clip-space, Q16.16 */
    s32 v0[4];
    s32 v1[4];
    s32 v2[4];
    /* colour components occupy one 32-bit command word each */
    u32 c0[3];
    u32 c1[3];
    u32 c2[3];
    /* depth, signed 32-bit */
    s32 d0;
    s32 d1;
    s32 d2;
    u32 shader_pc;
    u32 kernarg;
    /* unsigned Q16.16 texture coordinates */
    u32 uv0[2];
    u32 uv1[2];
    u32 uv2[2];
    u32 state;
    u32 sampler;
    u32 kernarg_bank_stride;
    u32 reserved[5];
};

/* ---------------------------------------------------------------------------
 * Kernarg SoA ABI (core-backed fragment shading, batched).  The batch packs
 * `count` fragments structure-of-arrays so a lane-aware shader can fetch each
 * attribute with one unit-stride vector load.  `stride = 4 * warps * lanes`
 * (32 for the default 2 warps x 4 lanes):
 *
 *   [0*stride, 1*stride)   per-fragment x (i32)
 *   [1*stride, 2*stride)   per-fragment y (i32)
 *   [2*stride, 3*stride)   depth (i32)
 *   [3*stride, 4*stride)   packed colour inputs (u32)
 *   [4*stride, 5*stride)   perspective-correct u (unsigned Q16.16)
 *   [5*stride, 6*stride)   perspective-correct v (unsigned Q16.16)
 *   [6*stride, 7*stride)   colour outputs (u32)
 *   [7*stride, 8*stride)   depth outputs (i32)
 *   [8*stride, 9*stride)   output-valid words (1 = emit, 0 = discard)
 *   [9*stride, ...)        per-draw uniforms
 * ------------------------------------------------------------------------ */
#define GPU_KERNARG_STRIDE(w, l)  (4u * (w) * (l))
#define GPU_KERNARG_X_OFF(s)      (0u * (s))
#define GPU_KERNARG_Y_OFF(s)      (1u * (s))
#define GPU_KERNARG_DEPTH_OFF(s)  (2u * (s))
#define GPU_KERNARG_COLOR_OFF(s)  (3u * (s))
#define GPU_KERNARG_U_OFF(s)      (4u * (s))
#define GPU_KERNARG_V_OFF(s)      (5u * (s))
#define GPU_KERNARG_OUT_OFF(s)    (6u * (s))
#define GPU_KERNARG_DEPTH_OUT_OFF(s) (7u * (s))
#define GPU_KERNARG_VALID_OFF(s)  (8u * (s))
#define GPU_KERNARG_UNIFORM_OFF(s)(9u * (s))
#define GPU_KERNARG_BANKS          2u
#define GPU_KERNARG_BANK_ALIGN     64u

/* ---------------------------------------------------------------------------
 * Vertex-core draw-call record (CommandBufferStage with vertCore=true),
 * 40 32-bit words, little-endian.  Replaces the legacy triangle record when
 * the GPU_CAP_VERTEX_CORE capability is present and the driver opts in.
 *                                                                   word idx
 *   vertex buffer base address                                         [0]
 *   vertex count (must be multiple of 3)                               [1]
 *   vertex stride in bytes (initially fixed at 32)                     [2]
 *   vertex shader entry PC                                             [3]
 *   vertex kernarg buffer address (64-byte aligned)                    [4]
 *   vertex kernarg bank stride (zero, or alternate-bank byte stride)   [5]
 *   vertex attribute format (0 = fixed layout)                         [6]
 *   reserved, must be zero                                             [7..23]
 *   fragment shader entry PC                                           [24]
 *   fragment kernarg address                                           [25]
 *   reserved                                                           [26..31]
 *   state override flags (same encoding as legacy record)              [32]
 *   LOD bias [4:0], minimum mip clamp [11:8]                          [33]
 *   fragment kernarg bank stride                                       [34]
 *   reserved                                                           [35..39]
 *
 * Vertex buffer layout (format=0, 32 bytes per vertex):
 *   word 0-3: posX/Y/Z/W (Q16.16 signed)
 *   word 4:   packed RGBA8888 colour
 *   word 5:   depth (signed i32)
 *   word 6:   texU (unsigned Q16.16)
 *   word 7:   texV (unsigned Q16.16)
 * ------------------------------------------------------------------------ */
#define GPU_VERT_DRAW_WORDS 40u
#define GPU_VERTEX_STRIDE_BYTES 32u
struct gpu_vert_draw_record {
    u32 vert_buffer_base;
    u32 vert_count;
    u32 vert_stride;
    u32 vert_shader_pc;
    u32 vert_kernarg;
    u32 vert_kernarg_bank_stride;
    u32 vert_attr_format;
    u32 reserved0[17];
    u32 frag_shader_pc;
    u32 frag_kernarg;
    u32 reserved1[6];
    u32 state;
    u32 sampler;
    u32 frag_kernarg_bank_stride;
    u32 reserved2[5];
};

/* ---------------------------------------------------------------------------
 * Vertex kernarg SoA ABI (core-backed vertex shading, batched).  When the
 * draw supplies a non-zero bank stride, successive batches alternate between
 * `vert_kernarg` and `vert_kernarg + vert_kernarg_bank_stride`.
 * `stride = 4 * warps * lanes` (128 for default GpuConfig: warps=4, lanes=8).
 *
 * Inputs (hardware-staged from vertex buffer):
 *   [0*stride, 1*stride)   pos_x (i32 Q16.16)
 *   [1*stride, 2*stride)   pos_y (i32 Q16.16)
 *   [2*stride, 3*stride)   pos_z (i32 Q16.16)
 *   [3*stride, 4*stride)   pos_w (i32 Q16.16)
 *   [4*stride, 5*stride)   colour (u32 RGBA8888)
 *   [5*stride, 6*stride)   depth (i32)
 *   [6*stride, 7*stride)   tex_u (u32 Q16.16)
 *   [7*stride, 8*stride)   tex_v (u32 Q16.16)
 *
 * Outputs (kernel writes, hardware reads back):
 *   [8*stride, 9*stride)   clip_x (i32 Q16.16)
 *   [9*stride, 10*stride)  clip_y (i32 Q16.16)
 *   [10*stride, 11*stride) clip_z (i32 Q16.16)
 *   [11*stride, 12*stride) clip_w (i32 Q16.16)
 *   [12*stride, 13*stride) out_colour (u32 RGBA8888)
 *   [13*stride, 14*stride) out_depth (i32)
 *   [14*stride, 15*stride) out_u (u32 Q16.16)
 *   [15*stride, 16*stride) out_v (u32 Q16.16)
 *
 *   [16*stride, ...)       per-draw uniforms (MVP matrix etc.)
 * ------------------------------------------------------------------------ */
#define GPU_VERT_KERNARG_STRIDE(w, l)  (4u * (w) * (l))
#define GPU_VERT_KERNARG_POS_X_OFF(s)  (0u * (s))
#define GPU_VERT_KERNARG_POS_Y_OFF(s)  (1u * (s))
#define GPU_VERT_KERNARG_POS_Z_OFF(s)  (2u * (s))
#define GPU_VERT_KERNARG_POS_W_OFF(s)  (3u * (s))
#define GPU_VERT_KERNARG_COLOR_OFF(s)  (4u * (s))
#define GPU_VERT_KERNARG_DEPTH_OFF(s)  (5u * (s))
#define GPU_VERT_KERNARG_TEX_U_OFF(s)  (6u * (s))
#define GPU_VERT_KERNARG_TEX_V_OFF(s)  (7u * (s))
#define GPU_VERT_KERNARG_CLIP_X_OFF(s) (8u * (s))
#define GPU_VERT_KERNARG_CLIP_Y_OFF(s) (9u * (s))
#define GPU_VERT_KERNARG_CLIP_Z_OFF(s) (10u * (s))
#define GPU_VERT_KERNARG_CLIP_W_OFF(s) (11u * (s))
#define GPU_VERT_KERNARG_OUT_COLOR_OFF(s) (12u * (s))
#define GPU_VERT_KERNARG_OUT_DEPTH_OFF(s) (13u * (s))
#define GPU_VERT_KERNARG_OUT_U_OFF(s)  (14u * (s))
#define GPU_VERT_KERNARG_OUT_V_OFF(s)  (15u * (s))
#define GPU_VERT_KERNARG_UNIFORM_OFF(s) (16u * (s))

/* ---------------------------------------------------------------------------
 * Job ring descriptor (JobQueue), 16 32-bit words (64 bytes), little-endian.
 * One entry per submission; the host publishes entries in shared memory and
 * rings the JOB_WPTR doorbell.  Ring entry counts must be powers of two;
 * pointers are free-running modulo 65536 and index entries as ptr & (n - 1).
 * The host must keep fewer jobs in flight than ring entries.
 *                                                                   word idx
 *   bits 15:0 job id, bits 31:16 command record count                    [0]
 *   command buffer base                                                  [1]
 *   colour buffer base                                                   [2]
 *   depth buffer base                                                    [3]
 *   framebuffer stride (bytes)                                           [4]
 *   state: bit0 depth test, bits 6:4 depth func, bit7 depth write,
 *          bits 9:8 cull mode                                            [5]
 *   texture base                                                         [6]
 *   bits 13:0 texture width, bits 29:16 texture height                   [7]
 *   TEX_CONFIG (bit0 CLAMP, bits 5:2 max mip level, bit8 enable)         [8]
 *   reserved                                                             [9..15]
 * ------------------------------------------------------------------------ */
#define GPU_JOB_WORDS 16u
struct gpu_job_record {
    u32 header;
    u32 cmd_base;
    u32 color_base;
    u32 depth_base;
    u32 stride;
    u32 state;
    u32 tex_base;
    u32 tex_size;
    u32 tex_config;
    u32 reserved[7];
};

#define GPU_JOB_HDR_ID(h)       ((h) & 0xffffu)
#define GPU_JOB_HDR_COUNT(h)    (((h) >> 16) & 0xffffu)
#define GPU_JOB_HDR(id, count)  ((((u32)(count)) << 16) | ((u32)(id)))
#define GPU_JOB_STATE(test, func, write, cull) \
    ((((u32)(cull)) << 8) | (((u32)(write)) << 7) | \
     (((u32)(func)) << 4) | ((u32)!!(test)))
#define GPU_JOB_TEX_SIZE(w, h)  ((((u32)(h)) << 16) | ((u32)(w) & 0x3fffu))

/* ---------------------------------------------------------------------------
 * IH (interrupt history) record, 4 32-bit words (16 bytes).  The device
 * writes one record per completed job, in job order, before raising the
 * completion interrupt.  The driver drains records from IH_RPTR up to the
 * device's IH_WPTR and retires the fence named by the job id.
 *                                                                   word idx
 *   bits 15:0 job id, bit16 DONE, bit17 ERROR                            [0]
 *   bits 15:0 job-ring slot index (queue position)                       [1]
 *   status code (0 = completed)                                          [2]
 *   reserved                                                             [3]
 * ------------------------------------------------------------------------ */
#define GPU_IH_WORDS 4u
struct gpu_ih_record {
    u32 header;
    u32 slot;
    u32 status;
    u32 reserved;
};

#define GPU_IH_HDR_ID(h)     ((h) & 0xffffu)
#define GPU_IH_HDR_DONE(h)   ((h) & (1u << 16))
#define GPU_IH_HDR_ERROR(h)  ((h) & (1u << 17))

#endif /* RISCV_SIMT_GPU_ABI_H */
