package gpu.core.memory

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import gpu.config.GpuConfig
import org.scalatest.flatspec.AnyFlatSpec

class InstructionTlbSpec extends AnyFlatSpec {
  behavior of "InstructionTlb"

  it should "walk an executable page and reuse the translation" in {
    val config = GpuConfig(warps = 1)
    simulate(new InstructionTlb(config, entries = 4)) { dut =>
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      dut.io.in.valid.poke(false.B)
      dut.io.translationEnabled.poke(true.B)
      dut.io.asid.poke(7.U)
      dut.io.flush.valid.poke(false.B)
      dut.io.out.ready.poke(true.B)
      dut.io.physicalRequest.ready.poke(false.B)
      dut.io.physicalResponse.valid.poke(false.B)
      dut.io.pageWalkRequest.ready.poke(true.B)
      dut.io.pageWalkResponse.valid.poke(false.B)

      def begin(pc: BigInt): Unit = {
        dut.io.in.valid.poke(true.B)
        dut.io.in.bits.warpId.poke(0.U)
        dut.io.in.bits.pc.poke(pc.U)
        dut.clock.step(); dut.io.in.valid.poke(false.B); dut.clock.step()
      }

      begin(0x12345004L)
      dut.io.pageWalkRequest.valid.expect(true.B)
      dut.io.pageWalkRequest.bits.isInstruction.expect(true.B)
      dut.clock.step()
      dut.io.pageWalkResponse.valid.poke(true.B)
      dut.io.pageWalkResponse.bits.physicalPageNumber.poke(0xabcde.U)
      dut.io.pageWalkResponse.bits.readable.poke(false.B)
      dut.io.pageWalkResponse.bits.writable.poke(false.B)
      dut.io.pageWalkResponse.bits.executable.poke(true.B)
      dut.io.pageWalkResponse.bits.global.poke(false.B)
      dut.io.pageWalkResponse.bits.fault.poke(false.B)
      dut.clock.step(); dut.io.pageWalkResponse.valid.poke(false.B)
      dut.io.physicalRequest.valid.expect(true.B)
      dut.io.physicalRequest.bits.pc.expect("habcde004".U)
      dut.io.physicalRequest.ready.poke(true.B); dut.clock.step()
      dut.io.physicalResponse.valid.poke(true.B)
      dut.io.physicalResponse.bits.warpId.poke(0.U)
      dut.io.physicalResponse.bits.instruction.poke("h30500073".U)
      dut.io.physicalResponse.bits.accessFault.poke(false.B)
      dut.io.out.bits.instruction.expect("h30500073".U)
      dut.clock.step(); dut.io.physicalResponse.valid.poke(false.B)

      dut.io.physicalRequest.ready.poke(false.B)
      begin(0x12345008L)
      dut.io.pageWalkRequest.valid.expect(false.B)
      dut.io.physicalRequest.valid.expect(true.B)
      dut.io.physicalRequest.bits.pc.expect("habcde008".U)
    }
  }

  it should "fault a non-executable mapping before ICache" in {
    val config = GpuConfig(warps = 1)
    simulate(new InstructionTlb(config, entries = 2)) { dut =>
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      dut.io.in.valid.poke(true.B)
      dut.io.in.bits.warpId.poke(0.U)
      dut.io.in.bits.pc.poke(0x4000.U)
      dut.io.translationEnabled.poke(true.B)
      dut.io.asid.poke(0.U)
      dut.io.flush.valid.poke(false.B)
      dut.io.out.ready.poke(false.B)
      dut.io.physicalRequest.ready.poke(true.B)
      dut.io.physicalResponse.valid.poke(false.B)
      dut.io.pageWalkRequest.ready.poke(true.B)
      dut.io.pageWalkResponse.valid.poke(false.B)
      dut.clock.step(); dut.io.in.valid.poke(false.B); dut.clock.step(2)
      dut.io.pageWalkResponse.valid.poke(true.B)
      dut.io.pageWalkResponse.bits.poke(0.U.asTypeOf(dut.io.pageWalkResponse.bits))
      dut.io.pageWalkResponse.bits.readable.poke(true.B)
      dut.clock.step(); dut.io.pageWalkResponse.valid.poke(false.B)
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.accessFault.expect(true.B)
      dut.io.physicalRequest.valid.expect(false.B)
    }
  }
}
