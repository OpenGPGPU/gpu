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
