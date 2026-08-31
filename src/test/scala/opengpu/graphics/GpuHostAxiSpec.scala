package opengpu.graphics

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import opengpu.config.GpuConfig
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
      assert(axiRead(dut, RenderHostRegs.CAPABILITIES) == 0x2002L,
        "fixed-function builds must advertise the job queue but not fragment-core execution")

      // Unaligned read -> SLVERR.
      axiRead(dut, 0x11)
      assert(respVar == 2L, s"unaligned read must return SLVERR, got RRESP=$respVar")

      // Out-of-map read -> SLVERR.
      axiRead(dut, 0x200)
      assert(respVar == 2L, s"out-of-map read must return SLVERR, got RRESP=$respVar")

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

  it should "accept a full INCR burst and drive an end-to-end draw until the interrupt" in {
    val config = GraphicsConfig(screenWidth = 16, screenHeight = 16, subPixelBits = 8)
    val cfg = GpuConfig(lanes = 4, warps = 2)
    val stride = 16 * 4
    val colorBase = 0x8000
    val depthBase = 0x9000
    val cmdBase = 0x4000

    def tri(x: Int, y: Int, d: Int) = ((x, y, 0, q(1.0)), (255, 0, 0), d)
    val record = Seq(
      tri(q(-1.0), q(-1.0), 0x10),
      tri(q(1.0), q(-1.0), 0x10),
      tri(q(-1.0), q(1.0), 0x10)
    )
    val m = new MemModel
    encode(record).zipWithIndex.foreach { case (w, i) => m.wwrite(cmdBase + i * 4, w) }
    for (i <- 0 until (16 * 16)) m.wwrite(depthBase + i * 4, 0xffffffff)

    simulate(new GpuHostAxi(config, cfg, fragCore = false)) { dut =>
      dut.io.s_axi_aresetn.poke(false.B)
      dut.clock.step()
      dut.io.s_axi_aresetn.poke(true.B)
      dut.clock.step()

      // Program the full register set in ONE INCR burst over 0x10..0x30
      // (contiguous, 4-byte spaced), exercising AXI4 burst support.
      axiWriteBurst(dut, RenderHostRegs.CMD_BASE, Seq(
        0x4000, // CMD_BASE
        0x1,    // CMD_COUNT
        0x8000, // COLOR_BASE
        0x9000, // DEPTH_BASE
        stride, // STRIDE
        0x1,    // DEPTH_TEST_ENABLE
        0x0,    // DEPTH_FUNC
        0x1,    // DEPTH_WRITE_ENABLE
        0x0     // CULL_MODE
      ))
      // Enable the completion interrupt.
      axiWrite(dut, RenderHostRegs.IRQ, 1)

      assert(axiRead(dut, RenderHostRegs.CMD_BASE) == 0x4000L)
      assert(axiRead(dut, RenderHostRegs.CMD_COUNT) == 1L)
      assert(axiRead(dut, RenderHostRegs.COLOR_BASE) == 0x8000L)
      assert(axiRead(dut, RenderHostRegs.DEPTH_BASE) == 0x9000L)
      assert(axiRead(dut, RenderHostRegs.STRIDE) == 64L)

      // Service the renderer's word ports.  Keep the request `ready` low until
      // the service loop starts so the renderer backpressures any early command
      // fetch rather than issuing a read that would be accepted and then lost
      // during the AXI CONTROL handshake's extra cycles.
      dut.io.cbMem.req.ready.poke(false.B)
      dut.io.cbMem.resp.valid.poke(false.B)
      dut.io.fbMem.req.ready.poke(false.B)
      dut.io.fbMem.resp.valid.poke(false.B)
      dut.io.kernelMemReq.ready.poke(true.B)
      dut.io.kernelMemResp.valid.poke(false.B)
      dut.io.kernelWordMemReq.ready.poke(true.B)
      dut.io.kernelWordMemResp.valid.poke(false.B)

      // Kick the engine over the bus.
      axiWrite(dut, RenderHostRegs.CONTROL, 1)

      // Robust request/response service: track the one outstanding read per
      // port so a request that fired during the AXI CONTROL handshake is still
      // answered even though its request beat has already been accepted.
      var cbPend = -1L
      var fbPend = -1L
      var guard = 0
      while (!dut.io.m_irq.peek().litToBoolean && guard < 60000) {
        dut.io.cbMem.req.ready.poke(true.B)
        if (cbPend >= 0) {
          dut.io.cbMem.resp.valid.poke(true.B)
          dut.io.cbMem.resp.bits.data.poke(m.word(cbPend).U)
          dut.io.cbMem.resp.bits.write.poke(false.B)
          if (dut.io.cbMem.resp.ready.peek().litToBoolean) cbPend = -1L
        } else dut.io.cbMem.resp.valid.poke(false.B)
        if (dut.io.cbMem.req.valid.peek().litToBoolean &&
            dut.io.cbMem.req.ready.peek().litToBoolean &&
            !dut.io.cbMem.req.bits.write.peek().litToBoolean)
          cbPend = dut.io.cbMem.req.bits.addr.peek().litValue.toLong

        dut.io.fbMem.req.ready.poke(true.B)
        if (fbPend >= 0) {
          dut.io.fbMem.resp.valid.poke(true.B)
          dut.io.fbMem.resp.bits.data.poke(m.word(fbPend).U)
          dut.io.fbMem.resp.bits.write.poke(false.B)
          if (dut.io.fbMem.resp.ready.peek().litToBoolean) fbPend = -1L
        } else dut.io.fbMem.resp.valid.poke(false.B)
        if (dut.io.fbMem.req.valid.peek().litToBoolean &&
            dut.io.fbMem.req.ready.peek().litToBoolean) {
          if (dut.io.fbMem.req.bits.write.peek().litToBoolean)
            m.wwrite(dut.io.fbMem.req.bits.addr.peek().litValue.toLong,
              dut.io.fbMem.req.bits.data.peek().litValue.toInt)
          else fbPend = dut.io.fbMem.req.bits.addr.peek().litValue.toLong
        }

        dut.clock.step()
        guard += 1
      }
      assert(guard < 60000,
        s"AXI4-driven draw did not complete; cbPend=$cbPend fbPend=$fbPend status=${(axiRead(dut, RenderHostRegs.STATUS) & 3)}")

      def rgb(x: Int, y: Int): (Int, Int, Int) = {
        val c = m.word(colorBase + (y * 16 + x) * 4).toInt
        (((c >> 24) & 0xff), ((c >> 16) & 0xff), ((c >> 8) & 0xff))
      }
      assert(rgb(5, 5) == (255, 0, 0), s"interior (5,5) should be red, got ${rgb(5, 5)}")
      assert(m.word(depthBase + (5 * 16 + 5) * 4) == 0x10,
        s"depth (5,5) should be 0x10, got 0x${m.word(depthBase + (5 * 16 + 5) * 4).toHexString}")

      // STATUS reads back DONE (busy cleared).
      assert((axiRead(dut, RenderHostRegs.STATUS) & 0x3) == 0x2L,
        "STATUS must report DONE after completion")
    }
  }
}
