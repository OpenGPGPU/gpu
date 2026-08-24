package gpu.graphics

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec

class ShadedPipelineSpec extends AnyFlatSpec {
  behavior of "ShadedPipeline"

  it should "shade a triangle through the SIMT program and write it to the framebuffer" in {
    val config = GraphicsConfig(screenWidth = 16, screenHeight = 16, subPixelBits = 8)
    val stride = 16 * 4
    val colorBase = 0x8000
    val depthBase = 0x9000

    simulate(new ShadedPipeline(config, progSize = 4)) { dut =>
      val mem = Array.fill(1 << 16)(0)
      for (i <- 0 until (16 * 16)) mem(depthBase / 4 + i) = 0xffffffff

      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      dut.io.colorBase.poke(colorBase.U)
      dut.io.depthBase.poke(depthBase.U)
      dut.io.stride.poke(stride.U)
      dut.io.depthTestEnable.poke(true.B)
      dut.io.depthFunc.poke(0.U)
      dut.io.depthWriteEnable.poke(true.B)
      dut.io.cullMode.poke(0.U)
      dut.io.mem.req.ready.poke(true.B)
      dut.io.mem.resp.valid.poke(false.B)

      def op(i: Int, o: Int, d: Int, a: Int, b: Int, imm: Int): Unit = {
        dut.io.prog(i).op.poke(o.U); dut.io.prog(i).dst.poke(d.U)
        dut.io.prog(i).a.poke(a.U); dut.io.prog(i).b.poke(b.U); dut.io.prog(i).imm.poke(imm.S)
      }
      op(0, 7, 0, 0, 0, 0) // out = r0 (interpolated colour), no uniform
      op(1, 0, 0, 0, 0, 0)
      op(2, 0, 0, 0, 0, 0)
      op(3, 0, 0, 0, 0, 0)
      dut.io.uniform(0).poke(100.S)

      dut.io.colors(0).r.poke(128.U); dut.io.colors(0).g.poke(0.U); dut.io.colors(0).b.poke(0.U)
      dut.io.colors(1).r.poke(128.U); dut.io.colors(1).g.poke(0.U); dut.io.colors(1).b.poke(0.U)
      dut.io.colors(2).r.poke(128.U); dut.io.colors(2).g.poke(0.U); dut.io.colors(2).b.poke(0.U)
      dut.io.depths(0).poke(0x10.S); dut.io.depths(1).poke(0x10.S); dut.io.depths(2).poke(0x10.S)

      dut.io.draw.valid.poke(true.B)
      dut.io.draw.bits.v0.x.poke(0.S); dut.io.draw.bits.v0.y.poke(0.S)
      dut.io.draw.bits.v1.x.poke((16 << 8).S); dut.io.draw.bits.v1.y.poke(0.S)
      dut.io.draw.bits.v2.x.poke(0.S); dut.io.draw.bits.v2.y.poke((16 << 8).S)
      dut.clock.step()
      dut.io.draw.valid.poke(false.B)

      var lastReadValid = false; var lastReadData = 0L
      var guard = 0; var colorWrites = 0
      while (!dut.io.done.peek().litToBoolean && guard < 4000) {
        dut.io.mem.req.ready.poke(true.B)
        if (lastReadValid) { dut.io.mem.resp.valid.poke(true.B); dut.io.mem.resp.bits.data.poke(lastReadData.U); lastReadValid = false }
        else dut.io.mem.resp.valid.poke(false.B)
        if (dut.io.mem.req.valid.peek().litToBoolean) {
          val a = dut.io.mem.req.bits.addr.peek().litValue.toInt
          val write = dut.io.mem.req.bits.write.peek().litToBoolean
          val data = dut.io.mem.req.bits.data.peek().litValue.toInt
          if (write) {
            if (a >= colorBase && a < depthBase) colorWrites += 1
            mem(a / 4) = data
          } else { lastReadValid = true; lastReadData = mem(a / 4) & 0xffffffffL }
        }
        dut.clock.step()
        guard += 1
      }
      assert(guard < 4000, "did not drain")
      assert(colorWrites > 0)
      // Every fragment is shaded through the SIMT program: interpolated colour
      // 128 -> OUT r0 -> grayscale (128,128,128) written to the framebuffer.
      def rgb(x: Int, y: Int): (Int, Int, Int) = {
        val c = mem(colorBase / 4 + (y * 16 + x))
        (((c >> 24) & 0xff), ((c >> 16) & 0xff), ((c >> 8) & 0xff))
      }
      assert(rgb(2, 2) == (128, 128, 128), s"SIMT-shaded pixel should be grayscale 128, got ${rgb(2, 2)}")
    }
  }
}
