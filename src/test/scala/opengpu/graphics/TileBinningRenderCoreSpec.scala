package opengpu.graphics

import chisel3._
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class TileBinningRenderCoreSpec extends AnyFlatSpec {
  behavior of "tile-major command replay"

  private def q(v: Double): Int = (v * (1 << 16)).toInt

  private def record(red: Boolean, depth: Int, scissor: Boolean = false): Seq[Int] = {
    val w = Array.fill(40)(0)
    val vertices = Seq((-1.0, -1.0), (1.0, -1.0), (-1.0, 1.0))
    for (i <- 0 until 3) {
      w(4 * i) = q(vertices(i)._1)
      w(4 * i + 1) = q(vertices(i)._2)
      w(4 * i + 3) = q(1.0)
      w(12 + 3 * i) = if (red) 255 else 0
      w(13 + 3 * i) = if (red) 0 else 255
      w(21 + i) = depth
    }
    if (scissor) {
      w(32) = 1 << 18
      w(38) = 8
      w(39) = (16 << 16) | 24
    }
    w.toSeq
  }

  private def render(replay: Boolean, sampleMode: Int):
      (Seq[Int], Seq[Int], Int) = {
    val samples = 1 << sampleMode
    val words = 32 * 16 * samples
    val cfg = GraphicsConfig(screenWidth = 32, screenHeight = 16,
      tileSize = 16, tileAttachments = true, tileBinning = replay)
    val cmdBase = 0x4000
    val colorBase = 0x8000
    val depthBase = 0x10000
    val command = Array.fill(1 << 16)(0)
    (record(red = true, depth = 0x10) ++
      record(red = false, depth = 0x08, scissor = true))
      .zipWithIndex.foreach { case (w, i) => command(cmdBase / 4 + i) = w }
    var result = (Seq.empty[Int], Seq.empty[Int], 0)
    simulate(new RenderCore(cfg)) { dut =>
      val fb = Array.fill(1 << 17)(0)
      for (i <- 0 until words) fb(depthBase / 4 + i) = 0x00ffffff
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      dut.io.cmdBase.poke(cmdBase.U)
      dut.io.cmdCount.poke(2.U)
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
      dut.io.cbMem.req.ready.poke(true.B)
      dut.io.cbMem.resp.valid.poke(false.B)
      dut.io.fbMem.req.ready.poke(true.B)
      dut.io.fbMem.resp.valid.poke(false.B)
      dut.io.start.poke(true.B)
      dut.clock.step()
      dut.io.start.poke(false.B)

      val commands = scala.collection.mutable.Queue.empty[(Int, Long)]
      val pixels = scala.collection.mutable.Queue.empty[(Boolean, Int, Long)]
      var requests = 0
      var cycles = 0
      while (!dut.io.done.peek().litToBoolean && cycles < 100000) {
        if (commands.nonEmpty) {
          val (addr, data) = commands.head
          dut.io.cbMem.resp.valid.poke(true.B)
          dut.io.cbMem.resp.bits.addr.poke(addr.U)
          dut.io.cbMem.resp.bits.data.poke(data.U)
        } else dut.io.cbMem.resp.valid.poke(false.B)
        if (commands.nonEmpty && dut.io.cbMem.resp.ready.peek().litToBoolean)
          commands.dequeue()
        if (dut.io.cbMem.req.valid.peek().litToBoolean) {
          val addr = dut.io.cbMem.req.bits.addr.peek().litValue.toInt
          commands.enqueue((addr, command(addr / 4) & 0xffffffffL))
        }
        if (pixels.nonEmpty) {
          val (write, addr, data) = pixels.head
          dut.io.fbMem.resp.valid.poke(true.B)
          dut.io.fbMem.resp.bits.write.poke(write.B)
          dut.io.fbMem.resp.bits.addr.poke(addr.U)
          dut.io.fbMem.resp.bits.data.poke(data.U)
        } else dut.io.fbMem.resp.valid.poke(false.B)
        if (pixels.nonEmpty && dut.io.fbMem.resp.ready.peek().litToBoolean)
          pixels.dequeue()
        if (dut.io.fbMem.req.valid.peek().litToBoolean) {
          requests += 1
          val addr = dut.io.fbMem.req.bits.addr.peek().litValue.toInt
          val write = dut.io.fbMem.req.bits.write.peek().litToBoolean
          if (write) fb(addr / 4) = dut.io.fbMem.req.bits.data.peek().litValue.toInt
          pixels.enqueue((write, addr, fb(addr / 4) & 0xffffffffL))
        }
        dut.clock.step()
        cycles += 1
      }
      assert(cycles < 100000, s"tile replay did not drain (replay=$replay)")
      result = ((0 until words).map(i => fb(colorBase / 4 + i)),
        (0 until words).map(i => fb(depthBase / 4 + i)), requests)
    }
    result
  }

  for (sampleMode <- Seq(0, 1, 2)) {
  it should s"preserve both tiles and reduce framebuffer traffic in mode $sampleMode" in {
    val samples = 1 << sampleMode
    val baseline = render(replay = false, sampleMode = sampleMode)
    val binned = render(replay = true, sampleMode = sampleMode)
    assert(binned._1 == baseline._1, "tile-major replay changed color")
    assert(binned._2 == baseline._2, "tile-major replay changed depth")
    assert(binned._1.count(_ != 0) > 100)
    assert(binned._1.slice((5 * 32 + 16) * samples,
      (5 * 32 + 32) * samples).exists(_ != 0),
      "the right-hand tile must be rendered")
    assert(binned._3 < baseline._3,
      s"tile-major replay must save framebuffer transactions: ${baseline._3} to ${binned._3}")
  }
  }
}
