package opengpu.core.system

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import opengpu.config.GpuConfig
import org.scalatest.flatspec.AnyFlatSpec

class WorkgroupBarrierControllerSpec extends AnyFlatSpec {
  behavior of "WorkgroupBarrierController"

  it should "hold every resident warp and release the group after final arrival" in {
    val config = GpuConfig(lanes = 4, warps = 4)
    simulate(new WorkgroupBarrierController(config)) { dut =>
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      dut.io.arrive.valid.poke(false.B)
      dut.io.release.ready.poke(false.B)
      dut.io.residentWarps.poke("b0111".U)
      dut.io.dispatchComplete.poke(false.B)
      dut.io.memoryIdle.poke(true.B)

      def arrive(warp: Int, pc: Int): Unit = {
        dut.io.arrive.valid.poke(true.B)
        dut.io.arrive.bits.warpId.poke(warp.U)
        dut.io.arrive.bits.pc.poke(pc.U)
        dut.io.arrive.bits.activeMask.poke((1 << warp).U)
        dut.io.arrive.ready.expect(true.B)
        dut.clock.step()
        dut.io.arrive.valid.poke(false.B)
      }

      arrive(2, 0x304)
      arrive(0, 0x104)
      arrive(1, 0x204)
      dut.io.waiting.expect("b0111".U)
      dut.io.release.valid.expect(false.B)

      // Arrival alone is insufficient until the dispatcher confirms that no
      // additional warp from this workgroup remains to be launched.
      dut.io.dispatchComplete.poke(true.B)
      dut.io.memoryIdle.poke(false.B)
      dut.clock.step()
      dut.io.release.valid.expect(false.B)
      dut.io.memoryIdle.poke(true.B)
      dut.clock.step()
      dut.io.release.valid.expect(true.B)
      dut.io.release.ready.poke(true.B)
      Seq((0, 0x104), (1, 0x204), (2, 0x304)).foreach {
        case (warp, pc) =>
        dut.io.release.valid.expect(true.B)
        dut.io.release.bits.warpId.expect(warp.U)
        dut.io.release.bits.pc.expect(pc.U)
        dut.clock.step()
      }
      dut.io.release.valid.expect(false.B)
      dut.io.waiting.expect(0.U)
    }
  }

  it should "reject duplicate arrivals until the current barrier releases" in {
    val config = GpuConfig(lanes = 4, warps = 2)
    simulate(new WorkgroupBarrierController(config)) { dut =>
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      dut.io.residentWarps.poke("b11".U)
      dut.io.dispatchComplete.poke(true.B)
      dut.io.memoryIdle.poke(true.B)
      dut.io.release.ready.poke(false.B)
      dut.io.arrive.valid.poke(true.B)
      dut.io.arrive.bits.warpId.poke(0.U)
      dut.io.arrive.bits.pc.poke(0x104.U)
      dut.io.arrive.bits.activeMask.poke(0xf.U)
      dut.clock.step()
      dut.io.arrive.ready.expect(false.B)
    }
  }
}
