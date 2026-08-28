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

/* TEX_CONFIG bits: bit0 wrap==CLAMP (else REPEAT), bit8 sampling enable. */
#define GPU_TEX_WRAP_CLAMP    (1u << 0)
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

/* ---- Pixel format: packed a8r8g8b8 (host byte order is little-endian,
 *      so a word 0xAARRGGBB maps to memory [BB GG RR AA]).  In the host's
 *      native view a pixel read back from the colour buffer is ARGB. ------- */
#define GPU_PIXEL_ARGB(a, r, g, b) \
    ((((u32)(a)) << 24) | (((u32)(r)) << 16) | (((u32)(g)) << 8) | ((u32)(b)))

/* ---------------------------------------------------------------------------
 * Draw-call record (CommandBufferStage), 26 32-bit words, little-endian.
 *                                                                   word idx
 *   v0/v1/v2 clip-space (x,y,z,w) as Q16.16                           [0..11]
 *   v0/v1/v2 colour (r,g,b) as 8-bit                                   [12..20]
 *   v0/v1/v2 depth as signed 32-bit (fixed-point, compared as-is)      [21..23]
 *   shader entry PC (shader descriptor)                                [24]
 *   kernarg buffer address                                             [25]
 *   per-vertex texture coords u,v (unsigned Q16.16, u0v0u1v1u2v2)      [26..31]
 *
 * The colour/depth registers are the fixed-function interpolation inputs on
 * `fragCore = false`; the shader descriptor is used only on the core-backed
 * path, where it selects a compiled RV32 kernel launched on the SIMT lanes.
 * ------------------------------------------------------------------------ */
struct gpu_draw_record {
    /* clip-space, Q16.16 */
    s32 v0[4];
    s32 v1[4];
    s32 v2[4];
    /* colour, 8-bit, one per vertex */
    u8  c0[3];
    u8  c1[3];
    u8  c2[3];
    /* depth, signed 32-bit */
    s32 d0;
    s32 d1;
    s32 d2;
    u32 shader_pc;
    u32 kernarg;
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
 *   [4*stride, 5*stride)   colour outputs (u32)
 *   [6*stride, ...)        per-draw uniforms
 * ------------------------------------------------------------------------ */
#define GPU_KERNARG_STRIDE(w, l)  (4u * (w) * (l))
#define GPU_KERNARG_X_OFF(s)      (0u * (s))
#define GPU_KERNARG_Y_OFF(s)      (1u * (s))
#define GPU_KERNARG_DEPTH_OFF(s)  (2u * (s))
#define GPU_KERNARG_COLOR_OFF(s)  (3u * (s))
#define GPU_KERNARG_OUT_OFF(s)    (4u * (s))
#define GPU_KERNARG_UNIFORM_OFF(s)(6u * (s))

#endif /* RISCV_SIMT_GPU_ABI_H */
