package opengpu.core.backend.register

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import opengpu.config.GpuConfig
import org.scalatest.flatspec.AnyFlatSpec

class VectorRegisterManagerSpec extends AnyFlatSpec {
  behavior of "VectorRegisterManager"

  it should "issue three operands and block a dependent request until writeback" in {
    val config = GpuConfig(lanes = 4, warps = 2)
    simulate(new VectorRegisterManager(config)) { dut =>
      dut.reset.poke(true.B)
      dut.io.request.valid.poke(false.B)
      dut.io.issue.ready.poke(true.B)
      dut.io.writeback.valid.poke(false.B)
      dut.clock.step()
      dut.reset.poke(false.B)

      def write(register: Int, base: Int): Unit = {
        dut.io.writeback.valid.poke(true.B)
        dut.io.writeback.bits.warpId.poke(0.U)
        dut.io.writeback.bits.vd.poke(register.U)
        for (lane <- 0 until config.lanes) {
          dut.io.writeback.bits.data(lane).poke((base + lane).U)
        }
        dut.clock.step()
        dut.io.writeback.valid.poke(false.B)
      }
      write(1, 0x10)
      write(2, 0x20)
      write(3, 0x30)

      dut.io.request.valid.poke(true.B)
      dut.io.request.bits.warpId.poke(0.U)
      dut.io.request.bits.vs1.poke(1.U)
      dut.io.request.bits.vs2.poke(2.U)
      dut.io.request.bits.vd.poke(3.U)
      dut.io.request.bits.useVs1.poke(true.B)
      dut.io.request.bits.useVs2.poke(true.B)
      dut.io.request.bits.readVd.poke(true.B)
      dut.io.request.bits.useMask.poke(false.B)
      dut.io.request.bits.writeVd.poke(true.B)
      dut.clock.step()
      dut.io.request.valid.poke(false.B)
      dut.clock.step()
      dut.io.issue.valid.expect(true.B)
      for (lane <- 0 until config.lanes) {
        dut.io.issue.bits.vs1Data(lane).expect((0x10 + lane).U)
        dut.io.issue.bits.vs2Data(lane).expect((0x20 + lane).U)
        dut.io.issue.bits.oldVdData(lane).expect((0x30 + lane).U)
      }
      dut.clock.step()

      dut.io.request.valid.poke(true.B)
      dut.io.request.bits.vs1.poke(3.U)
      dut.io.request.bits.useVs2.poke(false.B)
      dut.io.request.bits.readVd.poke(false.B)
      dut.io.request.bits.useMask.poke(false.B)
      dut.io.request.bits.writeVd.poke(false.B)
      dut.clock.step()
      dut.io.request.valid.poke(false.B)
      dut.clock.step()
      dut.io.rawHazard.expect(true.B)
      dut.io.issue.valid.expect(false.B)

      write(3, 0x40)
      dut.io.issue.valid.expect(true.B)
      dut.io.issue.bits.vs1Data(0).expect(0x40.U)
    }
  }
}
