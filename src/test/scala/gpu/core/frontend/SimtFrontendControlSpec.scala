package gpu.core.frontend

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import gpu.config.GpuConfig
import org.scalatest.flatspec.AnyFlatSpec

class SimtFrontendControlSpec extends AnyFlatSpec {
  behavior of "SimtFrontendControl"

  private def waitForIssue(dut: SimtFrontendControl): Unit = {
    var cycles = 0
    while (!dut.io.issue.valid.peek().litToBoolean && cycles < 12) {
      dut.clock.step()
      cycles += 1
    }
    assert(dut.io.issue.valid.peek().litToBoolean)
  }

  it should "feed divergent and restored masks back into warp scheduling" in {
    val config = GpuConfig(lanes = 4, warps = 2, simtStackDepth = 4)
    simulate(new SimtFrontendControl(config)) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      dut.io.issue.ready.poke(true.B)
      dut.io.launch.valid.poke(false.B)
      dut.io.branch.valid.poke(false.B)
      dut.io.restore.valid.poke(false.B)
      dut.io.restore.bits.poke(0.U)
      dut.io.scalarRedirect.valid.poke(false.B)
      dut.io.finish.valid.poke(false.B)
      dut.io.finish.bits.poke(0.U)

      dut.io.launch.valid.poke(true.B)
      dut.io.launch.bits.warpId.poke(0.U)
      dut.io.launch.bits.startPc.poke(0x100.U)
      dut.io.launch.bits.activeMask.poke(0xf.U)
      dut.io.launch.ready.expect(true.B)
      dut.clock.step()
      dut.io.launch.valid.poke(false.B)

      waitForIssue(dut)
      dut.io.issue.bits.warpId.expect(0.U)
      dut.io.issue.bits.pc.expect(0x100.U)
      dut.io.issue.bits.activeMask.expect(0xf.U)
      dut.clock.step()

      dut.io.branch.valid.poke(true.B)
      dut.io.branch.bits.warpId.poke(0.U)
      dut.io.branch.bits.pc.poke(0x100.U)
      dut.io.branch.bits.targetPc.poke(0x180.U)
      dut.io.branch.bits.fallthroughPc.poke(0x104.U)
      dut.io.branch.bits.reconvergePc.poke(0x200.U)
      dut.io.branch.bits.activeMask.poke(0xf.U)
      dut.io.branch.bits.takenMask.poke(0x5.U)
      while (!dut.io.branch.ready.peek().litToBoolean) {
        dut.clock.step()
      }
      dut.clock.step()
      dut.io.branch.valid.poke(false.B)

      waitForIssue(dut)
      dut.io.issue.bits.pc.expect(0x180.U)
      dut.io.issue.bits.activeMask.expect(0x5.U)
      dut.io.stackCounts(0).expect(2.U)
      dut.clock.step()

      dut.io.restore.valid.poke(true.B)
      dut.io.restore.bits.poke(0.U)
      dut.io.scalarRedirect.valid.poke(true.B)
      dut.io.scalarRedirect.bits.warpId.poke(1.U)
      dut.io.scalarRedirect.bits.pc.poke(0x300.U)
      dut.io.scalarRedirect.bits.activeMask.poke(0x3.U)
      while (!dut.io.restore.ready.peek().litToBoolean) {
        dut.clock.step()
      }
      dut.io.scalarRedirect.ready.expect(false.B)
      dut.clock.step()
      dut.io.restore.valid.poke(false.B)
      dut.io.scalarRedirect.ready.expect(true.B)
      dut.clock.step()
      dut.io.scalarRedirect.valid.poke(false.B)

      waitForIssue(dut)
      dut.io.issue.bits.pc.expect(0x104.U)
      dut.io.issue.bits.activeMask.expect(0xa.U)
      dut.io.stackCounts(0).expect(1.U)
    }
  }

  it should "resume a blocked warp from a scalar branch redirect" in {
    val config = GpuConfig(lanes = 4, warps = 2)
    simulate(new SimtFrontendControl(config)) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      dut.io.issue.ready.poke(true.B)
      dut.io.launch.valid.poke(true.B)
      dut.io.launch.bits.warpId.poke(0.U)
      dut.io.launch.bits.startPc.poke(0x100.U)
      dut.io.launch.bits.activeMask.poke(0xf.U)
      dut.io.branch.valid.poke(false.B)
      dut.io.restore.valid.poke(false.B)
      dut.io.restore.bits.poke(0.U)
      dut.io.scalarRedirect.valid.poke(false.B)
      dut.io.finish.valid.poke(false.B)
      dut.io.finish.bits.poke(0.U)
      dut.clock.step()
      dut.io.launch.valid.poke(false.B)

      while (!dut.io.issue.valid.peek().litToBoolean) {
        dut.clock.step()
      }
      dut.clock.step()

      dut.io.scalarRedirect.valid.poke(true.B)
      dut.io.scalarRedirect.bits.warpId.poke(0.U)
      dut.io.scalarRedirect.bits.pc.poke(0x140.U)
      dut.io.scalarRedirect.bits.activeMask.poke(0xf.U)
      dut.io.scalarRedirect.ready.expect(true.B)
      dut.clock.step()
      dut.io.scalarRedirect.valid.poke(false.B)

      while (!dut.io.issue.valid.peek().litToBoolean) {
        dut.clock.step()
      }
      dut.io.issue.bits.pc.expect(0x140.U)
      dut.io.issue.bits.activeMask.expect(0xf.U)
    }
  }
}
