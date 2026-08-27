package opengpu.core.backend.register

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import opengpu.config.GpuConfig
import org.scalatest.flatspec.AnyFlatSpec

class ScalarRegisterManagerSpec extends AnyFlatSpec {
  behavior of "ScalarRegisterManager"

  it should "release, bypass writeback data, and reserve atomically" in {
    val config = GpuConfig(warps = 4)
    simulate(new ScalarRegisterManager(config)) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)

      dut.io.writeback.valid.poke(false.B)
      dut.io.writeback.bits.warpId.poke(0.U)
      dut.io.writeback.bits.rd.poke(0.U)
      dut.io.writeback.bits.data.poke(0.U)
      dut.io.issue.ready.poke(false.B)
      dut.io.request.valid.poke(true.B)
      dut.io.request.bits.warpId.poke(2.U)
      dut.io.request.bits.rs1.poke(0.U)
      dut.io.request.bits.rs2.poke(0.U)
      dut.io.request.bits.rd.poke(5.U)
      dut.io.request.bits.useRs1.poke(false.B)
      dut.io.request.bits.useRs2.poke(false.B)
      dut.io.request.bits.writeRd.poke(true.B)
      dut.io.request.ready.expect(true.B)
      dut.clock.step()
      dut.io.request.valid.poke(false.B)
      dut.clock.step()
      dut.io.issue.valid.expect(true.B)
      dut.io.issue.bits.request.rd.expect(5.U)

      dut.io.issue.ready.poke(true.B)
      dut.clock.step()

      // The dependent request can enter the elastic request stage, but it
      // cannot advance to the operand output while x5 is outstanding.
      dut.io.issue.ready.poke(false.B)
      dut.io.request.valid.poke(true.B)
      dut.io.request.bits.rs1.poke(5.U)
      dut.io.request.bits.rd.poke(6.U)
      dut.io.request.bits.useRs1.poke(true.B)
      dut.io.request.ready.expect(true.B)
      dut.clock.step()
      dut.io.request.valid.poke(false.B)
      dut.io.rawHazard.expect(true.B)
      dut.io.issue.valid.expect(false.B)

      // Writeback clears x5 for hazard checking and bypasses its new value.
      // The dependent x6 reservation happens on the same clock edge.
      dut.io.writeback.valid.poke(true.B)
      dut.io.writeback.bits.warpId.poke(2.U)
      dut.io.writeback.bits.rd.poke(5.U)
      dut.io.writeback.bits.data.poke("h89abcdef".U)
      dut.io.rawHazard.expect(false.B)
      dut.clock.step()

      dut.io.writeback.valid.poke(false.B)
      dut.io.issue.valid.expect(true.B)
      dut.io.issue.bits.rs1Data.expect("h89abcdef".U)
      dut.io.issue.bits.request.rd.expect(6.U)
    }
  }
}
