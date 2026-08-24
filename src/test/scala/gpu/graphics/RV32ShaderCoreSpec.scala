package gpu.graphics

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec

class RV32ShaderCoreSpec extends AnyFlatSpec {
  behavior of "RV32ShaderCore"

  // Encoders for the RV32IM subset used by the tests.
  private def opcodeR(funct7: Int, rs2: Int, rs1: Int, funct3: Int, rd: Int): Int =
    (funct7 << 25) | (rs2 << 20) | (rs1 << 15) | (funct3 << 12) | (rd << 7) | 0x33
  private def opI(imm: Int, rs1: Int, funct3: Int, rd: Int): Int =
    ((imm & 0xfff) << 20) | (rs1 << 15) | (funct3 << 12) | (rd << 7) | 0x13

  private def run(dut: RV32ShaderCore, regs: Seq[(Int, Int)], prog: Seq[Int]): Unit = {
    dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
    dut.io.programBase.poke(0.U)
    // load per-lane init: each lane gets the same register values from `regs`.
    for (lane <- 0 until 1) for (r <- 0 until 32) {
      val v = regs.find(_._1 == r).map(_._2).getOrElse(0)
      dut.io.initReg(lane)(r).poke(v.S)
    }
    for (i <- 0 until prog.size) dut.io.prog(i).poke(prog(i).U)
    for (i <- prog.size until 16) dut.io.prog(i).poke(0.U)
    dut.io.init.poke(true.B); dut.clock.step(); dut.io.init.poke(false.B)
    dut.io.start.poke(true.B); dut.clock.step(); dut.io.start.poke(false.B)
    var guard = 0
    while (!dut.io.done.peek().litToBoolean && guard < 64) { dut.clock.step(); guard += 1 }
    assert(guard < 64, "program did not finish")
  }

  it should "execute RV32 addi then add on real instruction words" in {
    simulate(new RV32ShaderCore(lanes = 1, regs = 32, progSize = 16)) { dut =>
      // x10 = 7; addi x30, x10, 5  -> x30 = 12 ; add x30, x30, x10 -> 19
      val prog = Seq(opI(5, 10, 0, 30), opcodeR(0, 10, 30, 0, 30))
      run(dut, Seq((10, 7)), prog)
      assert(dut.io.color(0).peek().litValue.toInt == 19)
    }
  }

  it should "execute RV32 mul" in {
    simulate(new RV32ShaderCore(lanes = 1, regs = 32, progSize = 16)) { dut =>
      // x10 = 6, x11 = 7; mul x30, x10, x11 -> 42
      val prog = Seq(opcodeR(1, 11, 10, 0, 30))
      run(dut, Seq((10, 6), (11, 7)), prog)
      assert(dut.io.color(0).peek().litValue.toInt == 42)
    }
  }

  it should "saturate the colour output register" in {
    simulate(new RV32ShaderCore(lanes = 1, regs = 32, progSize = 16)) { dut =>
      // x10 = 300; addi x30, x10, 100 -> 400 -> saturate 255 at output
      val prog = Seq(opI(100, 10, 0, 30))
      run(dut, Seq((10, 300)), prog)
      assert(dut.io.color(0).peek().litValue.toInt == 255)
    }
  }
}
