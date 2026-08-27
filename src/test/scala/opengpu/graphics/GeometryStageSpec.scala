package opengpu.graphics

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec

class GeometryStageSpec extends AnyFlatSpec {
  behavior of "GeometryStage"

  it should "perspective-divide and map clip coords to fixed-point screen space" in {
    val config = GraphicsConfig(screenWidth = 16, screenHeight = 16, subPixelBits = 8)
    def q(v: Double): Int = (v * (1 << 16)).toInt

    simulate(new GeometryStage(config)) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      dut.io.screenW.poke(16.U)
      dut.io.screenH.poke(16.U)
      dut.io.color(0).r.poke(0.U); dut.io.color(0).g.poke(0.U); dut.io.color(0).b.poke(0.U)
      dut.io.color(1).r.poke(0.U); dut.io.color(1).g.poke(0.U); dut.io.color(1).b.poke(0.U)
      dut.io.color(2).r.poke(0.U); dut.io.color(2).g.poke(0.U); dut.io.color(2).b.poke(0.U)

      val verts = Seq(
        (q(0.0), q(0.0), q(1.0)), // x, y, w => screen (8, 8)
        (q(1.0), q(0.0), q(1.0)), //        => screen (16, 8)
        (q(0.0), q(1.0), q(2.0))  // x/w=0, y/w=0.5 => screen (8, 12)
      )
      for (i <- 0 until 3) {
        dut.io.clip(i).x.poke(verts(i)._1.S)
        dut.io.clip(i).y.poke(verts(i)._2.S)
        dut.io.clip(i).z.poke(0.S)
        dut.io.clip(i).w.poke(verts(i)._3.S)
      }
      dut.clock.step()

      // Hand-computed exact fixed-point results (subPixel=8 => *256), invW Q16.16:
      //   sx = ((x + w) * screenW * 256) / (2*w)
      val expectedSx = Seq(2048, 4096, 2048)
      val expectedSy = Seq(2048, 2048, 3072)
      val expectedInvW = Seq(65536, 65536, 32768)

      for (i <- 0 until 3) {
        assert(dut.io.out(i).sx.peek().litValue.toInt == expectedSx(i), s"sx[$i] mismatch")
        assert(dut.io.out(i).sy.peek().litValue.toInt == expectedSy(i), s"sy[$i] mismatch")
        assert(dut.io.out(i).invW.peek().litValue.toInt == expectedInvW(i), s"invW[$i] mismatch")
      }
    }
  }
}
