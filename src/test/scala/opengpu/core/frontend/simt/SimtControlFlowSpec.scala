package opengpu.core.frontend.simt

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import opengpu.config.GpuConfig
import org.scalatest.flatspec.AnyFlatSpec

class SimtControlFlowSpec extends AnyFlatSpec {
  behavior of "SimtControlFlow"

  private def driveBranch(
    dut: SimtControlFlow,
    warpId: Int,
    activeMask: Int,
    takenMask: Int
  ): Unit = {
    dut.io.branch.valid.poke(true.B)
    dut.io.branch.bits.warpId.poke(warpId.U)
    dut.io.branch.bits.pc.poke(0x100.U)
    dut.io.branch.bits.targetPc.poke(0x180.U)
    dut.io.branch.bits.fallthroughPc.poke(0x104.U)
    dut.io.branch.bits.reconvergePc.poke(0x200.U)
    dut.io.branch.bits.activeMask.poke(activeMask.U)
    dut.io.branch.bits.takenMask.poke(takenMask.U)
  }

  it should "keep each warp stack independent and restore entries in LIFO order" in {
    val config = GpuConfig(lanes = 4, warps = 2, simtStackDepth = 4)
    simulate(new SimtControlFlow(config)) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      dut.io.current.ready.poke(true.B)
      dut.io.restore.ready.poke(false.B)
      dut.io.restoreWarpId.poke(0.U)
      dut.io.clear.valid.poke(false.B)
      dut.io.clear.bits.poke(0.U)

      driveBranch(dut, warpId = 1, activeMask = 0xf, takenMask = 0x5)
      while (!dut.io.branch.ready.peek().litToBoolean) {
        dut.clock.step()
      }
      dut.clock.step()
      dut.io.branch.valid.poke(false.B)

      var sawCurrent = false
      for (_ <- 0 until 8) {
        if (dut.io.current.valid.peek().litToBoolean) {
          dut.io.current.bits.warpId.expect(1.U)
          dut.io.current.bits.pc.expect(0x180.U)
          dut.io.current.bits.activeMask.expect(0x5.U)
          sawCurrent = true
        }
        dut.clock.step()
      }
      assert(sawCurrent)
      dut.io.stackCounts(0).expect(0.U)
      dut.io.stackCounts(1).expect(2.U)

      dut.io.restoreWarpId.poke(1.U)
      dut.io.restore.ready.poke(true.B)
      dut.io.restore.valid.expect(true.B)
      dut.io.restore.bits.warpId.expect(1.U)
      dut.io.restore.bits.pc.expect(0x104.U)
      dut.io.restore.bits.activeMask.expect(0xa.U)
      dut.clock.step()

      dut.io.restore.valid.expect(true.B)
      dut.io.restore.bits.pc.expect(0x200.U)
      dut.io.restore.bits.activeMask.expect(0xf.U)
      dut.clock.step()
      dut.io.restore.valid.expect(false.B)
      dut.io.stackCounts(1).expect(0.U)
    }
  }

  it should "clear only the recycled warp's control-flow state" in {
    val config = GpuConfig(lanes = 4, warps = 2, simtStackDepth = 4)
    simulate(new SimtControlFlow(config)) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      dut.io.current.ready.poke(true.B)
      dut.io.restore.ready.poke(false.B)
      dut.io.restoreWarpId.poke(0.U)
      dut.io.clear.valid.poke(false.B)
      dut.io.clear.bits.poke(0.U)

      driveBranch(dut, warpId = 0, activeMask = 0xf, takenMask = 0x3)
      while (!dut.io.branch.ready.peek().litToBoolean) {
        dut.clock.step()
      }
      dut.clock.step()
      dut.io.branch.valid.poke(false.B)
      dut.clock.step(5)
      dut.io.stackCounts(0).expect(2.U)
      dut.io.stackCounts(1).expect(0.U)

      dut.io.clear.valid.poke(true.B)
      dut.io.clear.bits.poke(0.U)
      dut.clock.step()
      dut.io.clear.valid.poke(false.B)
      dut.io.stackCounts(0).expect(0.U)
      dut.io.stackCounts(1).expect(0.U)
    }
  }
}
