package opengpu.graphics

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec

class MsaaSpec extends AnyFlatSpec {
  behavior of "SampleCoverage"

  private def edgeInside(v: Long, front: Boolean, tl: Boolean): Boolean =
    if (front) v > 0L || (v == 0L && tl) else v < 0L || (v == 0L && tl)

  /** Software mirror of the fixed-position sample test. */
  private def reference(edges: Seq[Long], qdx: Seq[Long], qdy: Seq[Long],
                        front: Boolean, tl: Seq[Boolean], mode: Int): Int =
    Msaa.positions(mode).zipWithIndex.foldLeft(0) { case (acc, ((x, y), idx)) =>
      val covered = (0 until 3).forall { e =>
        edgeInside(edges(e) + x * qdx(e) + y * qdy(e), front, tl(e))
      }
      if (covered) acc | (1 << idx) else acc
    }

  private val edgeSets = Seq(
    (Seq(100L, -50L, 30L), Seq(20L, -8L, 5L), Seq(-10L, 4L, 3L)),
    (Seq(-7L, 200L, -3L), Seq(-30L, 12L, 9L), Seq(11L, -6L, 2L)),
    (Seq(0L, 0L, 0L), Seq(16L, -4L, 0L), Seq(0L, 8L, -12L))
  )

  it should "match the fixed sample positions for every mode" in {
    simulate(new SampleCoverage(4, 64)) { dut =>
      for ((edges, qdx, qdy) <- edgeSets; front <- Seq(true, false)) {
        val tl = Seq(true, false, true)
        dut.io.edges.zipWithIndex.foreach { case (e, i) => e.poke(edges(i).S) }
        dut.io.quarterDx.zipWithIndex.foreach { case (e, i) => e.poke(qdx(i).S) }
        dut.io.quarterDy.zipWithIndex.foreach { case (e, i) => e.poke(qdy(i).S) }
        dut.io.front.poke(front.B)
        dut.io.topLeft.zipWithIndex.foreach { case (e, i) => e.poke(tl(i).B) }
        for (mode <- 0 until 3) {
          dut.io.sampleMode.poke(mode.U)
          dut.clock.step()
          val expected = reference(edges, qdx, qdy, front, tl, mode)
          assert(dut.io.mask.peek().litValue.toInt == expected,
            s"mode $mode edges=$edges front=$front: got " +
              f"${dut.io.mask.peek().litValue.toInt}%x expected $expected%x")
        }
      }
    }
  }

  it should "reduce to the pixel-centre result in 1x mode" in {
    simulate(new SampleCoverage(4, 64)) { dut =>
      for (front <- Seq(true, false)) {
        val tl = Seq(false, true, false)
        // Centre: bit 0 only, and equal to the all-edges centre test.
        dut.io.edges(0).poke(5.S)
        dut.io.edges(1).poke(-5.S)
        dut.io.edges(2).poke(0.S)
        dut.io.quarterDx.foreach(_.poke(99.S))
        dut.io.quarterDy.foreach(_.poke(-99.S))
        dut.io.front.poke(front.B)
        dut.io.topLeft.zipWithIndex.foreach { case (e, i) => e.poke(tl(i).B) }
        dut.io.sampleMode.poke(0.U)
        dut.clock.step()
        val centre = (0 until 3).forall(i =>
          edgeInside(Seq(5L, -5L, 0L)(i), front, tl(i)))
        assert(dut.io.mask.peek().litValue.toInt == (if (centre) 1 else 0))
      }
    }
  }
}

class SampleExpanderSpec extends AnyFlatSpec {
  behavior of "SampleExpander"

  private def resetDut(dut: SampleExpander): Unit = {
    dut.io.in.valid.poke(false.B)
    dut.io.out.ready.poke(false.B)
    dut.reset.poke(true.B)
    dut.clock.step()
    dut.reset.poke(false.B)
  }

  private def present(dut: SampleExpander, mask: Int): Unit = {
    dut.io.in.bits.x.poke(7.U)
    dut.io.in.bits.y.poke(9.U)
    dut.io.in.bits.color.poke("h11223344".U)
    dut.io.in.bits.coverageMask.poke(mask.U)
    for (i <- 0 until 4) dut.io.in.bits.depths(i).poke((10 + i).U)
    dut.io.in.valid.poke(true.B)
  }

  it should "visit set coverage bits in ascending order with per-sample depth" in {
    simulate(new SampleExpander(4)) { dut =>
      resetDut(dut)
      present(dut, 0xa) // samples 1 and 3
      dut.clock.step() // accept
      dut.io.in.valid.poke(false.B)

      dut.io.out.valid.expect(true.B)
      dut.io.in.ready.expect(false.B)
      dut.io.drained.expect(false.B)
      dut.io.out.bits.sampleIndex.expect(1.U)
      dut.io.out.bits.depth.expect(11.U)
      dut.io.out.bits.x.expect(7.U)
      dut.io.out.bits.color.expect("h11223344".U)

      // Held against backpressure: payload and valid stay put.
      dut.clock.step()
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.sampleIndex.expect(1.U)

      dut.io.out.ready.poke(true.B)
      dut.clock.step()
      dut.io.out.bits.sampleIndex.expect(3.U)
      dut.io.out.bits.depth.expect(13.U)
      dut.clock.step()
      dut.io.out.valid.expect(false.B)
      dut.io.drained.expect(true.B)
      dut.io.in.ready.expect(true.B)
    }
  }

  it should "consume a zero mask without producing a sample" in {
    simulate(new SampleExpander(4)) { dut =>
      resetDut(dut)
      present(dut, 0x0)
      dut.clock.step()
      dut.io.out.valid.expect(false.B)
      dut.io.drained.expect(true.B)
      dut.io.in.ready.expect(true.B) // mask is only latched on the accept edge
      dut.io.in.valid.poke(false.B)
    }
  }
}

class DepthGradientSpec extends AnyFlatSpec {
  behavior of "DepthGradient"

  /** Low 32 bits of a Chisel SInt result, sign-extended to a Long. */
  private def lowSInt32(q: BigInt): Long = {
    val masked = q & BigInt("ffffffff", 16)
    val l = masked.toLong
    if (l >= 0x80000000L) l - 0x100000000L else l
  }

  /** BigInt reference; BigInt's `/` truncates toward zero like Chisel SInt. */
  private def gradRef(plane: Seq[Long], d: Seq[Long], area: Long): Long = {
    val num = plane.zip(d).map { case (a, dd) => BigInt(a) * BigInt(dd) }.sum
    lowSInt32((num << 6) / BigInt(area))
  }

  private val cases = Seq(
    // Lower-left-half triangle: d = (0, 4096, 2048) over a 16x16 viewport
    // gives an exact X + Y/2 depth ramp, i.e. qx = +64, qy = +32.
    (-4096L, 4096L, 0L, -4096L, 0L, 4096L, 16777216L, Seq(0L, 4096L, 2048L)),
    // Truncating toward zero for a positive and a negative quotient.
    (1L, 1L, 1L, -1L, -1L, -1L, 100L, Seq(1L, 2L, 3L)),
    // Quotient wider than 32 bits: the export keeps the low word.
    (1L, 0L, 0L, 0L, 0L, 0L, 1L, Seq(2147483647L, 0L, 0L))
  )

  it should "match the plane-gradient reference on both axes" in {
    simulate(new DepthGradient(GraphicsConfig())) { dut =>
      for ((a0, a1, a2, b0, b1, b2, area, d) <- cases) {
        val planeA = Seq(a0, a1, a2)
        val planeB = Seq(b0, b1, b2)
        dut.io.planeA.zipWithIndex.foreach { case (p, i) => p.poke(planeA(i).S) }
        dut.io.planeB.zipWithIndex.foreach { case (p, i) => p.poke(planeB(i).S) }
        dut.io.area.poke(area.S)
        dut.io.d0.poke(d(0).S)
        dut.io.d1.poke(d(1).S)
        dut.io.d2.poke(d(2).S)
        dut.clock.step()
        assert(dut.io.quarterDx.peek().litValue.toLong == gradRef(planeA, d, area),
          s"quarterDx planeA=$planeA d=$d area=$area")
        assert(dut.io.quarterDy.peek().litValue.toLong == gradRef(planeB, d, area),
          s"quarterDy planeB=$planeB d=$d area=$area")
      }
    }
  }
}

class SampleDepthSpec extends AnyFlatSpec {
  behavior of "SampleDepth"

  private def sampleRef(centre: Long, qdx: Long, qdy: Long, mode: Int): Seq[Long] = {
    val positions = Msaa.positions(mode)
    (0 until 4).map { i =>
      if (mode == 0 && i == 0) centre & 0x3fffffffL
      else if (i < positions.length) {
        val (sx, sy) = positions(i)
        val v = BigInt(centre) + BigInt(sx) * BigInt(qdx) + BigInt(sy) * BigInt(qdy)
        if (v < 0) 0L
        else if (v > ((1L << 30) - 1)) (1L << 30) - 1
        else v.toLong
      } else 0L
    }
  }

  private def check(dut: SampleDepth, centre: Long, qdx: Long, qdy: Long): Unit = {
    dut.io.centre.poke(centre.S)
    dut.io.quarterDx.poke(qdx.S)
    dut.io.quarterDy.poke(qdy.S)
    for (mode <- 0 until 3) {
      dut.io.sampleMode.poke(mode.U)
      dut.clock.step()
      val expected = sampleRef(centre, qdx, qdy, mode)
      for (i <- 0 until 4) {
        assert(dut.io.depths(i).peek().litValue.toLong == expected(i),
          s"mode $mode sample $i centre=$centre qdx=$qdx qdy=$qdy")
      }
    }
  }

  it should "match the affine per-sample reference in every mode" in {
    simulate(new SampleDepth(4)) { dut => check(dut, 1000L, 10L, 20L) }
  }

  it should "saturate multi-sample depths at both ends" in {
    // (-1,-1) underflows to zero; (+1,+1) overflows the D24 range.
    simulate(new SampleDepth(4)) { dut => check(dut, 5L, 10L, 10L) }
    simulate(new SampleDepth(4)) { dut => check(dut, (1L << 30) - 1, 10L, 10L) }
  }

  it should "keep the 1x sample as the legacy truncated centre" in {
    simulate(new SampleDepth(4)) { dut =>
      check(dut, 123456789L, 999L, -999L)
      // Negative and over-range centres truncate to the low 30 bits rather
      // than saturating, preserving the pre-MSAA single-sample output.
      check(dut, -1L, 999L, -999L)
      check(dut, 0x7fffffffL, 999L, -999L)
    }
  }
}
