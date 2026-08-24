package gpu.graphics

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec

import java.nio.file.{Files, Paths}

class RenderCoreSpec extends AnyFlatSpec {
  behavior of "RenderCore"

  private def q(v: Double): Int = (v * (1 << 16)).toInt

  private def encode(tri: Seq[((Int, Int, Int, Int), (Int, Int, Int), Int)]): Seq[Int] = {
    val w = Seq.newBuilder[Int]
    for (i <- 0 until 3) { w += tri(i)._1._1; w += tri(i)._1._2; w += tri(i)._1._3; w += tri(i)._1._4 }
    for (i <- 0 until 3) { w += tri(i)._2._1; w += tri(i)._2._2; w += tri(i)._2._3 }
    for (i <- 0 until 3) { w += tri(i)._3 }
    w.result()
  }

  private def rgbOf(fbMem: Array[Int], colorBase: Int, x: Int, y: Int): (Int, Int, Int) = {
    val c = fbMem(colorBase / 4 + (y * 16 + x))
    (((c >> 24) & 0xff), ((c >> 16) & 0xff), ((c >> 8) & 0xff))
  }

  it should "render a command-driven multi-triangle scene with depth and export PPM" in {
    val config = GraphicsConfig(screenWidth = 16, screenHeight = 16, subPixelBits = 8)
    val stride = 16 * 4
    val colorBase = 0x8000
    val depthBase = 0x9000
    val cmdBase = 0x4000

    def tri(x: Int, y: Int, r: Int, g: Int, b: Int, d: Int) = ((x, y, 0, q(1.0)), (r, g, b), d)
    val red = Seq(
      tri(q(-1.0), q(-1.0), 255, 0, 0, 0x10),
      tri(q(1.0), q(-1.0), 255, 0, 0, 0x10),
      tri(q(-1.0), q(1.0), 255, 0, 0, 0x10)
    )
    val green = Seq(
      tri(q(-0.5), q(-0.5), 0, 255, 0, 0x08),
      tri(q(0.5), q(-0.5), 0, 255, 0, 0x08),
      tri(q(-0.5), q(0.5), 0, 255, 0, 0x08)
    )

    val cbMem = Array.fill(1 << 15)(0)
    (encode(green) ++ encode(red)).zipWithIndex.foreach { case (w, i) => cbMem(cmdBase / 4 + i) = w }

    simulate(new RenderCore(config)) { dut =>
      val fbMem = Array.fill(1 << 16)(0)
      for (i <- 0 until (16 * 16)) fbMem(depthBase / 4 + i) = 0xffffffff

      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      dut.io.cmdBase.poke(cmdBase.U)
      dut.io.cmdCount.poke(2.U)
      dut.io.colorBase.poke(colorBase.U)
      dut.io.depthBase.poke(depthBase.U)
      dut.io.stride.poke(stride.U)
      dut.io.depthTestEnable.poke(true.B)
      dut.io.depthFunc.poke(0.U)
      dut.io.depthWriteEnable.poke(true.B)
      dut.io.cullMode.poke(0.U)
      dut.io.cbMem.req.ready.poke(true.B)
      dut.io.cbMem.resp.valid.poke(false.B)
      dut.io.fbMem.req.ready.poke(true.B)
      dut.io.fbMem.resp.valid.poke(false.B)

      dut.io.start.poke(true.B)
      dut.clock.step()
      dut.io.start.poke(false.B)

      var cbReadValid = false; var cbReadData = 0L
      var fbReadValid = false; var fbReadData = 0L
      var guard = 0
      while (!dut.io.done.peek().litToBoolean && guard < 20000) {
        dut.io.cbMem.req.ready.poke(true.B)
        if (cbReadValid) { dut.io.cbMem.resp.valid.poke(true.B); dut.io.cbMem.resp.bits.data.poke(cbReadData.U); cbReadValid = false }
        else dut.io.cbMem.resp.valid.poke(false.B)
        if (dut.io.cbMem.req.valid.peek().litToBoolean) {
          val a = dut.io.cbMem.req.bits.addr.peek().litValue.toInt
          cbReadValid = true; cbReadData = cbMem(a / 4) & 0xffffffffL
        }
        dut.io.fbMem.req.ready.poke(true.B)
        if (fbReadValid) { dut.io.fbMem.resp.valid.poke(true.B); dut.io.fbMem.resp.bits.data.poke(fbReadData.U); fbReadValid = false }
        else dut.io.fbMem.resp.valid.poke(false.B)
        if (dut.io.fbMem.req.valid.peek().litToBoolean) {
          val a = dut.io.fbMem.req.bits.addr.peek().litValue.toInt
          val write = dut.io.fbMem.req.bits.write.peek().litToBoolean
          val data = dut.io.fbMem.req.bits.data.peek().litValue.toInt
          if (write) { fbMem(a / 4) = data }
          else { fbReadValid = true; fbReadData = fbMem(a / 4) & 0xffffffffL }
        }
        dut.clock.step()
        guard += 1
      }
      assert(guard < 20000, "did not drain")
      def rgb(x: Int, y: Int): (Int, Int, Int) = rgbOf(fbMem, colorBase, x, y)
      // Green (near) wins strictly inside overlap; red survives red-only pixels.
      assert(rgb(5, 5) == (0, 255, 0), s"green interior (5,5) should be green, got ${rgb(5, 5)}")
      assert(rgb(2, 2) == (255, 0, 0), s"red-only (2,2) should be red, got ${rgb(2, 2)}")

      val px = new Array[Int](16 * 16 * 3); var i = 0
      for (y <- 0 until 16; x <- 0 until 16) {
        val c = fbMem(colorBase / 4 + (y * 16 + x))
        px(i) = (c >> 24) & 0xff; px(i + 1) = (c >> 16) & 0xff; px(i + 2) = (c >> 8) & 0xff
        i += 3
      }
      val p = Paths.get("generated/scene.ppm")
      Files.createDirectories(p.getParent)
      Files.write(p, ("P6\n16 16\n255\n").getBytes("US-ASCII") ++ px.map(_.toByte))
      System.err.println(s"wrote ${p.toAbsolutePath}")
    }
  }
}
