package opengpu.core.backend.scoreboard

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import opengpu.config.GpuConfig
import org.scalatest.flatspec.AnyFlatSpec

class RegisterScoreboardSpec extends AnyFlatSpec {
  private val config = GpuConfig(warps = 4)

  private def defaults(dut: RegisterScoreboard): Unit = {
    dut.io.reserve.valid.poke(false.B)
    dut.io.reserve.bits.warpId.poke(0.U)
    dut.io.reserve.bits.rs1.poke(0.U)
    dut.io.reserve.bits.rs2.poke(0.U)
    dut.io.reserve.bits.rd.poke(0.U)
    dut.io.reserve.bits.useRs1.poke(false.B)
    dut.io.reserve.bits.useRs2.poke(false.B)
    dut.io.reserve.bits.writeRd.poke(false.B)
    dut.io.release.valid.poke(false.B)
    dut.io.release.bits.warpId.poke(0.U)
    dut.io.release.bits.rd.poke(0.U)
    dut.io.cancel.valid.poke(false.B)
    dut.io.cancel.bits.warpId.poke(0.U)
    dut.io.cancel.bits.rd.poke(0.U)
  }

  private def reserveDestination(
    dut: RegisterScoreboard,
    warp: Int,
    rd: Int
  ): Unit = {
    dut.io.reserve.valid.poke(true.B)
    dut.io.reserve.bits.warpId.poke(warp.U)
    dut.io.reserve.bits.rd.poke(rd.U)
    dut.io.reserve.bits.writeRd.poke(true.B)
    dut.io.reserve.ready.expect(true.B)
    dut.clock.step()
    dut.io.reserve.valid.poke(false.B)
    dut.io.reserve.bits.writeRd.poke(false.B)
  }

  behavior of "RegisterScoreboard"

  it should "detect RAW and WAW hazards only within the selected warp" in {
    simulate(new RegisterScoreboard(config)) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      defaults(dut)

      reserveDestination(dut, warp = 1, rd = 5)
      dut.io.busyByWarp(1).expect((BigInt(1) << 5).U)

      dut.io.reserve.bits.warpId.poke(1.U)
      dut.io.reserve.bits.rs1.poke(5.U)
      dut.io.reserve.bits.useRs1.poke(true.B)
      dut.io.rawHazard.expect(true.B)
      dut.io.reserve.ready.expect(false.B)

      dut.io.reserve.bits.useRs1.poke(false.B)
      dut.io.reserve.bits.rd.poke(5.U)
      dut.io.reserve.bits.writeRd.poke(true.B)
      dut.io.wawHazard.expect(true.B)
      dut.io.reserve.ready.expect(false.B)

      dut.io.reserve.bits.warpId.poke(2.U)
      dut.io.rawHazard.expect(false.B)
      dut.io.wawHazard.expect(false.B)
      dut.io.reserve.ready.expect(true.B)
    }
  }

  it should "ignore x0 and allow same-cycle release and re-reservation" in {
    simulate(new RegisterScoreboard(config)) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      defaults(dut)

      reserveDestination(dut, warp = 0, rd = 5)

      dut.io.reserve.valid.poke(true.B)
      dut.io.reserve.bits.warpId.poke(0.U)
      dut.io.reserve.bits.rd.poke(5.U)
      dut.io.reserve.bits.writeRd.poke(true.B)
      dut.io.release.valid.poke(true.B)
      dut.io.release.bits.warpId.poke(0.U)
      dut.io.release.bits.rd.poke(5.U)
      dut.io.reserve.ready.expect(true.B)
      dut.io.wawHazard.expect(false.B)
      dut.clock.step()

      dut.io.reserve.valid.poke(false.B)
      dut.io.release.valid.poke(false.B)
      dut.io.busyByWarp(0).expect((BigInt(1) << 5).U)

      dut.io.reserve.bits.rs1.poke(0.U)
      dut.io.reserve.bits.useRs1.poke(true.B)
      dut.io.reserve.bits.rd.poke(0.U)
      dut.io.reserve.bits.writeRd.poke(true.B)
      dut.io.rawHazard.expect(false.B)
      dut.io.wawHazard.expect(false.B)
    }
  }

  it should "cancel a faulting destination so retry can reserve it" in {
    simulate(new RegisterScoreboard(config)) { dut =>
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B); defaults(dut)
      reserveDestination(dut, warp = 1, rd = 7)
      dut.io.cancel.valid.poke(true.B)
      dut.io.cancel.bits.warpId.poke(1.U)
      dut.io.cancel.bits.rd.poke(7.U)
      dut.clock.step(); dut.io.cancel.valid.poke(false.B)
      dut.io.busyByWarp(1).expect(0.U)
      dut.io.reserve.bits.warpId.poke(1.U)
      dut.io.reserve.bits.rd.poke(7.U)
      dut.io.reserve.bits.writeRd.poke(true.B)
      dut.io.reserve.ready.expect(true.B)
    }
  }
}
