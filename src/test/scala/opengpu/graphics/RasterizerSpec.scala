package opengpu.graphics

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
          val px = dut.io.pixel.bits.x.peek().litValue.toInt
          val py = dut.io.pixel.bits.y.peek().litValue.toInt
          covered += ((px, py))
        }
        dut.clock.step()
        cycles += 1
      }
      // Software reference mirroring the RTL top-left fill rule.
      def plane(ax: Long, ay: Long, bx: Long, by: Long): (Long, Long, Long) =
        ((ay - by), (bx - ax), (ax * by - bx * ay))
      val planes = Array(
        plane(v1._1, v1._2, v2._1, v2._2),
        plane(v2._1, v2._2, v0._1, v0._2),
        plane(v0._1, v0._2, v1._1, v1._2)
      )
      def inside(px: Int, py: Int): Boolean = {
        val x = config.toFixed(px).toLong
        val y = config.toFixed(py).toLong
        val e = planes.map { case (a, b, c) => a * x + b * y + c }
        val area = { val (a, b, c) = planes(0); a * v0._1 + b * v0._2 + c }
        val front = area >= 0
        (0 until 3).forall { i =>
          val (a, b, _) = planes(i)
          val ef = if (front) e(i) else -e(i)
          val af = if (front) a else -a
          val bf = if (front) b else -b
          ef > 0 || (ef == 0 && (af < 0 || (af == 0 && bf < 0)))
        }
      }
      val expected = (0 until 16).flatMap { y => (0 until 16).flatMap { x =>
        if (inside(x, y)) Some((x, y)) else None
      } }.toSet
      assert(covered == expected, s"coverage mismatch: ${(covered -- expected)} vs ${(expected -- covered)}")
    }
  }

  it should "cover edge-sharing triangles exactly once (no cracks, no double-draw)" in {
    val config = GraphicsConfig(screenWidth = 16, screenHeight = 16, subPixelBits = 8)
    // Above-left triangle: shares the diagonal with the below-right triangle.
    val t1 = ((config.toFixed(2), config.toFixed(2)), (config.toFixed(14), config.toFixed(2)), (config.toFixed(2), config.toFixed(14)))
    // Below-right triangle: shares the diagonal x+y==16 edge boundary pixels.
    val t2 = ((config.toFixed(14), config.toFixed(2)), (config.toFixed(14), config.toFixed(14)), (config.toFixed(2), config.toFixed(14)))
    simulate(new TriangleRasterizer(config)) { dut =>
      def drain(t: ((Int, Int), (Int, Int), (Int, Int))): Set[(Int, Int)] = {
        dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
        dut.io.pixel.ready.poke(true.B)
        dut.io.cullMode.poke(0.U)
        dut.io.draw.valid.poke(true.B)
        dut.io.draw.bits.v0.x.poke(t._1._1.S); dut.io.draw.bits.v0.y.poke(t._1._2.S)
        dut.io.draw.bits.v1.x.poke(t._2._1.S); dut.io.draw.bits.v1.y.poke(t._2._2.S)
        dut.io.draw.bits.v2.x.poke(t._3._1.S); dut.io.draw.bits.v2.y.poke(t._3._2.S)
        dut.clock.step(); dut.io.draw.valid.poke(false.B)
        val set = collection.mutable.Set.empty[(Int, Int)]
        while (!dut.io.draw.ready.peek().litToBoolean) {
          if (dut.io.pixel.valid.peek().litToBoolean) {
            set += ((dut.io.pixel.bits.x.peek().litValue.toInt, dut.io.pixel.bits.y.peek().litValue.toInt))
          }
          dut.clock.step()
        }
        set.toSet
      }
      val a = drain(t1)
      val b = drain(t2)
      // Shared edges (the diagonal from (2,14) to (14,2)) must be drawn by
      // exactly one of the two triangles, never both and never neither.
      val overlap = a.intersect(b)
      assert(overlap.isEmpty, s"double-draw on shared pixels: $overlap")
      assert(a.nonEmpty && b.nonEmpty)
    }
  }

  it should "emit one complete TL/TR/BL/BR quad per cycle and mark uncovered helper lanes" in {
    val config = GraphicsConfig(screenWidth = 16, screenHeight = 16,
      subPixelBits = 8)
    simulate(new TriangleRasterizer(config, quadMode = true)) { dut =>
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      dut.io.quad.ready.poke(true.B)
      dut.io.cullMode.poke(0.U)
      dut.io.draw.valid.poke(true.B)
      val vertices = Seq((2, 2), (12, 2), (2, 12))
      dut.io.draw.bits.v0.x.poke(config.toFixed(vertices(0)._1).S)
      dut.io.draw.bits.v0.y.poke(config.toFixed(vertices(0)._2).S)
      dut.io.draw.bits.v1.x.poke(config.toFixed(vertices(1)._1).S)
      dut.io.draw.bits.v1.y.poke(config.toFixed(vertices(1)._2).S)
      dut.io.draw.bits.v2.x.poke(config.toFixed(vertices(2)._1).S)
      dut.io.draw.bits.v2.y.poke(config.toFixed(vertices(2)._2).S)
      dut.clock.step(); dut.io.draw.valid.poke(false.B)

      val quads = collection.mutable.ArrayBuffer.empty[Seq[(Int, Int, Boolean)]]
      var guard = 0
      while (!dut.io.draw.ready.peek().litToBoolean && guard < 2000) {
        if (dut.io.quad.valid.peek().litToBoolean) {
          quads += (0 until 4).map { k =>
            (dut.io.quad.bits.lanes(k).x.peek().litValue.toInt,
              dut.io.quad.bits.lanes(k).y.peek().litValue.toInt,
              dut.io.quad.bits.lanes(k).covered.peek().litToBoolean)
          }
        }
        dut.clock.step(); guard += 1
      }
      assert(guard < 2000 && quads.nonEmpty)
      // Every beat carries the full TL/TR/BL/BR group at an even origin.
      quads.foreach { lanes =>
        val (x, y, _) = lanes.head
        assert((x & 1) == 0 && (y & 1) == 0)
        assert(lanes.map(p => (p._1, p._2)) ==
          Seq((x, y), (x + 1, y), (x, y + 1), (x + 1, y + 1)))
      }
      val emitted = quads.flatten
      val covered = emitted.collect { case (x, y, true) => (x, y) }.toSet
      // This winding's top-left rule excludes x=2/y=2 and owns the diagonal.
      val expected = (3 until 12).flatMap { y => (3 until 12).collect {
        case x if x + y <= 14 => (x, y)
      }}.toSet
      assert(covered == expected)
      assert(emitted.exists(!_._3), "edge quads must contain helper lanes")
      // One quad per cycle: every active beat above fired exactly one quad.
      // Quad origins run over the even-aligned bbox inclusive of maxX/maxY:
      // x,y ∈ {2,4,6,8,10,12}.
      val bboxQuads = 6 * 6
      assert(quads.size == bboxQuads,
        s"expected one beat per bbox quad ($bboxQuads), got ${quads.size}")
    }
  }

  it should "skip a back-facing triangle when cull mode is back" in {
    val config = GraphicsConfig(screenWidth = 16, screenHeight = 16, subPixelBits = 8)
    // Same geometry, but reversed winding => back-facing (negative area).
    val tri = ((config.toFixed(2), config.toFixed(2)), (config.toFixed(2), config.toFixed(14)), (config.toFixed(14), config.toFixed(2)))
    simulate(new TriangleRasterizer(config)) { dut =>
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      dut.io.pixel.ready.poke(true.B)
      dut.io.cullMode.poke(1.U) // cull back-facing
      dut.io.draw.valid.poke(true.B)
      dut.io.draw.bits.v0.x.poke(tri._1._1.S); dut.io.draw.bits.v0.y.poke(tri._1._2.S)
      dut.io.draw.bits.v1.x.poke(tri._2._1.S); dut.io.draw.bits.v1.y.poke(tri._2._2.S)
      dut.io.draw.bits.v2.x.poke(tri._3._1.S); dut.io.draw.bits.v2.y.poke(tri._3._2.S)
      dut.clock.step(); dut.io.draw.valid.poke(false.B)
      var emitted = 0
      while (!dut.io.draw.ready.peek().litToBoolean) {
        if (dut.io.pixel.valid.peek().litToBoolean) emitted += 1
        dut.clock.step()
      }
      assert(emitted == 0, s"back-facing triangle emitted $emitted pixels despite cull back")
    }
  }

  it should "match the M3b software reference bit-exactly across randomized triangles (M4b)" in {
    val config = GraphicsConfig(screenWidth = 64, screenHeight = 64, subPixelBits = 8)

    // Same fill-rule software reference as the M3b tests.
    def plane(ax: Long, ay: Long, bx: Long, by: Long): (Long, Long, Long) =
      ((ay - by), (bx - ax), (ax * by - bx * ay))
    def expected(v: Seq[(Int, Int)]): Set[(Int, Int)] = {
      val f = v.map { case (x, y) => (config.toFixed(x).toLong, config.toFixed(y).toLong) }
      val planes = Seq(
        plane(f(1)._1, f(1)._2, f(2)._1, f(2)._2),
        plane(f(2)._1, f(2)._2, f(0)._1, f(0)._2),
        plane(f(0)._1, f(0)._2, f(1)._1, f(1)._2))
      val areaV = { val (a, b, c) = planes(0); a * f(0)._1 + b * f(0)._2 + c }
      val front = areaV >= 0
      def inside(px: Int, py: Int): Boolean = {
        val x = config.toFixed(px).toLong
        val y = config.toFixed(py).toLong
        val e = planes.map { case (a, b, c) => a * x + b * y + c }
        (0 until 3).forall { i =>
          val (a, b, _) = planes(i)
          val ef = if (front) e(i) else -e(i)
          val af = if (front) a else -a
          val bf = if (front) b else -b
          ef > 0 || (ef == 0 && (af < 0 || (af == 0 && bf < 0)))
        }
      }
      (0 until 64).flatMap { y => (0 until 64).flatMap { x =>
        if (inside(x, y)) Some((x, y)) else None } }.toSet
    }

    def drain(dut: TriangleRasterizer,
              tri: Seq[(Int, Int)],
              cull: Int): Set[(Int, Int)] = {
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      dut.io.pixel.ready.poke(true.B)
      dut.io.cullMode.poke(cull.U)
      dut.io.draw.valid.poke(true.B)
      dut.io.draw.bits.v0.x.poke(config.toFixed(tri(0)._1).S); dut.io.draw.bits.v0.y.poke(config.toFixed(tri(0)._2).S)
      dut.io.draw.bits.v1.x.poke(config.toFixed(tri(1)._1).S); dut.io.draw.bits.v1.y.poke(config.toFixed(tri(1)._2).S)
      dut.io.draw.bits.v2.x.poke(config.toFixed(tri(2)._1).S); dut.io.draw.bits.v2.y.poke(config.toFixed(tri(2)._2).S)
      dut.clock.step(); dut.io.draw.valid.poke(false.B)
      val got = collection.mutable.Set.empty[(Int, Int)]
      var guard = 0
      while (!dut.io.draw.ready.peek().litToBoolean && guard < 20000) {
        if (dut.io.pixel.valid.peek().litToBoolean)
          got += ((dut.io.pixel.bits.x.peek().litValue.toInt,
            dut.io.pixel.bits.y.peek().litValue.toInt))
        dut.clock.step(); guard += 1
      }
      assert(guard < 20000, "rasterizer did not drain")
      got.toSet
    }

    simulate(new TriangleRasterizer(config)) { dut =>
      // Deterministic LCG so failures reproduce.
      var seed = 0x5eed1234L
      def rnd(n: Int): Int = {
        seed = seed * 6364136223846793005L + 1442695040888963407L
        (((seed >>> 33) & 0x7fffffffL) % n).toInt
      }
      for (t <- 0 until 24) {
        // Mix of solid interior, slivers, off-screen and reversed winding.
        def px(): Int = rnd(80) - 8
        def py(): Int = rnd(80) - 8
        var tri = Seq((px(), py()), (px(), py()), (px(), py()))
        if (t % 3 == 0) tri = tri.map { case (x, y) => (y, x) } // flip winding
        if (tri.map(_._1).distinct.size < 2 || tri.map(_._2).distinct.size < 2)
          tri = Seq((4, 4), (40, 12), (12, 40))
        val exp = expected(tri)
        val got = drain(dut, tri, 0)
        assert(got == exp,
          s"triangle $t $tri mismatch: hw-only=${(got -- exp).take(6)} " +
            s"sw-only=${(exp -- got).take(6)}")
      }
    }
  }
}
