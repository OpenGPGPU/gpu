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
    shaderKernarg: Int,
    state: Int = 0,
    sampler: Int = 0,
    kernargBankStride: Int = 0
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
    w += state
    w += sampler
    w += kernargBankStride
    for (_ <- 0 until 5) { w += 0 } // reserved
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
    val state0 = 1 | 2 | (3 << 4) | (1 << 7) | (2 << 8) |
      (1 << 10) | (1 << 11) | (2 << 12)
    val sampler0 = 0x1f | (1 << 8) // bias -1, minimum mip 1
    val words0 = encode(record0, shaderPc = 0x9000,
      shaderKernarg = 0x20000, state = state0, sampler = sampler0,
      kernargBankStride = 0x140)
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

      val decoded = collection.mutable.Buffer.empty[
        (Seq[Int], Seq[(Int, Int, Int)], Seq[Int], Int, Int, Int, Int, Int)]
      var lastReadValid = false
      var lastReadData = 0L
      var lastReadAddr = 0
      var guard = 0
      dut.io.start.poke(true.B)
      dut.clock.step()
      dut.io.start.poke(false.B)
      while (guard < 400) {
        dut.io.mem.req.ready.poke(true.B)
        if (lastReadValid) {
          dut.io.mem.resp.valid.poke(true.B)
          dut.io.mem.resp.bits.data.poke(lastReadData.U)
          dut.io.mem.resp.bits.addr.poke(lastReadAddr.U)
          lastReadValid = false
        } else {
          dut.io.mem.resp.valid.poke(false.B)
          dut.io.mem.resp.bits.data.poke(0.U)
        }
        if (dut.io.mem.req.valid.peek().litToBoolean) {
          val addr = dut.io.mem.req.bits.addr.peek().litValue.toInt
          lastReadValid = true
          lastReadData = mem(addr / 4) & 0xffffffffL
          lastReadAddr = addr
        }
        // Capture a presented draw.
        if (dut.io.draw.valid.peek().litToBoolean) {
          val d = dut.io.draw.bits.asInstanceOf[SceneTriangle]
          decoded += ((
            (0 until 3).map(i => Seq(d.clip(i).x.peek().litValue.toInt, d.clip(i).y.peek().litValue.toInt, d.clip(i).z.peek().litValue.toInt, d.clip(i).w.peek().litValue.toInt)).flatten,
            (0 until 3).map(i => (d.color(i).r.peek().litValue.toInt, d.color(i).g.peek().litValue.toInt, d.color(i).b.peek().litValue.toInt)),
            (0 until 3).map(i => d.depth(i).peek().litValue.toInt),
            d.shaderPc.peek().litValue.toInt,
            d.shaderKernarg.peek().litValue.toInt,
            (if (d.stateOverride.peek().litToBoolean) 1 else 0) |
              (if (d.depthTestEnable.peek().litToBoolean) 2 else 0) |
              (d.depthFunc.peek().litValue.toInt << 4) |
              (if (d.depthWriteEnable.peek().litToBoolean) 1 << 7 else 0) |
              (d.cullMode.peek().litValue.toInt << 8) |
              (if (d.texEnable.peek().litToBoolean) 1 << 10 else 0) |
              (if (d.texWrapClamp.peek().litToBoolean) 1 << 11 else 0) |
              (d.texMaxLevel.peek().litValue.toInt << 12) |
              (if (d.blendEnable.peek().litToBoolean) 1 << 16 else 0),
            (d.texLodBias.peek().litValue.toInt & 0x1f) |
              (d.texMinLevel.peek().litValue.toInt << 8),
            d.kernargBankStride.peek().litValue.toInt
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
      assert(decoded(0)._6 == state0)
      assert(decoded(0)._7 == sampler0)
      assert(decoded(0)._8 == 0x140)
      // Record 1 differs.
      assert(decoded(1)._2 == Seq((255, 255, 0), (0, 255, 255), (255, 0, 255)))
      assert(decoded(1)._3 == Seq(0x20, 0x20, 0x20))
      assert(decoded(1)._4 == 0x9100)
      assert(decoded(1)._5 == 0x21000)
      assert(decoded(1)._6 == 0)
      assert(decoded(1)._7 == 0)
      assert(decoded(1)._8 == 0)
      assert(dut.io.done.peek().litToBoolean)
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
      var responseAddr = 0
      val reads = collection.mutable.ArrayBuffer.empty[Int]
      var guard = 0
      dut.io.start.poke(true.B)
      dut.clock.step()
      dut.io.start.poke(false.B)

      // Keep the consumer stopped long enough for the configured FIFO to
      // fill.  The next record must not be fetched past FIFO capacity.
      while (guard < 800 && reads.size < fifoDepth * 40) {
        dut.io.mem.req.ready.poke(true.B)
        if (responsePending) {
          dut.io.mem.resp.valid.poke(true.B)
          dut.io.mem.resp.bits.data.poke(responseData.U)
          dut.io.mem.resp.bits.addr.poke(responseAddr.U)
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
          responseAddr = addr
        }
        dut.clock.step()
        guard += 1
      }

      assert(reads.size == fifoDepth * 40,
        s"expected $fifoDepth records prefetched, got ${reads.size / 40} records")
      assert(!reads.exists(_ >= base + fifoDepth * 40 * 4),
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
          dut.io.mem.resp.bits.addr.poke(responseAddr.U)
          responsePending = false
        } else {
          dut.io.mem.resp.valid.poke(false.B)
        }
        dut.io.draw.ready.poke(true.B)
        if (dut.io.draw.valid.peek().litToBoolean)
          decodedPcs += dut.io.draw.bits.asInstanceOf[SceneTriangle].shaderPc.peek().litValue.toLong
        if (dut.io.mem.req.valid.peek().litToBoolean) {
          val addr = dut.io.mem.req.bits.addr.peek().litValue.toInt
          responsePending = true
          responseData = mem(addr / 4) & 0xffffffffL
          responseAddr = addr
        }
        dut.clock.step()
        guard += 1
      }

      assert(guard < 1000, "FIFO-backed command parser did not drain")
      assert(decodedPcs.toSeq == (0 to fifoDepth).map(i => 0x1000L + i * 0x100L),
        s"draws must remain ordered, got $decodedPcs")
    }
  }

  it should "decode vertCore draw records into VertexDrawCommand" in {
    // Encode a vertCore draw record (new 40-word format):
    // [0] vertBufferBase, [1] vertCount, [2] vertStride, [3] vertShaderPc
    // [4] vertKernarg, [5] vertKernargBankStride, [6] vertAttrFormat, [7-23] reserved
    // [24] fragShaderPc, [25] fragKernarg, [26-31] reserved
    // [32] state flags, [33] LOD bias + min mip, [34] fragKernargBankStride, [35-39] reserved
    val base = 0x5000
    val vertBufferBase = 0x10000
    val vertCount = 36
    val vertStride = 32
    val vertShaderPc = 0x2000
    val vertKernarg = 0x30000
    val vertKernargBankStride = 0x200
    val vertAttrFormat = 0
    val fragShaderPc = 0x2100
    val fragKernarg = 0x31000
    val state = 1 | 2 | (3 << 4) | (1 << 7) | (2 << 8) |
      (1 << 10) | (1 << 11) | (2 << 12) | (1 << 16)
    val sampler = 0x1f | (1 << 8) // bias -1, min mip 1
    val fragKernargBankStride = 0x180

    val record = Seq(
      vertBufferBase,
      vertCount,
      vertStride,
      vertShaderPc,
      vertKernarg,
      vertKernargBankStride,
      vertAttrFormat
    ) ++ Seq.fill(17)(0) ++ Seq(
      fragShaderPc,
      fragKernarg
    ) ++ Seq.fill(6)(0) ++ Seq(
      state,
      sampler,
      fragKernargBankStride
    ) ++ Seq.fill(5)(0)

    val mem = Array.fill(1 << 15)(0)
    record.zipWithIndex.foreach { case (w, i) => mem(base / 4 + i) = w }

    simulate(new CommandBufferStage(GraphicsConfig(), vertCore = true)) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      dut.io.base.poke(base.U)
      dut.io.count.poke(1.U)
      dut.io.mem.req.ready.poke(true.B)
      dut.io.mem.resp.valid.poke(false.B)

      var decoded: Option[(Int, Int, Int, Int, Int, Int, Int, Int, Int, Int)] = None
      var lastReadValid = false
      var lastReadData = 0L
      var lastReadAddr = 0
      var guard = 0
      dut.io.start.poke(true.B)
      dut.clock.step()
      dut.io.start.poke(false.B)
      while (guard < 400 && decoded.isEmpty) {
        dut.io.mem.req.ready.poke(true.B)
        if (lastReadValid) {
          dut.io.mem.resp.valid.poke(true.B)
          dut.io.mem.resp.bits.data.poke(lastReadData.U)
          dut.io.mem.resp.bits.addr.poke(lastReadAddr.U)
          lastReadValid = false
        } else {
          dut.io.mem.resp.valid.poke(false.B)
          dut.io.mem.resp.bits.data.poke(0.U)
        }
        if (dut.io.mem.req.valid.peek().litToBoolean) {
          val addr = dut.io.mem.req.bits.addr.peek().litValue.toInt
          lastReadValid = true
          lastReadData = mem(addr / 4) & 0xffffffffL
          lastReadAddr = addr
        }
        if (dut.io.draw.valid.peek().litToBoolean) {
          val d = dut.io.draw.bits.asInstanceOf[VertexDrawCommand]
          decoded = Some((
            d.vertBufferBase.peek().litValue.toInt,
            d.vertCount.peek().litValue.toInt,
            d.vertStride.peek().litValue.toInt,
            d.vertShaderPc.peek().litValue.toInt,
            d.vertKernarg.peek().litValue.toInt,
            d.vertKernargBankStride.peek().litValue.toInt,
            d.fragShaderPc.peek().litValue.toInt,
            d.fragKernarg.peek().litValue.toInt,
            d.fragKernargBankStride.peek().litValue.toInt,
            (if (d.stateOverride.peek().litToBoolean) 1 else 0) |
              (if (d.depthTestEnable.peek().litToBoolean) 2 else 0) |
              (d.depthFunc.peek().litValue.toInt << 4) |
              (if (d.depthWriteEnable.peek().litToBoolean) 1 << 7 else 0) |
              (d.cullMode.peek().litValue.toInt << 8) |
              (if (d.texEnable.peek().litToBoolean) 1 << 10 else 0) |
              (if (d.texWrapClamp.peek().litToBoolean) 1 << 11 else 0) |
              (d.texMaxLevel.peek().litValue.toInt << 12) |
              (if (d.blendEnable.peek().litToBoolean) 1 << 16 else 0)
          ))
          dut.io.draw.ready.poke(true.B)
        } else {
          dut.io.draw.ready.poke(true.B)
        }
        dut.clock.step()
        if (dut.io.done.peek().litToBoolean) guard = 9999
        guard += 1
      }

      assert(decoded.isDefined, "vertCore draw did not decode")
      val (vb, vc, vs, vpc, vk, vkbs, fpc, fk, fkbs, st) = decoded.get
      assert(vb == vertBufferBase, s"vertBufferBase: expected $vertBufferBase, got $vb")
      assert(vc == vertCount, s"vertCount: expected $vertCount, got $vc")
      assert(vs == vertStride, s"vertStride: expected $vertStride, got $vs")
      assert(vpc == vertShaderPc, s"vertShaderPc: expected $vertShaderPc, got $vpc")
      assert(vk == vertKernarg, s"vertKernarg: expected $vertKernarg, got $vk")
      assert(vkbs == vertKernargBankStride, s"vertKernargBankStride: expected $vertKernargBankStride, got $vkbs")
      assert(fpc == fragShaderPc, s"fragShaderPc: expected $fragShaderPc, got $fpc")
      assert(fk == fragKernarg, s"fragKernarg: expected $fragKernarg, got $fk")
      assert(fkbs == fragKernargBankStride, s"fragKernargBankStride: expected $fragKernargBankStride, got $fkbs")
      assert(st == state, s"state: expected $state, got $st")
      assert(dut.io.done.peek().litToBoolean)
    }
  }
}
