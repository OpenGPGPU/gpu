package opengpu.graphics

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec

class OutputMergerSpec extends AnyFlatSpec {
  behavior of "OutputMerger"

  private val W = 4
  private val H = 4
  private val stride = W * 4 // RGBA8888 => 4 bytes/pixel
  private val colorBase = 0x1000
  private val depthBase = 0x2000

  private def addrOf(base: Int, x: Int, y: Int): Int =
    base + (y * stride) + x * 4

  /** Multi-outstanding byte-array memory model: requests are captured on the
    * cycle they fire (the parallel output merger issues requests while
    * fragments are still being submitted) and their address-tagged responses
    * are presented on a later cycle, so several reads may be in flight at
    * once - exactly what a shared memory hierarchy produces.  Write
    * acknowledgements carry write=true and data=0.
    */
  private class OooModel(val mem: Array[Int]) {
    // `pending` responses are presentable this cycle; `inflight` captures
    // become presentable the NEXT cycle (a memory system always has at least
    // one cycle of read latency - the OM's entries only wait in sWaitDepth
    // from the cycle after their request fired).
    val pending = scala.collection.mutable.Queue.empty[(Boolean, Int, Long)]
    val inflight = scala.collection.mutable.Queue.empty[(Boolean, Int, Long)]
    var heldAcks = 0
    // True once a read was captured while another read response was still
    // unconsumed - i.e. several reads were genuinely outstanding at once.
    var sawConcurrentReads = false

    def capture(addr: Int, write: Boolean, data: Int): Unit = {
      if (write) {
        mem(addr) = data
        heldAcks += 1
      } else {
        if (pending.exists(p => !p._1)) sawConcurrentReads = true
        // Release every held write ack ahead of this read's response, so an
        // ack can overtake the read (the L2's out-of-order path).
        while (heldAcks > 0) { inflight.enqueue((true, 0, 0L)); heldAcks -= 1 }
        inflight.enqueue((false, addr, mem(addr) & 0xffffffffL))
      }
    }

    def present(dut: OutputMerger): Unit = {
      if (pending.nonEmpty) {
        val (isWrite, addr, data) = pending.head
        dut.io.mem.resp.valid.poke(true.B)
        dut.io.mem.resp.bits.write.poke(isWrite.B)
        dut.io.mem.resp.bits.addr.poke(addr.U)
        dut.io.mem.resp.bits.data.poke(data.U)
      } else {
        dut.io.mem.resp.valid.poke(false.B)
      }
    }
  }

  /** One serviced cycle: promote last cycle's captured responses to
    * presentable, capture a fired request (it becomes presentable NEXT
    * cycle - a memory system always has at least one cycle of read latency,
    * and the OM's entries only wait in sWaitDepth from the cycle after their
    * request fired), present a pending response, advance the clock.
    */
  private def tick(dut: OutputMerger, model: OooModel): Boolean = {
    dut.io.mem.req.ready.poke(true.B)
    while (model.inflight.nonEmpty) model.pending.enqueue(model.inflight.dequeue())
    var fired = false
    if (dut.io.mem.req.valid.peek().litToBoolean &&
      dut.io.mem.req.ready.peek().litToBoolean) {
      val addr = dut.io.mem.req.bits.addr.peek().litValue.toInt
      val write = dut.io.mem.req.bits.write.peek().litToBoolean
      val data = dut.io.mem.req.bits.data.peek().litValue.toInt
      model.capture(addr, write, data)
      fired = true
    }
    model.present(dut)
    if (model.pending.nonEmpty && dut.io.mem.resp.ready.peek().litToBoolean)
      model.pending.dequeue()
    dut.clock.step()
    fired
  }

  /** Serves the OM until the memory port has been idle with no pending
    * responses for a few cycles (all in-flight entries drained).
    */
  private def drain(dut: OutputMerger, model: OooModel, guard: Int = 400): Unit = {
    var idle = 0
    var g = 0
    while (idle < 4 && g < guard) {
      idle = if (tick(dut, model) || model.pending.nonEmpty) 0 else idle + 1
      g += 1
    }
    assert(g < guard, "OM did not drain")
    dut.io.mem.resp.valid.poke(false.B)
  }

  private def pokeConfig(dut: OutputMerger, depthTestEnable: Boolean,
    depthFunc: Int, depthWrite: Boolean, blend: Boolean): Unit = {
    dut.io.colorBase.poke(colorBase.U)
    dut.io.depthBase.poke(depthBase.U)
    dut.io.stride.poke(stride.U)
    dut.io.depthTestEnable.poke(depthTestEnable.B)
    dut.io.depthFunc.poke(depthFunc.U)
    dut.io.depthWriteEnable.poke(depthWrite.B)
    dut.io.blendEnable.poke(blend.B)
    dut.io.mem.req.ready.poke(true.B)
    dut.io.mem.resp.valid.poke(false.B)
    dut.io.mem.resp.bits.write.poke(false.B)
    dut.io.mem.resp.bits.addr.poke(0.U)
    dut.io.mem.resp.bits.data.poke(0.U)
  }

  private def pokeFrag(dut: OutputMerger, x: Int, y: Int, color: Int, depth: Int): Unit = {
    dut.io.fragIn.bits.x.poke(x.U)
    dut.io.fragIn.bits.y.poke(y.U)
    dut.io.fragIn.bits.color.poke((color.toLong & 0xffffffffL).U)
    dut.io.fragIn.bits.depth.poke((depth.toLong & 0xffffffffL).U)
  }

  private def newDut(dut: OutputMerger): Unit = {
    dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
  }

  it should "write color and depth for a passing fragment" in {
    simulate(new OutputMerger(GraphicsConfig())) { dut =>
      val mem = Array.fill(1 << 15)(0xffffffff)
      newDut(dut)
      pokeConfig(dut, true, 0, true, false)
      val model = new OooModel(mem)

      dut.io.fragIn.valid.poke(true.B)
      pokeFrag(dut, 1, 1, 0x11223344, 0x00000010)
      dut.io.fragIn.ready.expect(true.B)
      tick(dut, model)
      dut.io.fragIn.valid.poke(false.B)
      drain(dut, model)
      assert(mem(addrOf(colorBase, 1, 1)) == 0x11223344)
      assert(mem(addrOf(depthBase, 1, 1)) == 0x00000010)
    }
  }

  it should "keep the nearer (smaller depth) of two overlapping fragments" in {
    simulate(new OutputMerger(GraphicsConfig())) { dut =>
      val mem = Array.fill(1 << 15)(0xffffffff)
      newDut(dut)
      pokeConfig(dut, true, 0, true, false)
      val model = new OooModel(mem)

      // Far fragment first, then near fragment.
      dut.io.fragIn.valid.poke(true.B)
      pokeFrag(dut, 1, 1, 0xAA000000, 0x00000050)
      tick(dut, model)
      dut.io.fragIn.valid.poke(false.B)
      drain(dut, model)
      dut.io.fragIn.valid.poke(true.B)
      pokeFrag(dut, 1, 1, 0x00BB0000, 0x00000010)
      tick(dut, model)
      dut.io.fragIn.valid.poke(false.B)
      drain(dut, model)
      val color = mem(addrOf(colorBase, 1, 1))
      assert(color == 0x00BB0000, s"nearer fragment should win, got 0x${color.toHexString}")

      // Near fragment first, then far fragment — far must be rejected.
      dut.io.fragIn.valid.poke(true.B)
      pokeFrag(dut, 2, 2, 0x0000CC00, 0x00000008)
      tick(dut, model)
      dut.io.fragIn.valid.poke(false.B)
      drain(dut, model)
      dut.io.fragIn.valid.poke(true.B)
      pokeFrag(dut, 2, 2, 0x000000DD, 0x00000060)
      tick(dut, model)
      dut.io.fragIn.valid.poke(false.B)
      drain(dut, model)
      assert(mem(addrOf(colorBase, 2, 2)) == 0x0000CC00)
    }
  }

  it should "reject a fragment that fails the depth test and not write it" in {
    simulate(new OutputMerger(GraphicsConfig())) { dut =>
      val mem = Array.fill(1 << 15)(0xffffffff)
      newDut(dut)
      pokeConfig(dut, true, 0, true, false)
      val model = new OooModel(mem)

      dut.io.fragIn.valid.poke(true.B)
      pokeFrag(dut, 0, 0, 0x11111111, 0x00000010)
      tick(dut, model)
      dut.io.fragIn.valid.poke(false.B)
      drain(dut, model)
      dut.io.fragIn.valid.poke(true.B)
      pokeFrag(dut, 0, 0, 0x22222222, 0x00000020) // larger depth => fail
      tick(dut, model)
      dut.io.fragIn.valid.poke(false.B)
      drain(dut, model)
      val color = mem(colorBase + 0 + 0)
      assert(color == 0x11111111, s"failing fragment must not overwrite, got 0x${color.toHexString}")
    }
  }

  it should "source-over blend a passing fragment and preserve depth semantics" in {
    simulate(new OutputMerger(GraphicsConfig())) { dut =>
      val mem = Array.fill(1 << 15)(0xffffffff)
      newDut(dut)
      pokeConfig(dut, true, 0, true, true)
      val model = new OooModel(mem)
      val addr = addrOf(colorBase, 1, 1)
      mem(addr) = 0x0000ffff // opaque blue destination
      // 50% red over blue: rounded source-over gives (128, 0, 127, 255).
      dut.io.fragIn.valid.poke(true.B)
      pokeFrag(dut, 1, 1, 0xff000080, 0x10)
      tick(dut, model)
      dut.io.fragIn.valid.poke(false.B)
      drain(dut, model)
      assert(mem(addr) == 0x80007fff,
        f"source-over blend was ${mem(addr)}%08x, expected 80007fff")
      assert(mem(addrOf(depthBase, 1, 1)) == 0x10,
        "a blended passing fragment must retain ordinary depth-write behaviour")
    }
  }

  it should "ignore write acknowledgements that arrive while a depth read is in flight" in {
    // The OooModel releases held write acks ahead of every read response, so
    // acks (tagged write=true, data=0) overtake the read.  The OM must pop
    // them and keep waiting for the real read data; consuming the ack as
    // depth (0) would fail every LESS test and reject the fragment.
    simulate(new OutputMerger(GraphicsConfig())) { dut =>
      val mem = Array.fill(1 << 15)(0xffffffff)
      newDut(dut)
      pokeConfig(dut, true, 0, true, false)
      val model = new OooModel(mem)

      dut.io.fragIn.valid.poke(true.B)
      pokeFrag(dut, 1, 1, 0xAA000000, 0x00000010) // passes against the clear value
      tick(dut, model)
      dut.io.fragIn.valid.poke(false.B)
      drain(dut, model)
      // Nearer fragment: must read back the stored 0x10, not the ack's 0.
      dut.io.fragIn.valid.poke(true.B)
      pokeFrag(dut, 1, 1, 0x00BB0000, 0x00000008)
      tick(dut, model)
      dut.io.fragIn.valid.poke(false.B)
      drain(dut, model)
      val color = mem(addrOf(colorBase, 1, 1))
      assert(color == 0x00BB0000,
        s"nearer fragment must pass the depth test despite overtaking write acks, got 0x${color.toHexString}")
      assert(mem(addrOf(depthBase, 1, 1)) == 0x00000008,
        "depth buffer must hold the winning fragment's depth")
    }
  }

  it should "process fragments of distinct pixels concurrently" in {
    // The in-flight table's whole point: several fragments' depth reads are
    // outstanding at once (attributed by address) instead of one serialized
    // read-modify-write at a time.
    simulate(new OutputMerger(GraphicsConfig())) { dut =>
      val mem = Array.fill(1 << 15)(0xffffffff)
      newDut(dut)
      pokeConfig(dut, true, 0, true, false)
      val model = new OooModel(mem)

      val frags = Seq(
        (0, 0, 0x01020304, 0x00000041),
        (1, 0, 0x11121314, 0x00000042),
        (2, 0, 0x21222324, 0x00000043),
        (3, 0, 0x31323334, 0x00000044)
      )
      // Hold fragIn.valid across back-to-back accepts: the four pixels are
      // distinct, so the merger must accept one per cycle.
      for ((x, y, c, d) <- frags) {
        dut.io.fragIn.valid.poke(true.B)
        pokeFrag(dut, x, y, c, d)
        dut.io.fragIn.ready.expect(true.B,
          s"distinct pixels (x=$x) must not stall the in-flight table")
        tick(dut, model)
      }
      dut.io.fragIn.valid.poke(false.B)
      drain(dut, model)
      assert(model.sawConcurrentReads,
        "expected several depth reads outstanding at once")
      for ((x, y, c, d) <- frags) {
        assert(mem(addrOf(colorBase, x, y)) == c,
          f"pixel ($x,$y) colour was 0x${mem(addrOf(colorBase, x, y))}%08x, expected 0x$c%08x")
        assert(mem(addrOf(depthBase, x, y)) == d,
          f"pixel ($x,$y) depth was 0x${mem(addrOf(depthBase, x, y))}%08x, expected 0x$d%08x")
      }
    }
  }

  it should "stall a second fragment of one pixel until the first completes" in {
    // Per-pixel submission order comes from the same-address stall: while an
    // earlier fragment of a pixel is in flight, a later one must not be
    // accepted.  Four back-to-back same-pixel fragments with descending
    // depth must still leave the nearest one written.
    simulate(new OutputMerger(GraphicsConfig())) { dut =>
      val mem = Array.fill(1 << 15)(0xffffffff)
      newDut(dut)
      pokeConfig(dut, true, 0, true, false)
      val model = new OooModel(mem)

      val depths = Seq(0x00000080, 0x00000060, 0x00000040, 0x00000020)
      val colors = Seq(0xAA000000, 0x00BB0000, 0x0000CC00, 0x000000DD)
      // First fragment accepted; the second same-pixel one must stall until
      // the first's writes are accepted and the pixel frees.
      dut.io.fragIn.valid.poke(true.B)
      pokeFrag(dut, 1, 1, colors(0), depths(0))
      dut.io.fragIn.ready.expect(true.B)
      tick(dut, model)
      // The in-flight entry holds the pixel: a same-pixel fragment must be
      // refused right after acceptance.
      dut.io.fragIn.ready.expect(false.B, "same-pixel fragment must stall")
      var accepted = 0
      var guard = 0
      while (accepted < depths.length && guard < 400) {
        if (dut.io.fragIn.ready.peek().litToBoolean) {
          pokeFrag(dut, 1, 1, colors(accepted), depths(accepted))
          accepted += 1
        }
        tick(dut, model)
        guard += 1
      }
      dut.io.fragIn.valid.poke(false.B)
      drain(dut, model)
      assert(accepted == depths.length, s"same-pixel fragments did not all accept: $accepted")
      assert(mem(addrOf(colorBase, 1, 1)) == (colors(3).toLong & 0xffffffffL).toInt,
        f"nearest fragment must win, got 0x${mem(addrOf(colorBase, 1, 1))}%08x")
      assert(mem(addrOf(depthBase, 1, 1)) == depths(3),
        "depth buffer must hold the nearest fragment's depth")
    }
  }

  it should "blend distinct pixels concurrently without cross-talk" in {
    simulate(new OutputMerger(GraphicsConfig())) { dut =>
      val mem = Array.fill(1 << 15)(0xffffffff)
      newDut(dut)
      pokeConfig(dut, true, 0, true, true)
      val model = new OooModel(mem)

      // Two destinations, two sources: blended results must not cross.
      val dstA = addrOf(colorBase, 0, 0)
      val dstB = addrOf(colorBase, 3, 3)
      mem(dstA) = 0x0000ffff // opaque blue
      mem(dstB) = 0x00ff00ff // opaque green
      // 50% red over blue -> (128, 0, 127, 255); over green -> (128, 127, 0, 255)
      dut.io.fragIn.valid.poke(true.B)
      pokeFrag(dut, 0, 0, 0xff000080, 0x10)
      dut.io.fragIn.ready.expect(true.B)
      tick(dut, model)
      pokeFrag(dut, 3, 3, 0xff000080, 0x11)
      dut.io.fragIn.ready.expect(true.B)
      tick(dut, model)
      dut.io.fragIn.valid.poke(false.B)
      drain(dut, model)
      assert(mem(dstA) == 0x80007fff,
        f"blend A was 0x${mem(dstA)}%08x, expected 0x80007fff")
      assert(mem(dstB) == 0x807f00ff,
        f"blend B was 0x${mem(dstB)}%08x, expected 0x807f00ff")
      assert(mem(addrOf(depthBase, 0, 0)) == 0x10)
      assert(mem(addrOf(depthBase, 3, 3)) == 0x11)
    }
  }
}
