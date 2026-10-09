package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class TileRenderPipelineSpec extends AnyFlatSpec {
  behavior of "tile-ordered render pipeline"

  private def q(v: Double): Int = (v * (1 << 16)).toInt

  private def render(tileSize: Int, attachments: Boolean = false,
      draws: Int = 1, sampleMode: Int = 0):
      (Seq[Int], Seq[Int], Int) = {
    val samples = 1 << sampleMode
    val cfg = GraphicsConfig(screenWidth = 32, screenHeight = 32,
      tileSize = tileSize, tileAttachments = attachments)
    val colorBase = 0x1000
    val depthBase = 0x20000
    val words = 32 * 32 * samples
    var result = (Seq.empty[Int], Seq.empty[Int], 0)
    simulate(new RenderPipeline(cfg)) { dut =>
      val mem = Array.fill(1 << 18)(0)
      for (i <- 0 until words) mem(depthBase / 4 + i) = 0x00ffffff
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      dut.io.colorBase.poke(colorBase.U)
      dut.io.depthBase.poke(depthBase.U)
      dut.io.stride.poke((32 * samples * 4).U)
      dut.io.depthTestEnable.poke(true.B)
      dut.io.depthFunc.poke(0.U)
      dut.io.depthWriteEnable.poke(true.B)
      dut.io.cullMode.poke(0.U)
      dut.io.sampleMode.poke(sampleMode.U)
      dut.io.texEnable.poke(false.B)
      dut.io.texBase.poke(0.U)
      dut.io.texWidth.poke(0.U)
      dut.io.texHeight.poke(0.U)
      dut.io.texWrapClamp.poke(false.B)
      dut.io.texMaxLevel.poke(0.U)
      dut.io.texMem.req.ready.poke(true.B)
      dut.io.texMem.resp.valid.poke(false.B)
      dut.io.mem.req.ready.poke(true.B)
      dut.io.mem.resp.valid.poke(false.B)

      val draw = dut.io.draw.bits.asInstanceOf[SceneTriangle]
      draw.poke(0.U.asTypeOf(new SceneTriangle(cfg)))
      draw.stateOverride.poke(false.B)
      val vertices = Seq((-1.0, -1.0), (1.0, -1.0), (-1.0, 1.0))
      for (i <- 0 until 3) {
        draw.clip(i).x.poke(q(vertices(i)._1).S)
        draw.clip(i).y.poke(q(vertices(i)._2).S)
        draw.clip(i).w.poke(q(1.0).S)
      }
      val pending = scala.collection.mutable.Queue.empty[(Boolean, Int, Long)]
      val responses = scala.collection.mutable.Queue.empty[(Boolean, Int, Long)]
      var requests = 0
      for (drawIndex <- 0 until draws) {
        for (i <- 0 until 3) {
          draw.color(i).r.poke((if (drawIndex == 0) 255 else 0).U)
          draw.color(i).g.poke((if (drawIndex == 0) 0 else 255).U)
          draw.depth(i).poke((if (drawIndex == 0) 0x10 else 0x08).S)
        }
        dut.io.draw.ready.expect(true.B)
        dut.io.draw.valid.poke(true.B)
        dut.clock.step()
        dut.io.draw.valid.poke(false.B)
        var cycles = 0
        while (!dut.io.done.peek().litToBoolean && cycles < 80000) {
          while (pending.nonEmpty) responses.enqueue(pending.dequeue())
          if (dut.io.mem.req.valid.peek().litToBoolean) {
            requests += 1
            val addr = dut.io.mem.req.bits.addr.peek().litValue.toInt
            val write = dut.io.mem.req.bits.write.peek().litToBoolean
            val data = dut.io.mem.req.bits.data.peek().litValue.toInt
            if (write) mem(addr / 4) = data
            pending.enqueue((write, addr, mem(addr / 4) & 0xffffffffL))
          }
          if (responses.nonEmpty) {
            val (write, addr, data) = responses.head
            dut.io.mem.resp.valid.poke(true.B)
            dut.io.mem.resp.bits.write.poke(write.B)
            dut.io.mem.resp.bits.addr.poke(addr.U)
            dut.io.mem.resp.bits.data.poke(data.U)
            if (dut.io.mem.resp.ready.peek().litToBoolean) responses.dequeue()
          } else dut.io.mem.resp.valid.poke(false.B)
          dut.clock.step()
          cycles += 1
        }
        assert(cycles < 80000,
          s"render pipeline did not drain: requests=$requests pending=${pending.size} responses=${responses.size}")
      }
      result = (
        (0 until words).map(i => mem(colorBase / 4 + i)),
        (0 until words).map(i => mem(depthBase / 4 + i)), requests)
    }
    result
  }

  it should "preserve the color and depth images across tile boundaries" in {
    val baseline = render(0)
    val tiled = render(16)
    assert(tiled._1 == baseline._1 && tiled._2 == baseline._2)
    assert(tiled._1.count(_ != 0) > 100)
    assert(tiled._1(20 * 32 + 5) != 0) // A covered pixel in a second tile row.
  }

  it should "preserve the image with on-chip tile attachments" in {
    val baseline = render(0, draws = 2)
    val cached = render(16, attachments = true, draws = 2)
    assert(cached._1 == baseline._1 && cached._2 == baseline._2)
    assert(cached._3 > 0)
  }

  for (mode <- Seq(1, 2)) {
    it should s"preserve all ${1 << mode}x sample words with tile attachments" in {
      val baseline = render(0, sampleMode = mode)
      val cached = render(16, attachments = true, sampleMode = mode)
      assert(cached._1 == baseline._1, s"color differs in mode $mode")
      assert(cached._2 == baseline._2, s"depth differs in mode $mode")
      assert(cached._1.count(_ != 0) > 100)
    }
  }
}
