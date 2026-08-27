package opengpu.graphics

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec

class FragmentInterpolatorSpec extends AnyFlatSpec {
  behavior of "FragmentInterpolator"

  it should "interpolate a colour at the triangle centroid (equal weights)" in {
    val config = GraphicsConfig()
    simulate(new FragmentInterpolator(config)) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)

      // Equal weights: e0 = e1 = e2 = 10, area = e0+e1+e2 = 30.
      dut.io.e0.poke(10.S)
      dut.io.e1.poke(10.S)
      dut.io.e2.poke(10.S)
      dut.io.area.poke(30.S)

      // c0=(90,60,30), c1=(30,90,60), c2=(60,30,90) => centroid average.
      dut.io.c0.r.poke(90.U); dut.io.c0.g.poke(60.U); dut.io.c0.b.poke(30.U)
      dut.io.c1.r.poke(30.U); dut.io.c1.g.poke(90.U); dut.io.c1.b.poke(60.U)
      dut.io.c2.r.poke(60.U); dut.io.c2.g.poke(30.U); dut.io.c2.b.poke(90.U)
      dut.clock.step()

      dut.io.color.r.expect(60.U) // (90+30+60)/3
      dut.io.color.g.expect(60.U) // (60+90+30)/3
      dut.io.color.b.expect(60.U) // (30+60+90)/3
    }
  }

  it should "reproduce a vertex colour exactly at that vertex" in {
    val config = GraphicsConfig()
    simulate(new FragmentInterpolator(config)) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)

      // At v0: e0 = area, e1 = e2 = 0.
      dut.io.e0.poke(45.S)
      dut.io.e1.poke(0.S)
      dut.io.e2.poke(0.S)
      dut.io.area.poke(45.S)
      dut.io.c0.r.poke(123.U); dut.io.c0.g.poke(77.U); dut.io.c0.b.poke(200.U)
      dut.io.c1.r.poke(1.U); dut.io.c1.g.poke(2.U); dut.io.c1.b.poke(3.U)
      dut.io.c2.r.poke(4.U); dut.io.c2.g.poke(5.U); dut.io.c2.b.poke(6.U)
      dut.clock.step()

      dut.io.color.r.expect(123.U)
      dut.io.color.g.expect(77.U)
      dut.io.color.b.expect(200.U)
    }
  }

  it should "shade a full triangle through the rasterizer-to-interpolator path" in {
    val config = GraphicsConfig(screenWidth = 16, screenHeight = 16, subPixelBits = 8)
    simulate(new RasterShader(config)) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)

      // Full-colour triangle: v0 red, v1 green, v2 blue.
      dut.io.colors(0).r.poke(255.U); dut.io.colors(0).g.poke(0.U); dut.io.colors(0).b.poke(0.U)
      dut.io.colors(1).r.poke(0.U); dut.io.colors(1).g.poke(255.U); dut.io.colors(1).b.poke(0.U)
      dut.io.colors(2).r.poke(0.U); dut.io.colors(2).g.poke(0.U); dut.io.colors(2).b.poke(255.U)

      // Triangle: v0=(2,2) v1=(14,2) v2=(2,14)
      dut.io.draw.valid.poke(true.B)
      dut.io.draw.bits.v0.x.poke(config.toFixed(2).S)
      dut.io.draw.bits.v0.y.poke(config.toFixed(2).S)
      dut.io.draw.bits.v1.x.poke(config.toFixed(14).S)
      dut.io.draw.bits.v1.y.poke(config.toFixed(2).S)
      dut.io.draw.bits.v2.x.poke(config.toFixed(2).S)
      dut.io.draw.bits.v2.y.poke(config.toFixed(14).S)
      dut.io.pixel.ready.poke(true.B)
      dut.io.cullMode.poke(0.U)
      dut.clock.step()
      dut.io.draw.valid.poke(false.B)

      val fb = collection.mutable.Map.empty[(Int, Int), (Int, Int, Int)]
      while (!dut.io.done.peek().litToBoolean) {
        if (dut.io.pixel.valid.peek().litToBoolean) {
          val px = dut.io.pixel.bits.x.peek().litValue.toInt
          val py = dut.io.pixel.bits.y.peek().litValue.toInt
          val r = dut.io.pixel.bits.color.r.peek().litValue.toInt
          val g = dut.io.pixel.bits.color.g.peek().litValue.toInt
          val b = dut.io.pixel.bits.color.b.peek().litValue.toInt
          fb((px, py)) = (r, g, b)
        }
        dut.clock.step()
      }

      // Interior pixels must exist and each channel increases toward its vertex:
      // near v0 -> red dominant, near v1 -> green dominant, near v2 -> blue.
      def maxChannel(c: (Int, Int, Int)): Int =
        if (c._1 >= c._2 && c._1 >= c._3) 0 else if (c._2 >= c._3) 1 else 2
      val nearV0 = fb.get((3, 3))
      val nearV1 = fb.get((12, 3))
      val nearV2 = fb.get((3, 12))
      assert(nearV0.exists(c => maxChannel(c) == 0))
      assert(nearV1.exists(c => maxChannel(c) == 1))
      assert(nearV2.exists(c => maxChannel(c) == 2))
    }
  }
}
