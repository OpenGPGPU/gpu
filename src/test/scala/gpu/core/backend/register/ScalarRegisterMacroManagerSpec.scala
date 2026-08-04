package gpu.core.backend.register

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import gpu.config.GpuConfig
import org.scalatest.flatspec.AnyFlatSpec

class ScalarRegisterMacroManagerSpec extends AnyFlatSpec {
  behavior of "ScalarRegisterMacroManager"

  private def request(
    dut: ScalarRegisterMacroManager,
    warp: Int,
    rs1: Int,
    rd: Int,
    useRs1: Boolean = true,
    writeRd: Boolean = true
  ): Unit = {
    dut.io.request.valid.poke(true.B)
    dut.io.request.bits.warpId.poke(warp.U)
    dut.io.request.bits.rs1.poke(rs1.U)
    dut.io.request.bits.rs2.poke(0.U)
    dut.io.request.bits.rd.poke(rd.U)
    dut.io.request.bits.useRs1.poke(useRs1.B)
    dut.io.request.bits.useRs2.poke(false.B)
    dut.io.request.bits.writeRd.poke(writeRd.B)
  }

  it should "read a writeback value through the synchronous macro pipeline" in {
    val config = GpuConfig(warps = 4)
    simulate(new ScalarRegisterMacroManager(config)) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)

      dut.io.request.valid.poke(false.B)
      dut.io.issue.ready.poke(true.B)
      dut.io.writeback.valid.poke(true.B)
      dut.io.writeback.bits.warpId.poke(2.U)
      dut.io.writeback.bits.rd.poke(5.U)
      dut.io.writeback.bits.data.poke("h89abcdef".U)
      dut.clock.step()

      dut.io.writeback.valid.poke(false.B)
      dut.io.request.valid.poke(true.B)
      dut.io.request.bits.warpId.poke(2.U)
      dut.io.request.bits.rs1.poke(5.U)
      dut.io.request.bits.rs2.poke(0.U)
      dut.io.request.bits.rd.poke(6.U)
      dut.io.request.bits.useRs1.poke(true.B)
      dut.io.request.bits.useRs2.poke(false.B)
      dut.io.request.bits.writeRd.poke(true.B)
      dut.clock.step()

      dut.io.request.valid.poke(false.B)
      dut.clock.step()
      dut.clock.step()

      dut.io.issue.valid.expect(true.B)
      dut.io.issue.bits.request.rd.expect(6.U)
      dut.io.issue.bits.rs1Data.expect("h89abcdef".U)
      dut.io.issue.bits.rs2Data.expect(0.U)
    }
  }

  it should "forward a back-to-back reservation into the next busy snapshot" in {
    val config = GpuConfig(warps = 4)
    simulate(new ScalarRegisterMacroManager(config)) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      dut.io.issue.ready.poke(true.B)
      dut.io.writeback.valid.poke(false.B)

      request(dut, warp = 1, rs1 = 0, rd = 5, useRs1 = false)
      dut.io.request.ready.expect(true.B)
      dut.clock.step()

      request(dut, warp = 1, rs1 = 5, rd = 6)
      dut.io.request.ready.expect(true.B)
      dut.clock.step()

      dut.io.request.valid.poke(false.B)
      dut.io.rawHazard.expect(true.B)
      dut.clock.step(2)
      dut.io.rawHazard.expect(true.B)

      dut.io.writeback.valid.poke(true.B)
      dut.io.writeback.bits.warpId.poke(1.U)
      dut.io.writeback.bits.rd.poke(5.U)
      dut.io.writeback.bits.data.poke("h12345678".U)
      dut.clock.step()
      dut.io.writeback.valid.poke(false.B)

      // Writeback is registered, then the refreshed snapshot is checked.
      dut.clock.step()
      dut.io.rawHazard.expect(false.B)
      dut.clock.step(2)
      dut.io.issue.valid.expect(true.B)
      dut.io.issue.bits.request.rd.expect(6.U)
      dut.io.issue.bits.rs1Data.expect("h12345678".U)
    }
  }

  it should "accept independent requests on consecutive cycles" in {
    val config = GpuConfig(warps = 4)
    simulate(new ScalarRegisterMacroManager(config)) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      dut.io.issue.ready.poke(true.B)
      dut.io.writeback.valid.poke(false.B)

      request(dut, warp = 0, rs1 = 0, rd = 3, useRs1 = false)
      dut.io.request.ready.expect(true.B)
      dut.clock.step()
      request(dut, warp = 1, rs1 = 0, rd = 4, useRs1 = false)
      dut.io.request.ready.expect(true.B)
      dut.clock.step()
      request(dut, warp = 2, rs1 = 0, rd = 5, useRs1 = false)
      dut.io.request.ready.expect(true.B)
      dut.clock.step()
      dut.io.request.valid.poke(false.B)

      dut.io.issue.valid.expect(true.B)
      dut.io.issue.bits.request.rd.expect(3.U)
      dut.clock.step()
      dut.io.issue.valid.expect(true.B)
      dut.io.issue.bits.request.rd.expect(4.U)
      dut.clock.step()
      dut.io.issue.valid.expect(true.B)
      dut.io.issue.bits.request.rd.expect(5.U)
    }
  }

}
