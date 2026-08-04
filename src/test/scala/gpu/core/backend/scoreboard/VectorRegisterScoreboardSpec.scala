package gpu.core.backend.scoreboard

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import gpu.config.GpuConfig
import org.scalatest.flatspec.AnyFlatSpec

class VectorRegisterScoreboardSpec extends AnyFlatSpec {
  behavior of "VectorRegisterScoreboard"

  it should "track v0 and release dependencies independently by warp" in {
    val config = GpuConfig(lanes = 4, warps = 2)
    simulate(new VectorRegisterScoreboard(config)) { dut =>
      dut.reset.poke(true.B)
      dut.io.reserve.valid.poke(false.B)
      dut.io.release.valid.poke(false.B)
      dut.io.cancel.valid.poke(false.B)
      dut.clock.step()
      dut.reset.poke(false.B)

      dut.io.reserve.valid.poke(true.B)
      dut.io.reserve.bits.warpId.poke(0.U)
      dut.io.reserve.bits.vs1.poke(1.U)
      dut.io.reserve.bits.vs2.poke(2.U)
      dut.io.reserve.bits.vd.poke(0.U)
      dut.io.reserve.bits.useVs1.poke(false.B)
      dut.io.reserve.bits.useVs2.poke(false.B)
      dut.io.reserve.bits.readVd.poke(false.B)
      dut.io.reserve.bits.useMask.poke(false.B)
      dut.io.reserve.bits.writeVd.poke(true.B)
      dut.io.reserve.ready.expect(true.B)
      dut.clock.step()

      dut.io.reserve.bits.useVs1.poke(true.B)
      dut.io.reserve.bits.vs1.poke(0.U)
      dut.io.reserve.bits.writeVd.poke(false.B)
      dut.io.rawHazard.expect(true.B)
      dut.io.reserve.ready.expect(false.B)

      dut.io.reserve.bits.useVs1.poke(false.B)
      dut.io.reserve.bits.useMask.poke(true.B)
      dut.io.rawHazard.expect(true.B)

      dut.io.reserve.bits.useMask.poke(false.B)
      dut.io.reserve.bits.useVs1.poke(true.B)
      dut.io.reserve.bits.warpId.poke(1.U)
      dut.io.rawHazard.expect(false.B)
      dut.io.reserve.ready.expect(true.B)

      dut.io.reserve.bits.warpId.poke(0.U)
      dut.io.release.valid.poke(true.B)
      dut.io.release.bits.warpId.poke(0.U)
      dut.io.release.bits.vd.poke(0.U)
      dut.io.rawHazard.expect(false.B)
      dut.io.reserve.ready.expect(true.B)
    }
  }

  it should "cancel a faulting vector destination before retry" in {
    val config = GpuConfig(lanes = 4, warps = 1)
    simulate(new VectorRegisterScoreboard(config)) { dut =>
      dut.reset.poke(true.B)
      dut.io.reserve.valid.poke(false.B)
      dut.io.release.valid.poke(false.B)
      dut.io.cancel.valid.poke(false.B)
      dut.clock.step(); dut.reset.poke(false.B)
      dut.io.reserve.valid.poke(true.B)
      dut.io.reserve.bits.warpId.poke(0.U)
      dut.io.reserve.bits.vs1.poke(0.U); dut.io.reserve.bits.vs2.poke(0.U)
      dut.io.reserve.bits.vd.poke(9.U)
      dut.io.reserve.bits.useVs1.poke(false.B); dut.io.reserve.bits.useVs2.poke(false.B)
      dut.io.reserve.bits.readVd.poke(false.B); dut.io.reserve.bits.useMask.poke(false.B)
      dut.io.reserve.bits.writeVd.poke(true.B)
      dut.clock.step(); dut.io.reserve.valid.poke(false.B)
      dut.io.cancel.valid.poke(true.B)
      dut.io.cancel.bits.warpId.poke(0.U); dut.io.cancel.bits.vd.poke(9.U)
      dut.clock.step(); dut.io.cancel.valid.poke(false.B)
      dut.io.busyByWarp(0).expect(0.U)
      dut.io.reserve.bits.vd.poke(9.U); dut.io.reserve.bits.writeVd.poke(true.B)
      dut.io.reserve.ready.expect(true.B)
    }
  }
}
