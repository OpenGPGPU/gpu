package opengpu.core.execute.control

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import opengpu.config.GpuConfig
import org.scalatest.flatspec.AnyFlatSpec

class SimtBranchResolverSpec extends AnyFlatSpec {
  behavior of "SimtBranchResolver"

  private def drive(
    dut: SimtBranchResolver,
    activeMask: Int,
    takenMask: Int
  ): Unit = {
    dut.io.in.valid.poke(true.B)
    dut.io.in.bits.warpId.poke(1.U)
    dut.io.in.bits.pc.poke(0x100.U)
    dut.io.in.bits.targetPc.poke(0x180.U)
    dut.io.in.bits.fallthroughPc.poke(0x104.U)
    dut.io.in.bits.reconvergePc.poke(0x200.U)
    dut.io.in.bits.activeMask.poke(activeMask.U)
    dut.io.in.bits.takenMask.poke(takenMask.U)
  }

  it should "create alternate and reconvergence entries for a divergent branch" in {
    val config = GpuConfig(lanes = 4)
    simulate(new SimtBranchResolver(config)) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      dut.io.out.ready.poke(true.B)
      drive(dut, activeMask = 0xf, takenMask = 0x5)
      dut.clock.step()

      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.divergent.expect(true.B)
      dut.io.out.bits.currentPc.expect(0x180.U)
      dut.io.out.bits.currentMask.expect(0x5.U)
      dut.io.out.bits.hasAlternate.expect(true.B)
      dut.io.out.bits.alternate.pc.expect(0x104.U)
      dut.io.out.bits.alternate.activeMask.expect(0xa.U)
      dut.io.out.bits.alternate.originalMask.expect(0xf.U)
      dut.io.out.bits.reconvergence.pc.expect(0x200.U)
      dut.io.out.bits.reconvergence.activeMask.expect(0xf.U)
    }
  }

  it should "avoid stack work when all active lanes choose one direction" in {
    val config = GpuConfig(lanes = 4)
    simulate(new SimtBranchResolver(config)) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      dut.io.out.ready.poke(true.B)

      drive(dut, activeMask = 0x7, takenMask = 0xf)
      dut.clock.step()
      dut.io.out.bits.divergent.expect(false.B)
      dut.io.out.bits.currentPc.expect(0x180.U)
      dut.io.out.bits.currentMask.expect(0x7.U)
      dut.io.out.bits.hasAlternate.expect(false.B)

      drive(dut, activeMask = 0x7, takenMask = 0x8)
      dut.clock.step()
      dut.io.out.bits.divergent.expect(false.B)
      dut.io.out.bits.currentPc.expect(0x104.U)
      dut.io.out.bits.currentMask.expect(0x7.U)
      dut.io.out.bits.hasReconvergence.expect(false.B)
    }
  }
}
