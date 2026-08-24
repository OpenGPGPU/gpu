package gpu.graphics

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec

class NearClipStageSpec extends AnyFlatSpec {
  behavior of "NearClipStage"

  private def q(v: Double): Int = (v * (1 << 16)).toInt

  // poke a ClipVertexColor vertex and run the 4-phase clip.
  private def clip(
    dut: NearClipStage,
    verts: Seq[(Int, Int, Int, Int, (Int, Int, Int))], // x,y,z,w, rgb
    wNear: Int
  ): Unit = {
    dut.reset.poke(true.B)
    dut.clock.step()
    dut.reset.poke(false.B)
    dut.io.wNear.poke(wNear.U)
    for (i <- 0 until 3) {
      dut.io.tri(i).x.poke(verts(i)._1.S)
      dut.io.tri(i).y.poke(verts(i)._2.S)
      dut.io.tri(i).z.poke(verts(i)._3.S)
      dut.io.tri(i).w.poke(verts(i)._4.S)
      dut.io.tri(i).color.r.poke(verts(i)._5._1.U)
      dut.io.tri(i).color.g.poke(verts(i)._5._2.U)
      dut.io.tri(i).color.b.poke(verts(i)._5._3.U)
    }
    dut.io.start.poke(true.B)
    dut.clock.step()
    dut.io.start.poke(false.B)
    // run through the 4 phases
    var i = 0
    while (dut.io.busy.peek().litToBoolean && i < 8) { dut.clock.step(); i += 1 }
    dut.clock.step()
  }

  it should "pass a fully in-front triangle through unchanged" in {
    val wNear = q(0.001)
    simulate(new NearClipStage(GraphicsConfig())) { dut =>
      val verts = Seq(
        (q(0.2), q(0.2), q(0.5), q(1.0), (255, 0, 0)),
        (q(0.8), q(0.2), q(0.5), q(1.0), (0, 255, 0)),
        (q(0.2), q(0.8), q(0.5), q(1.0), (0, 0, 255))
      )
      clip(dut, verts, wNear)
      assert(dut.io.outValid.peek().litValue.toInt == 1)
      for (i <- 0 until 3) {
        assert(dut.io.out(i).w.peek().litValue.toInt == q(1.0))
      }
      // The cyclic rotation may start at any vertex; the colour set is R,G,B.
      val colors = (0 until 3).map(i =>
        (dut.io.out(i).color.r.peek().litValue.toInt,
         dut.io.out(i).color.g.peek().litValue.toInt,
         dut.io.out(i).color.b.peek().litValue.toInt)
      ).toSet
      assert(colors == Set((255, 0, 0), (0, 255, 0), (0, 0, 255)))
    }
  }

  it should "reject a fully behind triangle" in {
    val wNear = q(0.001)
    simulate(new NearClipStage(GraphicsConfig())) { dut =>
      val verts = Seq(
        (q(0.2), q(0.2), q(-0.5), q(-1.0), (255, 0, 0)),
        (q(0.8), q(0.2), q(-0.5), q(-1.0), (0, 255, 0)),
        (q(0.2), q(0.8), q(-0.5), q(-1.0), (0, 0, 255))
      )
      clip(dut, verts, wNear)
      assert(dut.io.outValid.peek().litValue.toInt == 0)
    }
  }

  it should "clip a near-plane crossing triangle and interpolate w and varyings" in {
    val wNear = q(0.5)
    simulate(new NearClipStage(GraphicsConfig())) { dut =>
      // v0 in front (w=1), v1 and v2 behind (w=0). Clips to a triangle with two
      // new vertices at w == wNear (0.5) on edges v0-v1 and v0-v2.
      val verts = Seq(
        (q(0.0), q(0.0), q(0.0), q(1.0), (255, 0, 0)),
        (q(1.0), q(0.0), q(0.0), q(0.0), (0, 255, 0)),
        (q(0.0), q(1.0), q(0.0), q(0.0), (0, 0, 255))
      )
      clip(dut, verts, wNear)
      val n = dut.io.outValid.peek().litValue.toInt
      assert(n == 1, s"expected 1 clipped triangle, got $n")
      // Output triangle = [interp(v0-v1), interp(v2-v0), v0].  Two clipped
      // vertices are at w == wNear; the original in-front v0 stays at w == 1.
      val ws = (0 until 3).map(i => dut.io.out(i).w.peek().litValue.toInt)
      assert(ws.count(_ == wNear) == 2, s"expected 2 vertices at wNear, got $ws")
      assert(ws.count(_ == q(1.0)) == 1, s"expected 1 vertex at w=1, got $ws")
      // The surviving original vertex is v0 (red, at the origin).
      val hasV0 = (0 until 3).exists { i =>
        dut.io.out(i).x.peek().litValue.toInt == q(0.0) &&
        dut.io.out(i).color.r.peek().litValue.toInt == 255
      }
      assert(hasV0)
    }
  }
}
