package gpu.core.frontend.warp

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import gpu.config.GpuConfig
import org.scalatest.flatspec.AnyFlatSpec

class WarpSchedulerSpec extends AnyFlatSpec {
  private val config = GpuConfig(lanes = 8, warps = 4)

  private def launch(dut: WarpScheduler, pc: Int, mask: Int): Unit = {
    dut.io.launch.valid.poke(true.B)
    dut.io.launch.bits.startPc.poke(pc.U)
    dut.io.launch.bits.activeMask.poke(mask.U)
    dut.io.launch.ready.expect(true.B)
    dut.clock.step()
    dut.io.launch.valid.poke(false.B)
  }

  behavior of "WarpScheduler"

  it should "allocate free warps and issue them in round-robin order" in {
    simulate(new WarpScheduler(config)) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      dut.io.issue.ready.poke(false.B)
      dut.io.resume.valid.poke(false.B)
      dut.io.finish.valid.poke(false.B)

      launch(dut, 0x100, 0xff)
      launch(dut, 0x200, 0x0f)
      launch(dut, 0x300, 0xf0)

      dut.io.active.expect("b0111".U)
      dut.io.issue.ready.poke(true.B)

      dut.io.issue.valid.expect(true.B)
      dut.io.issue.bits.warpId.expect(0.U)
      dut.io.issue.bits.pc.expect(0x100.U)
      dut.io.issue.bits.activeMask.expect(0xff.U)
      dut.clock.step()

      dut.io.issue.bits.warpId.expect(1.U)
      dut.clock.step()
      dut.io.issue.bits.warpId.expect(2.U)
      dut.clock.step()

      dut.io.issue.valid.expect(false.B)
      dut.io.blocked.expect("b0111".U)

      dut.io.resume.valid.poke(true.B)
      dut.io.resume.bits.warpId.poke(0.U)
      dut.io.resume.bits.nextPc.poke(0x104.U)
      dut.io.resume.bits.activeMask.poke(0x3c.U)
      dut.clock.step()
      dut.io.resume.valid.poke(false.B)
      // Registered issue boundary: resumed state is selected next cycle.
      dut.clock.step()

      dut.io.issue.valid.expect(true.B)
      dut.io.issue.bits.warpId.expect(0.U)
      dut.io.issue.bits.pc.expect(0x104.U)
      dut.io.issue.bits.activeMask.expect(0x3c.U)
    }
  }

  it should "hold its grant under backpressure and recycle finished warps" in {
    simulate(new WarpScheduler(config)) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      dut.io.issue.ready.poke(false.B)
      dut.io.resume.valid.poke(false.B)
      dut.io.finish.valid.poke(false.B)

      launch(dut, 0x400, 0xaa)
      launch(dut, 0x500, 0x55)

      dut.io.issue.valid.expect(true.B)
      dut.io.issue.bits.warpId.expect(0.U)
      dut.clock.step(3)
      dut.io.issue.bits.warpId.expect(0.U)
      dut.io.blocked.expect("b0001".U)

      // Accept the registered grant before later finishing that warp.
      dut.io.issue.ready.poke(true.B)
      dut.clock.step()
      dut.io.issue.ready.poke(false.B)

      // Finish is a clocked release event and is not on the grant path.
      dut.io.finish.valid.poke(true.B)
      dut.io.finish.bits.poke(0.U)
      dut.clock.step()
      dut.io.finish.valid.poke(false.B)
      dut.io.active.expect("b0010".U)
      dut.io.issue.bits.warpId.expect(1.U)

      // Lowest free slot is reused on the next launch.
      launch(dut, 0x600, 0xff)
      dut.io.active.expect("b0011".U)
      dut.io.launch.valid.poke(true.B)
      dut.io.launch.bits.startPc.poke(0.U)
      dut.io.launch.bits.activeMask.poke(0.U)
      // Two slots remain free in this four-warp configuration.
      dut.io.launch.ready.expect(true.B)
    }
  }

  it should "deassert launch ready when all hardware warps are occupied" in {
    simulate(new WarpScheduler(config)) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      dut.io.issue.ready.poke(false.B)
      dut.io.resume.valid.poke(false.B)
      dut.io.finish.valid.poke(false.B)

      (0 until config.warps).foreach(i => launch(dut, 0x1000 + i * 4, 0xff))
      dut.io.active.expect("b1111".U)
      dut.io.launch.ready.expect(false.B)
    }
  }

  it should "support a single hardware warp without zero-width IDs" in {
    val singleWarp = GpuConfig(lanes = 4, warps = 1)
    simulate(new WarpScheduler(singleWarp)) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      dut.io.issue.ready.poke(false.B)
      dut.io.resume.valid.poke(false.B)
      dut.io.finish.valid.poke(false.B)

      dut.io.launch.valid.poke(true.B)
      dut.io.launch.bits.startPc.poke(0x80.U)
      dut.io.launch.bits.activeMask.poke("b1111".U)
      dut.clock.step()
      dut.io.launch.valid.poke(false.B)
      dut.clock.step()

      dut.io.issue.valid.expect(true.B)
      dut.io.issue.bits.warpId.expect(0.U)
      dut.io.issue.bits.pc.expect(0x80.U)
    }
  }
}
