package opengpu.graphics

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import opengpu.config.GpuConfig
import org.scalatest.flatspec.AnyFlatSpec

import scala.collection.mutable

/** Verifies the M6 host interface: the MMIO register file, the engine control
  * state machine (START -> BUSY -> DONE), the completion interrupt, and that a
  * host programmes the registers and drives a real draw through `RenderCore`.
  */
class RenderHostSpec extends AnyFlatSpec {
  behavior of "RenderHost"

  private def q(v: Double): Int = (v * (1 << 16)).toInt

  /** Word-addressed (by byte address) memory model. */
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
    w += 0; w += 0 // shader descriptor (unused on the fixed-function path)
    w.result()
  }

  private def regWrite(
    dut: RenderHost,
    addr: Int,
    data: Int
  ): Unit = {
    dut.io.reg.req.valid.poke(true.B)
    dut.io.reg.req.bits.isWrite.poke(true.B)
    dut.io.reg.req.bits.addr.poke(addr.U)
    dut.io.reg.req.bits.data.poke(data.U)
    dut.io.reg.req.bits.strb.poke(0xf.U)
    dut.clock.step()
    dut.io.reg.req.valid.poke(false.B)
  }

  private def regRead(dut: RenderHost, addr: Int): BigInt = {
    dut.io.reg.req.valid.poke(true.B)
    dut.io.reg.req.bits.isWrite.poke(false.B)
    dut.io.reg.req.bits.addr.poke(addr.U)
    dut.io.reg.req.bits.data.poke(0.U)
    dut.io.reg.req.bits.strb.poke(0xf.U)
    assert(dut.io.reg.resp.valid.peek().litToBoolean,
      s"read of 0x$addr%x must produce a response")
    val data = dut.io.reg.resp.bits.data.peek().litValue
    dut.clock.step()
    dut.io.reg.req.valid.poke(false.B)
    data
  }

  it should "expose the device ID and a program-able register file" in {
    simulate(new RenderHost(deviceId = 0x4755, version = 0x0001)) { dut =>
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      assert(regRead(dut, RenderHostRegs.ID) == 0x47550001L,
        "device ID register must report device<<16 | version")

      // An unmapped address yields ok=false.
      dut.io.reg.req.valid.poke(true.B)
      dut.io.reg.req.bits.isWrite.poke(false.B)
      dut.io.reg.req.bits.addr.poke(0x200.U)
      dut.io.reg.req.bits.data.poke(0.U)
      dut.io.reg.req.bits.strb.poke(0xf.U)
      assert(!dut.io.reg.resp.bits.ok.peek().litToBoolean)
      dut.clock.step()
      dut.io.reg.req.valid.poke(false.B)

      // Unaligned (non 4-byte) address is rejected as well.
      dut.io.reg.req.valid.poke(true.B)
      dut.io.reg.req.bits.isWrite.poke(false.B)
      dut.io.reg.req.bits.addr.poke(0x11.U)
      dut.io.reg.req.bits.data.poke(0.U)
      dut.io.reg.req.bits.strb.poke(0xf.U)
      assert(!dut.io.reg.resp.bits.ok.peek().litToBoolean)
      dut.clock.step()
      dut.io.reg.req.valid.poke(false.B)

      // Program the configuration registers and read them back.
      regWrite(dut, RenderHostRegs.CMD_BASE, 0x4000)
      regWrite(dut, RenderHostRegs.CMD_COUNT, 1)
      regWrite(dut, RenderHostRegs.COLOR_BASE, 0x8000)
      regWrite(dut, RenderHostRegs.DEPTH_BASE, 0x9000)
      regWrite(dut, RenderHostRegs.STRIDE, 64)
      regWrite(dut, RenderHostRegs.DEPTH_TEST_ENABLE, 1)
      regWrite(dut, RenderHostRegs.DEPTH_FUNC, 0)
      regWrite(dut, RenderHostRegs.DEPTH_WRITE_ENABLE, 1)
      regWrite(dut, RenderHostRegs.CULL_MODE, 2)
      assert(regRead(dut, RenderHostRegs.CMD_BASE) == 0x4000L)
      assert(regRead(dut, RenderHostRegs.CMD_COUNT) == 1L)
      assert(regRead(dut, RenderHostRegs.COLOR_BASE) == 0x8000L)
      assert(regRead(dut, RenderHostRegs.DEPTH_BASE) == 0x9000L)
      assert(regRead(dut, RenderHostRegs.STRIDE) == 64L)
      assert(regRead(dut, RenderHostRegs.DEPTH_TEST_ENABLE) == 1L)
      assert(regRead(dut, RenderHostRegs.DEPTH_FUNC) == 0L)
      assert(regRead(dut, RenderHostRegs.DEPTH_WRITE_ENABLE) == 1L)
      assert(regRead(dut, RenderHostRegs.CULL_MODE) == 2L)
      // Idle status: BUSY=0, DONE=0.
      assert((regRead(dut, RenderHostRegs.STATUS) & 0x3) == 0L)
    }
  }

  it should "drive a draw from the register file and raise done and the interrupt" in {
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

    simulate(new RenderHost(config, cfg, fragCore = false)) { dut =>
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)

      // Program the render via the register file.
      regWrite(dut, RenderHostRegs.CMD_BASE, cmdBase)
      regWrite(dut, RenderHostRegs.CMD_COUNT, 1)
      regWrite(dut, RenderHostRegs.COLOR_BASE, colorBase)
      regWrite(dut, RenderHostRegs.DEPTH_BASE, depthBase)
      regWrite(dut, RenderHostRegs.STRIDE, stride)
      regWrite(dut, RenderHostRegs.DEPTH_TEST_ENABLE, 1)
      regWrite(dut, RenderHostRegs.DEPTH_FUNC, 0)
      regWrite(dut, RenderHostRegs.DEPTH_WRITE_ENABLE, 1)
      regWrite(dut, RenderHostRegs.CULL_MODE, 0)
      regWrite(dut, RenderHostRegs.IRQ, 1) // enable the completion interrupt

      // Service the renderer's command-buffer / framebuffer word ports.
      dut.io.cbMem.req.ready.poke(true.B)
      dut.io.cbMem.resp.valid.poke(false.B)
      dut.io.fbMem.req.ready.poke(true.B)
      dut.io.fbMem.resp.valid.poke(false.B)
      // The core-backed kernel queue is unused on the fixed-function path.
      dut.io.kernelMemReq.ready.poke(true.B)
      dut.io.kernelMemResp.valid.poke(false.B)
      dut.io.kernelWordMemReq.ready.poke(true.B)
      dut.io.kernelWordMemResp.valid.poke(false.B)

      // Kick the engine.
      regWrite(dut, RenderHostRegs.CONTROL, 1)

      var cbR = false; var cbD = 0L
      var fbR = false; var fbD = 0L
      var guard = 0
      while (!dut.io.irq.peek().litToBoolean && guard < 60000) {
        dut.io.cbMem.req.ready.poke(true.B)
        if (cbR) {
          dut.io.cbMem.resp.valid.poke(true.B)
          dut.io.cbMem.resp.bits.data.poke(cbD.U)
          dut.io.cbMem.resp.bits.write.poke(false.B)
          cbR = false
        } else dut.io.cbMem.resp.valid.poke(false.B)
        if (dut.io.cbMem.req.valid.peek().litToBoolean &&
            dut.io.cbMem.req.ready.peek().litToBoolean &&
            !dut.io.cbMem.req.bits.write.peek().litToBoolean) {
          cbR = true
          cbD = m.word(dut.io.cbMem.req.bits.addr.peek().litValue.toLong)
        }

        dut.io.fbMem.req.ready.poke(true.B)
        if (fbR) {
          dut.io.fbMem.resp.valid.poke(true.B)
          dut.io.fbMem.resp.bits.data.poke(fbD.U)
          dut.io.fbMem.resp.bits.write.poke(false.B)
          fbR = false
        } else dut.io.fbMem.resp.valid.poke(false.B)
        if (dut.io.fbMem.req.valid.peek().litToBoolean &&
            dut.io.fbMem.req.ready.peek().litToBoolean) {
          val a = dut.io.fbMem.req.bits.addr.peek().litValue.toLong
          if (dut.io.fbMem.req.bits.write.peek().litToBoolean)
            m.wwrite(a, dut.io.fbMem.req.bits.data.peek().litValue.toInt)
          else { fbR = true; fbD = m.word(a) }
        }

        dut.clock.step()
        guard += 1
      }
      assert(guard < 60000, "host-driven draw did not complete")

      // Completion state: BUSY=0, DONE=1; the interrupt fired.
      assert(dut.io.irq.peek().litToBoolean, "completion interrupt must assert")
      assert((regRead(dut, RenderHostRegs.STATUS) & 0x3) == 0x2L,
        "STATUS must report DONE (busy cleared)")

      def rgb(x: Int, y: Int): (Int, Int, Int) = {
        val c = m.word(colorBase + (y * 16 + x) * 4).toInt
        (((c >> 24) & 0xff), ((c >> 16) & 0xff), ((c >> 8) & 0xff))
      }
      // The register-file-driven draw rendered and depth-tested the triangle.
      assert(rgb(5, 5) == (255, 0, 0), s"interior (5,5) should be red, got ${rgb(5, 5)}")
      assert(m.word(depthBase + (5 * 16 + 5) * 4) == 0x10,
        s"depth (5,5) should be 0x10, got 0x${m.word(depthBase + (5 * 16 + 5) * 4).toHexString}")

      // Clearing DONE by writing 1 to STATUS's DONE bit (W1C).
      regWrite(dut, RenderHostRegs.STATUS, 0x2) // bit1 = DONE clear
      assert((regRead(dut, RenderHostRegs.STATUS) & 0x2) == 0L,
        "writing the DONE bit must clear it")

      // Clear the interrupt by writing IRQ bit1 = 1 (W1C on PENDING).
      regWrite(dut, RenderHostRegs.IRQ, 0x3) // ENABLE=1, clear PENDING
      assert(!dut.io.irq.peek().litToBoolean,
        "clearing IRQ.PENDING must deassert the interrupt")
    }
  }
}
