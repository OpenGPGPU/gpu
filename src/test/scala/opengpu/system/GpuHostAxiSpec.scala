package opengpu.system

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import opengpu.config.GpuConfig
import opengpu.graphics.{GpuCommandMmioRegs, GraphicsConfig, RenderHostRegs}
import org.scalatest.flatspec.AnyFlatSpec

import scala.collection.mutable

/** Verifies the AXI4 host-control interface (`GpuHostAxi`).
  *
  * This is the port ARTI auto-bridges: the spec drives the standard AXI4
  * write/read channels (single-beat and INCR bursts), checks the register
  * returns SLVERR for unaligned/out-of-map addresses, and confirms a full
  * submission can be programmed through the bus and that the completion
  * interrupt fires once the renderer drains.
  */
class GpuHostAxiSpec extends AnyFlatSpec {
  behavior of "GpuHostAxi"

  private def q(v: Double): Int = (v * (1 << 16)).toInt

  private class MemModel {
    val words = mutable.LongMap[Int]()
    def word(a: Long): Long = words.getOrElse(a, 0) & 0xffffffffL
    def wwrite(a: Long, d: Int): Unit = words(a) = d & 0xffffffff
  }

  private def encode(
    tri: Seq[((Int, Int, Int, Int), (Int, Int, Int), Int)]
  ): Seq[Int] = {
    val w = Seq.newBuilder[Int]
    for (i <- 0 until 3) { w += tri(i)._1._1; w += tri(i)._1._2; w += tri(i)._1._3; w += tri(i)._1._4 }
    for (i <- 0 until 3) { w += tri(i)._2._1; w += tri(i)._2._2; w += tri(i)._2._3 }
    for (i <- 0 until 3) { w += tri(i)._3 }
    w += 0; w += 0
    for (_ <- 0 until 6) { w += 0 } // uv0..uv2
    for (_ <- 0 until 8) { w += 0 } // state override + reserved
    w.result()
  }

  // ---------------------------------------------------------------------------
  // AXI4 single-beat write: drives AW + W (WLAST=1) and consumes B.
  // ---------------------------------------------------------------------------
  private def axiWrite(dut: GpuHostAxi, addr: Int, data: Int): Unit = {
    dut.io.s_axi_awvalid.poke(true.B)
    dut.io.s_axi_awaddr.poke(addr.U)
    dut.io.s_axi_awlen.poke(0.U)
    dut.io.s_axi_awsize.poke(2.U)
    dut.io.s_axi_awburst.poke(0.U)
    dut.io.s_axi_wvalid.poke(true.B)
    dut.io.s_axi_wdata.poke(data.U)
    dut.io.s_axi_wstrb.poke(0xf.U)
    dut.io.s_axi_wlast.poke(true.B)
    dut.io.s_axi_bready.poke(true.B)
    var awDone = false; var wDone = false; var bDone = false
    var guard = 0
    while (!bDone && guard < 64) {
      if (!awDone && dut.io.s_axi_awvalid.peek().litToBoolean &&
          dut.io.s_axi_awready.peek().litToBoolean) awDone = true
      if (!wDone && dut.io.s_axi_wvalid.peek().litToBoolean &&
          dut.io.s_axi_wready.peek().litToBoolean) wDone = true
      if (dut.io.s_axi_bvalid.peek().litToBoolean &&
          dut.io.s_axi_bready.peek().litToBoolean) bDone = true
      dut.clock.step()
      if (awDone) dut.io.s_axi_awvalid.poke(false.B)
      if (wDone) dut.io.s_axi_wvalid.poke(false.B)
      if (bDone) dut.io.s_axi_bready.poke(false.B)
      guard += 1
    }
    assert(bDone, s"AXI write to 0x$addr%x did not complete")
    dut.io.s_axi_awvalid.poke(false.B)
    dut.io.s_axi_wvalid.poke(false.B)
    dut.io.s_axi_wlast.poke(false.B)
    dut.io.s_axi_bready.poke(false.B)
  }

  // ---------------------------------------------------------------------------
  // AXI4 single-beat read: drives AR, consumes R, returns the word.
  // ---------------------------------------------------------------------------
  private def axiRead(dut: GpuHostAxi, addr: Int): BigInt = {
    dut.io.s_axi_arvalid.poke(true.B)
    dut.io.s_axi_araddr.poke(addr.U)
    dut.io.s_axi_arlen.poke(0.U)
    dut.io.s_axi_arsize.poke(2.U)
    dut.io.s_axi_arburst.poke(0.U)
    dut.io.s_axi_rready.poke(true.B)
    var arDone = false; var rDone = false
    var data = BigInt(0); var resp = BigInt(0)
    var guard = 0
    while (!rDone && guard < 64) {
      if (!arDone && dut.io.s_axi_arvalid.peek().litToBoolean &&
          dut.io.s_axi_arready.peek().litToBoolean) arDone = true
      if (dut.io.s_axi_rvalid.peek().litToBoolean &&
          dut.io.s_axi_rready.peek().litToBoolean) {
        data = dut.io.s_axi_rdata.peek().litValue
        resp = dut.io.s_axi_rresp.peek().litValue
        rDone = true
      }
      dut.clock.step()
      if (arDone) dut.io.s_axi_arvalid.poke(false.B)
      guard += 1
    }
    assert(rDone, s"AXI read of 0x$addr%x did not complete")
    respVar = resp
    dut.io.s_axi_arvalid.poke(false.B)
    dut.io.s_axi_rready.poke(false.B)
    data
  }

  /** Records the RRESP/BRESP of the last access. */
  private var respVar = BigInt(0)

  // ---------------------------------------------------------------------------
  // AXI4 INCR burst write of `words` (len = words-1) starting at addr.
  // ---------------------------------------------------------------------------
  private def axiWriteBurst(dut: GpuHostAxi, addr: Int, words: Seq[Int]): Unit = {
    val len = words.length - 1
    dut.io.s_axi_awvalid.poke(true.B)
    dut.io.s_axi_awaddr.poke(addr.U)
    dut.io.s_axi_awlen.poke(len.U)
    dut.io.s_axi_awsize.poke(2.U)
    dut.io.s_axi_awburst.poke(0.U)
    dut.io.s_axi_bready.poke(true.B)
    var awDone = false
    var idx = 0
    var bDone = false
    var guard = 0
    while (!bDone && guard < 256) {
      dut.io.s_axi_wvalid.poke(true.B)
      dut.io.s_axi_wdata.poke(words(idx).U)
      dut.io.s_axi_wstrb.poke(0xf.U)
      dut.io.s_axi_wlast.poke((idx == len).B)
      if (!awDone && dut.io.s_axi_awvalid.peek().litToBoolean &&
          dut.io.s_axi_awready.peek().litToBoolean) awDone = true
      if (dut.io.s_axi_wvalid.peek().litToBoolean &&
          dut.io.s_axi_wready.peek().litToBoolean) {
        if (idx != len) idx += 1
      }
      if (dut.io.s_axi_bvalid.peek().litToBoolean &&
          dut.io.s_axi_bready.peek().litToBoolean) bDone = true
      dut.clock.step()
      if (awDone) dut.io.s_axi_awvalid.poke(false.B)
      if (bDone) dut.io.s_axi_bready.poke(false.B)
      guard += 1
    }
    assert(bDone, s"AXI burst write to 0x$addr%x did not complete")
    dut.io.s_axi_awvalid.poke(false.B)
    dut.io.s_axi_wvalid.poke(false.B)
    dut.io.s_axi_wlast.poke(false.B)
    dut.io.s_axi_bready.poke(false.B)
  }

  it should "program the register file through the AXI4 channels and return SLVERR on bad reads" in {
    simulate(new GpuHostAxi(deviceId = 0x4755, version = 0x0001)) { dut =>
      dut.io.s_axi_aresetn.poke(false.B)
      dut.clock.step()
      dut.io.s_axi_aresetn.poke(true.B)
      dut.clock.step()

      assert(axiRead(dut, RenderHostRegs.ID) == 0x47550001L,
        "device ID must read back through AXI4")
      assert(axiRead(dut, RenderHostRegs.CAPABILITIES) == 0x2a20b8L,
        "fixed-function builds must advertise the DMA engines, persistent depth and MSAA but not fragment-core execution")

      // Unaligned read -> SLVERR.
      axiRead(dut, 0x11)
      assert(respVar == 2L, s"unaligned read must return SLVERR, got RRESP=$respVar")

      // Out-of-map read -> SLVERR.
      axiRead(dut, 0x200)
      assert(respVar == 2L, s"out-of-map read must return SLVERR, got RRESP=$respVar")

      // END is past the end of the whole register map.
      axiRead(dut, GpuCommandMmioRegs.END)
      assert(respVar == 2L, s"read past END must return SLVERR, got RRESP=$respVar")

      // MSAA_CONFIG is routed to RenderHost at 0x134.
      axiWrite(dut, RenderHostRegs.MSAA_CONFIG, 2)
      assert(axiRead(dut, RenderHostRegs.MSAA_CONFIG) == 2L,
        "MSAA_CONFIG must round-trip through the AXI4 register path")

      // The stencil/blend registers round-trip through the AXI4 register path.
      axiWrite(dut, RenderHostRegs.STENCIL_CONFIG, 0x1055)
      assert(axiRead(dut, RenderHostRegs.STENCIL_CONFIG) == 0x1055L,
        "STENCIL_CONFIG must round-trip through the AXI4 register path")
      axiWrite(dut, RenderHostRegs.STENCIL_REF_MASKS, 0x00ff5aa5)
      assert(axiRead(dut, RenderHostRegs.STENCIL_REF_MASKS) == 0x00ff5aa5L,
        "STENCIL_REF_MASKS must round-trip through the AXI4 register path")
      axiWrite(dut, RenderHostRegs.BLEND_CONFIG, 0x2111)
      assert(axiRead(dut, RenderHostRegs.BLEND_CONFIG) == 0x2111L,
        "BLEND_CONFIG must round-trip through the AXI4 register path")

      // Program and read back a few registers via single-beat AXis.
      axiWrite(dut, RenderHostRegs.CMD_BASE, 0x4000)
      axiWrite(dut, RenderHostRegs.CMD_COUNT, 1)
      axiWrite(dut, RenderHostRegs.COLOR_BASE, 0x8000)
      axiWrite(dut, RenderHostRegs.STRIDE, 64)
      axiWrite(dut, RenderHostRegs.SCANOUT_BASE, 0xb000)
      axiWrite(dut, RenderHostRegs.SCANOUT_STRIDE, 128)
      axiWrite(dut, RenderHostRegs.SCANOUT_WIDTH, 16)
      axiWrite(dut, RenderHostRegs.SCANOUT_HEIGHT, 16)
      axiWrite(dut, RenderHostRegs.SCANOUT_CONTROL, 1)
      assert(axiRead(dut, RenderHostRegs.CMD_BASE) == 0x4000L)
      assert(axiRead(dut, RenderHostRegs.CMD_COUNT) == 1L)
      assert(axiRead(dut, RenderHostRegs.COLOR_BASE) == 0x8000L)
      assert(axiRead(dut, RenderHostRegs.STRIDE) == 64L)
      assert(axiRead(dut, RenderHostRegs.SCANOUT_BASE) == 0xb000L)
      assert(axiRead(dut, RenderHostRegs.SCANOUT_STRIDE) == 128L)
      assert(axiRead(dut, RenderHostRegs.SCANOUT_WIDTH) == 16L)
      assert(axiRead(dut, RenderHostRegs.SCANOUT_HEIGHT) == 16L)
      assert(axiRead(dut, RenderHostRegs.SCANOUT_STATUS) == 1L)
      assert((axiRead(dut, RenderHostRegs.STATUS) & 0x3) == 0L)
    }
  }

  it should "route the unified reset register over AXI and raise the completion IRQ" in {
    simulate(new GpuHostAxi(deviceId = 0x4755, version = 0x0001,
      unifiedCommandMmio = true)) { dut =>
      dut.io.s_axi_aresetn.poke(false.B)
      dut.clock.step()
      dut.io.s_axi_aresetn.poke(true.B)
      dut.clock.step()
      dut.io.commandResetDone.get.poke(false.B)

      // Unified builds advertise the safe reset capability (bit 18).
      assert((axiRead(dut, RenderHostRegs.CAPABILITIES) & (1 << 18)) != 0,
        "unified builds must advertise GPU_CAP_UNIFIED_RESET")

      // RESET lives at 0x138, inside the extended map but outside RenderHost.
      axiWrite(dut, RenderHostRegs.IRQ, 1)
      axiWrite(dut, GpuCommandMmioRegs.RESET, 1)
      dut.io.commandResetActive.get.expect(true.B)

      // The system's drain acknowledgement ends the request and rings the IRQ.
      dut.io.commandResetDone.get.poke(true.B)
      dut.clock.step()
      dut.io.commandResetDone.get.poke(false.B)
      dut.io.commandResetActive.get.expect(false.B)
      dut.io.m_irq.expect(true.B)
    }
  }

  it should "clear a memory range through the hardware fill engine" in {
    val m = new MemModel
    simulate(new GpuHostAxi(deviceId = 0x4755, version = 0x0001)) { dut =>
      dut.io.s_axi_aresetn.poke(true.B)
      dut.io.s_axi_awvalid.poke(false.B)
      dut.io.s_axi_wvalid.poke(false.B)
      dut.io.s_axi_bready.poke(false.B)
      dut.io.s_axi_arvalid.poke(false.B)
      dut.io.s_axi_rready.poke(false.B)
      dut.io.cbMem.req.ready.poke(true.B)
      dut.io.cbMem.resp.valid.poke(false.B)
      dut.io.fbMem.req.ready.poke(true.B)
      dut.io.fbMem.resp.valid.poke(false.B)
      dut.io.texMem.req.ready.poke(true.B)
      dut.io.texMem.resp.valid.poke(false.B)
      dut.io.kernelMemReq.ready.poke(true.B)
      dut.io.kernelMemResp.valid.poke(false.B)
      dut.io.kernelMemResp.bits.readData.poke(0.U)
      dut.io.kernelMemResp.bits.fault.poke(false.B)
      dut.io.kernelMemResp.bits.transactionId.poke(0.U)
      dut.io.kernelWordMemReq.ready.poke(true.B)
      dut.io.kernelWordMemResp.valid.poke(false.B)
      dut.io.kernelWordMemResp.bits.readData.poke(0.U)
      dut.io.kernelWordMemResp.bits.fault.poke(false.B)
      dut.io.kernelWordMemResp.bits.transactionId.poke(0.U)
      dut.io.s_axi_aresetn.poke(false.B)
      dut.clock.step()
      dut.io.s_axi_aresetn.poke(true.B)
      dut.clock.step()

      assert((axiRead(dut, RenderHostRegs.CAPABILITIES) & (1 << 3)) != 0L,
        "CAPABILITIES must report the clear engine")

      val base = 0x9000L
      val bytes = 128 // two 64-byte lines
      // Pre-poison the range and its neighbours so the test observes the
      // clear and proves the engine did not overshoot.
      for (w <- -1 until (bytes / 4 + 1).toInt)
        m.wwrite(base + w * 4L, 0xdeadbeef)

      axiWrite(dut, RenderHostRegs.CLEAR_BASE, base.toInt)
      axiWrite(dut, RenderHostRegs.CLEAR_BYTES, bytes)
      axiWrite(dut, RenderHostRegs.CLEAR_PATTERN, 0x12345678)
      // Hold the line port closed across the START write so the engine's
      // first request fires inside the serviced loop (no lost acknowledgement).
      dut.io.kernelWordMemReq.ready.poke(false.B)
      axiWrite(dut, RenderHostRegs.CLEAR_START, 1)

      // Service the shared kernelWordMem line port while the engine runs:
      // writes apply at fire (word-granular through the byte mask) and the
      // acknowledgement echoes the request's transaction id, presented from
      // the cycle after the fire (one cycle of memory latency).
      val ackQ = mutable.Queue.empty[BigInt]
      val ackPending = mutable.Queue.empty[BigInt]
      val expectedLines = bytes / 64
      var fires = 0
      var guard = 0
      while ((fires < expectedLines || ackQ.nonEmpty || ackPending.nonEmpty) &&
        guard < 2000) {
        dut.io.kernelWordMemReq.ready.poke(true.B)
        // Responses are presented for ONE cycle only; re-presenting a stale
        // acknowledgement across the STATUS read's clock step would feed the
        // engine an already-consumed transaction id.
        dut.io.kernelWordMemResp.valid.poke(false.B)
        // Promote LAST cycle's captures before capturing this cycle's fire,
        // so a response is presented no earlier than one cycle after its
        // request fired (the fill only expects it from that cycle on).
        while (ackPending.nonEmpty) ackQ.enqueue(ackPending.dequeue())
        if (dut.io.kernelWordMemReq.valid.peek().litToBoolean &&
            dut.io.kernelWordMemReq.ready.peek().litToBoolean) {
          val a = dut.io.kernelWordMemReq.bits.address.peek().litValue.toLong
          val wd = dut.io.kernelWordMemReq.bits.writeData.peek().litValue
          val bm = dut.io.kernelWordMemReq.bits.byteMask.peek().litValue
          for (w <- 0 until 16) {
            if (((bm >> (w * 4)) & 0xfL) != 0L)
              m.wwrite(a + w * 4L, ((wd >> (w * 32)) & 0xffffffffL).toInt)
          }
          ackPending.enqueue(dut.io.kernelWordMemReq.bits.transactionId.peek().litValue)
          fires += 1
        }
        if (ackQ.nonEmpty) {
          dut.io.kernelWordMemResp.valid.poke(true.B)
          dut.io.kernelWordMemResp.bits.fault.poke(false.B)
          dut.io.kernelWordMemResp.bits.transactionId.poke(ackQ.head.U)
          if (dut.io.kernelWordMemResp.ready.peek().litToBoolean) ackQ.dequeue()
        }
        dut.clock.step()
        dut.io.kernelWordMemResp.valid.poke(false.B)
        dut.io.kernelWordMemReq.ready.poke(false.B)
        guard += 1
      }
      assert(fires == expectedLines && ackQ.isEmpty && ackPending.isEmpty,
        s"hardware clear did not drain: fires=$fires acks=${ackQ.size}")
      dut.io.kernelWordMemReq.ready.poke(false.B)
      // Let the fill engine retire its completion entry (busy falls one
      // cycle after the last acknowledgement is consumed).
      dut.clock.step(2)
      assert((axiRead(dut, RenderHostRegs.STATUS) & (1 << 3)) == 0L,
        "STATUS must report the clear engine idle after completion")

      for (w <- 0 until (bytes / 4).toInt)
        assert(m.word(base + w * 4L) == 0x12345678L,
          f"clear left word $w as 0x${m.word(base + w * 4L)}%08x")
      // Nothing outside the range may be touched.
      assert(m.word(base - 4L) == 0xdeadbeefL, "clear under-ran the range")
      assert(m.word(base + bytes) == 0xdeadbeefL, "clear over-ran the range")
    }
  }
}
