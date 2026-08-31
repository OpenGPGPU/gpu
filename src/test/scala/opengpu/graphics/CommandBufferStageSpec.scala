package opengpu.graphics

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec

class CommandBufferStageSpec extends AnyFlatSpec {
  behavior of "CommandBufferStage"

  private def q(v: Double): Int = (v * (1 << 16)).toInt

  // Encode a SceneTriangle plus shader descriptor into a 32-word record.
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
    // uv0..uv2 as unsigned Q16.16 (unused by these tests)
    for (_ <- 0 until 6) { w += 0 }
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

  it should "fill a configurable draw FIFO without fetching past capacity" in {
    def tri(depth: Int, r: Int, g: Int, b: Int) =
      Seq(
        ((q(-1), q(-1), 0, q(1)), (r, g, b), depth),
        ((q(1), q(-1), 0, q(1)), (r, g, b), depth),
        ((q(-1), q(1), 0, q(1)), (r, g, b), depth))

    val base = 0x6000
    val fifoDepth = 4
    val records = (0 to fifoDepth).map { i =>
      encode(tri(0x10 + i, 40 * i, 255 - 40 * i, 20 * i),
        0x1000 + i * 0x100, 0x2000 + i * 0x100)
    }
    val mem = Array.fill(1 << 15)(0)
    records.flatten.zipWithIndex.foreach { case (w, i) => mem(base / 4 + i) = w }

    simulate(new CommandBufferStage(GraphicsConfig(drawFifoDepth = fifoDepth))) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      dut.io.base.poke(base.U)
      dut.io.count.poke(records.size.U)
      dut.io.mem.req.ready.poke(true.B)
      dut.io.mem.resp.valid.poke(false.B)
      dut.io.draw.ready.poke(false.B)

      var responsePending = false
      var responseData = 0L
      val reads = collection.mutable.ArrayBuffer.empty[Int]
      var guard = 0
      dut.io.start.poke(true.B)
      dut.clock.step()
      dut.io.start.poke(false.B)

      // Keep the consumer stopped long enough for the configured FIFO to
      // fill.  The next record must not be fetched past FIFO capacity.
      while (guard < 800 && reads.size < fifoDepth * 32) {
        dut.io.mem.req.ready.poke(true.B)
        if (responsePending) {
          dut.io.mem.resp.valid.poke(true.B)
          dut.io.mem.resp.bits.data.poke(responseData.U)
          responsePending = false
        } else {
          dut.io.mem.resp.valid.poke(false.B)
        }
        dut.io.draw.ready.poke(false.B)
        if (dut.io.mem.req.valid.peek().litToBoolean) {
          val addr = dut.io.mem.req.bits.addr.peek().litValue.toInt
          reads += addr
          responsePending = true
          responseData = mem(addr / 4) & 0xffffffffL
        }
        dut.clock.step()
        guard += 1
      }

      assert(reads.size == fifoDepth * 32,
        s"expected $fifoDepth records prefetched, got ${reads.size / 32} records")
      assert(!reads.exists(_ >= base + fifoDepth * 32 * 4),
        "a stalled consumer must prevent fetching beyond FIFO capacity")
      assert(dut.io.draw.valid.peek().litToBoolean,
        "the FIFO must hold a draw under consumer backpressure")

      // Release the consumer and drain every record in order.
      var decodedPcs = collection.mutable.ArrayBuffer.empty[Long]
      guard = 0
      while (!dut.io.done.peek().litToBoolean && guard < 1000) {
        dut.io.mem.req.ready.poke(true.B)
        if (responsePending) {
          dut.io.mem.resp.valid.poke(true.B)
          dut.io.mem.resp.bits.data.poke(responseData.U)
          responsePending = false
        } else {
          dut.io.mem.resp.valid.poke(false.B)
        }
        dut.io.draw.ready.poke(true.B)
        if (dut.io.draw.valid.peek().litToBoolean)
          decodedPcs += dut.io.draw.bits.shaderPc.peek().litValue.toLong
        if (dut.io.mem.req.valid.peek().litToBoolean) {
          val addr = dut.io.mem.req.bits.addr.peek().litValue.toInt
          responsePending = true
          responseData = mem(addr / 4) & 0xffffffffL
        }
        dut.clock.step()
        guard += 1
      }

      assert(guard < 1000, "FIFO-backed command parser did not drain")
      assert(decodedPcs.toSeq == (0 to fifoDepth).map(i => 0x1000L + i * 0x100L),
        s"draws must remain ordered, got $decodedPcs")
    }
  }
}
