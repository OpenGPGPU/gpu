package opengpu.graphics

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec

class PerspectiveInterpolatorSpec extends AnyFlatSpec {
  behavior of "PerspectiveInterpolator"

  it should "interpolate a colour with perspective (1/w) weights" in {
    val config = GraphicsConfig()
    simulate(new PerspectiveInterpolator(config)) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      // Edge values all equal (equal screen-space barycentric), but 1/w differ:
      //   invW = {1, 2, 4}  =>  weights (1,2,4)/7.
      dut.io.e0.poke(1.S)
      dut.io.e1.poke(1.S)
      dut.io.e2.poke(1.S)
      dut.io.invW0.poke(1.S)
      dut.io.invW1.poke(2.S)
      dut.io.invW2.poke(4.S)

      // attr = (a0*1 + a1*2 + a2*4) / 7.
      // (7,7,7) -> 7 ; (7,0,0) -> 1 ; (0,7,0) -> 2 ; (0,0,7) -> 4.
      dut.io.c0.r.poke(7.U); dut.io.c0.g.poke(7.U); dut.io.c0.b.poke(7.U)
      dut.io.c1.r.poke(7.U); dut.io.c1.g.poke(0.U); dut.io.c1.b.poke(0.U)
      dut.io.c2.r.poke(7.U); dut.io.c2.g.poke(0.U); dut.io.c2.b.poke(7.U)
      dut.clock.step()

      // r = (7 + 14 + 28)/7 = 7, g = (7 + 0 + 0)/7 = 1, b = (7 + 0 + 28)/7 = 5.
      dut.io.color.r.expect(7.U)
      dut.io.color.g.expect(1.U)
      dut.io.color.b.expect(5.U)
    }
  }

  it should "reduce to the screen-space average when all 1/w are equal" in {
    val config = GraphicsConfig()
    simulate(new PerspectiveInterpolator(config)) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      dut.io.e0.poke(1.S); dut.io.e1.poke(1.S); dut.io.e2.poke(1.S)
      dut.io.invW0.poke(1.S); dut.io.invW1.poke(1.S); dut.io.invW2.poke(1.S)
      dut.io.c0.r.poke(10.U); dut.io.c1.r.poke(20.U); dut.io.c2.r.poke(30.U)
      dut.io.c0.g.poke(0.U); dut.io.c1.g.poke(0.U); dut.io.c2.g.poke(0.U)
      dut.io.c0.b.poke(0.U); dut.io.c1.b.poke(0.U); dut.io.c2.b.poke(0.U)
      dut.clock.step()
      dut.io.color.r.expect(20.U) // (10+20+30)/3
      dut.io.color.g.expect(0.U)
    }
  }
}
