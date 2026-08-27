package opengpu.core.execute.integer

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import opengpu.config.GpuConfig
import opengpu.core.frontend.decode.ExecutionType
import org.scalatest.flatspec.AnyFlatSpec

class DivideExecuteStageSpec extends AnyFlatSpec {
  behavior of "DivideExecuteStage"

  private def drive(
    dut: DivideExecuteStage,
    funct3: Int,
    lhs: Long,
    rhs: Long
  ): Unit = {
    dut.io.in.valid.poke(true.B)
    dut.io.in.bits.decode.instruction
      .poke((BigInt(1) << 25 | BigInt(funct3) << 12 | 0x33).U)
    dut.io.in.bits.decode.pc.poke(0x100.U)
    dut.io.in.bits.decode.warpId.poke(1.U)
    dut.io.in.bits.decode.activeMask.poke(0x5.U)
    dut.io.in.bits.decode.instructionAccessFault.poke(false.B)
    dut.io.in.bits.decode.executionType.poke(ExecutionType.integer)
    dut.io.in.bits.decode.illegalInstruction.poke(false.B)
    dut.io.in.bits.decode.decoded.multiply.poke(false.B)
    dut.io.in.bits.decode.decoded.divide.poke(true.B)
    dut.io.in.bits.decode.decoded.rd.poke(3.U)
    dut.io.in.bits.decode.decoded.writeRd.poke(true.B)
    dut.io.in.bits.rs1Data.poke((lhs & 0xffffffffL).U)
    dut.io.in.bits.rs2Data.poke((rhs & 0xffffffffL).U)
  }

  private def waitForResult(dut: DivideExecuteStage): Unit = {
    var cycles = 0
    while (!dut.io.out.valid.peek().litToBoolean && cycles < 36) {
      dut.clock.step()
      cycles += 1
    }
    assert(dut.io.out.valid.peek().litToBoolean)
  }

  it should "execute signed and unsigned divide and remainder" in {
    simulate(new DivideExecuteStage(GpuConfig(lanes = 4, warps = 2))) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      dut.io.out.ready.poke(true.B)

      val cases = Seq(
        (4, 0xfffffff9L, 3L, 0xfffffffeL), // div -7, 3
        (5, 0xfffffff9L, 3L, 0x55555553L), // divu
        (6, 0xfffffff9L, 3L, 0xffffffffL), // rem -7, 3
        (7, 0xfffffff9L, 3L, 0L) // remu
      )

      for ((funct3, lhs, rhs, expected) <- cases) {
        drive(dut, funct3, lhs, rhs)
        dut.io.in.ready.expect(true.B)
        dut.clock.step()
        dut.io.in.valid.poke(false.B)
        waitForResult(dut)
        dut.io.out.bits.data.expect(expected.U)
        dut.io.out.bits.rd.expect(3.U)
        dut.clock.step()
      }
    }
  }

  it should "implement divide-by-zero and signed-overflow results" in {
    simulate(new DivideExecuteStage(GpuConfig(lanes = 4, warps = 2))) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      dut.io.out.ready.poke(true.B)

      val cases = Seq(
        (4, 7L, 0L, 0xffffffffL),
        (6, 7L, 0L, 7L),
        (4, 0x80000000L, 0xffffffffL, 0x80000000L),
        (6, 0x80000000L, 0xffffffffL, 0L)
      )

      for ((funct3, lhs, rhs, expected) <- cases) {
        drive(dut, funct3, lhs, rhs)
        dut.clock.step()
        dut.io.in.valid.poke(false.B)
        dut.io.out.valid.expect(true.B)
        dut.io.out.bits.data.expect(expected.U)
        dut.clock.step()
      }
    }
  }
}
