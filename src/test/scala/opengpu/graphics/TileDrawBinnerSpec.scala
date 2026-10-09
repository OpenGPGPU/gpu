package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class TileDrawBinnerSpec extends AnyFlatSpec {
  behavior of "TileDrawBinner"

  it should "retain all desktop triangle tiles at 640x480" in {
    val cfg = GraphicsConfig(screenWidth = 640, screenHeight = 480,
      tileSize = 16, tileAttachments = true, tileBinning = true)
    def ndc(pixel: Int, extent: Int): Int =
      (((pixel.toLong * 2 - extent) << 16) / extent).toInt
    simulate(new TileDrawBinner(cfg)) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      dut.io.drawIn.valid.poke(false.B)
      dut.io.drawOut.ready.poke(true.B)
      dut.io.renderDrained.poke(true.B)
      dut.io.passDone.poke(true.B)
      dut.io.inputDone.poke(false.B)
      dut.io.start.poke(true.B)
      dut.clock.step()
      dut.io.start.poke(false.B)

      val vertices = Seq((16, 48), (336, 48), (16, 464))
      val seen = scala.collection.mutable.Set.empty[(Int, Int)]
      var sent = false
      var cycles = 0
      while (!dut.io.done.peek().litToBoolean && cycles < 12000) {
        dut.io.drawIn.valid.poke((!sent).B)
        dut.io.drawIn.bits.poke(0.U.asTypeOf(new SceneTriangle(cfg)))
        for (i <- 0 until 3) {
          dut.io.drawIn.bits.clip(i).x.poke(ndc(vertices(i)._1, 640).S)
          dut.io.drawIn.bits.clip(i).y.poke(ndc(vertices(i)._2, 480).S)
          dut.io.drawIn.bits.clip(i).w.poke(65536.S)
        }
        if (!sent && dut.io.drawIn.ready.peek().litToBoolean) sent = true
        if (sent) dut.io.inputDone.poke(true.B)
        if (dut.io.drawOut.valid.peek().litToBoolean)
          seen += ((dut.io.drawOut.bits.scissorMinX.peek().litValue.toInt,
            dut.io.drawOut.bits.scissorMinY.peek().litValue.toInt))
        dut.clock.step()
        cycles += 1
      }
      assert(cycles < 12000)
      val expected = for {
        y <- 48 until 464 by 16
        x <- 16 until 336 by 16
      } yield (x, y)
      assert(expected.forall(seen.contains),
        s"missing desktop tiles: ${expected.filterNot(seen.contains)}")
    }
  }

  it should "replay ordered draws per tile and intersect their scissors" in {
    val cfg = GraphicsConfig(screenWidth = 30, screenHeight = 16,
      tileSize = 16, tileAttachments = true, tileBinning = true)
    simulate(new TileDrawBinner(cfg)) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      dut.io.drawIn.valid.poke(false.B)
      dut.io.drawOut.ready.poke(true.B)
      dut.io.renderDrained.poke(true.B)
      dut.io.passDone.poke(true.B)
      dut.io.inputDone.poke(false.B)
      dut.io.start.poke(true.B)
      dut.clock.step()
      dut.io.start.poke(false.B)

      var sent = 0
      var passEnds = 0
      val seen = scala.collection.mutable.ArrayBuffer.empty[(Int, Int, Int)]
      var cycles = 0
      while (!dut.io.done.peek().litToBoolean && cycles < 100) {
        if (sent < 2) {
          dut.io.drawIn.valid.poke(true.B)
          dut.io.drawIn.bits.poke(0.U.asTypeOf(new SceneTriangle(cfg)))
          dut.io.drawIn.bits.shaderPc.poke((sent + 1).U)
          dut.io.drawIn.bits.scissorEnable.poke((sent == 1).B)
          dut.io.drawIn.bits.scissorMinX.poke(8.U)
          dut.io.drawIn.bits.scissorMaxX.poke(24.U)
          dut.io.drawIn.bits.scissorMinY.poke(0.U)
          dut.io.drawIn.bits.scissorMaxY.poke(16.U)
          if (dut.io.drawIn.ready.peek().litToBoolean) sent += 1
        } else {
          dut.io.drawIn.valid.poke(false.B)
          dut.io.inputDone.poke(true.B)
        }
        if (dut.io.drawOut.valid.peek().litToBoolean) {
          seen += ((dut.io.drawOut.bits.shaderPc.peek().litValue.toInt,
            dut.io.drawOut.bits.scissorMinX.peek().litValue.toInt,
            dut.io.drawOut.bits.scissorMaxX.peek().litValue.toInt))
          dut.io.drawOut.bits.scissorEnable.expect(true.B)
        }
        if (dut.io.passEnd.peek().litToBoolean) passEnds += 1
        dut.clock.step()
        cycles += 1
      }
      assert(cycles < 100, "tile draw replay did not finish")
      assert(seen.toSeq == Seq((1, 0, 16), (2, 8, 16),
        (1, 16, 30), (2, 16, 24)))
      assert(passEnds == 1)
    }
  }

  it should "flush between bounded chunks without losing later commands" in {
    val cfg = GraphicsConfig(screenWidth = 16, screenHeight = 16,
      tileSize = 16, tileAttachments = true, tileBinning = true)
    simulate(new TileDrawBinner(cfg)) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      dut.io.drawIn.valid.poke(false.B)
      dut.io.drawOut.ready.poke(true.B)
      dut.io.renderDrained.poke(true.B)
      dut.io.passDone.poke(true.B)
      dut.io.inputDone.poke(false.B)
      dut.io.start.poke(true.B)
      dut.clock.step()
      dut.io.start.poke(false.B)
      var sent = 0
      var passEnds = 0
      val seen = scala.collection.mutable.ArrayBuffer.empty[Int]
      var cycles = 0
      while (!dut.io.done.peek().litToBoolean && cycles < 200) {
        dut.io.drawIn.valid.poke((sent < 9).B)
        dut.io.drawIn.bits.poke(0.U.asTypeOf(new SceneTriangle(cfg)))
        dut.io.drawIn.bits.shaderPc.poke((sent + 1).U)
        if (sent < 9 && dut.io.drawIn.ready.peek().litToBoolean) sent += 1
        if (sent == 9) dut.io.inputDone.poke(true.B)
        if (dut.io.drawOut.valid.peek().litToBoolean)
          seen += dut.io.drawOut.bits.shaderPc.peek().litValue.toInt
        if (dut.io.passEnd.peek().litToBoolean) passEnds += 1
        dut.clock.step()
        cycles += 1
      }
      assert(cycles < 200)
      assert(seen.toSeq == (1 to 9))
      assert(passEnds == 2)
    }
  }

  it should "skip tiles outside conservative clip-space triangle bounds" in {
    val cfg = GraphicsConfig(screenWidth = 32, screenHeight = 16,
      tileSize = 16, tileAttachments = true, tileBinning = true)
    simulate(new TileDrawBinner(cfg)) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      dut.io.drawIn.valid.poke(false.B)
      dut.io.drawOut.ready.poke(true.B)
      dut.io.renderDrained.poke(true.B)
      dut.io.passDone.poke(true.B)
      dut.io.inputDone.poke(false.B)
      dut.io.start.poke(true.B)
      dut.clock.step()
      dut.io.start.poke(false.B)

      val xs = Seq(Seq(-65536, -32768, -13107), Seq(13107, 65536, 32768))
      var sent = 0
      val seen = scala.collection.mutable.ArrayBuffer.empty[(Int, Int)]
      var cycles = 0
      while (!dut.io.done.peek().litToBoolean && cycles < 100) {
        dut.io.drawIn.valid.poke((sent < 2).B)
        dut.io.drawIn.bits.poke(0.U.asTypeOf(new SceneTriangle(cfg)))
        if (sent < 2) {
          dut.io.drawIn.bits.shaderPc.poke((sent + 1).U)
          for (i <- 0 until 3) {
            dut.io.drawIn.bits.clip(i).x.poke(xs(sent)(i).S)
            dut.io.drawIn.bits.clip(i).w.poke(65536.S)
          }
        }
        if (sent < 2 && dut.io.drawIn.ready.peek().litToBoolean) sent += 1
        if (sent == 2) dut.io.inputDone.poke(true.B)
        if (dut.io.drawOut.valid.peek().litToBoolean)
          seen += ((dut.io.drawOut.bits.shaderPc.peek().litValue.toInt,
            dut.io.drawOut.bits.scissorMinX.peek().litValue.toInt))
        dut.clock.step()
        cycles += 1
      }
      assert(cycles < 100)
      assert(seen.toSeq == Seq((1, 0), (2, 16)))
    }
  }

  it should "replay sparse tile masks in submission order" in {
    val cfg = GraphicsConfig(screenWidth = 32, screenHeight = 16,
      tileSize = 16, tileAttachments = true, tileBinning = true)
    simulate(new TileDrawBinner(cfg)) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      dut.io.drawIn.valid.poke(false.B)
      dut.io.drawOut.ready.poke(true.B)
      dut.io.renderDrained.poke(true.B)
      dut.io.passDone.poke(true.B)
      dut.io.inputDone.poke(false.B)
      dut.io.start.poke(true.B)
      dut.clock.step()
      dut.io.start.poke(false.B)

      var sent = 0
      val seen = scala.collection.mutable.ArrayBuffer.empty[(Int, Int)]
      var cycles = 0
      while (!dut.io.done.peek().litToBoolean && cycles < 150) {
        dut.io.drawIn.valid.poke((sent < 5).B)
        dut.io.drawIn.bits.poke(0.U.asTypeOf(new SceneTriangle(cfg)))
        if (sent < 5) {
          dut.io.drawIn.bits.shaderPc.poke((sent + 1).U)
          dut.io.drawIn.bits.scissorEnable.poke((sent != 4).B)
          dut.io.drawIn.bits.scissorMinX.poke((if (sent % 2 == 0) 0 else 16).U)
          dut.io.drawIn.bits.scissorMaxX.poke((if (sent % 2 == 0) 16 else 32).U)
          dut.io.drawIn.bits.scissorMaxY.poke(16.U)
        }
        if (sent < 5 && dut.io.drawIn.ready.peek().litToBoolean) sent += 1
        if (sent == 5) dut.io.inputDone.poke(true.B)
        if (dut.io.drawOut.valid.peek().litToBoolean)
          seen += ((dut.io.drawOut.bits.scissorMinX.peek().litValue.toInt,
            dut.io.drawOut.bits.shaderPc.peek().litValue.toInt))
        dut.clock.step()
        cycles += 1
      }
      assert(cycles < 150)
      assert(seen.toSeq == Seq((0, 1), (0, 3), (0, 5),
        (16, 2), (16, 4), (16, 5)))
    }
  }

  it should "skip per-draw geometry scans for tiles outside every scissor" in {
    val cfg = GraphicsConfig(screenWidth = 64, screenHeight = 64,
      tileSize = 16, tileAttachments = true, tileBinning = true)
    simulate(new TileDrawBinner(cfg)) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      dut.io.drawIn.valid.poke(false.B)
      dut.io.drawOut.ready.poke(true.B)
      dut.io.renderDrained.poke(true.B)
      dut.io.passDone.poke(true.B)
      dut.io.inputDone.poke(false.B)
      dut.io.start.poke(true.B)
      dut.clock.step()
      dut.io.start.poke(false.B)

      var sent = 0
      val seen = scala.collection.mutable.ArrayBuffer.empty[Int]
      var cycles = 0
      while (!dut.io.done.peek().litToBoolean && cycles < 200) {
        dut.io.drawIn.valid.poke((sent < 8).B)
        dut.io.drawIn.bits.poke(0.U.asTypeOf(new SceneTriangle(cfg)))
        if (sent < 8) {
          dut.io.drawIn.bits.shaderPc.poke((sent + 1).U)
          dut.io.drawIn.bits.scissorEnable.poke(true.B)
          dut.io.drawIn.bits.scissorMaxX.poke(16.U)
          dut.io.drawIn.bits.scissorMaxY.poke(16.U)
        }
        if (sent < 8 && dut.io.drawIn.ready.peek().litToBoolean) sent += 1
        if (sent == 8) dut.io.inputDone.poke(true.B)
        if (dut.io.drawOut.valid.peek().litToBoolean)
          seen += dut.io.drawOut.bits.shaderPc.peek().litValue.toInt
        dut.clock.step()
        cycles += 1
      }
      assert(seen.toSeq == (1 to 8))
      assert(cycles < 160, s"sparse scissor binning took $cycles cycles")
    }
  }

  it should "prefilter unscissored triangles by their conservative tile ranges" in {
    val cfg = GraphicsConfig(screenWidth = 64, screenHeight = 64,
      tileSize = 16, tileAttachments = true, tileBinning = true)
    simulate(new TileDrawBinner(cfg)) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      dut.io.drawIn.valid.poke(false.B)
      dut.io.drawOut.ready.poke(true.B)
      dut.io.renderDrained.poke(true.B)
      dut.io.passDone.poke(true.B)
      dut.io.inputDone.poke(false.B)
      dut.io.start.poke(true.B)
      dut.clock.step()
      dut.io.start.poke(false.B)

      val xs = Seq(-65536, -52428, -39321)
      val ys = Seq(-65536, -39321, -52428)
      val ws = Seq(65536, 131072, 32768)
      var sent = 0
      val seen = scala.collection.mutable.ArrayBuffer.empty[(Int, Int, Int)]
      var cycles = 0
      while (!dut.io.done.peek().litToBoolean && cycles < 260) {
        dut.io.drawIn.valid.poke((sent < 8).B)
        dut.io.drawIn.bits.poke(0.U.asTypeOf(new SceneTriangle(cfg)))
        if (sent < 8) {
          dut.io.drawIn.bits.shaderPc.poke((sent + 1).U)
          for (i <- 0 until 3) {
            dut.io.drawIn.bits.clip(i).x.poke(((xs(i).toLong * ws(i)) / 65536).toInt.S)
            dut.io.drawIn.bits.clip(i).y.poke(((ys(i).toLong * ws(i)) / 65536).toInt.S)
            dut.io.drawIn.bits.clip(i).w.poke(ws(i).S)
          }
        }
        if (sent < 8 && dut.io.drawIn.ready.peek().litToBoolean) sent += 1
        if (sent == 8) dut.io.inputDone.poke(true.B)
        if (dut.io.drawOut.valid.peek().litToBoolean)
          seen += ((dut.io.drawOut.bits.shaderPc.peek().litValue.toInt,
            dut.io.drawOut.bits.scissorMinX.peek().litValue.toInt,
            dut.io.drawOut.bits.scissorMinY.peek().litValue.toInt))
        dut.clock.step()
        cycles += 1
      }
      assert(seen.toSeq == (1 to 8).map(i => (i, 0, 0)))
      assert(cycles < 210, s"unscissored bounds prefilter took $cycles cycles")
    }
  }

  it should "omit an unscissored triangle entirely outside the target" in {
    val cfg = GraphicsConfig(screenWidth = 32, screenHeight = 16,
      tileSize = 16, tileAttachments = true, tileBinning = true)
    simulate(new TileDrawBinner(cfg)) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      dut.io.drawIn.valid.poke(true.B)
      dut.io.drawIn.bits.poke(0.U.asTypeOf(new SceneTriangle(cfg)))
      for (i <- 0 until 3) {
        dut.io.drawIn.bits.clip(i).x.poke((98304 + i * 16384).S)
        dut.io.drawIn.bits.clip(i).w.poke(65536.S)
      }
      dut.io.drawOut.ready.poke(true.B)
      dut.io.renderDrained.poke(true.B)
      dut.io.passDone.poke(true.B)
      dut.io.inputDone.poke(false.B)
      dut.io.start.poke(true.B)
      dut.clock.step()
      dut.io.start.poke(false.B)
      var sent = false
      var emitted = 0
      var cycles = 0
      while (!dut.io.done.peek().litToBoolean && cycles < 100) {
        dut.io.drawIn.valid.poke((!sent).B)
        if (!sent && dut.io.drawIn.ready.peek().litToBoolean) sent = true
        if (sent) dut.io.inputDone.poke(true.B)
        if (dut.io.drawOut.valid.peek().litToBoolean) emitted += 1
        dut.clock.step()
        cycles += 1
      }
      assert(cycles < 100)
      assert(emitted == 0)
    }
  }

  it should "use bounded searches for sparse geometry on a wider grid" in {
    val cfg = GraphicsConfig(screenWidth = 256, screenHeight = 64,
      tileSize = 16, tileAttachments = true, tileBinning = true)
    simulate(new TileDrawBinner(cfg)) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      dut.io.drawIn.valid.poke(false.B)
      dut.io.drawOut.ready.poke(true.B)
      dut.io.renderDrained.poke(true.B)
      dut.io.passDone.poke(true.B)
      dut.io.inputDone.poke(false.B)
      dut.io.start.poke(true.B)
      dut.clock.step()
      dut.io.start.poke(false.B)

      val leftXs = Seq(-65536, -62259, -58982)
      val leftYs = Seq(-65536, -39321, -52428)
      val middleXs = Seq(2048, 4096, 6144)
      val middleYs = Seq(8192, 16384, 24576)
      val ws = Seq(65536, 131072, 32768)
      var sent = 0
      val seen = scala.collection.mutable.ArrayBuffer.empty[(Int, Int, Int)]
      var cycles = 0
      while (!dut.io.done.peek().litToBoolean && cycles < 600) {
        dut.io.drawIn.valid.poke((sent < 8).B)
        dut.io.drawIn.bits.poke(0.U.asTypeOf(new SceneTriangle(cfg)))
        if (sent < 8) {
          dut.io.drawIn.bits.shaderPc.poke((sent + 1).U)
          val xs = if (sent % 2 == 0) leftXs else middleXs
          val ys = if (sent % 2 == 0) leftYs else middleYs
          for (i <- 0 until 3) {
            dut.io.drawIn.bits.clip(i).x.poke(((xs(i).toLong * ws(i)) / 65536).toInt.S)
            dut.io.drawIn.bits.clip(i).y.poke(((ys(i).toLong * ws(i)) / 65536).toInt.S)
            dut.io.drawIn.bits.clip(i).w.poke(ws(i).S)
          }
        }
        if (sent < 8 && dut.io.drawIn.ready.peek().litToBoolean) sent += 1
        if (sent == 8) dut.io.inputDone.poke(true.B)
        if (dut.io.drawOut.valid.peek().litToBoolean)
          seen += ((dut.io.drawOut.bits.shaderPc.peek().litValue.toInt,
            dut.io.drawOut.bits.scissorMinX.peek().litValue.toInt,
            dut.io.drawOut.bits.scissorMinY.peek().litValue.toInt))
        dut.clock.step()
        cycles += 1
      }
      assert(seen.toSeq == Seq((1, 0, 0), (3, 0, 0), (5, 0, 0), (7, 0, 0),
        (2, 128, 32), (4, 128, 32), (6, 128, 32), (8, 128, 32)))
      assert(cycles < 500, s"wide-grid bounds search took $cycles cycles")
    }
  }

  it should "discard triangles beyond either side of a wide target" in {
    val cfg = GraphicsConfig(screenWidth = 256, screenHeight = 64,
      tileSize = 16, tileAttachments = true, tileBinning = true)
    simulate(new TileDrawBinner(cfg)) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      dut.io.drawIn.valid.poke(false.B)
      dut.io.drawOut.ready.poke(true.B)
      dut.io.renderDrained.poke(true.B)
      dut.io.passDone.poke(true.B)
      dut.io.inputDone.poke(false.B)
      dut.io.start.poke(true.B)
      dut.clock.step()
      dut.io.start.poke(false.B)

      var sent = 0
      var emitted = 0
      var cycles = 0
      while (!dut.io.done.peek().litToBoolean && cycles < 500) {
        dut.io.drawIn.valid.poke((sent < 2).B)
        dut.io.drawIn.bits.poke(0.U.asTypeOf(new SceneTriangle(cfg)))
        if (sent < 2) {
          for (i <- 0 until 3) {
            val x = (98304 + i * 16384) * (if (sent == 0) 1 else -1)
            dut.io.drawIn.bits.clip(i).x.poke(x.S)
            dut.io.drawIn.bits.clip(i).w.poke(65536.S)
          }
        }
        if (sent < 2 && dut.io.drawIn.ready.peek().litToBoolean) sent += 1
        if (sent == 2) dut.io.inputDone.poke(true.B)
        if (dut.io.drawOut.valid.peek().litToBoolean) emitted += 1
        dut.clock.step()
        cycles += 1
      }
      assert(cycles < 500)
      assert(emitted == 0)
    }
  }
}
