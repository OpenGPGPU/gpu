package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class TileRasterizerSpec extends AnyFlatSpec {
  behavior of "tile-ordered triangle rasterization"

  private case class Sample(x: Int, y: Int, e0: BigInt, e1: BigInt,
                            e2: BigInt, mask: BigInt)

  private def drain(tileSize: Int, quadMode: Boolean): Seq[Sample] = {
    val cfg = GraphicsConfig(screenWidth = 40, screenHeight = 40,
      subPixelBits = 8, tileSize = tileSize)
    var seen = Vector.empty[Sample]
    simulate(new TriangleRasterizer(cfg, quadMode)) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      dut.io.sampleMode.poke(2.U) // 4x MSAA exercises per-lane masks.
      dut.io.cullMode.poke(0.U)
      dut.io.scissorEnable.poke(true.B)
      dut.io.scissorMinX.poke(5.U)
      dut.io.scissorMinY.poke(7.U)
      dut.io.scissorMaxX.poke(37.U)
      dut.io.scissorMaxY.poke(38.U)
      dut.io.draw.valid.poke(true.B)
      val vertices = Seq((3, 5), (38, 7), (4, 39))
      dut.io.draw.bits.v0.x.poke(cfg.toFixed(vertices(0)._1).S)
      dut.io.draw.bits.v0.y.poke(cfg.toFixed(vertices(0)._2).S)
      dut.io.draw.bits.v1.x.poke(cfg.toFixed(vertices(1)._1).S)
      dut.io.draw.bits.v1.y.poke(cfg.toFixed(vertices(1)._2).S)
      dut.io.draw.bits.v2.x.poke(cfg.toFixed(vertices(2)._1).S)
      dut.io.draw.bits.v2.y.poke(cfg.toFixed(vertices(2)._2).S)
      dut.io.pixel.ready.poke(true.B)
      dut.io.quad.ready.poke(true.B)
      dut.clock.step()
      dut.io.draw.valid.poke(false.B)

      var cycles = 0
      while (!dut.io.draw.ready.peek().litToBoolean && cycles < 3000) {
        val ready = cycles % 5 != 0
        dut.io.pixel.ready.poke(ready.B)
        dut.io.quad.ready.poke(ready.B)
        if (quadMode && ready && dut.io.quad.valid.peek().litToBoolean) {
          for (lane <- dut.io.quad.bits.lanes) {
            seen :+= Sample(lane.x.peek().litValue.toInt,
              lane.y.peek().litValue.toInt,
              lane.e0.peek().litValue, lane.e1.peek().litValue,
              lane.e2.peek().litValue, lane.coverageMask.peek().litValue)
          }
        } else if (!quadMode && ready && dut.io.pixel.valid.peek().litToBoolean) {
          val p = dut.io.pixel.bits
          seen :+= Sample(p.x.peek().litValue.toInt, p.y.peek().litValue.toInt,
            p.e0.peek().litValue, p.e1.peek().litValue,
            p.e2.peek().litValue, p.coverageMask.peek().litValue)
        }
        dut.clock.step()
        cycles += 1
      }
      assert(cycles < 3000, "rasterizer did not drain")
    }
    seen
  }

  for (quadMode <- Seq(false, true)) {
    it should s"match scanline coverage and edge planes in ${if (quadMode) "quad" else "pixel"} mode" in {
      val scanline = drain(tileSize = 0, quadMode)
      val tiled = drain(tileSize = 16, quadMode)
      assert(tiled.nonEmpty)
      assert(tiled.size == tiled.map(p => (p.x, p.y)).distinct.size,
        "tile traversal emitted a position twice")
      val a = tiled.sortBy(p => (p.y, p.x))
      val b = scanline.sortBy(p => (p.y, p.x))
      val mismatch = a.zip(b).indexWhere { case (x, y) => x != y }
      assert(a.size == b.size && mismatch < 0,
        s"tiled=${a.size} scanline=${b.size} first mismatch $mismatch: " +
          s"${a.lift(mismatch)} != ${b.lift(mismatch)}")
      val tileOrder = tiled.map(p => (p.y / 16, p.x / 16))
      assert(tileOrder == tileOrder.sorted, "tile traversal revisited a tile")
      assert(tileOrder.distinct.size >= 4, "test must cross tile boundaries")
    }
  }
}
