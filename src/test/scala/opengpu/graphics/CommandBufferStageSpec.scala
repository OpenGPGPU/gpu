package opengpu.graphics

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec

class CommandBufferStageSpec extends AnyFlatSpec {
  behavior of "CommandBufferStage"

  private def q(v: Double): Int = (v * (1 << 16)).toInt

  // Encode a SceneTriangle plus shader descriptor into a 26-word record.
  private def encode(
    tri: Seq[((Int, Int, Int, Int), (Int, Int, Int), Int)],
    shaderPc: Int,
    shaderKernarg: Int
  ): Seq[Int] = {
    val w = Seq.newBuilder[Int]
    for (i <- 0 until 3) {
      w += tri(i)._1._1; w += tri(i)._1._2; w += tri(i)._1._3; w += tri(i)._1._4
    }
    for (i <- 0 until 3) { w += tri(i)._2._1; w += tri(i)._2._2; w += tri(i)._2._3 }
    for (i <- 0 until 3) { w += tri(i)._3 }
    w += shaderPc; w += shaderKernarg
    w.result()
  }

  it should "decode draw-call records from a command buffer" in {
    def tri(x: Int, y: Int, w: Int, r: Int, g: Int, b: Int, d: Int) =
      ((x, y, 0, w), (r, g, b), d)
    val record0 = Seq(
      tri(q(-1), q(-1), q(1), 255, 0, 0, 0x10),
      tri(q(1), q(-1), q(1), 0, 255, 0, 0x10),
      tri(q(-1), q(1), q(1), 0, 0, 255, 0x10)
    )
    val record1 = Seq(
      tri(q(-1), q(-1), q(1), 255, 255, 0, 0x20),
      tri(q(1), q(-1), q(1), 0, 255, 255, 0x20),
      tri(q(-1), q(1), q(1), 255, 0, 255, 0x20)
    )
    val base = 0x4000
    val words0 = encode(record0, shaderPc = 0x9000, shaderKernarg = 0x20000)
    val words1 = encode(record1, shaderPc = 0x9100, shaderKernarg = 0x21000)
    val mem = Array.fill(1 << 15)(0)
    (words0 ++ words1).zipWithIndex.foreach { case (w, i) => mem(base / 4 + i) = w }

    simulate(new CommandBufferStage(GraphicsConfig())) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      dut.io.base.poke(base.U)
      dut.io.count.poke(2.U)
      dut.io.mem.req.ready.poke(true.B)
      dut.io.mem.resp.valid.poke(false.B)

      val decoded =
        collection.mutable.Buffer.empty[(Seq[Int], Seq[(Int, Int, Int)], Seq[Int], Int, Int)]
      var lastReadValid = false
      var lastReadData = 0L
      var guard = 0
      dut.io.start.poke(true.B)
      dut.clock.step()
      dut.io.start.poke(false.B)
      while (guard < 400) {
        dut.io.mem.req.ready.poke(true.B)
        if (lastReadValid) {
          dut.io.mem.resp.valid.poke(true.B)
          dut.io.mem.resp.bits.data.poke(lastReadData.U)
          lastReadValid = false
        } else {
          dut.io.mem.resp.valid.poke(false.B)
          dut.io.mem.resp.bits.data.poke(0.U)
        }
        if (dut.io.mem.req.valid.peek().litToBoolean) {
          val addr = dut.io.mem.req.bits.addr.peek().litValue.toInt
          lastReadValid = true
          lastReadData = mem(addr / 4) & 0xffffffffL
        }
        // Capture a presented draw.
        if (dut.io.draw.valid.peek().litToBoolean) {
          val d = dut.io.draw.bits
          decoded += ((
            (0 until 3).map(i => Seq(d.clip(i).x.peek().litValue.toInt, d.clip(i).y.peek().litValue.toInt, d.clip(i).z.peek().litValue.toInt, d.clip(i).w.peek().litValue.toInt)).flatten,
            (0 until 3).map(i => (d.color(i).r.peek().litValue.toInt, d.color(i).g.peek().litValue.toInt, d.color(i).b.peek().litValue.toInt)),
            (0 until 3).map(i => d.depth(i).peek().litValue.toInt),
            d.shaderPc.peek().litValue.toInt,
            d.shaderKernarg.peek().litValue.toInt
          ))
          dut.io.draw.ready.poke(true.B)
        } else {
          dut.io.draw.ready.poke(true.B)
        }
        dut.clock.step()
        if (dut.io.done.peek().litToBoolean) guard = 9999
        guard += 1
      }

      assert(decoded.size == 2, s"expected 2 draws, got ${decoded.size}")
      // Record 0: red/green/blue vertices (0,0,0 clip z) with depth 0x10.
      assert(decoded(0)._1.slice(0, 4) == Seq(q(-1), q(-1), 0, q(1)))
      assert(decoded(0)._2 == Seq((255, 0, 0), (0, 255, 0), (0, 0, 255)))
      assert(decoded(0)._3 == Seq(0x10, 0x10, 0x10))
      // Shader descriptor carried through the draw record.
      assert(decoded(0)._4 == 0x9000)
      assert(decoded(0)._5 == 0x20000)
      // Record 1 differs.
      assert(decoded(1)._2 == Seq((255, 255, 0), (0, 255, 255), (255, 0, 255)))
      assert(decoded(1)._3 == Seq(0x20, 0x20, 0x20))
      assert(decoded(1)._4 == 0x9100)
      assert(decoded(1)._5 == 0x21000)
    }
  }
}
