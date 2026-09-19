package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.command.{RenderCompletion, RenderDescriptor, RenderStatus}
import opengpu.dma.{CopyEngine, FillEngine, StridedCopyEngine}
import opengpu.core.memory.{
  CacheLineInvalidate,
  ComputeMemoryRequest,
  ComputeMemoryResponse,
  SharedAtomicRequest,
  SharedAtomicResponse
}

/** Byte offsets of the graphics renderer's control/status register file.
  *
  * All registers are 32-bit and byte-aligned (offsets are byte addresses the
  * host writes through the MMIO port).  Each config register is host-writable
  * at any time; it is snapshotted into a shadow the engine actually uses the
  * moment a START is accepted, so the host may program the next frame while
  * the current one is still in flight (a minimal double-buffered submission).
  */
object RenderHostRegs {
  /** First invalid byte offset (exclusive end of the register file). */
  val END               = 0x148
  val ID                = 0x00
  val CONTROL           = 0x04
  val STATUS            = 0x08
  val IRQ               = 0x0C
  val CMD_BASE          = 0x10
  val CMD_COUNT         = 0x14
  val COLOR_BASE        = 0x18
  val DEPTH_BASE        = 0x1C
  val STRIDE            = 0x20
  val DEPTH_TEST_ENABLE = 0x24
  val DEPTH_FUNC        = 0x28
  val DEPTH_WRITE_ENABLE= 0x2C
  val CULL_MODE         = 0x30
  val TEX_BASE          = 0x34
  val TEX_WIDTH         = 0x38
  val TEX_HEIGHT        = 0x3C
  /** bit0: CLAMP; bits[5:2]: max mip level; bit8: sampling enable. */
  val TEX_CONFIG        = 0x40
  /** Display registers are independent of the execution COLOR_BASE/STRIDE. */
  val SCANOUT_BASE      = 0x44
  val SCANOUT_STRIDE    = 0x48
  val SCANOUT_WIDTH     = 0x4C
  val SCANOUT_HEIGHT    = 0x50
  /** 0: packed RGBA8888 (0xRRGGBBAA). */
  val SCANOUT_FORMAT    = 0x54
  /** bit0: scanout enable. */
  val SCANOUT_CONTROL   = 0x58
  /** bit0: active (enable && non-zero base/stride/size). */
  val SCANOUT_STATUS    = 0x5C
  /** bit0: fragment core; bit2: vertex core;
    * bits 15:8: fragment batch capacity. */
  val CAPABILITIES      = 0x60
  /* 0x64..0x84 were the retired host-memory job ring and interrupt-history
   * ring registers; they are reserved and unmapped. */
  /** Hardware clear (FillEngine): destination base, byte count (multiple of
    * the 64-byte line) and the 32-bit fill pattern. */
  val CLEAR_BASE        = 0x88
  val CLEAR_BYTES       = 0x8C
  val CLEAR_PATTERN     = 0x90
  /** Write 1 to start one clear of the programmed range. */
  val CLEAR_START       = 0x94
  /** Hardware colour blit (CopyEngine): 64-byte aligned, non-overlapping
    * source/destination regions and a byte count that is a multiple of 64. */
  val BLIT_SRC_BASE     = 0x98
  val BLIT_DST_BASE     = 0x9C
  val BLIT_BYTES        = 0xA0
  /** Write 1 to start one blit of the programmed range. */
  val BLIT_START        = 0xA4
  /** Hardware two-dimensional copy (StridedCopyEngine). */
  val STRIDED_SRC_BASE  = 0xA8
  val STRIDED_DST_BASE  = 0xAC
  val STRIDED_WIDTH     = 0xB0
  val STRIDED_HEIGHT    = 0xB4
  val STRIDED_SRC_STRIDE= 0xB8
  val STRIDED_DST_STRIDE= 0xBC
  val STRIDED_START     = 0xC0
  /** bits[1:0] sample mode: 0 = 1x, 1 = 2x, 2 = 4x.  Reserved on
    * programmable (fragment-core) builds, which do not advertise MSAA. */
  val MSAA_CONFIG       = 0x134
  /** The unified-command block owns 0xC4..0x130 plus RESET at 0x138, so the
    * stencil/blend registers live immediately after it.  Layouts mirror the
    * per-draw record words 36/37/35 (see CommandBufferStage). */
  /** bit0 stencil-test enable; bits[6:4] func; bits[9:7] fail op;
    * bits[12:10] z-fail op; bits[15:13] z-pass op. */
  val STENCIL_CONFIG    = 0x13c
  /** bits[7:0] ref, bits[15:8] read mask, bits[23:16] write mask. */
  val STENCIL_REF_MASKS = 0x140
  /** bit0 present; bits[7:4] src factor; bits[11:8] dst factor;
    * bits[14:12] equation.  Present overrides legacy source-over. */
  val BLEND_CONFIG      = 0x144
}

/** A host memory-mapped register access (read or write of one 32-bit word). */
class RenderHostRegRequest extends Bundle {
  /** Byte address within the register file. Only 4-byte-aligned offsets are
    * valid; `ok` goes low for unaligned or unmapped addresses. */
  val addr = UInt(10.W)
  val data = UInt(32.W)
  /** Byte lane write-enable; for reads this is ignored. */
  val strb = UInt(4.W)
  val isWrite = Bool()
}

/** Response to a register read. Writes are fire-and-forget (acknowledged by
  * the request channel's `ready`), so the response channel only carries reads. */
class RenderHostRegResponse extends Bundle {
  val data = UInt(32.W)
  val ok = Bool()
}

/** M6 host interface for the graphics renderer.
  *
  * Presents a software-programmable memory-mapped register file (command-buffer
  * base, render-target bases, depth/state knobs, engine control, and status)
  * with a device ID and a completion interrupt — the first hardware piece of
  * the host-interface / Linux-device milestone.  A host writes the config
  * registers, then sets START; the engine latches the configuration and drives
  * the underlying `RenderCore`.  When the render completes, `done` is raised
  * and `irq` is pulsed if the interrupt is enabled.
  *
  * The renderer's memory ports (command-buffer and framebuffer word ports, plus
  * the core-backed shader kernel's line ports and coherence/atomic side ports)
  * are passed straight through so the SoC/host attaches them to the shared
  * off-chip hierarchy (`RenderCoreL2`, or directly to DRAM).  This module
  * concerns itself only with the register file, the control state machine, and
  * the completion interrupt.
  *
  * Register map (see `RenderHostRegs`): 0x00 ID (ro), 0x04 CONTROL (w1p;
  * bit0 START), 0x08 STATUS (ro + w1c; bit0 BUSY, bit1 DONE, bit2 ERROR),
   * 0x0C IRQ (bit0 ENABLE, bit1 PENDING w1c), execution config 0x10..0x40,
   * the independent display scanout bank 0x44..0x5c, read-only
   * CAPABILITIES at 0x60, and a reserved region at 0x64..0x84 that held the
   * retired job-ring / interrupt-history registers.
   *
   * Draws arrive either as legacy register-programmed START submissions or as
   * unified render commands (`GPU_UCMD_OP_RENDER`) whose 16-word descriptor the
   * engine fetches through the translated command port. The host-memory job
   * ring was retired.
  */
class RenderHost(
  config: GraphicsConfig = GraphicsConfig(),
  gpuConfig: GpuConfig = GpuConfig(),
  fragCore: Boolean = false,
  vertCore: Boolean = false,
  deviceId: Int = 0x4755, // 'GU'
  version: Int = 0x0001,
  unifiedCommands: Boolean = false,
  textureFaultReporting: Boolean = false,
  commandIdWidth: Int = 8
) extends Module {
  override def desiredName: String = "RenderHost"

  /** Largest sample mode this build supports: log2 of the sample count. */
  private val maxSampleMode = log2Ceil(config.maxSampleCount)

  val io = IO(new Bundle {
    val reg = new Bundle {
      val req = Flipped(Decoupled(new RenderHostRegRequest))
      val resp = Decoupled(new RenderHostRegResponse)
    }
    /** Graphics/unified completion interrupt; IRQ.PENDING && IRQ.ENABLE. */
    val irq = Output(Bool())
    /** Completion event from an integrated compute/command subsystem. */
    val externalCompletion = Input(Bool())
    /** Pulse when a failed texture response is consumed by the renderer. */
    val textureFault = if (textureFaultReporting) Some(Input(Bool())) else None

    val cbMem = new Bundle {
      val req = Decoupled(new OmMemoryRequest)
      val resp = Flipped(Decoupled(new OmMemoryResponse))
    }
    val fbMem = new Bundle {
      val req = Decoupled(new OmMemoryRequest)
      val resp = Flipped(Decoupled(new OmMemoryResponse))
    }
    val kernelMemReq = Decoupled(new ComputeMemoryRequest(gpuConfig))
    val kernelMemResp = Flipped(Decoupled(new ComputeMemoryResponse()))
    val kernelWordMemReq = Decoupled(new ComputeMemoryRequest(gpuConfig, 64, 8))
    val kernelWordMemResp = Flipped(Decoupled(new ComputeMemoryResponse(64, 8)))
    val kernelL1Invalidate = Flipped(Decoupled(new CacheLineInvalidate(gpuConfig)))
    val kernelL1InvalidateDone = Decoupled(new CacheLineInvalidate(gpuConfig))
    val kernelGlobalAtomicRequest = Decoupled(new SharedAtomicRequest(gpuConfig))
    val kernelGlobalAtomicResponse =
      Flipped(Decoupled(new SharedAtomicResponse(gpuConfig)))
    val texMem = new Bundle {
      val req = Decoupled(new OmMemoryRequest)
      val resp = Flipped(Decoupled(new OmMemoryResponse))
    }
    /** Unified render command: the VM address of a 16-word render descriptor.
      * The engine fetches it through the translated command port and returns a
      * unified completion. */
    val renderCommand = Flipped(Decoupled(
      new RenderDescriptor(gpuConfig, commandIdWidth)))
    val renderCompletion = Decoupled(new RenderCompletion(commandIdWidth))
  })

  private val core = Module(new RenderCore(config, gpuConfig, fragCore, vertCore))

  // The DMA engines share the kernelWordMem line port with the core's
  // staging/sampler bridge. Responses are routed by transaction-ID range:
  // ids [0,4) belong to the core. The DMA engines share [4,8), but their
  // descriptor admission below makes them mutually exclusive.
  private val fill = Module(new FillEngine(gpuConfig, maxOutstanding = 8,
    transactionIdBase = 4))
  private val blit = Module(new CopyEngine(gpuConfig, maxOutstanding = 8,
    lineSlots = 2, transactionIdBase = 4))
  private val strided = Module(new StridedCopyEngine(gpuConfig,
    maxOutstanding = 8, transactionIdBase = 4))
  private val dmaIdle :: dmaFill :: dmaBlit :: dmaStrided :: Nil = Enum(4)
  private val dmaOwner = RegInit(dmaIdle)
  // Hardware clear (FillEngine) programming.
  private val clearBaseReg = RegInit(0.U(32.W))
  private val clearBytesReg = RegInit(0.U(32.W))
  private val clearPatternReg = RegInit(0.U(32.W))
  private val blitSrcBaseReg = RegInit(0.U(32.W))
  private val blitDstBaseReg = RegInit(0.U(32.W))
  private val blitBytesReg = RegInit(0.U(32.W))
  private val stridedSrcBaseReg = RegInit(0.U(32.W))
  private val stridedDstBaseReg = RegInit(0.U(32.W))
  private val stridedWidthReg = RegInit(0.U(32.W))
  private val stridedHeightReg = RegInit(0.U(32.W))
  private val stridedSrcStrideReg = RegInit(0.U(32.W))
  private val stridedDstStrideReg = RegInit(0.U(32.W))
  // DMA engines win the shared port when they have work; the core's staging
  // bridge waits. Fixed priority only matters if unsafe software attempts
  // multiple START writes in one cycle; normal admission is mutually exclusive.
  io.kernelWordMemReq.valid :=
    fill.io.memoryRequest.valid || blit.io.memoryRequest.valid ||
      strided.io.memoryRequest.valid ||
      core.io.kernelWordMemReq.valid
  io.kernelWordMemReq.bits := Mux(fill.io.memoryRequest.valid,
    fill.io.memoryRequest.bits, Mux(blit.io.memoryRequest.valid,
      blit.io.memoryRequest.bits, Mux(strided.io.memoryRequest.valid,
        strided.io.memoryRequest.bits, core.io.kernelWordMemReq.bits)))
  fill.io.memoryRequest.ready :=
    io.kernelWordMemReq.ready && fill.io.memoryRequest.valid
  blit.io.memoryRequest.ready :=
    io.kernelWordMemReq.ready && !fill.io.memoryRequest.valid &&
      blit.io.memoryRequest.valid
  strided.io.memoryRequest.ready :=
    io.kernelWordMemReq.ready && !fill.io.memoryRequest.valid &&
      !blit.io.memoryRequest.valid && strided.io.memoryRequest.valid
  core.io.kernelWordMemReq.ready :=
    io.kernelWordMemReq.ready && !fill.io.memoryRequest.valid &&
      !blit.io.memoryRequest.valid && !strided.io.memoryRequest.valid

  private val wordRespIsDma = io.kernelWordMemResp.bits.transactionId >= 4.U
  private val wordRespIsBlit = wordRespIsDma && dmaOwner === dmaBlit
  private val wordRespIsFill = wordRespIsDma && dmaOwner === dmaFill
  private val wordRespIsStrided = wordRespIsDma && dmaOwner === dmaStrided
  // Responses keep the global transaction ID; each DMA engine subtracts its
  // transactionIdBase to recover its local slot.
  fill.io.memoryResponse.valid := io.kernelWordMemResp.valid && wordRespIsFill
  fill.io.memoryResponse.bits := io.kernelWordMemResp.bits
  blit.io.memoryResponse.valid := io.kernelWordMemResp.valid && wordRespIsBlit
  blit.io.memoryResponse.bits := io.kernelWordMemResp.bits
  strided.io.memoryResponse.valid :=
    io.kernelWordMemResp.valid && wordRespIsStrided
  strided.io.memoryResponse.bits := io.kernelWordMemResp.bits
  core.io.kernelWordMemResp.valid :=
    io.kernelWordMemResp.valid && !wordRespIsFill && !wordRespIsBlit &&
      !wordRespIsStrided
  core.io.kernelWordMemResp.bits := io.kernelWordMemResp.bits
  io.kernelWordMemResp.ready :=
    Mux(wordRespIsStrided, strided.io.memoryResponse.ready,
      Mux(wordRespIsBlit, blit.io.memoryResponse.ready,
      Mux(wordRespIsFill, fill.io.memoryResponse.ready,
        core.io.kernelWordMemResp.ready)))

  fill.io.descriptor.bits.descriptorId := 0.U
  fill.io.descriptor.bits.destinationAddress := clearBaseReg
  fill.io.descriptor.bits.bytes := clearBytesReg
  fill.io.descriptor.bits.pattern := clearPatternReg
  fill.io.completion.ready := true.B
  blit.io.descriptor.bits.descriptorId := 0.U
  blit.io.descriptor.bits.sourceAddress := blitSrcBaseReg
  blit.io.descriptor.bits.destinationAddress := blitDstBaseReg
  blit.io.descriptor.bits.bytes := blitBytesReg
  blit.io.completion.ready := true.B
  strided.io.descriptor.bits.descriptorId := 0.U
  strided.io.descriptor.bits.sourceAddress := stridedSrcBaseReg
  strided.io.descriptor.bits.destinationAddress := stridedDstBaseReg
  strided.io.descriptor.bits.widthBytes := stridedWidthReg
  strided.io.descriptor.bits.height := stridedHeightReg
  strided.io.descriptor.bits.sourceStride := stridedSrcStrideReg
  strided.io.descriptor.bits.destinationStride := stridedDstStrideReg
  strided.io.completion.ready := true.B

  // ---------------------------------------------------------------------------
  // Registers (host-visible) and the shadow the engine runs from.
  // ---------------------------------------------------------------------------
  private val cmdBaseReg = RegInit(0.U(32.W))
  private val cmdCountReg = RegInit(0.U(32.W))
  private val colorBaseReg = RegInit(0.U(32.W))
  private val depthBaseReg = RegInit(0.U(32.W))
  private val strideReg = RegInit(0.U(32.W))
  private val depthTestEnableReg = RegInit(0.U(32.W))
  private val depthFuncReg = RegInit(0.U(32.W))
  private val depthWriteEnableReg = RegInit(0.U(32.W))
  private val cullModeReg = RegInit(0.U(32.W))
  private val texBaseReg = RegInit(0.U(32.W))
  private val texWidthReg = RegInit(0.U(32.W))
  private val texHeightReg = RegInit(0.U(32.W))
  private val texConfigReg = RegInit(0.U(32.W))
  private val msaaConfigReg = RegInit(0.U(32.W))
  private val stencilConfigReg = RegInit(0.U(32.W))
  private val stencilRefMasksReg = RegInit(0.U(32.W))
  private val blendConfigReg = RegInit(0.U(32.W))
  private val scanoutBaseReg = RegInit(0.U(32.W))
  private val scanoutStrideReg = RegInit(0.U(32.W))
  private val scanoutWidthReg = RegInit(0.U(32.W))
  private val scanoutHeightReg = RegInit(0.U(32.W))
  private val scanoutFormatReg = RegInit(0.U(32.W))
  private val scanoutControlReg = RegInit(0.U(32.W))

  // Job queue / IH ring programming (host-written; device exposes RPTR/WPTR).
  private val activeCmdBase = RegInit(0.U(32.W))
  private val activeCmdCount = RegInit(0.U(32.W))
  private val activeColorBase = RegInit(0.U(32.W))
  private val activeDepthBase = RegInit(0.U(32.W))
  private val activeStride = RegInit(0.U(32.W))
  private val activeDepthTestEnable = RegInit(0.U(32.W))
  private val activeDepthFunc = RegInit(0.U(32.W))
  private val activeDepthWriteEnable = RegInit(0.U(32.W))
  private val activeCullMode = RegInit(0.U(32.W))
  private val activeTexBase = RegInit(0.U(32.W))
  private val activeTexWidth = RegInit(0.U(32.W))
  private val activeTexHeight = RegInit(0.U(32.W))
  private val activeTexConfig = RegInit(0.U(32.W))
  private val activeMsaaConfig = RegInit(0.U(32.W))
  private val activeStencilConfig = RegInit(0.U(32.W))
  private val activeStencilRefMasks = RegInit(0.U(32.W))
  private val activeBlendConfig = RegInit(0.U(32.W))

  private val busy = RegInit(false.B)
  private val done = RegInit(false.B)
  private val error = RegInit(false.B)
  // Per-job state is separate from STATUS.ERROR, which software can clear.
  private val textureFaulted = RegInit(false.B)
  private val textureFaultNow = io.textureFault.getOrElse(false.B) && busy
  private val irqEnable = RegInit(false.B)
  private val irqPending = RegInit(false.B)
  private val sawBusy = RegInit(false.B)
  /** The job queue was retired; no submission is queue-owned. */
  private val ownerQueue = false.B
  /** True while the running job was launched by a unified render command. */
  private val ownerUnified = RegInit(false.B)

  // Unified render command: fetch the 16-word render descriptor through the
  // translated command port, then launch from a packed register snapshot.
  private val renderFetching = RegInit(false.B)
  private val descOutstanding = RegInit(false.B)
  private val renderWord = RegInit(0.U(4.W))
  private val renderWords = Reg(Vec(16, UInt(32.W)))
  private val renderId = RegInit(0.U(commandIdWidth.W))
  private val renderBytes = RegInit(0.U(64.W))
  private val renderDonePending = RegInit(false.B)
  private val renderLaunch = RegInit(false.B)
  private val renderAddr = RegInit(0.U(gpuConfig.xLen.W))

  private val id = ((deviceId & 0xffff) << 16 | (version & 0xffff)).U

  // ---------------------------------------------------------------------------
  // The host-memory job ring (JobQueue) was retired: user draws are unified
  // render commands and the fallback/self-test uses the legacy START snapshot.
  // ---------------------------------------------------------------------------
  private val noCfg = 0.U.asTypeOf(new JobConfig())

  // ---------------------------------------------------------------------------
  // Register read (combinational) and write (registered) access.
  // ---------------------------------------------------------------------------
  private val rAddr = io.reg.req.bits.addr
  private val wFire = io.reg.req.fire && io.reg.req.bits.isWrite
  private val startWrite =
    wFire && rAddr === RenderHostRegs.CONTROL.U && io.reg.req.bits.data(0)

  private val clearStartWrite =
    wFire && rAddr === RenderHostRegs.CLEAR_START.U && io.reg.req.bits.data(0)
  private val blitStartWrite =
    wFire && rAddr === RenderHostRegs.BLIT_START.U && io.reg.req.bits.data(0)
  private val stridedStartWrite =
    wFire && rAddr === RenderHostRegs.STRIDED_START.U && io.reg.req.bits.data(0)
  fill.io.descriptor.valid := clearStartWrite && dmaOwner === dmaIdle &&
    !blitStartWrite && !stridedStartWrite
  blit.io.descriptor.valid := blitStartWrite && dmaOwner === dmaIdle &&
    !clearStartWrite && !stridedStartWrite
  strided.io.descriptor.valid := stridedStartWrite && dmaOwner === dmaIdle &&
    !clearStartWrite && !blitStartWrite
  when(fill.io.descriptor.fire) { dmaOwner := dmaFill }
  when(blit.io.descriptor.fire) { dmaOwner := dmaBlit }
  when(strided.io.descriptor.fire) { dmaOwner := dmaStrided }
  when(fill.io.completion.fire || blit.io.completion.fire ||
      strided.io.completion.fire) {
    dmaOwner := dmaIdle
  }

  // Queue launches are held off when a legacy START pulses the same cycle.
  // (The job ring is retired; no queue launches occur.)

  private val statusBits =
    Cat(0.U(26.W), strided.io.busy, blit.io.busy, fill.io.busy,
      error, done, busy)
  private val irqBits = Cat(0.U(30.W), irqPending, irqEnable)
  private val scanoutActive = scanoutControlReg(0) &&
    scanoutBaseReg.orR && scanoutStrideReg.orR &&
    scanoutWidthReg.orR && scanoutHeightReg.orR
  private val scanoutStatusBits = Cat(0.U(31.W), scanoutActive)
  private val capabilityBits =
    ((if (fragCore) (1 << GpuCapabilities.FragmentCore) else 0) |
      (if (vertCore) (1 << GpuCapabilities.VertexCore) else 0) |
      (1 << GpuCapabilities.ClearEngine) |
      (1 << GpuCapabilities.BlitEngine) |
      (1 << GpuCapabilities.StridedEngine) |
      (if (unifiedCommands) (1 << GpuCapabilities.UnifiedCommands) else 0) |
      // MSAA is advertised on both backends; the backend is selected by that
      // backend's own capability bit (FragmentCore here).  A functional claim
      // only: physical timing closure of the programmable stage is a separate
      // PPA gate and is not implied by this bit.
      (1 << GpuCapabilities.Msaa) |
      (maxSampleMode << GpuCapabilities.MsaaMaxModeShift) |
      (if (unifiedCommands) (1 << GpuCapabilities.UnifiedReset) else 0) |
      (1 << GpuCapabilities.PersistentDepth) |
      (if (unifiedCommands) (1 << GpuCapabilities.UnifiedRender) else 0) |
      (gpuConfig.warps * gpuConfig.lanes << GpuCapabilities.FragmentBatchShift))
      .U(32.W)

  private def readReg: UInt =
    MuxLookup(rAddr, 0.U(32.W))(Seq(
      RenderHostRegs.ID.U -> id,
      RenderHostRegs.STATUS.U -> statusBits,
      RenderHostRegs.IRQ.U -> irqBits,
      RenderHostRegs.CMD_BASE.U -> cmdBaseReg,
      RenderHostRegs.CMD_COUNT.U -> cmdCountReg,
      RenderHostRegs.COLOR_BASE.U -> colorBaseReg,
      RenderHostRegs.DEPTH_BASE.U -> depthBaseReg,
      RenderHostRegs.STRIDE.U -> strideReg,
      RenderHostRegs.DEPTH_TEST_ENABLE.U -> depthTestEnableReg,
      RenderHostRegs.DEPTH_FUNC.U -> depthFuncReg,
      RenderHostRegs.DEPTH_WRITE_ENABLE.U -> depthWriteEnableReg,
      RenderHostRegs.CULL_MODE.U -> cullModeReg,
      RenderHostRegs.TEX_BASE.U -> texBaseReg,
      RenderHostRegs.TEX_WIDTH.U -> texWidthReg,
      RenderHostRegs.TEX_HEIGHT.U -> texHeightReg,
      RenderHostRegs.TEX_CONFIG.U -> texConfigReg,
      RenderHostRegs.MSAA_CONFIG.U -> msaaConfigReg,
      RenderHostRegs.STENCIL_CONFIG.U -> stencilConfigReg,
      RenderHostRegs.STENCIL_REF_MASKS.U -> stencilRefMasksReg,
      RenderHostRegs.BLEND_CONFIG.U -> blendConfigReg,
      RenderHostRegs.SCANOUT_BASE.U -> scanoutBaseReg,
      RenderHostRegs.SCANOUT_STRIDE.U -> scanoutStrideReg,
      RenderHostRegs.SCANOUT_WIDTH.U -> scanoutWidthReg,
      RenderHostRegs.SCANOUT_HEIGHT.U -> scanoutHeightReg,
      RenderHostRegs.SCANOUT_FORMAT.U -> scanoutFormatReg,
      RenderHostRegs.SCANOUT_CONTROL.U -> scanoutControlReg,
      RenderHostRegs.SCANOUT_STATUS.U -> scanoutStatusBits,
      RenderHostRegs.CAPABILITIES.U -> capabilityBits,
      RenderHostRegs.CLEAR_BASE.U -> clearBaseReg,
      RenderHostRegs.CLEAR_BYTES.U -> clearBytesReg,
      RenderHostRegs.CLEAR_PATTERN.U -> clearPatternReg,
      RenderHostRegs.BLIT_SRC_BASE.U -> blitSrcBaseReg,
      RenderHostRegs.BLIT_DST_BASE.U -> blitDstBaseReg,
      RenderHostRegs.BLIT_BYTES.U -> blitBytesReg,
      RenderHostRegs.STRIDED_SRC_BASE.U -> stridedSrcBaseReg,
      RenderHostRegs.STRIDED_DST_BASE.U -> stridedDstBaseReg,
      RenderHostRegs.STRIDED_WIDTH.U -> stridedWidthReg,
      RenderHostRegs.STRIDED_HEIGHT.U -> stridedHeightReg,
      RenderHostRegs.STRIDED_SRC_STRIDE.U -> stridedSrcStrideReg,
      RenderHostRegs.STRIDED_DST_STRIDE.U -> stridedDstStrideReg
    ))

  // Merge a byte-masked write into a 32-bit register (partial-word writes with
  // a Strobe pattern, matching a host bus that can write sub-words).
  private def merge(regVal: UInt, data: UInt, strb: UInt): UInt = {
    val n = Wire(Vec(4, UInt(8.W)))
    for (i <- 0 until 4) {
      n(i) := Mux(strb(i), data((8 * i) + 7, 8 * i), regVal((8 * i) + 7, 8 * i))
    }
    n.asUInt
  }

  when(wFire) {
    switch(rAddr) {
      is(RenderHostRegs.CMD_BASE.U) {
        cmdBaseReg := merge(cmdBaseReg, io.reg.req.bits.data, io.reg.req.bits.strb)
      }
      is(RenderHostRegs.CMD_COUNT.U) {
        cmdCountReg := merge(cmdCountReg, io.reg.req.bits.data, io.reg.req.bits.strb)
      }
      is(RenderHostRegs.COLOR_BASE.U) {
        colorBaseReg := merge(colorBaseReg, io.reg.req.bits.data, io.reg.req.bits.strb)
      }
      is(RenderHostRegs.DEPTH_BASE.U) {
        depthBaseReg := merge(depthBaseReg, io.reg.req.bits.data, io.reg.req.bits.strb)
      }
      is(RenderHostRegs.STRIDE.U) {
        strideReg := merge(strideReg, io.reg.req.bits.data, io.reg.req.bits.strb)
      }
      is(RenderHostRegs.DEPTH_TEST_ENABLE.U) {
        depthTestEnableReg := merge(
          depthTestEnableReg, io.reg.req.bits.data, io.reg.req.bits.strb)
      }
      is(RenderHostRegs.DEPTH_FUNC.U) {
        depthFuncReg := merge(depthFuncReg, io.reg.req.bits.data, io.reg.req.bits.strb)
      }
      is(RenderHostRegs.DEPTH_WRITE_ENABLE.U) {
        depthWriteEnableReg := merge(
          depthWriteEnableReg, io.reg.req.bits.data, io.reg.req.bits.strb)
      }
      is(RenderHostRegs.CULL_MODE.U) {
        cullModeReg := merge(cullModeReg, io.reg.req.bits.data, io.reg.req.bits.strb)
      }
      is(RenderHostRegs.TEX_BASE.U) {
        texBaseReg := merge(texBaseReg, io.reg.req.bits.data, io.reg.req.bits.strb)
      }
      is(RenderHostRegs.TEX_WIDTH.U) {
        texWidthReg := merge(texWidthReg, io.reg.req.bits.data, io.reg.req.bits.strb)
      }
      is(RenderHostRegs.TEX_HEIGHT.U) {
        texHeightReg := merge(texHeightReg, io.reg.req.bits.data, io.reg.req.bits.strb)
      }
      is(RenderHostRegs.TEX_CONFIG.U) {
        texConfigReg := merge(texConfigReg, io.reg.req.bits.data, io.reg.req.bits.strb)
      }
      is(RenderHostRegs.SCANOUT_BASE.U) {
        scanoutBaseReg := merge(scanoutBaseReg, io.reg.req.bits.data, io.reg.req.bits.strb)
      }
      is(RenderHostRegs.SCANOUT_STRIDE.U) {
        scanoutStrideReg := merge(scanoutStrideReg, io.reg.req.bits.data, io.reg.req.bits.strb)
      }
      is(RenderHostRegs.SCANOUT_WIDTH.U) {
        scanoutWidthReg := merge(scanoutWidthReg, io.reg.req.bits.data, io.reg.req.bits.strb)
      }
      is(RenderHostRegs.SCANOUT_HEIGHT.U) {
        scanoutHeightReg := merge(scanoutHeightReg, io.reg.req.bits.data, io.reg.req.bits.strb)
      }
      is(RenderHostRegs.SCANOUT_FORMAT.U) {
        scanoutFormatReg := merge(scanoutFormatReg, io.reg.req.bits.data, io.reg.req.bits.strb)
      }
      is(RenderHostRegs.SCANOUT_CONTROL.U) {
        scanoutControlReg := merge(
          scanoutControlReg, io.reg.req.bits.data, io.reg.req.bits.strb)
      }
      is(RenderHostRegs.STATUS.U) {
        when(io.reg.req.bits.data(1)) { done := false.B }
        when(io.reg.req.bits.data(2)) { error := false.B }
      }
      is(RenderHostRegs.IRQ.U) {
        irqEnable := io.reg.req.bits.data(0)
        when(io.reg.req.bits.data(1)) { irqPending := false.B }
      }
      is(RenderHostRegs.CLEAR_BASE.U) {
        clearBaseReg := merge(clearBaseReg, io.reg.req.bits.data, io.reg.req.bits.strb)
      }
      is(RenderHostRegs.CLEAR_BYTES.U) {
        clearBytesReg := merge(clearBytesReg, io.reg.req.bits.data, io.reg.req.bits.strb)
      }
      is(RenderHostRegs.CLEAR_PATTERN.U) {
        clearPatternReg := merge(clearPatternReg, io.reg.req.bits.data, io.reg.req.bits.strb)
      }
      is(RenderHostRegs.BLIT_SRC_BASE.U) {
        blitSrcBaseReg := merge(blitSrcBaseReg,
          io.reg.req.bits.data, io.reg.req.bits.strb)
      }
      is(RenderHostRegs.BLIT_DST_BASE.U) {
        blitDstBaseReg := merge(blitDstBaseReg,
          io.reg.req.bits.data, io.reg.req.bits.strb)
      }
      is(RenderHostRegs.BLIT_BYTES.U) {
        blitBytesReg := merge(blitBytesReg,
          io.reg.req.bits.data, io.reg.req.bits.strb)
      }
      is(RenderHostRegs.STRIDED_SRC_BASE.U) {
        stridedSrcBaseReg := merge(stridedSrcBaseReg,
          io.reg.req.bits.data, io.reg.req.bits.strb)
      }
      is(RenderHostRegs.STRIDED_DST_BASE.U) {
        stridedDstBaseReg := merge(stridedDstBaseReg,
          io.reg.req.bits.data, io.reg.req.bits.strb)
      }
      is(RenderHostRegs.STRIDED_WIDTH.U) {
        stridedWidthReg := merge(stridedWidthReg,
          io.reg.req.bits.data, io.reg.req.bits.strb)
      }
      is(RenderHostRegs.STRIDED_HEIGHT.U) {
        stridedHeightReg := merge(stridedHeightReg,
          io.reg.req.bits.data, io.reg.req.bits.strb)
      }
      is(RenderHostRegs.STRIDED_SRC_STRIDE.U) {
        stridedSrcStrideReg := merge(stridedSrcStrideReg,
          io.reg.req.bits.data, io.reg.req.bits.strb)
      }
      is(RenderHostRegs.STRIDED_DST_STRIDE.U) {
        stridedDstStrideReg := merge(stridedDstStrideReg,
          io.reg.req.bits.data, io.reg.req.bits.strb)
      }
      is(RenderHostRegs.MSAA_CONFIG.U) {
        msaaConfigReg := merge(msaaConfigReg,
          io.reg.req.bits.data, io.reg.req.bits.strb)
      }
      is(RenderHostRegs.STENCIL_CONFIG.U) {
        stencilConfigReg := merge(stencilConfigReg,
          io.reg.req.bits.data, io.reg.req.bits.strb)
      }
      is(RenderHostRegs.STENCIL_REF_MASKS.U) {
        stencilRefMasksReg := merge(stencilRefMasksReg,
          io.reg.req.bits.data, io.reg.req.bits.strb)
      }
      is(RenderHostRegs.BLEND_CONFIG.U) {
        blendConfigReg := merge(blendConfigReg,
          io.reg.req.bits.data, io.reg.req.bits.strb)
      }
    }
  }

  // START and queued descriptors share the same full-word admission rule.
  private val msaaModeValid = Msaa.validModeWord(msaaConfigReg, config.maxSampleCount)
  private val launch = startWrite && !busy && msaaModeValid

  when(blitStartWrite &&
      (dmaOwner =/= dmaIdle || clearStartWrite || stridedStartWrite)) {
    error := true.B
  }
  when(clearStartWrite &&
      (dmaOwner =/= dmaIdle || blitStartWrite || stridedStartWrite)) {
    error := true.B
  }
  when(stridedStartWrite &&
      (dmaOwner =/= dmaIdle || clearStartWrite || blitStartWrite)) {
    error := true.B
  }
  when(blit.io.completion.fire && !blit.io.completion.bits.success) {
    error := true.B
  }
  when(fill.io.completion.fire && !fill.io.completion.bits.success) {
    error := true.B
  }
  when(strided.io.completion.fire && !strided.io.completion.bits.success) {
    error := true.B
  }

  when(startWrite) {
    when(!busy && msaaModeValid) {
      busy := true.B
      done := false.B
      error := false.B
      sawBusy := false.B
      ownerUnified := false.B
      textureFaulted := false.B
      activeCmdBase := cmdBaseReg
      activeCmdCount := cmdCountReg
      activeColorBase := colorBaseReg
      activeDepthBase := depthBaseReg
      activeStride := strideReg
      activeDepthTestEnable := depthTestEnableReg
      activeDepthFunc := depthFuncReg
      activeDepthWriteEnable := depthWriteEnableReg
      activeCullMode := cullModeReg
      activeTexBase := texBaseReg
      activeTexWidth := texWidthReg
      activeTexHeight := texHeightReg
      activeTexConfig := texConfigReg
      activeMsaaConfig := msaaConfigReg
      activeStencilConfig := stencilConfigReg
      activeStencilRefMasks := stencilRefMasksReg
      activeBlendConfig := blendConfigReg
    }
  }
  // An invalid programmed sample mode rejects the launch and latches ERROR.
  // This must follow the snapshot block so it wins over its ERROR clear.
  when(startWrite && !msaaModeValid) { error := true.B }

  // Queue-initiated launches were retired with the job ring; only the legacy
  // START and the unified render command launch the engine.

  // Unified render command: fetch the 16-word descriptor over the translated
  // command port, then launch from a packed register snapshot.
  renderLaunch := false.B
  io.renderCommand.ready := !renderFetching && !busy && !renderDonePending
  when(io.renderCommand.fire) {
    renderFetching := true.B
    descOutstanding := false.B
    renderWord := 0.U
    renderId := io.renderCommand.bits.descriptorId
    renderAddr := io.renderCommand.bits.descriptorAddress
    renderBytes := io.renderCommand.bits.bytes
  }
  when(renderFetching && io.cbMem.req.fire) { descOutstanding := true.B }
  when(renderFetching && io.cbMem.resp.fire) {
    descOutstanding := false.B
    renderWords(renderWord) := io.cbMem.resp.bits.data
    when(renderWord === 15.U) {
      renderFetching := false.B
      renderLaunch := true.B
    }.otherwise {
      renderWord := renderWord + 1.U
    }
  }
  when(renderLaunch && !busy) {
    busy := true.B
    done := false.B
    error := false.B
    sawBusy := false.B
    ownerUnified := true.B
    textureFaulted := false.B
    // Pack the gpu_job_record words into the legacy snapshot layout the core
    // mux already consumes, so no third configuration source is needed.
    activeCmdBase := renderWords(1)
    activeCmdCount := Cat(0.U(16.W), renderWords(0)(31, 16))
    activeColorBase := renderWords(2)
    activeDepthBase := renderWords(3)
    activeStride := renderWords(4)
    activeDepthTestEnable := Cat(0.U(31.W), renderWords(5)(0))
    activeDepthFunc := Cat(0.U(29.W), renderWords(5)(6, 4))
    activeDepthWriteEnable := Cat(0.U(31.W), renderWords(5)(7))
    activeCullMode := Cat(0.U(30.W), renderWords(5)(9, 8))
    activeTexBase := renderWords(6)
    activeTexWidth := Cat(0.U(18.W), renderWords(7)(13, 0))
    activeTexHeight := Cat(0.U(18.W), renderWords(7)(29, 16))
    activeTexConfig := renderWords(8)
    activeMsaaConfig := Cat(0.U(30.W), renderWords(9)(1, 0))
    activeStencilConfig := Cat(0.U(16.W), renderWords(10)(11, 9),
      renderWords(10)(8, 6), renderWords(10)(5, 3),
      renderWords(10)(2, 0), 0.U(3.W), renderWords(5)(17))
    activeStencilRefMasks := Cat(0.U(8.W), renderWords(11)(23, 16),
      renderWords(11)(15, 8), renderWords(11)(7, 0))
    activeBlendConfig := renderWords(12)
  }

  // Completion: the engine must be observed non-idle after launch before its
  // return to idle counts as done.  `core.done` is high before/at the launch
  // cycle (the command stage has not yet started), so the sawBusy guard stops
  // that pre-launch idle from being mistaken for completion.
  when(busy && !core.io.done) { sawBusy := true.B }
  when(busy && sawBusy && core.io.done) {
    busy := false.B
    done := true.B
    when(ownerUnified) {
      renderDonePending := true.B
    }.otherwise {
      irqPending := true.B
    }
  }
  when(textureFaultNow) {
    textureFaulted := true.B
    error := true.B
  }
  // Preserve failure at completion even if STATUS.ERROR was acknowledged
  // while the failed draw was still draining.
  when(busy && sawBusy && core.io.done && textureFaulted) { error := true.B }
  // Integrated compute/DMA completions share the same sticky, AXI-visible
  // pending bit and W1C acknowledgement as graphics completions.
  when(io.externalCompletion) { irqPending := true.B }

  // Unified render completions return to the command router rather than the
  // graphics IRQ; the bridge publishes them through the unified result slot.
  io.renderCompletion.valid := renderDonePending
  io.renderCompletion.bits.descriptorId := renderId
  io.renderCompletion.bits.status :=
    Mux(textureFaulted, RenderStatus.memoryFault, RenderStatus.success)
  io.renderCompletion.bits.success := !textureFaulted
  io.renderCompletion.bits.bytesProcessed := renderBytes
  when(io.renderCompletion.fire) { renderDonePending := false.B }

  // Tie the engine to the latched configuration: legacy register snapshots
  // when the host programmed START, the queue's descriptor snapshot when a
  // queued job was accepted.
  private def muxActive[T <: Data](legacy: T, queued: T): T =
    Mux(ownerQueue, queued, legacy)
  core.io.cmdBase := muxActive(activeCmdBase, noCfg.cmdBase)
  core.io.cmdCount := muxActive(activeCmdCount(15, 0), noCfg.cmdCount)
  core.io.colorBase := muxActive(activeColorBase, noCfg.colorBase)
  core.io.depthBase := muxActive(activeDepthBase, noCfg.depthBase)
  core.io.stride := muxActive(activeStride, noCfg.stride)
  core.io.depthTestEnable := muxActive(
    activeDepthTestEnable(0), noCfg.depthTestEnable)
  core.io.depthFunc := muxActive(activeDepthFunc(2, 0), noCfg.depthFunc)
  core.io.depthWriteEnable := muxActive(
    activeDepthWriteEnable(0), noCfg.depthWriteEnable)
  core.io.blendCfgEnable := muxActive(
    activeBlendConfig(0), noCfg.blendCfgEnable)
  core.io.blendSrcFactor := muxActive(
    activeBlendConfig(7, 4), noCfg.blendSrcFactor)
  core.io.blendDstFactor := muxActive(
    activeBlendConfig(11, 8), noCfg.blendDstFactor)
  core.io.blendEquation := muxActive(
    activeBlendConfig(14, 12), noCfg.blendEquation)
  core.io.stencilTestEnable := muxActive(
    activeStencilConfig(0), noCfg.stencilTestEnable)
  core.io.stencilFunc := muxActive(
    activeStencilConfig(6, 4), noCfg.stencilFunc)
  core.io.stencilRef := muxActive(
    activeStencilRefMasks(7, 0), noCfg.stencilRef)
  core.io.stencilReadMask := muxActive(
    activeStencilRefMasks(15, 8), noCfg.stencilReadMask)
  core.io.stencilWriteMask := muxActive(
    activeStencilRefMasks(23, 16), noCfg.stencilWriteMask)
  core.io.stencilFailOp := muxActive(
    activeStencilConfig(9, 7), noCfg.stencilFailOp)
  core.io.stencilZFailOp := muxActive(
    activeStencilConfig(12, 10), noCfg.stencilZFailOp)
  core.io.stencilZPassOp := muxActive(
    activeStencilConfig(15, 13), noCfg.stencilZPassOp)
  core.io.cullMode := muxActive(activeCullMode(1, 0), noCfg.cullMode)
  core.io.texEnable := muxActive(activeTexConfig(8), noCfg.texEnable)
  core.io.texBase := muxActive(activeTexBase, noCfg.texBase)
  core.io.texWidth := muxActive(activeTexWidth(13, 0), noCfg.texWidth)
  core.io.texHeight := muxActive(activeTexHeight(13, 0), noCfg.texHeight)
  core.io.texWrapClamp := muxActive(
    activeTexConfig(0), noCfg.texWrapClamp)
  core.io.texMaxLevel := muxActive(
    activeTexConfig(5, 2), noCfg.texMaxLevel)
  core.io.sampleMode := muxActive(
    activeMsaaConfig(1, 0), noCfg.sampleMode)
  core.io.start := launch || (renderLaunch && !busy)

  // Separate word ports.  The engine's command stage (draw records) and the
  // job queue's admin agent (descriptor fetches, IH record writes) are distinct
  // clients with different coherence and translation needs, so they expose
  // The command word port carries draw records and the unified render
  // descriptor; the admin port was removed with the job ring.
  private val cbPort = core.io.cbMem

  // The descriptor fetch owns the command word port while it runs (the core is
  // idle until launch), then hands it back to the command stage.
  private val descReqValid = renderFetching && !descOutstanding
  io.cbMem.req.valid := Mux(renderFetching, descReqValid, cbPort.req.valid)
  io.cbMem.req.bits.addr :=
    Mux(renderFetching, renderAddr + (renderWord << 2), cbPort.req.bits.addr)
  io.cbMem.req.bits.write :=
    Mux(renderFetching, false.B, cbPort.req.bits.write)
  io.cbMem.req.bits.data := Mux(renderFetching, 0.U, cbPort.req.bits.data)
  cbPort.req.ready := !renderFetching && io.cbMem.req.ready
  cbPort.resp.valid := !renderFetching && io.cbMem.resp.valid
  cbPort.resp.bits := io.cbMem.resp.bits
  io.cbMem.resp.ready := Mux(renderFetching, true.B, cbPort.resp.ready)

  core.io.fbMem.req <> io.fbMem.req
  io.fbMem.resp <> core.io.fbMem.resp
  io.kernelMemReq <> core.io.kernelMemReq
  core.io.kernelMemResp <> io.kernelMemResp

  core.io.kernelL1Invalidate <> io.kernelL1Invalidate
  io.kernelL1InvalidateDone <> core.io.kernelL1InvalidateDone
  io.kernelGlobalAtomicRequest <> core.io.kernelGlobalAtomicRequest
  core.io.kernelGlobalAtomicResponse <> io.kernelGlobalAtomicResponse
  core.io.texMem <> io.texMem

  io.irq := irqEnable && irqPending

  // Read port: single-cycle combinational response; writes are fire-and-forget.
  io.reg.req.ready := true.B
  io.reg.resp.valid := io.reg.req.valid && !io.reg.req.bits.isWrite
  io.reg.resp.bits.data := readReg
  private val aligned = (rAddr & 0x3.U) === 0.U
  private val mapped = rAddr < RenderHostRegs.END.U
  io.reg.resp.bits.ok := aligned && mapped
}
