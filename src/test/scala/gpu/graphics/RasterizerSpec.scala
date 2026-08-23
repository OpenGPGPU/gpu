package gpu.graphics

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec

class RasterizerSpec extends AnyFlatSpec {
  behavior of "TriangleCoverage"

  private def wrapped(x: Int, bits: Int = 64): BigInt =
    if (x >= 0) BigInt(x) else (BigInt(1) << bits) + x

  it should "report inside and consistent edge values for a CCW triangle" in {
    val config = GraphicsConfig(screenWidth = 128, screenHeight = 128, subPixelBits = 8)
    // A simple right triangle with vertices at fixed-point locations.
    val v0 = (config.toFixed(20), config.toFixed(20))
    val v1 = (config.toFixed(60), config.toFixed(20))
    val v2 = (config.toFixed(20), config.toFixed(60))
    simulate(new TriangleCoverage(config)) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      dut.io.vertices.v0.x.poke(v0._1.S)
      dut.io.vertices.v0.y.poke(v0._2.S)
      dut.io.vertices.v1.x.poke(v1._1.S)
      dut.io.vertices.v1.y.poke(v1._2.S)
      dut.io.vertices.v2.x.poke(v2._1.S)
      dut.io.vertices.v2.y.poke(v2._2.S)

      // The centroid (approx (33.3, 33.3)) must be inside.
      val cx = config.toFixed(33)
      val cy = config.toFixed(33)
      dut.io.pixel.x.poke(cx.S)
      dut.io.pixel.y.poke(cy.S)
      dut.clock.step()
      dut.io.inside.expect(true.B)

      // The three edge values must sum to the signed twice-the-area (a
      // barycentric/plane-equation invariant independent of fixed-point scale).
      val e0 = dut.io.e0.peek().litValue.toLong
      val e1 = dut.io.e1.peek().litValue.toLong
      val e2 = dut.io.e2.peek().litValue.toLong
      val area = dut.io.area.peek().litValue.toLong
      assert(e0 + e1 + e2 == area)

      // An outside point (well below-left of the triangle) must be rejected.
      dut.io.pixel.x.poke(config.toFixed(8).S)
      dut.io.pixel.y.poke(config.toFixed(8).S)
      dut.clock.step()
      dut.io.inside.expect(false.B)
    }
  }

  it should "emit exactly the covered pixels for a triangle via the rasterizer" in {
    val config = GraphicsConfig(screenWidth = 16, screenHeight = 16, subPixelBits = 8)
    val v0 = (config.toFixed(2), config.toFixed(2))
    val v1 = (config.toFixed(12), config.toFixed(2))
    val v2 = (config.toFixed(2), config.toFixed(12))
    simulate(new TriangleRasterizer(config)) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      dut.io.pixel.ready.poke(true.B)
      dut.io.draw.valid.poke(true.B)
      dut.io.draw.bits.v0.x.poke(v0._1.S)
      dut.io.draw.bits.v0.y.poke(v0._2.S)
      dut.io.draw.bits.v1.x.poke(v1._1.S)
      dut.io.draw.bits.v1.y.poke(v1._2.S)
      dut.io.draw.bits.v2.x.poke(v2._1.S)
      dut.io.draw.bits.v2.y.poke(v2._2.S)
      dut.clock.step()
      dut.io.draw.valid.poke(false.B)

      // Drain until the scanline is exhausted (io.draw.ready returns high).
      // The rasterizer interleaves non-covered (pixel.valid low) and covered
      // cycles, so we must keep stepping until the full bounding box is done.
      var covered = Set.empty[(Int, Int)]
      var cycles = 0
      while (!dut.io.draw.ready.peek().litToBoolean && cycles < 10000) {
        if (dut.io.pixel.valid.peek().litToBoolean) {
          val px = (dut.io.pixel.bits.x.peek().litValue >> 8).toInt
          val py = (dut.io.pixel.bits.y.peek().litValue >> 8).toInt
          covered += ((px, py))
        }
        dut.clock.step()
        cycles += 1
      }
      // Software reference: a point is covered iff all three cross products >= 0.
      val expected = (0 until 16).flatMap { y =>
        (0 until 16).flatMap { x =>
          val p = (config.toFixed(x), config.toFixed(y))
          val ab0 = (v1._1 - v0._1) * (p._2 - v0._2) - (v1._2 - v0._2) * (p._1 - v0._1)
          val ab1 = (v2._1 - v1._1) * (p._2 - v1._2) - (v2._2 - v1._2) * (p._1 - v1._1)
          val ab2 = (v0._1 - v2._1) * (p._2 - v2._2) - (v0._2 - v2._2) * (p._1 - v2._1)
          if (ab0 >= 0 && ab1 >= 0 && ab2 >= 0) Some((x, y)) else None
        }
      }.toSet
      assert(covered == expected, s"coverage mismatch: ${(covered -- expected)} vs ${(expected -- covered)}")
    }
  }
}
