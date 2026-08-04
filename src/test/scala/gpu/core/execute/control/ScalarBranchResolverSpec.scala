package gpu.core.execute.control

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import gpu.config.GpuConfig
import gpu.core.frontend.decode.BranchOp
import org.scalatest.flatspec.AnyFlatSpec

class ScalarBranchResolverSpec extends AnyFlatSpec {
  behavior of "ScalarBranchResolver"

  private def defaults(dut: ScalarBranchResolver): Unit = {
    dut.io.in.valid.poke(false.B)
    dut.io.out.ready.poke(true.B)
    dut.io.in.bits.warpId.poke(0.U)
    dut.io.in.bits.pc.poke(0.U)
    dut.io.in.bits.immediate.poke(0.U)
    dut.io.in.bits.lhs.poke(0.U)
    dut.io.in.bits.rhs.poke(0.U)
    dut.io.in.bits.branchOp.poke(BranchOp.none)
    dut.io.in.bits.kind.poke(ControlFlowKind.conditional)
  }

  it should "resolve conditional branches with signed and unsigned comparisons" in {
    simulate(new ScalarBranchResolver(GpuConfig())) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      defaults(dut)

      dut.io.in.valid.poke(true.B)
      dut.io.in.bits.warpId.poke(2.U)
      dut.io.in.bits.pc.poke(0x100.U)
      dut.io.in.bits.immediate.poke(0x20.U)
      dut.io.in.bits.lhs.poke("hffffffff".U)
      dut.io.in.bits.rhs.poke(1.U)
      dut.io.in.bits.branchOp.poke(BranchOp.lt)
      dut.clock.step()

      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.warpId.expect(2.U)
      dut.io.out.bits.taken.expect(true.B)
      dut.io.out.bits.targetPc.expect(0x120.U)
      dut.io.out.bits.linkPc.expect(0x104.U)

      dut.io.in.bits.branchOp.poke(BranchOp.ltu)
      dut.clock.step()
      dut.io.out.bits.taken.expect(false.B)
      dut.io.out.bits.targetPc.expect(0x104.U)
    }
  }

  it should "resolve JAL and aligned JALR targets while holding backpressure" in {
    simulate(new ScalarBranchResolver(GpuConfig())) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      defaults(dut)

      dut.io.in.valid.poke(true.B)
      dut.io.in.bits.pc.poke(0x200.U)
      dut.io.in.bits.immediate.poke(0x40.U)
      dut.io.in.bits.kind.poke(ControlFlowKind.jal)
      dut.clock.step()
      dut.io.out.bits.targetPc.expect(0x240.U)
      dut.io.out.bits.linkPc.expect(0x204.U)

      dut.io.out.ready.poke(false.B)
      dut.io.in.ready.expect(false.B)
      dut.io.in.bits.pc.poke(0x300.U)
      dut.io.in.bits.lhs.poke(0x101.U)
      dut.io.in.bits.immediate.poke(2.U)
      dut.io.in.bits.kind.poke(ControlFlowKind.jalr)
      dut.clock.step()
      dut.io.out.bits.targetPc.expect(0x240.U)

      dut.io.out.ready.poke(true.B)
      dut.clock.step()
      dut.io.out.bits.targetPc.expect(0x102.U)
      dut.io.out.bits.linkPc.expect(0x304.U)
    }
  }
}
