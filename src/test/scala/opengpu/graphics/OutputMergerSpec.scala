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
        inflight.enqueue((true, addr, 0L))
      } else {
        if (pending.exists(p => !p._1)) sawConcurrentReads = true
        // Release every held write ack ahead of this read's response, so an
        // ack can overtake the read (the L2's out-of-order path).
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
    dut.io.drained.expect(true.B)
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
    dut.io.blendCfgEnable.poke(false.B)
    dut.io.blendSrcFactor.poke(0.U)
    dut.io.blendDstFactor.poke(0.U)
    dut.io.blendEquation.poke(0.U)
    dut.io.stencilTestEnable.poke(false.B)
    dut.io.stencilFunc.poke(0.U)
    dut.io.stencilRef.poke(0.U)
    dut.io.stencilReadMask.poke(0.U)
    dut.io.stencilWriteMask.poke(0.U)
    dut.io.stencilFailOp.poke(0.U)
    dut.io.stencilZFailOp.poke(0.U)
    dut.io.stencilZPassOp.poke(0.U)
    dut.io.mem.req.ready.poke(true.B)
    dut.io.mem.resp.valid.poke(false.B)
    dut.io.mem.resp.bits.write.poke(false.B)
    dut.io.mem.resp.bits.addr.poke(0.U)
    dut.io.mem.resp.bits.data.poke(0.U)
  }

  private def pokeBlendCfg(dut: OutputMerger, present: Boolean,
    srcFactor: Int, dstFactor: Int, equation: Int): Unit = {
    dut.io.blendCfgEnable.poke(present.B)
    dut.io.blendSrcFactor.poke(srcFactor.U)
    dut.io.blendDstFactor.poke(dstFactor.U)
    dut.io.blendEquation.poke(equation.U)
  }

  private def pokeStencil(dut: OutputMerger, enable: Boolean, func: Int,
    ref: Int, readMask: Int, writeMask: Int, failOp: Int, zFailOp: Int,
    zPassOp: Int): Unit = {
    dut.io.stencilTestEnable.poke(enable.B)
    dut.io.stencilFunc.poke(func.U)
    dut.io.stencilRef.poke(ref.U)
    dut.io.stencilReadMask.poke(readMask.U)
    dut.io.stencilWriteMask.poke(writeMask.U)
    dut.io.stencilFailOp.poke(failOp.U)
    dut.io.stencilZFailOp.poke(zFailOp.U)
    dut.io.stencilZPassOp.poke(zPassOp.U)
  }

  /** Software mirror of the stencil func (shares the depth-func encoding). */
  private def stencilFuncPass(func: Int, ref: Int, stored: Int, mask: Int): Boolean = {
    val a = ref & mask
    val b = stored & mask
    func match {
      case 0 => a < b
      case 1 => a <= b
      case 2 => a > b
      case 3 => a >= b
      case 4 => a == b
      case 5 => a != b
      case 6 => true
      case _ => false
    }
  }

  /** Software mirror of the 3-bit GL stencil op. */
  private def stencilRefOp(op: Int, stored: Int, ref: Int): Int = op match {
    case 0 => stored
    case 1 => 0
    case 2 => ref
    case 3 => math.min(255, stored + 1)
    case 4 => math.max(0, stored - 1)
    case 5 => (~stored) & 0xff
    case 6 => (stored + 1) & 0xff
    case _ => (stored - 1) & 0xff
  }

  /** One depth-tested fragment against a seeded D24S8 word; returns
    * (colour word written, depth word written). */
  private def stencilOnce(dut: OutputMerger, model: OooModel, mem: Array[Int],
    x: Int, y: Int, color: Int, fragDepth: Int, storedWord: Int): (Int, Int) = {
    val cAddr = addrOf(colorBase, x, y)
    val dAddr = addrOf(depthBase, x, y)
    mem(cAddr) = 0xdeadbeef
    mem(dAddr) = storedWord
    dut.io.fragIn.valid.poke(true.B)
    pokeFrag(dut, x, y, color, fragDepth)
    tick(dut, model)
    dut.io.fragIn.valid.poke(false.B)
    drain(dut, model)
    (mem(cAddr), mem(dAddr))
  }

  /** Software mirror of the OM's GL-style blendGeneral on RGBA8888 words
    * (R = bits 31:24 ... A = bits 7:0). */
  private def blendRef(src: Int, dst: Int, sf: Int, df: Int, eq: Int): Int = {
    def ch(w: Int, hi: Int) = (w >> hi) & 0xff
    def mul255(c: Int, m: Int) = (c * m + 127) / 255
    def inv(x: Int) = 255 - x
    def fm(f: Int, sc: Int, sa: Int, dc: Int, da: Int): Int = f match {
      case 0 => 0
      case 1 => 255
      case 2 => sc
      case 3 => inv(sc)
      case 4 => sa
      case 5 => inv(sa)
      case 6 => dc
      case 7 => inv(dc)
      case 8 => da
      case 9 => inv(da)
      case 10 => math.min(sa, inv(da))
      case _ => 0
    }
    val sa = ch(src, 0)
    val da = ch(dst, 0)
    (0 to 3).map { i =>
      val hi = 24 - 8 * i
      val sc = ch(src, hi)
      val dc = ch(dst, hi)
      val s = mul255(sc, fm(sf, sc, sa, dc, da))
      val d = mul255(dc, fm(df, sc, sa, dc, da))
      eq match {
        case 0 => math.min(255, s + d)
        case 1 => math.max(0, s - d)
        case 2 => math.max(0, d - s)
        case 3 => math.min(sc, dc)
        case 4 => math.max(sc, dc)
        case _ => math.min(255, s + d)
      }
    }.foldLeft(0)((acc, v) => (acc << 8) | v)
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

  it should "hold reservations and completion until delayed writes are acknowledged" in {
    simulate(new OutputMerger(GraphicsConfig())) { dut =>
      newDut(dut)
      pokeConfig(dut, false, 0, true, false)
      pokeFrag(dut, 1, 1, 0x11223344, 0x10)
      dut.io.fragIn.valid.poke(true.B)
      dut.clock.step()
      dut.io.fragIn.valid.poke(false.B)
      // Depth testing is disabled: no unnecessary depth read.
      dut.io.mem.req.valid.expect(true.B)
      dut.io.mem.req.bits.write.expect(true.B)
      dut.io.mem.req.bits.addr.expect(addrOf(colorBase, 1, 1).U)
      dut.clock.step()
      for (_ <- 0 until 12) {
        dut.io.drained.expect(false.B)
        dut.io.fragIn.ready.expect(false.B)
        dut.io.mem.req.valid.expect(false.B)
        dut.clock.step()
      }
      // A distinct pixel can still be admitted while this write is pending.
      pokeFrag(dut, 2, 1, 0, 0)
      dut.io.fragIn.ready.expect(true.B)
      dut.io.drained.expect(false.B)
      dut.io.mem.resp.valid.poke(true.B)
      dut.io.mem.resp.bits.write.poke(true.B)
      dut.io.mem.resp.bits.addr.poke(addrOf(colorBase, 1, 1).U)
      dut.clock.step()
      dut.io.mem.resp.valid.poke(false.B)
      dut.io.mem.req.bits.addr.expect(addrOf(depthBase, 1, 1).U)
      dut.io.mem.req.valid.expect(true.B)
      dut.clock.step()
      dut.clock.step(12)
      dut.io.drained.expect(false.B)
      dut.io.mem.resp.valid.poke(true.B)
      dut.io.mem.resp.bits.addr.poke(addrOf(depthBase, 1, 1).U)
      dut.clock.step()
      dut.io.mem.resp.valid.poke(false.B)
      dut.io.drained.expect(true.B)
    }
  }

  it should "write color and depth for a passing fragment" in {
    simulate(new OutputMerger(GraphicsConfig())) { dut =>
      val mem = Array.fill(1 << 15)(0x00ffffff) // stencil 0, depth far (D24S8 clear)
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

  it should "preserve the stored stencil byte on a plain depth write" in {
    simulate(new OutputMerger(GraphicsConfig())) { dut =>
      val mem = Array.fill(1 << 15)(0x00ffffff)
      newDut(dut)
      pokeConfig(dut, true, 0, true, false)
      val model = new OooModel(mem)
      // Non-zero stored stencil byte: a plain (stencil-disabled) depth write
      // must keep it and replace only the D24 depth.
      mem(addrOf(depthBase, 2, 1)) = 0xabffffff
      dut.io.fragIn.valid.poke(true.B)
      pokeFrag(dut, 2, 1, 0x00ff00ff, 0x00000020)
      tick(dut, model)
      dut.io.fragIn.valid.poke(false.B)
      drain(dut, model)
      assert(mem(addrOf(depthBase, 2, 1)) == 0xab000020,
        f"depth write was 0x${mem(addrOf(depthBase, 2, 1))}%08x, expected 0xab000020")
    }
  }

  it should "keep the nearer (smaller depth) of two overlapping fragments" in {
    simulate(new OutputMerger(GraphicsConfig())) { dut =>
      val mem = Array.fill(1 << 15)(0x00ffffff) // stencil 0, depth far (D24S8 clear)
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
      val mem = Array.fill(1 << 15)(0x00ffffff) // stencil 0, depth far (D24S8 clear)
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
      val mem = Array.fill(1 << 15)(0x00ffffff) // stencil 0, depth far (D24S8 clear)
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
      val mem = Array.fill(1 << 15)(0x00ffffff) // stencil 0, depth far (D24S8 clear)
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
      val mem = Array.fill(1 << 15)(0x00ffffff) // stencil 0, depth far (D24S8 clear)
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
      val mem = Array.fill(1 << 15)(0x00ffffff) // stencil 0, depth far (D24S8 clear)
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
      val mem = Array.fill(1 << 15)(0x00ffffff) // stencil 0, depth far (D24S8 clear)
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

  /** One blended RMW: seed the destination, submit the fragment, return the
    * written colour word. */
  private def blendOnce(dut: OutputMerger, model: OooModel, mem: Array[Int],
    x: Int, y: Int, src: Int, dst: Int): Int = {
    val addr = addrOf(colorBase, x % W, y % H)
    mem(addr) = dst
    dut.io.fragIn.valid.poke(true.B)
    pokeFrag(dut, x % W, y % H, src, 0)
    tick(dut, model)
    dut.io.fragIn.valid.poke(false.B)
    drain(dut, model)
    mem(addr)
  }

  it should "blend with every GL factor and equation" in {
    simulate(new OutputMerger(GraphicsConfig())) { dut =>
      val mem = Array.fill(1 << 15)(0x00ffffff)
      newDut(dut)
      // Depth testing disabled: only the colour RMW is exercised.
      pokeConfig(dut, false, 0, false, false)
      val model = new OooModel(mem)
      val src = 0x40c08020
      val dst = 0x8040c0ff
      var n = 0
      var mismatches = List.empty[String]
      for (sf <- 0 to 10; df <- 0 to 10; eq <- 0 to 4) {
        pokeBlendCfg(dut, true, sf, df, eq)
        val got = blendOnce(dut, model, mem, n % W, (n / W) % H, src, dst)
        val expected = blendRef(src, dst, sf, df, eq)
        if (got != expected)
          mismatches = mismatches :+
            f"sf=$sf df=$df eq=$eq: got 0x$got%08x expected 0x$expected%08x"
        n += 1
      }
      assert(mismatches.isEmpty, s"${mismatches.size} factor/equation " +
        s"mismatches:\n${mismatches.take(8).mkString("\n")}")
    }
  }

  it should "let a present blend config override legacy source-over" in {
    simulate(new OutputMerger(GraphicsConfig())) { dut =>
      val mem = Array.fill(1 << 15)(0x00ffffff)
      newDut(dut)
      pokeConfig(dut, false, 0, false, blend = true)
      val model = new OooModel(mem)
      // src ONE, dst ZERO, ADD: the pass-through equation.  Legacy source-over
      // on this destination would have produced 0x80007fff.
      pokeBlendCfg(dut, true, 1, 0, 0)
      val got = blendOnce(dut, model, mem, 1, 1, 0xff000080, 0x0000ffff)
      assert(got == 0xff000080,
        f"blend config must override source-over, got 0x$got%08x")
    }
  }

  it should "run every stencil func before the depth test and apply the z-pass op" in {
    simulate(new OutputMerger(GraphicsConfig())) { dut =>
      val mem = Array.fill(1 << 15)(0x00ffffff)
      newDut(dut)
      // Depth ALWAYS so the depth test never gates the stencil sweep.
      pokeConfig(dut, true, 6, true, false)
      val model = new OooModel(mem)
      val pairs = Seq((0x00, 0xff), (0xff, 0x00), (0x80, 0x80), (0x7f, 0x80),
        (0x80, 0x7f), (0xff, 0xff), (0x00, 0x00), (0x01, 0x02))
      var n = 0
      var bad = List.empty[String]
      for (func <- 0 to 7; (ref, stored) <- pairs) {
        pokeStencil(dut, true, func, ref, 0xff, 0xff,
          failOp = 1, zFailOp = 1, zPassOp = 2) // fail/zfail ZERO, zpass REPLACE
        val expectPass = stencilFuncPass(func, ref, stored, 0xff)
        val (colour, depth) = stencilOnce(dut, model, mem,
          n % W, (n / W) % H, 0x11223344, 0x30, (stored << 24) | 0x00ffff)
        if (expectPass) {
          if (colour != 0x11223344)
            bad = bad :+ s"func=$func ref=$ref stored=$stored: colour skipped on pass"
          if (depth != ((ref << 24) | 0x30))
            bad = bad :+ f"func=$func ref=$ref stored=$stored: depth 0x$depth%08x"
        } else {
          if (colour != 0xdeadbeef)
            bad = bad :+ s"func=$func ref=$ref stored=$stored: colour written on stencil fail"
          // The fail op (ZERO) applies to the stored byte; the D24 stays.
          val failByte = stencilRefOp(1, stored, ref)
          if (depth != ((failByte << 24) | 0x00ffff))
            bad = bad :+ f"func=$func ref=$ref stored=$stored: stencil-fail word 0x$depth%08x"
        }
        n += 1
      }
      assert(bad.isEmpty, s"${bad.size} stencil-func mismatches:\n${bad.take(8).mkString("\n")}")
    }
  }

  it should "apply every stencil op with saturation, wrap, and masks" in {
    simulate(new OutputMerger(GraphicsConfig())) { dut =>
      val mem = Array.fill(1 << 15)(0x00ffffff)
      newDut(dut)
      pokeConfig(dut, true, 6, true, false)
      val model = new OooModel(mem)
      val ref = 0x42
      var n = 0
      var bad = List.empty[String]
      // (op, stored, writeMask, expected op result before masking)
      val cases = for (op <- 0 to 7; stored <- Seq(0x9c, 0x00, 0xff)) yield (op, stored)
      for ((op, stored) <- cases) {
        val mask = 0xff
        pokeStencil(dut, true, 6, ref, 0xff, mask, 1, 1, op)
        val (colour, depth) = stencilOnce(dut, model, mem,
          n % W, (n / W) % H, 0x00ff00ff, 0x40, (stored << 24) | 0x11ffff)
        val expectedByte = stencilRefOp(op, stored, ref)
        if (colour != 0x00ff00ff)
          bad = bad :+ f"op=$op stored=$stored: colour 0x$colour%08x"
        if (depth != ((expectedByte << 24) | 0x40))
          bad = bad :+ f"op=$op stored=$stored: depth 0x$depth%08x expected byte 0x$expectedByte%02x"
        n += 1
      }
      // Partial write mask: only the masked nibble of the stored byte
      // changes (0xcd & 0x0f) | (0xab & 0xf0) = 0xad; the D24 takes the
      // fragment depth because depth write is enabled.
      pokeStencil(dut, true, 6, 0xcd, 0xff, 0x0f, 1, 1, 2) // zpass REPLACE
      val (_, depthMasked) = stencilOnce(dut, model, mem, 0, 0, 0xff,
        0x50, 0xab123456)
      assert(depthMasked == 0xad000050,
        f"masked stencil write was 0x$depthMasked%08x, expected 0xad000050")
      assert(bad.isEmpty, s"${bad.size} stencil-op mismatches:\n${bad.take(8).mkString("\n")}")
    }
  }

  it should "apply zfail on a depth fail and skip colour on stencil fail" in {
    simulate(new OutputMerger(GraphicsConfig())) { dut =>
      val mem = Array.fill(1 << 15)(0x00ffffff)
      newDut(dut)
      pokeConfig(dut, true, 0, true, false) // depth LESS, write enabled
      val model = new OooModel(mem)
      // Depth fail: fragDepth 0x30 is not < stored 0x20; zfail INVERT.
      pokeStencil(dut, true, 6, 0, 0xff, 0xff, 1, 5, 2)
      val (colourFail, depthFail) = stencilOnce(dut, model, mem, 0, 0,
        0x12345678, 0x30, 0x3c000020)
      assert(colourFail == 0xdeadbeef, "a depth fail must not write colour")
      assert(depthFail == ((0xc3 << 24) | 0x20),
        f"zfail INVERT gave 0x$depthFail%08x")
      // Stencil fail: func NOTEQUAL with ref == stored; fail INCR.
      pokeStencil(dut, true, 5, 0x55, 0xff, 0xff, 3, 5, 2)
      val (colourSkip, depthSkip) = stencilOnce(dut, model, mem, 1, 1,
        0x12345678, 0x05, 0x55000020)
      assert(colourSkip == 0xdeadbeef, "a stencil fail must not write colour")
      assert(depthSkip == ((0x56 << 24) | 0x20),
        f"stencil fail kept stored depth but applied INCR: 0x$depthSkip%08x")
    }
  }

  it should "update the stencil byte with depth write disabled and keep the depth value" in {
    simulate(new OutputMerger(GraphicsConfig())) { dut =>
      val mem = Array.fill(1 << 15)(0x00ffffff)
      newDut(dut)
      pokeConfig(dut, true, 6, depthWrite = false, false)
      val model = new OooModel(mem)
      pokeStencil(dut, true, 6, 0x77, 0xff, 0xff, 1, 1, 2) // zpass REPLACE
      val (colour, depth) = stencilOnce(dut, model, mem, 2, 2,
        0x00c0ffee, 0x99, 0x12000040)
      assert(colour == 0x00c0ffee, "the fragment still passes and writes colour")
      assert(depth == ((0x77 << 24) | 0x40),
        f"stencil update must force the depth-word write with the stored D24: 0x$depth%08x")
    }
  }

  it should "run the stencil test with the depth test disabled" in {
    simulate(new OutputMerger(GraphicsConfig())) { dut =>
      val mem = Array.fill(1 << 15)(0x00ffffff)
      newDut(dut)
      // Depth test disabled; the stencil test still rides the D24S8 read.
      pokeConfig(dut, false, 0, true, false)
      val model = new OooModel(mem)
      // Stencil pass (EQUAL to the stored byte): zpass REPLACE + colour.
      pokeStencil(dut, true, 4, 0xa5, 0xff, 0xff, 1, 1, 2)
      val (colourPass, depthPass) = stencilOnce(dut, model, mem, 3, 0,
        0x0badf00d, 0x77, 0xa5000060)
      assert(colourPass == 0x0badf00d, "stencil pass with depth test disabled writes colour")
      assert(depthPass == ((0xa5 << 24) | 0x77),
        f"implicit depth pass must write the fragment depth: 0x$depthPass%08x")
      // Stencil fail: fail op ZERO and no colour.
      pokeStencil(dut, true, 5, 0xa5, 0xff, 0xff, 1, 1, 2)
      val (colourFail, depthFail) = stencilOnce(dut, model, mem, 3, 1,
        0x0badf00d, 0x77, 0xa5000060)
      assert(colourFail == 0xdeadbeef, "stencil fail with depth test disabled skips colour")
      assert(depthFail == 0x00000060, f"fail ZERO cleared the byte: 0x$depthFail%08x")
    }
  }
}
