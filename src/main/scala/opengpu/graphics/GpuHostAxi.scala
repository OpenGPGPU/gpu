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

/** AXI4 slave control interface for the graphics renderer.
  *
  * This is the M6 host-facing port that ARTI (the RTL-to-QEMU integration
  * framework) can auto-bridge: it exposes the standard AXI4 memory-mapped
  * write and read channels (s_axi_aw*, s_axi_w*, s_axi_b*, s_axi_ar*,
  * s_axi_r*), plus the single-clock / active-low-reset infrastructure and an
  * interrupt output (m_irq), so ARTI's naming-based inferencer detects an
  * AXI4 slave, maps the registers, and generates the embedded QEMU device
  * model and device-tree node.  Internally it forwards every access to
  * `RenderHost`'s register file and forwards the renderer's shared-memory
  * ports unchanged.
  *
  * AXI4 bursts are handled word-at-a-time (32-bit data, size = 2): a write
  * burst issues one register write per beat at awaddr + beat*4 (INCR; WRAP/FIXED
  * fall back to the base address), and a read burst returns one word per beat.
  * Unaligned or out-of-map accesses are acknowledged with SLVERR (RRESP/BRESP = 2'b10); successful accesses
  * return `OKAY`.  The register file is single-ported, so at most one AXI
  * transaction (read or write) is in flight at a time; the handshakes
  * backpressure the host accordingly.
  */
class GpuHostAxi(
  config: GraphicsConfig = GraphicsConfig(),
  gpuConfig: GpuConfig = GpuConfig(),
  fragCore: Boolean = false,
  deviceId: Int = 0x4755,
  version: Int = 0x0001
) extends Module {
  override def desiredName: String = "GpuHostAxi"

  val io = IO(new Bundle {
    val s_axi_aclk = Input(Clock())
    val s_axi_aresetn = Input(Bool())

    val s_axi_awaddr = Input(UInt(32.W))
    val s_axi_awlen = Input(UInt(8.W))
    val s_axi_awsize = Input(UInt(3.W))
    val s_axi_awburst = Input(UInt(2.W))
    val s_axi_awvalid = Input(Bool())
    val s_axi_awready = Output(Bool())

    val s_axi_wdata = Input(UInt(32.W))
    val s_axi_wstrb = Input(UInt(4.W))
    val s_axi_wlast = Input(Bool())
    val s_axi_wvalid = Input(Bool())
    val s_axi_wready = Output(Bool())

    val s_axi_bresp = Output(UInt(2.W))
    val s_axi_bvalid = Output(Bool())
    val s_axi_bready = Input(Bool())

    val s_axi_araddr = Input(UInt(32.W))
    val s_axi_arlen = Input(UInt(8.W))
    val s_axi_arsize = Input(UInt(3.W))
    val s_axi_arburst = Input(UInt(2.W))
    val s_axi_arvalid = Input(Bool())
    val s_axi_arready = Output(Bool())

    val s_axi_rdata = Output(UInt(32.W))
    val s_axi_rresp = Output(UInt(2.W))
    val s_axi_rlast = Output(Bool())
    val s_axi_rvalid = Output(Bool())
    val s_axi_rready = Input(Bool())

    /** Completion interrupt (asserted while IRQ.PENDING && IRQ.ENABLE). */
    val m_irq = Output(Bool())

    // Renderer shared-memory ports (command buffer + framebuffer words, and the
    // core-backed shader kernel's line/coherence side ports) pass straight
    // through so the SoC attaches them to the shared off-chip hierarchy.
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
  })

  withClockAndReset(clock, !io.s_axi_aresetn) {
    val host = Module(new RenderHost(config, gpuConfig, fragCore, deviceId, version))

    io.m_irq := host.io.irq
    host.io.cbMem.req <> io.cbMem.req
    io.cbMem.resp <> host.io.cbMem.resp
    host.io.fbMem.req <> io.fbMem.req
    io.fbMem.resp <> host.io.fbMem.resp
    io.kernelMemReq <> host.io.kernelMemReq
    host.io.kernelMemResp <> io.kernelMemResp
    io.kernelWordMemReq <> host.io.kernelWordMemReq
    host.io.kernelWordMemResp <> io.kernelWordMemResp
    host.io.kernelL1Invalidate <> io.kernelL1Invalidate
    io.kernelL1InvalidateDone <> host.io.kernelL1InvalidateDone
    io.kernelGlobalAtomicRequest <> host.io.kernelGlobalAtomicRequest
    host.io.kernelGlobalAtomicResponse <> io.kernelGlobalAtomicResponse

    val reg = host.io.reg
    reg.resp.ready := true.B
    reg.req.bits.isWrite := false.B
    reg.req.bits.data := 0.U
    reg.req.bits.strb := 0xf.U
    reg.req.bits.addr := 0.U
    reg.req.valid := false.B

    // -------------------------------------------------------------------------
    // One in-flight transaction.  The single-ported register file cannot serve
    // a read and a write simultaneously, so the read and write channel
    // addresses each require the other idle.
    // -------------------------------------------------------------------------
    val writeActive = RegInit(false.B)
    val readActive = RegInit(false.B)
    val addrReg = RegInit(0.U(32.W))
    val lenReg = RegInit(0.U(8.W))
    val sizeReg = RegInit(0.U(3.W))
    val burstReg = RegInit(0.U(2.W))
    val beat = RegInit(0.U(8.W))
    val bPending = RegInit(false.B)

    // INCR burst addressing: word-at-a-time, byte increment = 1<<sizeReg
    // (4 bytes for a 32-bit device, matching awsize/arsize = 2).  WRAP/FIXED
    // burst modes hold the base address constant.
    val beatAddr =
      Mux(burstReg === 0.U, addrReg + (beat << sizeReg), addrReg)
    val lastBeat = beat === lenReg
    val beatOk =
      (addrReg & 0x3.U) === 0.U && addrReg <= RenderHostRegs.CULL_MODE.U

    val writeBeat = writeActive && !bPending
    val wBeatFire = writeBeat && io.s_axi_wvalid && io.s_axi_wready
    val rBeatFire = readActive && io.s_axi_rvalid && io.s_axi_rready

    // Multiplex the single-ported register file between the read and write
    // channel (mutually exclusive by construction).
    reg.req.valid := Mux(readActive, true.B, Mux(writeBeat, io.s_axi_wvalid, false.B))
    reg.req.bits.isWrite := writeActive
    reg.req.bits.addr := beatAddr(9, 0)
    reg.req.bits.data := io.s_axi_wdata
    reg.req.bits.strb := io.s_axi_wstrb

    // ---------------------------------------------------------------------
    // Read channel.
    // ---------------------------------------------------------------------
    io.s_axi_arready := !writeActive && !readActive
    when(io.s_axi_arvalid && io.s_axi_arready && !writeActive) {
      readActive := true.B
      addrReg := io.s_axi_araddr
      lenReg := io.s_axi_arlen
      sizeReg := io.s_axi_arsize
      burstReg := io.s_axi_arburst
      beat := 0.U
    }

    io.s_axi_rvalid := readActive
    io.s_axi_rlast := readActive && lastBeat
    io.s_axi_rresp := Mux(beatOk, 0.U, 2.U)
    val rdata = WireDefault(0.U(32.W))
    when(readActive) {
      rdata := reg.resp.bits.data
      when(rBeatFire) {
        when(lastBeat) {
          readActive := false.B
        }.otherwise {
          beat := beat + 1.U
        }
      }
    }
    io.s_axi_rdata := rdata

    // ---------------------------------------------------------------------
    // Write channel.
    // ---------------------------------------------------------------------
    io.s_axi_awready := !writeActive && !readActive
    when(io.s_axi_awvalid && io.s_axi_awready && !readActive) {
      writeActive := true.B
      addrReg := io.s_axi_awaddr
      lenReg := io.s_axi_awlen
      sizeReg := io.s_axi_awsize
      burstReg := io.s_axi_awburst
      beat := 0.U
      bPending := false.B
    }
    io.s_axi_wready := writeBeat
    when(wBeatFire) {
      when(io.s_axi_wlast) {
        bPending := true.B
      }.otherwise {
        beat := beat + 1.U
      }
    }

    io.s_axi_bvalid := bPending
    io.s_axi_bresp := Mux(beatOk, 0.U, 2.U)
    when(io.s_axi_bvalid && io.s_axi_bready) {
      bPending := false.B
      writeActive := false.B
    }
  }
}
