package opengpu.core.execute.integer

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import opengpu.config.GpuConfig
import opengpu.core.frontend.decode.ExecutionType
import org.scalatest.flatspec.AnyFlatSpec

class MultiplyExecuteStageSpec extends AnyFlatSpec {
  behavior of "MultiplyExecuteStage"

  private def waitForResult(dut: MultiplyExecuteStage): Unit = {
    var cycles = 0
    while (!dut.io.out.valid.peek().litToBoolean && cycles < 4) {
      dut.clock.step()
      cycles += 1
    }
    assert(dut.io.out.valid.peek().litToBoolean)
  }

  it should "execute all RV32M multiply signedness variants" in {
    simulate(new MultiplyExecuteStage(GpuConfig(lanes = 4, warps = 2))) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      dut.io.out.ready.poke(true.B)

      val cases = Seq(
        // funct3, lhs, rhs, expected
        (0, 0xffffffffL, 3L, 0xfffffffdL), // mul
        (1, 0xfffffffeL, 3L, 0xffffffffL), // mulh: -2 * 3
        (2, 0xfffffffeL, 0xffffffffL, 0xfffffffeL), // mulhsu
        (3, 0xffffffffL, 0xffffffffL, 0xfffffffeL) // mulhu
      )

      for ((funct3, lhs, rhs, expected) <- cases) {
        dut.io.in.valid.poke(true.B)
        dut.io.in.bits.decode.instruction
          .poke((BigInt(1) << 25 | BigInt(funct3) << 12 | 0x33).U)
        dut.io.in.bits.decode.pc.poke(0x100.U)
        dut.io.in.bits.decode.warpId.poke(1.U)
        dut.io.in.bits.decode.activeMask.poke(0x5.U)
        dut.io.in.bits.decode.instructionAccessFault.poke(false.B)
        dut.io.in.bits.decode.executionType.poke(ExecutionType.integer)
        dut.io.in.bits.decode.illegalInstruction.poke(false.B)
        dut.io.in.bits.decode.decoded.multiply.poke(true.B)
        dut.io.in.bits.decode.decoded.divide.poke(false.B)
        dut.io.in.bits.decode.decoded.rd.poke(3.U)
        dut.io.in.bits.decode.decoded.writeRd.poke(true.B)
        dut.io.in.bits.rs1Data.poke(lhs.U)
        dut.io.in.bits.rs2Data.poke(rhs.U)
        dut.io.in.ready.expect(true.B)
        dut.clock.step()
        dut.io.in.valid.poke(false.B)
        waitForResult(dut)
        dut.io.out.valid.expect(true.B)
        dut.io.out.bits.data.expect(expected.U)
        dut.io.out.bits.rd.expect(3.U)
        dut.io.out.bits.warpId.expect(1.U)
        dut.clock.step()
      }
    }
  }

  it should "hold a completed product under backpressure" in {
    simulate(new MultiplyExecuteStage(GpuConfig(lanes = 4, warps = 2))) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      dut.io.out.ready.poke(false.B)
      dut.io.in.valid.poke(true.B)
      dut.io.in.bits.decode.instruction.poke(
        ((BigInt(1) << 25) | 0x33).U
      )
      dut.io.in.bits.decode.pc.poke(0.U)
      dut.io.in.bits.decode.warpId.poke(0.U)
      dut.io.in.bits.decode.activeMask.poke(0xf.U)
      dut.io.in.bits.decode.instructionAccessFault.poke(false.B)
      dut.io.in.bits.decode.executionType.poke(ExecutionType.integer)
      dut.io.in.bits.decode.illegalInstruction.poke(false.B)
      dut.io.in.bits.decode.decoded.multiply.poke(true.B)
      dut.io.in.bits.decode.decoded.divide.poke(false.B)
      dut.io.in.bits.decode.decoded.rd.poke(1.U)
      dut.io.in.bits.decode.decoded.writeRd.poke(true.B)
      dut.io.in.bits.rs1Data.poke(7.U)
      dut.io.in.bits.rs2Data.poke(9.U)
      dut.clock.step()
      dut.io.in.valid.poke(false.B)
      waitForResult(dut)

      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.data.expect(63.U)
      dut.clock.step(3)
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.data.expect(63.U)
    }
  }
}
