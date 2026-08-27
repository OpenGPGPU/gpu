package opengpu.core.backend.writeback

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import opengpu.config.GpuConfig
import org.scalatest.flatspec.AnyFlatSpec

class ScalarCommitStageSpec extends AnyFlatSpec {
  behavior of "ScalarCommitStage"

  private def drive(
    dut: ScalarCommitStage,
    writeRd: Boolean,
    rd: Int
  ): Unit = {
    dut.io.in.valid.poke(true.B)
    dut.io.in.bits.warpId.poke(1.U)
    dut.io.in.bits.nextPc.poke(0x104.U)
    dut.io.in.bits.activeMask.poke(0x5.U)
    dut.io.in.bits.writeRd.poke(writeRd.B)
    dut.io.in.bits.rd.poke(rd.U)
    dut.io.in.bits.data.poke(0x1234.U)
  }

  it should "transfer a destination write and redirect atomically" in {
    simulate(new ScalarCommitStage(GpuConfig(lanes = 4, warps = 2))) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      drive(dut, writeRd = true, rd = 3)

      dut.io.writeback.ready.poke(true.B)
      dut.io.redirect.ready.poke(false.B)
      dut.io.in.ready.expect(false.B)
      dut.io.writeback.valid.expect(false.B)

      dut.io.writeback.ready.poke(false.B)
      dut.io.redirect.ready.poke(true.B)
      dut.io.in.ready.expect(false.B)
      dut.io.redirect.valid.expect(false.B)

      dut.io.writeback.ready.poke(true.B)
      dut.io.in.ready.expect(true.B)
      dut.io.writeback.valid.expect(true.B)
      dut.io.redirect.valid.expect(true.B)
      dut.io.writeback.bits.data.expect(0x1234.U)
      dut.io.redirect.bits.pc.expect(0x104.U)
      dut.clock.step()

      drive(dut, writeRd = false, rd = 0)
      dut.io.writeback.ready.poke(false.B)
      dut.io.redirect.ready.poke(true.B)
      dut.io.in.ready.expect(true.B)
      dut.io.writeback.valid.expect(false.B)
      dut.io.redirect.valid.expect(true.B)
    }
  }
}
