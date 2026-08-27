package opengpu.core.execute.control

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import opengpu.config.GpuConfig
import opengpu.core.frontend.decode.{BranchOp, ExecutionType}
import org.scalatest.flatspec.AnyFlatSpec

class ScalarBranchExecuteStageSpec extends AnyFlatSpec {
  behavior of "ScalarBranchExecuteStage"

  private def drive(
    dut: ScalarBranchExecuteStage,
    branchOp: UInt,
    jump: Boolean,
    useRs1: Boolean,
    lhs: BigInt,
    rhs: BigInt,
    immediate: BigInt
  ): Unit = {
    dut.io.in.valid.poke(true.B)
    dut.io.in.bits.decode.executionType.poke(ExecutionType.branch)
    dut.io.in.bits.decode.warpId.poke(1.U)
    dut.io.in.bits.decode.pc.poke(0x100.U)
    dut.io.in.bits.decode.activeMask.poke(0xb.U)
    dut.io.in.bits.decode.decoded.branchOp.poke(branchOp)
    dut.io.in.bits.decode.decoded.jump.poke(jump.B)
    dut.io.in.bits.decode.decoded.useRs1.poke(useRs1.B)
    dut.io.in.bits.decode.decoded.immediate.poke(immediate.U)
    dut.io.in.bits.decode.decoded.rd.poke(5.U)
    dut.io.in.bits.decode.decoded.writeRd.poke(jump.B)
    dut.io.in.bits.rs1Data.poke(lhs.U)
    dut.io.in.bits.rs2Data.poke(rhs.U)
  }

  it should "resolve conditional and JALR paths while preserving metadata" in {
    simulate(new ScalarBranchExecuteStage(GpuConfig(lanes = 4, warps = 2))) {
      dut =>
        dut.reset.poke(true.B)
        dut.clock.step()
        dut.reset.poke(false.B)
        dut.io.out.ready.poke(false.B)

        drive(
          dut,
          BranchOp.lt,
          jump = false,
          useRs1 = true,
          lhs = BigInt("ffffffff", 16),
          rhs = 1,
          immediate = 0x20
        )
        dut.clock.step()
        dut.io.in.valid.poke(false.B)
        dut.clock.step()
        dut.io.out.valid.expect(true.B)
        dut.io.out.bits.nextPc.expect(0x120.U)
        dut.io.out.bits.activeMask.expect(0xb.U)
        dut.io.out.bits.writeLink.expect(false.B)
        dut.clock.step(2)
        dut.io.out.bits.nextPc.expect(0x120.U)

        dut.io.out.ready.poke(true.B)
        dut.clock.step()
        dut.io.out.ready.poke(false.B)

        drive(
          dut,
          BranchOp.none,
          jump = true,
          useRs1 = true,
          lhs = 0x203,
          rhs = 0,
          immediate = 4
        )
        dut.clock.step()
        dut.io.in.valid.poke(false.B)
        dut.clock.step()
        dut.io.out.valid.expect(true.B)
        dut.io.out.bits.nextPc.expect(0x206.U)
        dut.io.out.bits.linkPc.expect(0x104.U)
        dut.io.out.bits.rd.expect(5.U)
        dut.io.out.bits.writeLink.expect(true.B)
    }
  }
}
