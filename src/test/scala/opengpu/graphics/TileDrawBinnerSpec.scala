package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class TileDrawBinnerSpec extends AnyFlatSpec {
  behavior of "TileDrawBinner"

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
}
