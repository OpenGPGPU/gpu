package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
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
  val END               = 0x60
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
  /** bit0: wrap==CLAMP (else REPEAT); bit8: texture sampling enable. */
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
  * and the independent display scanout bank 0x44..0x5c.
  */
class RenderHost(
  config: GraphicsConfig = GraphicsConfig(),
  gpuConfig: GpuConfig = GpuConfig(),
  fragCore: Boolean = false,
  deviceId: Int = 0x4755, // 'GU'
  version: Int = 0x0001
) extends Module {
  override def desiredName: String = "RenderHost"

  val io = IO(new Bundle {
    val reg = new Bundle {
      val req = Flipped(Decoupled(new RenderHostRegRequest))
      val resp = Decoupled(new RenderHostRegResponse)
    }
    /** Completion interrupt; asserted while IRQ.PENDING && IRQ.ENABLE. */
    val irq = Output(Bool())

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
    val kernelWordMemReq = Decoupled(new ComputeMemoryRequest(gpuConfig))
    val kernelWordMemResp = Flipped(Decoupled(new ComputeMemoryResponse()))
    val kernelL1Invalidate = Flipped(Decoupled(new CacheLineInvalidate(gpuConfig)))
    val kernelL1InvalidateDone = Decoupled(new CacheLineInvalidate(gpuConfig))
    val kernelGlobalAtomicRequest = Decoupled(new SharedAtomicRequest(gpuConfig))
    val kernelGlobalAtomicResponse =
      Flipped(Decoupled(new SharedAtomicResponse(gpuConfig)))
    val texMem = new Bundle {
      val req = Decoupled(new OmMemoryRequest)
      val resp = Flipped(Decoupled(new OmMemoryResponse))
    }
  })

  private val core = Module(new RenderCore(config, gpuConfig, fragCore))

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
  private val scanoutBaseReg = RegInit(0.U(32.W))
  private val scanoutStrideReg = RegInit(0.U(32.W))
  private val scanoutWidthReg = RegInit(0.U(32.W))
  private val scanoutHeightReg = RegInit(0.U(32.W))
  private val scanoutFormatReg = RegInit(0.U(32.W))
  private val scanoutControlReg = RegInit(0.U(32.W))

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

  private val busy = RegInit(false.B)
  private val done = RegInit(false.B)
  private val error = RegInit(false.B)
  private val irqEnable = RegInit(false.B)
  private val irqPending = RegInit(false.B)
  private val sawBusy = RegInit(false.B)

  private val id = ((deviceId & 0xffff) << 16 | (version & 0xffff)).U

  // ---------------------------------------------------------------------------
  // Register read (combinational) and write (registered) access.
  // ---------------------------------------------------------------------------
  private val rAddr = io.reg.req.bits.addr
  private val wFire = io.reg.req.fire && io.reg.req.bits.isWrite

  private val statusBits =
    Cat(0.U(29.W), error, done, busy)
  private val irqBits = Cat(0.U(30.W), irqPending, irqEnable)
  private val scanoutActive = scanoutControlReg(0) &&
    scanoutBaseReg.orR && scanoutStrideReg.orR &&
    scanoutWidthReg.orR && scanoutHeightReg.orR
  private val scanoutStatusBits = Cat(0.U(31.W), scanoutActive)

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
      RenderHostRegs.SCANOUT_BASE.U -> scanoutBaseReg,
      RenderHostRegs.SCANOUT_STRIDE.U -> scanoutStrideReg,
      RenderHostRegs.SCANOUT_WIDTH.U -> scanoutWidthReg,
      RenderHostRegs.SCANOUT_HEIGHT.U -> scanoutHeightReg,
      RenderHostRegs.SCANOUT_FORMAT.U -> scanoutFormatReg,
      RenderHostRegs.SCANOUT_CONTROL.U -> scanoutControlReg,
      RenderHostRegs.SCANOUT_STATUS.U -> scanoutStatusBits
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
    }
  }

  private val startWrite =
    wFire && rAddr === RenderHostRegs.CONTROL.U && io.reg.req.bits.data(0)
  private val launch = startWrite && !busy

  when(startWrite) {
    when(!busy) {
      busy := true.B
      done := false.B
      error := false.B
      sawBusy := false.B
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
    }
  }

  // Completion: the engine must be observed non-idle after launch before its
  // return to idle counts as done.  `core.done` is high before/at the launch
  // cycle (the command stage has not yet started), so the sawBusy guard stops
  // that pre-launch idle from being mistaken for completion.
  when(busy && !core.io.done) { sawBusy := true.B }
  when(busy && sawBusy && core.io.done) {
    busy := false.B
    done := true.B
    irqPending := true.B
  }

  // Tie the engine to the latched configuration.
  core.io.cmdBase := activeCmdBase
  core.io.cmdCount := activeCmdCount(15, 0)
  core.io.colorBase := activeColorBase
  core.io.depthBase := activeDepthBase
  core.io.stride := activeStride
  core.io.depthTestEnable := activeDepthTestEnable(0)
  core.io.depthFunc := activeDepthFunc(2, 0)
  core.io.depthWriteEnable := activeDepthWriteEnable(0)
  core.io.cullMode := activeCullMode(1, 0)
  core.io.texEnable := activeTexConfig(8)
  core.io.texBase := activeTexBase
  core.io.texWidth := activeTexWidth(13, 0)
  core.io.texHeight := activeTexHeight(13, 0)
  core.io.texWrapClamp := activeTexConfig(0)
  core.io.start := launch

  core.io.cbMem.req <> io.cbMem.req
  io.cbMem.resp <> core.io.cbMem.resp
  core.io.fbMem.req <> io.fbMem.req
  io.fbMem.resp <> core.io.fbMem.resp
  io.kernelMemReq <> core.io.kernelMemReq
  core.io.kernelMemResp <> io.kernelMemResp
  io.kernelWordMemReq <> core.io.kernelWordMemReq
  core.io.kernelWordMemResp <> io.kernelWordMemResp
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
