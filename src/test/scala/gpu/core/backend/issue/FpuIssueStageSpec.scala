package gpu.core.backend.issue

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import gpu.config.GpuConfig
import org.scalatest.flatspec.AnyFlatSpec

class FpuIssueStageSpec extends AnyFlatSpec {
  behavior of "FpuIssueStage"

  it should "align three operands and stall a dependent FP request" in {
    simulate(new FpuIssueStage(GpuConfig(warps = 2))) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      dut.io.out.ready.poke(true.B)
      dut.io.in.valid.poke(false.B)
      dut.io.writeback.valid.poke(false.B)

      def write(reg: Int, data: BigInt): Unit = {
        dut.io.writeback.valid.poke(true.B)
        dut.io.writeback.bits.warpId.poke(0.U)
        dut.io.writeback.bits.rd.poke(reg.U)
        dut.io.writeback.bits.data.poke(data.U)
        dut.clock.step()
        dut.io.writeback.valid.poke(false.B)
      }
      write(1, BigInt("3fc00000", 16))
      write(2, BigInt("40000000", 16))
      write(3, BigInt("3f000000", 16))

      // fmadd.s f4, f1, f2, f3
      dut.io.in.bits.poke(0.U.asTypeOf(dut.io.in.bits))
      dut.io.in.bits.instruction.poke(
        "b00011_00_00010_00001_000_00100_1000011".U)
      dut.io.in.bits.warpId.poke(0.U)
      dut.io.in.bits.activeMask.poke("hff".U)
      dut.io.in.bits.decoded.readsRs1.poke(true.B)
      dut.io.in.bits.decoded.readsRs2.poke(true.B)
      dut.io.in.bits.decoded.readsRs3.poke(true.B)
      dut.io.in.bits.decoded.writesFp.poke(true.B)
      dut.io.in.valid.poke(true.B)
      dut.io.in.ready.expect(true.B)
      dut.clock.step()
      dut.io.in.valid.poke(false.B)
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.rs1Data.expect("h3fc00000".U)
      dut.io.out.bits.rs2Data.expect("h40000000".U)
      dut.io.out.bits.rs3Data.expect("h3f000000".U)
      dut.clock.step()

      // fadd.s f5, f4, f1 must wait for f4 writeback.
      dut.io.in.bits.poke(0.U.asTypeOf(dut.io.in.bits))
      dut.io.in.bits.instruction.poke(
        "b0000000_00001_00100_000_00101_1010011".U)
      dut.io.in.bits.warpId.poke(0.U)
      dut.io.in.bits.decoded.readsRs1.poke(true.B)
      dut.io.in.bits.decoded.readsRs2.poke(true.B)
      dut.io.in.bits.decoded.writesFp.poke(true.B)
      dut.io.in.valid.poke(true.B)
      dut.io.rawHazard.expect(true.B)
      dut.io.in.ready.expect(false.B)

      dut.io.writeback.valid.poke(true.B)
      dut.io.writeback.bits.warpId.poke(0.U)
      dut.io.writeback.bits.rd.poke(4.U)
      dut.io.writeback.bits.data.poke("h40600000".U)
      dut.io.rawHazard.expect(false.B)
      dut.io.in.ready.expect(true.B)
      dut.clock.step()
      dut.io.in.valid.poke(false.B)
      dut.io.writeback.valid.poke(false.B)
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.rs1Data.expect("h40600000".U)
      dut.io.out.bits.rs2Data.expect("h3fc00000".U)
    }
  }
}
