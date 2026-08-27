package opengpu.core.backend.scoreboard

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import opengpu.config.GpuConfig
import org.scalatest.flatspec.AnyFlatSpec

class FpuScoreboardSpec extends AnyFlatSpec {
  behavior of "FpuScoreboard"

  it should "track three sources and treat f0 as an ordinary register" in {
    simulate(new FpuScoreboard(GpuConfig(warps = 2))) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      dut.io.release.valid.poke(false.B)
      dut.io.reserve.valid.poke(true.B)
      dut.io.reserve.bits.warpId.poke(0.U)
      dut.io.reserve.bits.rs1.poke(0.U)
      dut.io.reserve.bits.rs2.poke(1.U)
      dut.io.reserve.bits.rs3.poke(2.U)
      dut.io.reserve.bits.useRs1.poke(false.B)
      dut.io.reserve.bits.useRs2.poke(false.B)
      dut.io.reserve.bits.useRs3.poke(false.B)
      dut.io.reserve.bits.rd.poke(0.U)
      dut.io.reserve.bits.writeRd.poke(true.B)
      dut.io.reserve.ready.expect(true.B)
      dut.clock.step()

      dut.io.reserve.bits.writeRd.poke(false.B)
      dut.io.reserve.bits.useRs3.poke(true.B)
      dut.io.reserve.bits.rs3.poke(0.U)
      dut.io.rawHazard.expect(true.B)
      dut.io.reserve.ready.expect(false.B)

      dut.io.release.valid.poke(true.B)
      dut.io.release.bits.warpId.poke(0.U)
      dut.io.release.bits.rd.poke(0.U)
      dut.io.rawHazard.expect(false.B)
      dut.io.reserve.ready.expect(true.B)

      dut.io.reserve.bits.warpId.poke(1.U)
      dut.io.release.valid.poke(false.B)
      dut.io.rawHazard.expect(false.B)
    }
  }
}
