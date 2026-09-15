package opengpu.graphics

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec

import scala.collection.mutable

/** Verifies [[MsaaResolveEngine]]: channel averaging, rounding, padded strides
  * and the single-outstanding request protocol.  The engine is the streaming
  * backend for the typed resolve operation defined in
  * [[docs/MSAA_DESIGN.md]]; range validation and scheduler fencing belong to
  * the caller and are covered separately.
  */
class MsaaResolveSpec extends AnyFlatSpec {
  behavior of "MsaaResolveEngine"

  private class MemModel {
    val words = mutable.LongMap[Int]()
    def word(a: Long): Long = words.getOrElse(a, 0) & 0xffffffffL
    def wwrite(a: Long, d: Int): Unit = words(a) = d & 0xffffffff
  }

  /** Rounded half-up channel average of packed RGBA8888 words. */
  private def average(samples: Seq[Int]): Long = {
    val n = samples.length
    val round = if (n == 1) 0 else n / 2
    val channels = (0 until 4).map { c =>
      val sum = samples.map(w => (w >> (8 * c)) & 0xff).sum
      ((sum + round) / n) & 0xff
    }
    channels(0).toLong | (channels(1).toLong << 8) |
      (channels(2).toLong << 16) | (channels(3).toLong << 24)
  }

  /** Runs one resolve and returns the memory plus the set of written addresses.
    * `fill(px, py, sample)` provides the source words. */
  private def run(
    mode: Int, width: Int, height: Int,
    srcBase: Int, dstBase: Int, srcStride: Int, dstStride: Int
  )(fill: (Int, Int, Int) => Int): (MemModel, Set[Long]) = {
    val m = new MemModel
    val samples = 1 << mode
    for (py <- 0 until height; px <- 0 until width; s <- 0 until samples)
      m.wwrite(srcBase + py * srcStride + (px * samples + s) * 4L,
        fill(px, py, s))
    val writes = mutable.Set.empty[Long]
    simulate(new MsaaResolveEngine) { dut =>
      dut.io.start.poke(false.B)
      dut.io.srcBase.poke(srcBase.U)
      dut.io.dstBase.poke(dstBase.U)
      dut.io.srcStride.poke(srcStride.U)
      dut.io.dstStride.poke(dstStride.U)
      dut.io.imgWidth.poke(width.U)
      dut.io.imgHeight.poke(height.U)
      dut.io.sampleMode.poke(mode.U)
      dut.io.mem.resp.valid.poke(false.B)
      dut.io.mem.resp.bits.write.poke(false.B)
      dut.io.mem.resp.bits.addr.poke(0.U)
      dut.io.mem.resp.bits.data.poke(0.U)
      dut.reset.poke(true.B); dut.clock.step()
      dut.reset.poke(false.B)
      dut.io.start.poke(true.B)
      dut.clock.step()
      dut.io.start.poke(false.B)

      var pending: Option[(Boolean, Long, Long)] = None
      var doneSeen = false
      var guard = 0
      while (!doneSeen && guard < 20000) {
        dut.io.mem.req.ready.poke(true.B)
        pending match {
          case Some((w, a, d)) =>
            dut.io.mem.resp.valid.poke(true.B)
            dut.io.mem.resp.bits.write.poke(w.B)
            dut.io.mem.resp.bits.addr.poke(a.U)
            dut.io.mem.resp.bits.data.poke(d.U)
          case None => dut.io.mem.resp.valid.poke(false.B)
        }
        val respReady = dut.io.mem.resp.ready.peek().litToBoolean
        if (pending.nonEmpty && respReady) {
          val (w, a, d) = pending.get
          if (w) { m.wwrite(a, (d & 0xffffffffL).toInt); writes += a }
          pending = None
        }
        val reqFire = dut.io.mem.req.valid.peek().litToBoolean &&
          dut.io.mem.req.ready.peek().litToBoolean
        if (pending.isEmpty && reqFire) {
          val w = dut.io.mem.req.bits.write.peek().litToBoolean
          val a = dut.io.mem.req.bits.addr.peek().litValue.toLong
          val d = if (w) dut.io.mem.req.bits.data.peek().litValue.toLong
            else m.word(a)
          pending = Some((w, a, d))
        }
        if (dut.io.done.peek().litToBoolean) doneSeen = true
        dut.clock.step()
        guard += 1
      }
      assert(doneSeen, "resolve engine did not complete")
      dut.clock.step()
      assert(!dut.io.busy.peek().litToBoolean, "busy must clear after done")
    }
    (m, writes.toSet)
  }

  it should "average every 4x sample channel with half-up rounding" in {
    val width = 3
    val height = 2
    val samples = 4
    val srcBase = 0x1000
    val dstBase = 0x2000
    val srcStride = width * samples * 4 + 8 // padded physical row
    val dstStride = width * 4 + 4

    val data = Map(
      (0, 0) -> Seq(0x00000000, 0xffffffff, 0xff00ff00, 0x00ff00ff),
      (1, 0) -> Seq(0x11223344, 0x11223344, 0x11223344, 0x11223344),
      (2, 0) -> Seq(0xffffffff, 0xff000000, 0x00ff0000, 0x0000ff00),
      (0, 1) -> Seq(0x01020304, 0x05060708, 0x090a0b0c, 0x0d0e0f10),
      (1, 1) -> Seq.fill(4)(0xdeadbeef),
      (2, 1) -> Seq.fill(4)(0x80000000)
    )
    val (m, writes) = run(2, width, height, srcBase, dstBase,
      srcStride, dstStride)((px, py, s) => data((px, py))(s))

    // 510/4 = 127.5 rounds up to 128 per channel.
    assert(m.word(dstBase) == 0x80808080L,
      f"expected 0x80808080, got 0x${m.word(dstBase)}%08x")
    assert(m.word(dstBase + 4) == 0x11223344L)
    assert(m.word(dstBase + 8) == average(data((2, 0))))
    assert(m.word(dstBase + dstStride) == average(data((0, 1))))
    assert(m.word(dstBase + dstStride + 4) == 0xdeadbeefL)
    assert(m.word(dstBase + dstStride + 8) == 0x80000000L,
      f"expected 0x80000000, got 0x${m.word(dstBase + dstStride + 8)}%08x")

    val expectedWrites = (for (py <- 0 until height; px <- 0 until width)
      yield dstBase + py * dstStride + px * 4L).toSet
    assert(writes == expectedWrites,
      s"the engine must write exactly the destination pixels, got $writes")
  }

  it should "average 2x samples and pass 1x samples through unchanged" in {
    val srcBase = 0x1000
    val dstBase = 0x2000

    val twoX = Map((0, 0) -> Seq(0xffffffff, 0x00000000),
      (1, 0) -> Seq(0x01020304, 0x05060708))
    val (m2, _) = run(1, 2, 1, srcBase, dstBase, 2 * 2 * 4 + 12, 2 * 4) {
      (px, py, s) => twoX((px, py))(s)
    }
    assert(m2.word(dstBase) == 0x80808080L,
      f"255/0 must round to 128, got 0x${m2.word(dstBase)}%08x")
    assert(m2.word(dstBase + 4) == average(twoX((1, 0))))

    val oneX = Map((0, 0) -> Seq(0x00c0ffee),
      (1, 0) -> Seq(0xffffffff), (0, 1) -> Seq(0x01020304),
      (1, 1) -> Seq(0x80000000))
    val (m1, _) = run(0, 2, 2, srcBase, dstBase, 2 * 4 + 16, 2 * 4 + 8) {
      (px, py, s) => oneX((px, py))(s)
    }
    assert(m1.word(dstBase) == 0x00c0ffeeL)
    assert(m1.word(dstBase + 4) == 0xffffffffL)
    assert(m1.word(dstBase + 2 * 4 + 8) == 0x01020304L)
    assert(m1.word(dstBase + 2 * 4 + 8 + 4) == 0x80000000L)
  }

  it should "complete a zero-extent resolve without touching memory" in {
    simulate(new MsaaResolveEngine) { dut =>
      dut.io.start.poke(false.B)
      dut.io.srcBase.poke(0x1000.U)
      dut.io.dstBase.poke(0x2000.U)
      dut.io.srcStride.poke(64.U)
      dut.io.dstStride.poke(16.U)
      dut.io.imgWidth.poke(0.U)
      dut.io.imgHeight.poke(4.U)
      dut.io.sampleMode.poke(2.U)
      dut.io.mem.req.ready.poke(true.B)
      dut.io.mem.resp.valid.poke(false.B)
      dut.reset.poke(true.B); dut.clock.step()
      dut.reset.poke(false.B)
      dut.io.start.poke(true.B)
      dut.clock.step()
      dut.io.start.poke(false.B)
      var sawRequest = false
      var doneSeen = false
      var guard = 0
      while (!doneSeen && guard < 16) {
        if (dut.io.mem.req.valid.peek().litToBoolean) sawRequest = true
        if (dut.io.done.peek().litToBoolean) doneSeen = true
        dut.clock.step()
        guard += 1
      }
      assert(doneSeen, "a zero-extent resolve must complete")
      assert(!sawRequest, "a zero-extent resolve must not issue requests")
    }
  }
}
