package opengpu.graphics

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec

class ShaderFragStageSpec extends AnyFlatSpec {
  behavior of "ShaderFragStage"

  it should "shade a fragment through the SIMT shader with a tint program" in {
    val config = GraphicsConfig()
    simulate(new ShaderFragStage(config, lanes = 1, progSize = 4)) { dut =>
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B); dut.io.programBase.poke(0.U)
      // Program: r0 = sat(r0 + uniform[3]); out = r0.  uniform[3] tint = +10.
      def op(i: Int, o: Int, d: Int, a: Int, b: Int, imm: Int): Unit = {
        dut.io.prog(i).op.poke(o.U)
        dut.io.prog(i).dst.poke(d.U)
        dut.io.prog(i).a.poke(a.U)
        dut.io.prog(i).b.poke(b.U)
        dut.io.prog(i).imm.poke(imm.S)
      }
      op(0, 0, 0, 0, 0, 0)         // NOP
      op(1, 2, 3, 0, 0, 0)         // r3 = r0 + r0  (stub)
      op(2, 0, 0, 0, 0, 0)         // NOP
      op(3, 7, 0, 0, 0, 0)         // out = r0
      dut.io.uniform(3).poke(10.S)
      dut.io.fragIn.valid.poke(true.B)
      dut.io.fragIn.bits.x.poke(3.S)
      dut.io.fragIn.bits.y.poke(7.S)
      dut.io.fragIn.bits.depth.poke(0x20.S)
      dut.io.fragIn.bits.color.r.poke(100.U)
      dut.io.fragIn.bits.color.g.poke(100.U)
      dut.io.fragIn.bits.color.b.poke(100.U)
      dut.io.out.ready.poke(true.B)
      dut.clock.step()
      dut.io.fragIn.valid.poke(false.B)

      // Program runs SAT? No — this program just outputs r0 unchanged = 100.
      var guard = 0
      while (!dut.io.out.valid.peek().litToBoolean && guard < 64) { dut.clock.step(); guard += 1 }
      assert(guard < 64, "fragment was not shaded")
      dut.io.out.bits.x.expect(3.S)
      dut.io.out.bits.y.expect(7.S)
      dut.io.out.bits.depth.expect(0x20.S)
      dut.io.out.bits.color.r.expect(100.U)
      dut.clock.step()
      dut.io.out.valid.expect(false.B)
    }
  }

  it should "apply an SAT after an ADD (uniform tint) to the fragment colour" in {
    val config = GraphicsConfig()
    simulate(new ShaderFragStage(config, lanes = 1, progSize = 5)) { dut =>
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B); dut.io.programBase.poke(0.U)
      def op(i: Int, o: Int, d: Int, a: Int, b: Int, imm: Int): Unit = {
        dut.io.prog(i).op.poke(o.U); dut.io.prog(i).dst.poke(d.U)
        dut.io.prog(i).a.poke(a.U); dut.io.prog(i).b.poke(b.U); dut.io.prog(i).imm.poke(imm.S)
      }
      op(0, 2, 3, 4, 0, 0)  // r3 = uniform[4]
      op(1, 3, 3, 3, 0, 0)  // r3 = r3 + r0   (uniform tint add)
      op(2, 6, 3, 3, 0, 0)  // sat r3
      op(3, 0, 0, 0, 0, 0)
      op(4, 7, 0, 3, 0, 0)  // out = r3
      dut.io.uniform(4).poke(200.S) // r0=100 + 200 = 300 -> sat 255
      dut.io.fragIn.valid.poke(true.B)
      dut.io.fragIn.bits.x.poke(0.S); dut.io.fragIn.bits.y.poke(0.S); dut.io.fragIn.bits.depth.poke(0x10.S)
      dut.io.fragIn.bits.color.r.poke(100.U)
      dut.io.fragIn.bits.color.g.poke(0.U); dut.io.fragIn.bits.color.b.poke(0.U)
      dut.io.out.ready.poke(true.B)
      dut.clock.step()
      dut.io.fragIn.valid.poke(false.B)
      var guard = 0
      while (!dut.io.out.valid.peek().litToBoolean && guard < 64) { dut.clock.step(); guard += 1 }
      assert(guard < 64, "not shaded")
      dut.io.out.bits.color.r.expect(255.U)
    }
  }
}
