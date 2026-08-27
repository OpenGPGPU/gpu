package opengpu.graphics

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec

class ShaderCoreSpec extends AnyFlatSpec {
  behavior of "ShaderCore"

  private def run(
    op: Int, dst: Int, a: Int, b: Int, imm: Int
  )(dut: ShaderCore, i: Int): Unit = {
    dut.io.prog(i).op.poke(op.U)
    dut.io.prog(i).dst.poke(dst.U)
    dut.io.prog(i).a.poke(a.U)
    dut.io.prog(i).b.poke(b.U)
    dut.io.prog(i).imm.poke(imm.S)
  }

  it should "execute a MUL/SAT/OUT program across lanes in lock-step" in {
    simulate(new ShaderCore(lanes = 8, regs = 8, progSize = 5)) { dut =>
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B); dut.io.programBase.poke(0.U)
      run(1, 0, 0, 0, 5)(dut, 0)   // r0 = 5
      run(1, 1, 0, 0, 3)(dut, 1)   // r1 = 3
      run(4, 2, 0, 1, 0)(dut, 2)   // r2 = r0 * r1 = 15
      run(6, 2, 2, 0, 0)(dut, 3)   // saturate r2 -> 15
      run(7, 0, 2, 0, 0)(dut, 4)   // out = r2
      dut.io.start.poke(true.B); dut.clock.step(); dut.io.start.poke(false.B)
      var guard = 0
      while (!dut.io.done.peek().litToBoolean && guard < 64) { dut.clock.step(); guard += 1 }
      assert(guard < 64, "program did not finish")
      for (lane <- 0 until 8) assert(dut.io.color(lane).peek().litValue.toInt == 15, s"lane $lane color")
      assert(dut.io.pc.peek().litValue.toInt == 4)
    }
  }

  it should "use the uniform bank and an ADD" in {
    simulate(new ShaderCore(lanes = 4, regs = 8, progSize = 4)) { dut =>
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B); dut.io.programBase.poke(0.U)
      run(1, 0, 0, 0, 2)(dut, 0)    // r0 = 2
      run(2, 1, 7, 0, 0)(dut, 1)    // r1 = uniform[7]
      run(3, 2, 0, 1, 0)(dut, 2)    // r2 = r0 + r1
      run(7, 0, 2, 0, 0)(dut, 3)    // out = r2
      dut.io.uniform(7).poke(100.S)
      dut.io.start.poke(true.B); dut.clock.step(); dut.io.start.poke(false.B)
      var guard = 0
      while (!dut.io.done.peek().litToBoolean && guard < 64) { dut.clock.step(); guard += 1 }
      assert(guard < 64, "program did not finish")
      for (lane <- 0 until 4) assert(dut.io.color(lane).peek().litValue.toInt == 102, s"lane $lane color")
    }
  }

  it should "saturate large values to 255" in {
    simulate(new ShaderCore(lanes = 2, regs = 8, progSize = 3)) { dut =>
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B); dut.io.programBase.poke(0.U)
      run(1, 0, 0, 0, 300)(dut, 0) // r0 = 300
      run(6, 1, 0, 0, 0)(dut, 1)   // saturate r0 -> 255
      run(7, 0, 1, 0, 0)(dut, 2)   // out = r1
      dut.io.start.poke(true.B); dut.clock.step(); dut.io.start.poke(false.B)
      var guard = 0
      while (!dut.io.done.peek().litToBoolean && guard < 64) { dut.clock.step(); guard += 1 }
      for (lane <- 0 until 2) assert(dut.io.color(lane).peek().litValue.toInt == 255)
    }
  }

  it should "shade distinct per-lane data in parallel (SIMT warp)" in {
    simulate(new ShaderCore(lanes = 4, regs = 8, progSize = 3)) { dut =>
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B); dut.io.programBase.poke(0.U)
      // Program: r0 = r0 + uniform[0]; out = r0.  Each lane has distinct r0.
      run(2, 3, 0, 0, 0)(dut, 0) // r3 = uniform[0]
      run(3, 0, 3, 0, 0)(dut, 1) // r0 = r3 + r0
      run(7, 0, 0, 0, 0)(dut, 2) // out = r0
      dut.io.uniform(0).poke(10.S)
      // Distinct per-lane r0: lane k = k*10.
      for (lane <- 0 until 4) dut.io.initReg(lane)(0).poke((lane * 10).S)
      for (lane <- 0 until 4) for (r <- 1 until 8) dut.io.initReg(lane)(r).poke(0.S)
      dut.io.init.poke(true.B); dut.clock.step(); dut.io.init.poke(false.B)
      dut.io.start.poke(true.B); dut.clock.step(); dut.io.start.poke(false.B)
      var guard = 0
      while (!dut.io.done.peek().litToBoolean && guard < 64) { dut.clock.step(); guard += 1 }
      assert(guard < 64, "warp did not finish")
      for (lane <- 0 until 4) {
        val expected = lane * 10 + 10 // uniform(10) + lane*10
        assert(dut.io.color(lane).peek().litValue.toInt == expected, s"lane $lane color")
      }
    }
  }
}
