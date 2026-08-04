package gpu.core.execute.control

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import gpu.config.GpuConfig
import org.scalatest.flatspec.AnyFlatSpec

class SimtBranchStackSequencerSpec extends AnyFlatSpec {
  behavior of "SimtBranchStackSequencer"

  private def driveDivergence(
    dut: SimtBranchStackSequencer,
    valid: Boolean = true
  ): Unit = {
    dut.io.in.valid.poke(valid.B)
    dut.io.in.bits.warpId.poke(1.U)
    dut.io.in.bits.currentPc.poke(0x180.U)
    dut.io.in.bits.currentMask.poke(0x5.U)
    dut.io.in.bits.divergent.poke(true.B)
    dut.io.in.bits.hasAlternate.poke(true.B)
    dut.io.in.bits.alternate.pc.poke(0x104.U)
    dut.io.in.bits.alternate.activeMask.poke(0xa.U)
    dut.io.in.bits.alternate.originalMask.poke(0xf.U)
    dut.io.in.bits.alternate.divergent.poke(true.B)
    dut.io.in.bits.hasReconvergence.poke(true.B)
    dut.io.in.bits.reconvergence.pc.poke(0x200.U)
    dut.io.in.bits.reconvergence.activeMask.poke(0xf.U)
    dut.io.in.bits.reconvergence.originalMask.poke(0xf.U)
    dut.io.in.bits.reconvergence.divergent.poke(false.B)
  }

  it should "push reconvergence then alternate before releasing the current path" in {
    val config = GpuConfig(lanes = 4, simtStackDepth = 4)
    simulate(new SimtBranchStackSequencer(config)) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      dut.io.freeEntries.poke(4.U)
      dut.io.stackPush.ready.poke(true.B)
      dut.io.current.ready.poke(false.B)
      driveDivergence(dut)

      dut.io.in.ready.expect(true.B)
      dut.clock.step()
      dut.io.in.valid.poke(false.B)
      dut.io.stackPush.valid.expect(true.B)
      dut.io.stackPush.bits.pc.expect(0x200.U)

      dut.clock.step()
      dut.io.stackPush.valid.expect(true.B)
      dut.io.stackPush.bits.pc.expect(0x104.U)

      dut.clock.step()
      dut.io.current.valid.expect(true.B)
      dut.io.current.bits.pc.expect(0x180.U)
      dut.io.current.bits.activeMask.expect(0x5.U)
      dut.clock.step(2)
      dut.io.current.valid.expect(true.B)

      dut.io.current.ready.poke(true.B)
      dut.clock.step()
      dut.io.current.valid.expect(false.B)
    }
  }

  it should "wait for enough stack capacity before accepting divergence" in {
    val config = GpuConfig(lanes = 4, simtStackDepth = 4)
    simulate(new SimtBranchStackSequencer(config)) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      dut.io.stackPush.ready.poke(true.B)
      dut.io.current.ready.poke(true.B)
      dut.io.freeEntries.poke(1.U)
      driveDivergence(dut)

      dut.io.in.ready.expect(false.B)
      dut.clock.step(2)
      dut.io.stackPush.valid.expect(false.B)

      dut.io.freeEntries.poke(2.U)
      dut.io.in.ready.expect(true.B)
    }
  }
}
