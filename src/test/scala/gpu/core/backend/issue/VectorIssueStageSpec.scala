package gpu.core.backend.issue

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import gpu.config.GpuConfig
import gpu.core.frontend.decode.VectorUnit
import org.scalatest.flatspec.AnyFlatSpec

class VectorIssueStageSpec extends AnyFlatSpec {
  behavior of "VectorIssueStage"

  it should "keep decoded metadata, scalar operands, and vector operands aligned" in {
    val config = GpuConfig(lanes = 4, warps = 2)
    simulate(new VectorIssueStage(config)) { dut =>
      dut.reset.poke(true.B)
      dut.io.in.valid.poke(false.B)
      dut.io.out.ready.poke(true.B)
      dut.io.vectorWriteback.valid.poke(false.B)
      dut.io.scalarRs1Data.poke(0.U)
      dut.io.scalarRs2Data.poke(0.U)
      dut.clock.step()
      dut.reset.poke(false.B)

      def write(register: Int, base: Int): Unit = {
        dut.io.vectorWriteback.valid.poke(true.B)
        dut.io.vectorWriteback.bits.warpId.poke(1.U)
        dut.io.vectorWriteback.bits.vd.poke(register.U)
        for (lane <- 0 until config.lanes) {
          dut.io.vectorWriteback.bits.data(lane).poke((base + lane).U)
        }
        dut.clock.step()
        dut.io.vectorWriteback.valid.poke(false.B)
      }
      write(1, 0x10)
      write(2, 0x20)
      write(3, 0x30)

      // vadd.vx v3, v2, x1: vector vs2 and old vd are read while x1 is
      // captured through the scalar RF bridge.
      val instruction =
        (BigInt(1) << 25) | (BigInt(2) << 20) | (BigInt(1) << 15) |
          (BigInt(4) << 12) | (BigInt(3) << 7) | 0x57
      dut.io.in.valid.poke(true.B)
      dut.io.in.bits.instruction.poke(instruction.U)
      dut.io.in.bits.pc.poke("h1000".U)
      dut.io.in.bits.warpId.poke(1.U)
      dut.io.in.bits.activeMask.poke("b1011".U)
      dut.io.in.bits.decoded.recognized.poke(true.B)
      dut.io.in.bits.decoded.valid.poke(true.B)
      dut.io.in.bits.decoded.unit.poke(VectorUnit.alu)
      dut.io.in.bits.decoded.funct6.poke(0.U)
      dut.io.in.bits.decoded.operandType.poke(4.U)
      dut.io.in.bits.decoded.vm.poke(true.B)
      dut.io.in.bits.decoded.nf.poke(0.U)
      dut.io.in.bits.decoded.mop.poke(0.U)
      dut.io.in.bits.decoded.elementWidth.poke(4.U)
      dut.io.in.bits.decoded.readsVs1.poke(false.B)
      dut.io.in.bits.decoded.readsVs2.poke(true.B)
      dut.io.in.bits.decoded.readsScalar.poke(true.B)
      dut.io.in.bits.decoded.readsFloat.poke(false.B)
      dut.io.in.bits.decoded.writesVd.poke(true.B)
      dut.io.in.bits.decoded.memoryRead.poke(false.B)
      dut.io.in.bits.decoded.memoryWrite.poke(false.B)
      dut.io.in.bits.decoded.configure.poke(false.B)
      dut.io.scalarRs1Data.poke("h12345678".U)
      dut.io.scalarRs2Data.poke("h87654321".U)
      dut.io.scalarRead.warpId.expect(1.U)
      dut.io.scalarRead.rs1.expect(1.U)
      dut.io.scalarRead.rs2.expect(2.U)
      dut.io.in.ready.expect(true.B)
      dut.clock.step()
      dut.io.in.valid.poke(false.B)

      var cycles = 0
      while (!dut.io.out.valid.peek().litToBoolean && cycles < 4) {
        dut.clock.step()
        cycles += 1
      }
      assert(dut.io.out.valid.peek().litToBoolean)
      dut.io.out.bits.decode.pc.expect("h1000".U)
      dut.io.out.bits.decode.activeMask.expect("b1011".U)
      dut.io.out.bits.scalarRs1Data.expect("h12345678".U)
      for (lane <- 0 until config.lanes) {
        dut.io.out.bits.vs2Data(lane).expect((0x20 + lane).U)
        dut.io.out.bits.oldVdData(lane).expect((0x30 + lane).U)
      }
    }
  }
}
