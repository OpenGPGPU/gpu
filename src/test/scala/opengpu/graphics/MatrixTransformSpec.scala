package opengpu.graphics

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec

class MatrixTransformSpec extends AnyFlatSpec {
  behavior of "MatrixTransform"

  private def q(v: Double): Int = (v * (1 << 16)).toInt

  it should "leave the input unchanged for an identity matrix" in {
    simulate(new MatrixTransform()) { dut =>
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      for (i <- 0 until 4; j <- 0 until 4)
        dut.io.m.m(i)(j).poke((if (i == j) q(1.0) else 0).S)
      dut.io.v(0).poke(q(0.5).S)
      dut.io.v(1).poke(q(-0.25).S)
      dut.io.v(2).poke(q(1.5).S)
      dut.io.v(3).poke(q(1.0).S)
      dut.clock.step()
      dut.io.out(0).expect(q(0.5).S)
      dut.io.out(1).expect(q(-0.25).S)
      dut.io.out(2).expect(q(1.5).S)
      dut.io.out(3).expect(q(1.0).S)
    }
  }

  it should "apply a uniform scale (present as a constant diagonal)" in {
    simulate(new MatrixTransform()) { dut =>
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      // diag(2, 2, 2, 1)
      for (i <- 0 until 4; j <- 0 until 4) {
        val d = if (i == j) (if (i == 3) q(1.0) else q(2.0)) else 0
        dut.io.m.m(i)(j).poke(d.S)
      }
      dut.io.v(0).poke(q(0.5).S)
      dut.io.v(1).poke(q(1.25).S)
      dut.io.v(2).poke(q(-0.5).S)
      dut.io.v(3).poke(q(1.0).S)
      dut.clock.step()
      dut.io.out(0).expect(q(1.0).S) // 0.5*2
      dut.io.out(1).expect(q(2.5).S) // 1.25*2
      dut.io.out(2).expect(q(-1.0).S) // -0.5*2
      dut.io.out(3).expect(q(1.0).S)
    }
  }

  it should "accumulate a row-wise weighted sum (translation in row 3)" in {
    simulate(new MatrixTransform()) { dut =>
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      // identity plus a translation: row3 col0 = x_translation (= 0.25), col3=1.
      for (i <- 0 until 4; j <- 0 until 4) {
        val d = if (i == j) q(1.0) else 0
        val v = if (i == 3 && j == 0) q(0.25) else d
        dut.io.m.m(i)(j).poke(v.S)
      }
      dut.io.v(0).poke(q(1.0).S)
      dut.io.v(1).poke(q(2.0).S)
      dut.io.v(2).poke(q(3.0).S)
      dut.io.v(3).poke(q(1.0).S)
      dut.clock.step()
      // out(3) = 1.0*col0 + 1.0 + ... = 0.25*1.0 + 0 + 0 + 1.0*1.0 = 1.25
      dut.io.out(3).expect(q(1.25).S)
      dut.io.out(0).expect(q(1.0).S)
    }
  }
}
