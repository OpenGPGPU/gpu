package opengpu.dispatch

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import opengpu.config.GpuConfig
import org.scalatest.flatspec.AnyFlatSpec

class WorkgroupDispatcherSpec extends AnyFlatSpec {
  private val config = GpuConfig(lanes = 8, warps = 4)

  private def sendWorkgroup(dut: WorkgroupDispatcher, size: Seq[Int]): Unit = {
    dut.io.workgroup.valid.poke(true.B)
    dut.io.workgroup.bits.kernelPc.poke(0x1000.U)
    dut.io.workgroup.bits.kernargAddress.poke(0x8000.U)
    (0 until 3).foreach { i =>
      dut.io.workgroup.bits.gridSize(i).poke(1.U)
      dut.io.workgroup.bits.localSize(i).poke(size(i).U)
      dut.io.workgroup.bits.groupId(i).poke(0.U)
    }
    dut.clock.step()
    dut.io.workgroup.valid.poke(false.B)
  }

  behavior of "WorkgroupDispatcher"

  it should "produce full warps and an exact tail mask" in {
    simulate(new WorkgroupDispatcher(config)) { dut =>
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      dut.io.workgroup.valid.poke(false.B)
      dut.io.warp.ready.poke(true.B)
      dut.io.warpCompletion.valid.poke(false.B)
      dut.io.completion.ready.poke(true.B)
      sendWorkgroup(dut, Seq(17, 1, 1))

      Seq((0, 0xff, true, false), (8, 0xff, false, false),
        (16, 0x01, false, true)).foreach { case (base, mask, first, last) =>
        dut.io.warp.valid.expect(true.B)
        dut.io.warp.bits.localLinearBase.expect(base.U)
        dut.io.warp.bits.activeMask.expect(mask.U)
        dut.io.warp.bits.firstWarp.expect(first.B)
        dut.io.warp.bits.lastWarp.expect(last.B)
        dut.clock.step()
      }
      dut.io.warp.valid.expect(false.B)
      (0 until 3).foreach { _ =>
        dut.io.warpCompletion.valid.poke(true.B)
        dut.io.warpCompletion.bits.success.poke(true.B)
        dut.clock.step()
        dut.io.warpCompletion.valid.poke(false.B)
      }
      dut.io.completion.valid.expect(true.B)
      dut.io.completion.bits.success.expect(true.B)
    }
  }

  it should "keep multiple dispatched warps resident until all complete" in {
    simulate(new WorkgroupDispatcher(config)) { dut =>
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      dut.io.workgroup.valid.poke(false.B)
      dut.io.warp.ready.poke(true.B)
      dut.io.warpCompletion.valid.poke(false.B)
      dut.io.completion.ready.poke(false.B)
      sendWorkgroup(dut, Seq(24, 1, 1))

      dut.clock.step(3)
      dut.io.warp.valid.expect(false.B)
      dut.io.completion.valid.expect(false.B)
      (0 until 2).foreach { _ =>
        dut.io.warpCompletion.valid.poke(true.B)
        dut.io.warpCompletion.bits.success.poke(true.B)
        dut.clock.step()
      }
      dut.io.completion.valid.expect(false.B)
      dut.clock.step()
      dut.io.warpCompletion.valid.poke(false.B)
      dut.io.completion.valid.expect(true.B)
    }
  }

  it should "report an empty local size as an unsuccessful workgroup" in {
    simulate(new WorkgroupDispatcher(config)) { dut =>
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      dut.io.warp.ready.poke(true.B)
      dut.io.warpCompletion.valid.poke(false.B)
      dut.io.completion.ready.poke(false.B)
      sendWorkgroup(dut, Seq(0, 1, 1))
      dut.io.warp.valid.expect(false.B)
      dut.io.completion.valid.expect(true.B)
      dut.io.completion.bits.success.expect(false.B)
    }
  }

  it should "reject a workgroup that cannot keep all barrier participants resident" in {
    simulate(new WorkgroupDispatcher(config)) { dut =>
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      dut.io.warp.ready.poke(true.B)
      dut.io.warpCompletion.valid.poke(false.B)
      dut.io.completion.ready.poke(false.B)
      sendWorkgroup(dut, Seq(config.lanes * config.warps + 1, 1, 1))
      dut.io.warp.valid.expect(false.B)
      dut.io.completion.valid.expect(true.B)
      dut.io.completion.bits.success.expect(false.B)
    }
  }
}
