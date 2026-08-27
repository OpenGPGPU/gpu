package opengpu.graphics

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec

import java.nio.file.{Files, Paths}

class RenderPipelineSpec extends AnyFlatSpec {
  behavior of "RenderPipeline"

  private def q(v: Double): Int = (v * (1 << 16)).toInt

  it should "render a triangle to a framebuffer and export a PPM image" in {
    val config = GraphicsConfig(screenWidth = 16, screenHeight = 16, subPixelBits = 8)
    val stride = 16 * 4
    val colorBase = 0x1000
    val depthBase = 0x2000

    simulate(new RenderPipeline(config)) { dut =>
      val mem = Array.fill(1 << 15)(0x00000000)
      // depth buffer starts "far" so the near triangle passes the LESS test.
      for (i <- 0 until (16 * 16)) mem(depthBase / 4 + i) = 0xffffffff

      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      dut.io.colorBase.poke(colorBase.U)
      dut.io.depthBase.poke(depthBase.U)
      dut.io.stride.poke(stride.U)
      dut.io.depthTestEnable.poke(true.B)
      dut.io.depthFunc.poke(0.U) // less
      dut.io.depthWriteEnable.poke(true.B)
      dut.io.cullMode.poke(0.U)
      dut.io.mem.req.ready.poke(true.B)
      dut.io.mem.resp.valid.poke(false.B)

      // One red triangle covering the lower-left half (clip x+y maps to sx+sy<=16).
      val red: (Int, Int, Int) = (255, 0, 0)
      val clip = Seq(
        (q(-1.0), q(-1.0), q(1.0)),
        (q(1.0), q(-1.0), q(1.0)),
        (q(-1.0), q(1.0), q(1.0))
      )
      dut.io.draw.valid.poke(true.B)
      for (i <- 0 until 3) {
        dut.io.draw.bits.clip(i).x.poke(clip(i)._1.S)
        dut.io.draw.bits.clip(i).y.poke(clip(i)._2.S)
        dut.io.draw.bits.clip(i).z.poke(0.S)
        dut.io.draw.bits.clip(i).w.poke(clip(i)._3.S)
        dut.io.draw.bits.color(i).r.poke(red._1.U)
        dut.io.draw.bits.color(i).g.poke(red._2.U)
        dut.io.draw.bits.color(i).b.poke(red._3.U)
        dut.io.draw.bits.depth(i).poke(0x10.S)
      }
      dut.clock.step()
      dut.io.draw.valid.poke(false.B)

      // Drain: service the OM memory port (1-cycle read latency) until done.
      var lastReadValid = false
      var lastReadData = 0L
      var guard = 0
      while (!dut.io.done.peek().litToBoolean && guard < 4000) {
        dut.io.mem.req.ready.poke(true.B)
        if (lastReadValid) {
          dut.io.mem.resp.valid.poke(true.B)
          dut.io.mem.resp.bits.data.poke(lastReadData.U)
          lastReadValid = false
        } else {
          dut.io.mem.resp.valid.poke(false.B)
          dut.io.mem.resp.bits.data.poke(0.U)
        }
        if (dut.io.mem.req.valid.peek().litToBoolean) {
          val addr = dut.io.mem.req.bits.addr.peek().litValue.toInt
          val write = dut.io.mem.req.bits.write.peek().litToBoolean
          val data = dut.io.mem.req.bits.data.peek().litValue.toInt
          if (write) mem(addr / 4) = data
          else { lastReadValid = true; lastReadData = mem(addr / 4) & 0xffffffffL }
        }
        dut.clock.step()
        guard += 1
      }
      assert(guard < 4000, "pipeline did not drain")

      // Read back the colour buffer and export a P6 PPM.
      val px = new Array[Int](16 * 16 * 3)
      var i = 0
      for (y <- 0 until 16; x <- 0 until 16) {
        val c = mem(colorBase / 4 + (y * 16 + x))
        px(i) = (c >> 24) & 0xff; px(i + 1) = (c >> 16) & 0xff; px(i + 2) = (c >> 8) & 0xff
        i += 3
      }
      val out = new StringBuilder
      out.append("P6\n16 16\n255\n")
      val bytes = out.toString.getBytes("US-ASCII") ++ px.map(_.toByte)
      val path = Paths.get("generated/render.ppm")
      Files.createDirectories(path.getParent)
      Files.write(path, bytes)
      System.err.println(s"wrote ${path.toAbsolutePath}")

      // Verify the framebuffer: the triangle's interior pixel (5,5) is red.
      def rgb(x: Int, y: Int): (Int, Int, Int) = {
        val c = mem(colorBase / 4 + (y * 16 + x))
        (((c >> 24) & 0xff), ((c >> 16) & 0xff), ((c >> 8) & 0xff))
      }
      assert(rgb(5, 5) == (255, 0, 0), s"interior (5,5) should be red, got ${rgb(5, 5)}")
      // A pixel in the non-covered upper-right half stays background (black).
      assert(rgb(13, 13) == (0, 0, 0), s"outside (13,13) should be black, got ${rgb(13, 13)}")
    }
  }
}
