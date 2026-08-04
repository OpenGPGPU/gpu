package gpu.core.execute.integer

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import gpu.config.GpuConfig
import gpu.core.backend.issue.ScalarIssuedInstruction
import gpu.core.frontend.decode.ExecutionType
import org.scalatest.flatspec.AnyFlatSpec

class IntegerExecuteStageSpec extends AnyFlatSpec {
  behavior of "IntegerExecuteStage"

  private def drive(
    dut: IntegerExecuteStage,
    aluOp: UInt,
    rs1: Long,
    rs2: Long,
    immediate: Long,
    useImmediate: Boolean,
    usePc: Boolean
  ): Unit = {
    dut.io.in.valid.poke(true.B)
    dut.io.in.bits.decode.instruction.poke(0.U)
    dut.io.in.bits.decode.pc.poke(0x100.U)
    dut.io.in.bits.decode.warpId.poke(1.U)
    dut.io.in.bits.decode.activeMask.poke(0x5.U)
    dut.io.in.bits.decode.instructionAccessFault.poke(false.B)
    dut.io.in.bits.decode.executionType.poke(ExecutionType.integer)
    dut.io.in.bits.decode.illegalInstruction.poke(false.B)
    dut.io.in.bits.decode.decoded.aluOp.poke(aluOp)
    dut.io.in.bits.decode.decoded.immediate.poke((immediate & 0xffffffffL).U)
    dut.io.in.bits.decode.decoded.useImmediate.poke(useImmediate.B)
    dut.io.in.bits.decode.decoded.usePc.poke(usePc.B)
    dut.io.in.bits.decode.decoded.rd.poke(3.U)
    dut.io.in.bits.decode.decoded.writeRd.poke(true.B)
    dut.io.in.bits.decode.decoded.multiply.poke(false.B)
    dut.io.in.bits.decode.decoded.divide.poke(false.B)
    dut.io.in.bits.rs1Data.poke((rs1 & 0xffffffffL).U)
    dut.io.in.bits.rs2Data.poke((rs2 & 0xffffffffL).U)
  }

  it should "select register, immediate, and PC operands while holding output" in {
    simulate(new IntegerExecuteStage(GpuConfig(lanes = 4, warps = 2))) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      dut.io.out.ready.poke(false.B)

      drive(
        dut,
        AluOp.add,
        rs1 = 7,
        rs2 = 9,
        immediate = 5,
        useImmediate = true,
        usePc = false
      )
      dut.io.in.ready.expect(true.B)
      dut.clock.step()
      dut.io.in.valid.poke(false.B)
      dut.clock.step()
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.data.expect(12.U)
      dut.io.out.bits.warpId.expect(1.U)
      dut.io.out.bits.activeMask.expect(0x5.U)
      dut.clock.step(2)
      dut.io.out.bits.data.expect(12.U)

      dut.io.out.ready.poke(true.B)
      drive(
        dut,
        AluOp.add,
        rs1 = 0,
        rs2 = 0,
        immediate = 0x20,
        useImmediate = true,
        usePc = true
      )
      dut.clock.step()
      dut.io.in.valid.poke(false.B)
      dut.clock.step()
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.data.expect(0x120.U)
    }
  }

  it should "match RV32 add and subtract across carry boundaries" in {
    simulate(new IntegerExecuteStage(GpuConfig(lanes = 4, warps = 2))) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      dut.io.out.ready.poke(true.B)

      val vectors = Seq(
        (0x00000000L, 0x00000000L),
        (0x0000ffffL, 0x00000001L),
        (0x7fffffffL, 0x00000001L),
        (0xffffffffL, 0x00000001L),
        (0x89abcdefL, 0x76543210L)
      )

      for ((operation, subtract) <- Seq((AluOp.add, false), (AluOp.sub, true))) {
        for ((lhs, rhs) <- vectors) {
          drive(
            dut,
            operation,
            rs1 = lhs,
            rs2 = rhs,
            immediate = 0,
            useImmediate = false,
            usePc = false
          )
          dut.clock.step()
          dut.io.in.valid.poke(false.B)
          dut.clock.step()
          dut.io.out.valid.expect(true.B)
          val expected =
            if (subtract) (lhs - rhs) & 0xffffffffL
            else (lhs + rhs) & 0xffffffffL
          dut.io.out.bits.data.expect(expected.U)
        }
      }
    }
  }
}
