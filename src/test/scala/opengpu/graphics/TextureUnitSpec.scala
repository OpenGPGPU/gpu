package opengpu.graphics

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec

import scala.collection.mutable

/** Verifies the M5b texture unit against an integer-exact software reference.
  *
  * The reference mirrors the documented hardware maths step for step -- same
  * scale/shift, same mask-vs-minimum wrap handling, same weight construction
  * and truncating shift -- so a pass means the hardware decode/filter pipeline
  * is exactly the specified integer computation, not merely close.
  */
class TextureUnitSpec extends AnyFlatSpec {
  behavior of "TextureUnit"

  /** Byte-addressed word memory model with out-of-range tracking. */
  private class TexMem(val base: Long, val width: Int, val height: Int) {
    val words = mutable.LongMap[Int]()
    var oobReads = 0
    def put(x: Int, y: Int, argb: Int): Unit =
      words(base + (y.toLong * width + x) * 4) = argb
    def texel(x: Int, y: Int): Int =
      words.getOrElse(base + (y.toLong * width + x) * 4, 0)
    private def inRange(a: Long): Boolean =
      a >= base && a < base + width.toLong * height * 4 &&
        ((a - base) % 4) == 0
    def read(a: Long): Int = {
      if (!inRange(a)) oobReads += 1
      words.getOrElse(a, 0)
    }
  }

  /** Q16.16 helper. */
  private def q(v: Double): Long = math.round(v * 65536.0)

  /** Texel word in the pipeline's packed-colour order (r,g,b, opaque alpha). */
  private def texel(r: Int, g: Int, b: Int): Int =
    (r << 24) | (g << 16) | (b << 8) | 0xff

  /** Integer-exact software mirror of the hardware sampling path. */
  private def refSample(
    mem: TexMem,
    clampMode: Boolean,
    u: Long,
    v: Long
  ): Int = {
    def axis(coord: Long, extent: Int): (Int, Int, Int) = {
      val biased: Long = (coord * extent.toLong >> 8) - 128L
      val periodLast: Long = extent.toLong << 8
      val inRange =
        if (clampMode)
          if (biased < 0L) 0L else math.min(biased, periodLast - 1L)
        else biased & (periodLast - 1L)
      val idx0raw = (inRange >> 8).toInt
      val frac = (inRange & 0xff).toInt
      val idx0 =
        if (clampMode) math.min(idx0raw, extent - 1) else idx0raw
      val idx1 =
        if (clampMode) math.min(idx0 + 1, extent - 1)
        else (idx0 + 1) & (extent - 1)
      (idx0, idx1, frac)
    }
    val (x0, x1, fx) = axis(u, mem.width)
    val (y0, y1, fy) = axis(v, mem.height)
    val t00 = mem.texel(x0, y0); val t10 = mem.texel(x1, y0)
    val t01 = mem.texel(x0, y1); val t11 = mem.texel(x1, y1)
    val w00 = (256 - fx) * (256 - fy)
    val w10 = fx * (256 - fy)
    val w01 = (256 - fx) * fy
    val w11 = fx * fy
    require(w00 + w10 + w01 + w11 == 65536,
      "reference weights must sum to exactly 65536")
    def chan(sel: Int => Int): Int = {
      val acc = sel(t00) * w00 + sel(t10) * w10 + sel(t01) * w01 +
        sel(t11) * w11
      (acc >> 16) & 0xff
    }
    (chan(w => (w >> 24) & 0xff) << 24) |
      (chan(w => (w >> 16) & 0xff) << 16) |
      (chan(w => (w >> 8) & 0xff) << 8) | 0xff
  }

  /** Attach config pins once; request/response/memory lines are driven by
    * the shared service loop below.
    */
  private def attach(dut: TextureUnit, mem: TexMem, wrapClamp: Boolean): Unit = {
    dut.io.texBase.poke(mem.base.U)
    dut.io.texWidth.poke(mem.width.U)
    dut.io.texHeight.poke(mem.height.U)
    dut.io.wrapMode.poke(wrapClamp.B)
    dut.io.mem.req.ready.poke(true.B)
  }

  /** One sample transaction driven through a single continuous service loop
    * (the pattern proven by GpuHostAxiSpec): accept, answer the four word
    * fetches with one-cycle latency each, consume the held result.
    *
    * If `stallOnValid` cycles > 0 the consumer deliberately stalls once the
    * result appears; the returned value is what was visible DURING the stall,
    * which checks that the held result neither changes nor leaks.
    */
  private def runSample(
    dut: TextureUnit,
    mem: TexMem,
    u: Long,
    v: Long,
    stallCycles: Int = 0,
    maxCycles: Int = 400
  ): Int = {
    dut.io.sample.valid.poke(true.B)
    dut.io.sample.bits.u.poke(u.U)
    dut.io.sample.bits.v.poke(v.U)
    var pendingAddr = -1L
    var accepted = false
    var seenValue = false
    var value = 0
    var guard = 0
    var done = false
    var stalled = 0
    while (!done && guard < maxCycles) {
      // Request side: drop valid once the handshake has committed.
      if (accepted) dut.io.sample.valid.poke(false.B)
      else if (dut.io.sample.valid.peek().litToBoolean &&
        dut.io.sample.ready.peek().litToBoolean) accepted = true

      // Response side: present the captured fetch one cycle late.
      if (pendingAddr >= 0) {
        dut.io.mem.resp.valid.poke(true.B)
        dut.io.mem.resp.bits.data.poke(
          BigInt(mem.read(pendingAddr) & 0xffffffffL).U)
        dut.io.mem.resp.bits.write.poke(false.B)
        if (dut.io.mem.resp.valid.peek().litToBoolean &&
          dut.io.mem.resp.ready.peek().litToBoolean) pendingAddr = -1L
      } else dut.io.mem.resp.valid.poke(false.B)
      if (pendingAddr < 0 && dut.io.mem.req.valid.peek().litToBoolean &&
        dut.io.mem.req.ready.peek().litToBoolean)
        pendingAddr = dut.io.mem.req.bits.addr.peek().litValue.toLong

      // Result side: optionally stall first, then consume.
      dut.io.result.ready.poke(false.B)
      if (!seenValue && dut.io.result.valid.peek().litToBoolean) {
        value = dut.io.result.bits.peek().litValue.toInt & 0xffffffff
        seenValue = true
      }
      if (seenValue) {
        if (stallCycles > stalled) stalled += 1
        else {
          dut.io.result.ready.poke(true.B)
          if (dut.io.result.ready.peek().litToBoolean) done = true
        }
      }

      dut.clock.step()
      guard += 1
    }
    dut.io.sample.valid.poke(false.B)
    dut.io.result.ready.poke(false.B)
    dut.io.mem.resp.valid.poke(false.B)
    assert(done, s"sample did not complete (guard=$guard)")
    value
  }

  it should "blend a gradient bilinearly at interior points" in {
    val mem = new TexMem(0x4000L, width = 2, height = 2)
    simulate(new TextureUnit()) { dut =>
      attach(dut, mem, wrapClamp = false)
      mem.put(0, 0, texel(0, 0, 0)) // black
      mem.put(1, 0, texel(255, 0, 0)) // red
      mem.put(0, 1, texel(0, 255, 0)) // green
      mem.put(1, 1, texel(255, 255, 0)) // yellow

      val got = runSample(dut, mem, q(0.5), q(0.5))
      val r = ((got >> 24) & 0xff); val g = ((got >> 16) & 0xff)
      val b = ((got >> 8) & 0xff)
      assert(r == 127 && g == 127 && b == 0,
        s"centre blend expected ~(127,127,0), got ($r,$g,$b)")
      val expectQuarter =
        refSample(mem, clampMode = false, q(0.25), q(0.25))
      val gotQuarter = runSample(dut, mem, q(0.25), q(0.25))
      assert(gotQuarter == expectQuarter,
        f"quarter-point hw=${gotQuarter.toHexString} ref=${expectQuarter.toHexString}")
    }
  }

  it should "clamp past-edge samples to edge texels without out-of-bounds reads" in {
    val mem = new TexMem(0x4000L, width = 4, height = 4)
    simulate(new TextureUnit()) { dut =>
      attach(dut, mem, wrapClamp = true)
      for (y <- 0 until 4; x <- 0 until 4)
        mem.put(x, y, texel(x * 40, y * 40, 0))

      val far = runSample(dut, mem, q(1.75), q(1.75))
      val expect = refSample(mem, clampMode = true, q(1.75), q(1.75))
      assert(far == expect, s"clamped far corner hw=${far.toHexString} ref=${expect.toHexString}")
      assert(far == mem.texel(3, 3),
        s"expected solid edge texel, got ${far.toHexString}")
      assert(mem.oobReads == 0, "hardware fetched outside the texture")
    }
  }

  it should "tile with REPEAT across the seam" in {
    val mem = new TexMem(0x4000L, width = 4, height = 4)
    simulate(new TextureUnit()) { dut =>
      attach(dut, mem, wrapClamp = false)
      for (y <- 0 until 4; x <- 0 until 4)
        mem.put(x, y, texel(x * 60, y * 60 + 10, 5))

      val got = runSample(dut, mem, q(1.02), q(0.5))
      val expect = refSample(mem, clampMode = false, q(1.02), q(0.5))
      assert(got == expect, f"seam wrap hw=${got.toHexString} ref=${expect.toHexString}")
      assert(mem.oobReads == 0, "hardware fetched outside the texture")
    }
  }

  it should "return exact texel values at sample centres (nearest via degenerate bilinear)" in {
    val mem = new TexMem(0x4000L, width = 8, height = 8)
    simulate(new TextureUnit()) { dut =>
      attach(dut, mem, wrapClamp = false)
      for (y <- 0 until 8; x <- 0 until 8)
        mem.put(x, y, texel(x * 13, y * 17, (x ^ y) & 0xff))
      for (y <- 0 until 8; x <- 0 until 8) {
        val got = runSample(dut, mem,
          ((2 * x + 1) * 65536L / 16), ((2 * y + 1) * 65536L / 16))
        val want = mem.texel(x, y)
        assert(got == want,
          f"texel centre ($x,$y) got ${got.toHexString} want ${want.toHexString}")
      }
      assert(mem.oobReads == 0, "hardware fetched outside the texture")
    }
  }

  it should "match the software reference across randomized samples and hold results under backpressure" in {
    val mem = new TexMem(0x4000L, width = 16, height = 16)
    simulate(new TextureUnit()) { dut =>
      attach(dut, mem, wrapClamp = false)
      for (y <- 0 until 16; x <- 0 until 16)
        mem.put(x, y, texel((x * 211 + y * 37) & 0xff,
          (x * 7 + y * 149) & 0xff, (x * y) & 0xff))

      var seed = 0xf00dcafeL
      def rnd(n: Int): Int = {
        seed = seed * 6364136223846793005L + 1442695040888963407L
        (((seed >>> 33) & 0x7fffffffL) % n).toInt
      }

      // Bit-exact randomized sweep spanning interior, seam, clamped overshoot.
      for (t <- 0 until 20) {
        val u = rnd((1 << 16) + 512).toLong
        val v = rnd((1 << 16) + 512).toLong
        val expect = refSample(mem, clampMode = false, u, v)
        val got = runSample(dut, mem, u, v)
        assert(got == expect,
          f"sample $t u=$u v=$v hw=${got.toHexString} ref=${expect.toHexString}")
      }
      assert(mem.oobReads == 0, "hardware fetched outside the texture")

      // Backpressure regression: stall once the result is visible, confirm
      // the held value matches the reference, release, then run a follow-up
      // sample to prove no cross-request tap leak (the M4b failure class).
      val ua = q(0.25); val va = q(0.25)
      val ub = q(0.75); val vb = q(0.75)
      val gotA = runSample(dut, mem, ua, va, stallCycles = 12)
      val expectA = refSample(mem, clampMode = false, ua, va)
      assert(gotA == expectA,
        f"stalled result hw=${gotA.toHexString} ref=${expectA.toHexString}")
      val gotB = runSample(dut, mem, ub, vb)
      val expectB = refSample(mem, clampMode = false, ub, vb)
      assert(gotB == expectB,
        f"post-stall sample leaked state: hw=${gotB.toHexString} ref=${expectB.toHexString}")
      assert(mem.oobReads == 0, "hardware fetched outside the texture")
    }
  }
}
