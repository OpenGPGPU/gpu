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
  val END               = 0x88
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
  /** bit0: fragments execute on the shared SIMT core. */
  val CAPABILITIES      = 0x60
  /** Job submission ring (host memory): base byte address of the entries. */
  val JOB_RING_BASE     = 0x64
  /** Job ring entry count (power of two). 0 keeps the queue disabled. */
  val JOB_RING_SIZE     = 0x68
  /** Host doorbell: index of the next free job-ring entry. */
  val JOB_WPTR          = 0x6C
  /** Device read pointer (RO): next job-ring entry to fetch. */
  val JOB_RPTR          = 0x70
  /** bit0 ENABLE (RW), bit1 RESET (w1p), bit8 ACTIVE (ro), bit9 PENDING (ro). */
  val JOB_CONTROL       = 0x74
  /** Interrupt-history ring (host memory): base byte address of the records. */
  val IH_BASE           = 0x78
  /** IH ring record count (power of two). */
  val IH_SIZE           = 0x7C
  /** Device write pointer (RO): next IH record the engine will write. */
  val IH_WPTR           = 0x80
  /** Host read pointer (RW, informational): next IH record to drain. */
  val IH_RPTR           = 0x84
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
   * CAPABILITIES at 0x60, and the hardware job queue / interrupt-history
   * (IH) ring programming at 0x64..0x84.
   *
   * The job queue (AMDGPU-style) fetches job descriptors from a host-memory
   * ring behind JOB_RING_BASE/JOB_RING_SIZE, launched whenever the engine is
   * idle and the host has rung the JOB_WPTR doorbell; on completion it writes
   * an IH record (job id, ring slot, status) into the host-memory ring behind
   * IH_BASE/IH_SIZE and then raises the same completion interrupt. Legacy
   * register-programmed START submissions remain fully functional.
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

  // Job queue / IH ring programming (host-written; device exposes RPTR/WPTR).
  private val jobRingBaseReg = RegInit(0.U(32.W))
  private val jobRingSizeReg = RegInit(0.U(32.W))
  private val jobWptrReg = RegInit(0.U(32.W))
  private val jobEnableReg = RegInit(false.B)
  private val ihBaseReg = RegInit(0.U(32.W))
  private val ihSizeReg = RegInit(0.U(32.W))
  private val ihRptrReg = RegInit(0.U(32.W))
  private val jqResetPulse = RegInit(false.B)

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
  /** True while the running job was launched by the hardware job queue. */
  private val ownerQueue = RegInit(false.B)
  /** One-cycle pulse telling the queue its launched job has completed. */
  private val jqDonePulse = RegInit(false.B)

  private val id = ((deviceId & 0xffff) << 16 | (version & 0xffff)).U

  // ---------------------------------------------------------------------------
  // Hardware job queue: fetches descriptors from the host-memory ring and
  // writes IH records into a second host-memory ring before raising IRQs.
  // ---------------------------------------------------------------------------
  private val jq = Module(new JobQueue)
  private val jqEnabled = jobEnableReg && jobRingSizeReg.orR &&
    jobRingBaseReg.orR && ihSizeReg.orR && ihBaseReg.orR
  jq.io.enable := jqEnabled
  jq.io.ringBase := jobRingBaseReg
  jq.io.ringMask := (jobRingSizeReg - 1.U)(15, 0)
  jq.io.hostWptr := jobWptrReg(15, 0)
  jq.io.ihBase := ihBaseReg
  jq.io.ihMask := (ihSizeReg - 1.U)(15, 0)
  jq.io.reset := jqResetPulse

  // ---------------------------------------------------------------------------
  // Register read (combinational) and write (registered) access.
  // ---------------------------------------------------------------------------
  private val rAddr = io.reg.req.bits.addr
  private val wFire = io.reg.req.fire && io.reg.req.bits.isWrite
  private val startWrite =
    wFire && rAddr === RenderHostRegs.CONTROL.U && io.reg.req.bits.data(0)

  // Queue launches are held off when a legacy START pulses the same cycle.
  jq.io.launchReady := !busy && !startWrite

  private val statusBits =
    Cat(0.U(29.W), error, done, busy)
  private val irqBits = Cat(0.U(30.W), irqPending, irqEnable)
  private val scanoutActive = scanoutControlReg(0) &&
    scanoutBaseReg.orR && scanoutStrideReg.orR &&
    scanoutWidthReg.orR && scanoutHeightReg.orR
  private val scanoutStatusBits = Cat(0.U(31.W), scanoutActive)
  private val capabilityBits =
    ((if (fragCore) 1 else 0) | (1 << 1) |
      (gpuConfig.warps * gpuConfig.lanes << 8)).U(32.W)
  private val jobStatusBits = Cat(
    0.U(22.W), jq.io.pendingValid, jq.io.running, 0.U(7.W), jqEnabled)

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
      RenderHostRegs.SCANOUT_STATUS.U -> scanoutStatusBits,
      RenderHostRegs.CAPABILITIES.U -> capabilityBits,
      RenderHostRegs.JOB_RING_BASE.U -> jobRingBaseReg,
      RenderHostRegs.JOB_RING_SIZE.U -> jobRingSizeReg,
      RenderHostRegs.JOB_WPTR.U -> jobWptrReg,
      RenderHostRegs.JOB_RPTR.U -> Cat(0.U(16.W), jq.io.rptr),
      RenderHostRegs.JOB_CONTROL.U -> jobStatusBits,
      RenderHostRegs.IH_BASE.U -> ihBaseReg,
      RenderHostRegs.IH_SIZE.U -> ihSizeReg,
      RenderHostRegs.IH_WPTR.U -> Cat(0.U(16.W), jq.io.ihWptr),
      RenderHostRegs.IH_RPTR.U -> ihRptrReg
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
      is(RenderHostRegs.JOB_RING_BASE.U) {
        jobRingBaseReg := merge(jobRingBaseReg, io.reg.req.bits.data, io.reg.req.bits.strb)
      }
      is(RenderHostRegs.JOB_RING_SIZE.U) {
        jobRingSizeReg := merge(jobRingSizeReg, io.reg.req.bits.data, io.reg.req.bits.strb)
      }
      is(RenderHostRegs.JOB_WPTR.U) {
        // Doorbell: the queue compares its (masked) read pointer against this.
        jobWptrReg := merge(jobWptrReg, io.reg.req.bits.data, io.reg.req.bits.strb)
      }
      is(RenderHostRegs.JOB_CONTROL.U) {
        jobEnableReg := io.reg.req.bits.data(0)
        when(io.reg.req.bits.data(1)) { jqResetPulse := true.B }
      }
      is(RenderHostRegs.IH_BASE.U) {
        ihBaseReg := merge(ihBaseReg, io.reg.req.bits.data, io.reg.req.bits.strb)
      }
      is(RenderHostRegs.IH_SIZE.U) {
        ihSizeReg := merge(ihSizeReg, io.reg.req.bits.data, io.reg.req.bits.strb)
      }
      is(RenderHostRegs.IH_RPTR.U) {
        ihRptrReg := merge(ihRptrReg, io.reg.req.bits.data, io.reg.req.bits.strb)
      }
    }
  }

  // The reset pulse lasts a single cycle.
  jqResetPulse := false.B
  when(wFire && rAddr === RenderHostRegs.JOB_CONTROL.U &&
      io.reg.req.bits.data(1)) {
    jqResetPulse := true.B
  }

  private val launch = startWrite && !busy

  when(startWrite) {
    when(!busy) {
      busy := true.B
      done := false.B
      error := false.B
      sawBusy := false.B
      ownerQueue := false.B
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

  // Queue-initiated launches are accepted when the engine is idle and no
  // legacy START pulses in the same cycle (legacy has priority).
  when(jq.io.launch && jq.io.launchReady) {
    busy := true.B
    done := false.B
    error := false.B
    sawBusy := false.B
    ownerQueue := true.B
  }

  // Completion: the engine must be observed non-idle after launch before its
  // return to idle counts as done.  `core.done` is high before/at the launch
  // cycle (the command stage has not yet started), so the sawBusy guard stops
  // that pre-launch idle from being mistaken for completion.
  jqDonePulse := false.B
  when(busy && !core.io.done) { sawBusy := true.B }
  when(busy && sawBusy && core.io.done) {
    busy := false.B
    done := true.B
    when(ownerQueue) {
      jqDonePulse := true.B
    }.otherwise {
      irqPending := true.B
    }
  }
  jq.io.done := jqDonePulse
  // Queue completions become interrupt-visible only after all IH words have
  // completed and JobQueue has advanced IH_WPTR.  Raising IRQ at core.done
  // races the handler against the still-empty IH ring.
  when(jq.io.ihCommitted) { irqPending := true.B }

  // Tie the engine to the latched configuration: legacy register snapshots
  // when the host programmed START, the queue's descriptor snapshot when a
  // queued job was accepted.
  private def muxActive[T <: Data](legacy: T, queued: T): T =
    Mux(ownerQueue, queued, legacy)
  core.io.cmdBase := muxActive(activeCmdBase, jq.io.cfg.cmdBase)
  core.io.cmdCount := muxActive(activeCmdCount(15, 0), jq.io.cfg.cmdCount)
  core.io.colorBase := muxActive(activeColorBase, jq.io.cfg.colorBase)
  core.io.depthBase := muxActive(activeDepthBase, jq.io.cfg.depthBase)
  core.io.stride := muxActive(activeStride, jq.io.cfg.stride)
  core.io.depthTestEnable := muxActive(
    activeDepthTestEnable(0), jq.io.cfg.depthTestEnable)
  core.io.depthFunc := muxActive(activeDepthFunc(2, 0), jq.io.cfg.depthFunc)
  core.io.depthWriteEnable := muxActive(
    activeDepthWriteEnable(0), jq.io.cfg.depthWriteEnable)
  core.io.cullMode := muxActive(activeCullMode(1, 0), jq.io.cfg.cullMode)
  core.io.texEnable := muxActive(activeTexConfig(8), jq.io.cfg.texEnable)
  core.io.texBase := muxActive(activeTexBase, jq.io.cfg.texBase)
  core.io.texWidth := muxActive(activeTexWidth(13, 0), jq.io.cfg.texWidth)
  core.io.texHeight := muxActive(activeTexHeight(13, 0), jq.io.cfg.texHeight)
  core.io.texWrapClamp := muxActive(
    activeTexConfig(0), jq.io.cfg.texWrapClamp)
  core.io.texMaxLevel := muxActive(
    activeTexConfig(5, 2), jq.io.cfg.texMaxLevel)
  core.io.start := launch || (jq.io.launch && jq.io.launchReady)

  // Command-buffer memory port arbitration. The queue's admin agent
  // (descriptor fetches, IH record writes) and the engine's command stage
  // share the single word port. At most one request is outstanding, and the
  // response is routed back to the agent that owns it. The admin agent has
  // priority; its bursts are short (16-word fetch, 4-word IH record), so the
  // engine's command stream only ever pauses for a few cycles — and during a
  // job the fetch overlaps rendering instead of serializing in front of it.
  private val admin = jq.io.mem
  private val cbPort = core.io.cbMem
  private val occupied = RegInit(false.B) // a granted request awaits its response
  private val adminOwner = RegInit(false.B) // owner of the outstanding request
  private val adminGrant = !occupied && admin.req.valid
  private val cbGrant = !occupied && !admin.req.valid && cbPort.req.valid
  // A grant only becomes a transaction when the shared port's consumer
  // accepts the beat; until then the requesting agent stays backpressured.
  private val extFire = io.cbMem.req.valid && io.cbMem.req.ready
  when(adminGrant && io.cbMem.req.ready) { adminOwner := true.B }
  when(cbGrant && io.cbMem.req.ready) { adminOwner := false.B }
  val respFire = io.cbMem.resp.valid && io.cbMem.resp.ready
  occupied := Mux(respFire, extFire, occupied || extFire)

  io.cbMem.req.valid := (adminGrant && admin.req.valid) ||
    (cbGrant && cbPort.req.valid)
  io.cbMem.req.bits := Mux(adminGrant && admin.req.valid,
    admin.req.bits, cbPort.req.bits)
  admin.req.ready := adminGrant && io.cbMem.req.ready
  cbPort.req.ready := cbGrant && io.cbMem.req.ready

  admin.resp.valid := occupied && adminOwner && io.cbMem.resp.valid
  admin.resp.bits := io.cbMem.resp.bits
  cbPort.resp.valid := occupied && !adminOwner && io.cbMem.resp.valid
  cbPort.resp.bits := io.cbMem.resp.bits
  // Only the owner of the outstanding request can accept its response; the
  // other agent's ready may be low (e.g. the command stage only raises it
  // while one of its own reads is in flight).
  io.cbMem.resp.ready := Mux(adminOwner, admin.resp.ready, cbPort.resp.ready)

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
