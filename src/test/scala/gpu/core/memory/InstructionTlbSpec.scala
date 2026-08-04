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
        dut.io.in.ready.expect(true.B)
        dut.clock.step(); dut.io.in.valid.poke(false.B)
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
      dut.io.in.valid.poke(true.B)
      dut.io.in.bits.warpId.poke(0.U)
      dut.io.in.bits.pc.poke(0x12345008L.U)
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

  it should "forward a cached hit while another page walk is outstanding" in {
    val config = GpuConfig(warps = 2)
    simulate(new InstructionTlb(config, entries = 4)) { dut =>
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      dut.io.in.valid.poke(false.B)
      dut.io.translationEnabled.poke(true.B)
      dut.io.asid.poke(3.U)
      dut.io.flush.valid.poke(false.B)
      dut.io.out.ready.poke(true.B)
      dut.io.physicalRequest.ready.poke(true.B)
      dut.io.physicalResponse.valid.poke(false.B)
      dut.io.pageWalkRequest.ready.poke(true.B)
      dut.io.pageWalkResponse.valid.poke(false.B)

      def request(warp: Int, pc: BigInt): Unit = {
        dut.io.in.valid.poke(true.B)
        dut.io.in.bits.warpId.poke(warp.U)
        dut.io.in.bits.pc.poke(pc.U)
        dut.io.in.ready.expect(true.B)
        dut.clock.step()
        dut.io.in.valid.poke(false.B)
      }

      // Install one executable translation.
      request(0, 0x12345004L)
      dut.io.pageWalkRequest.valid.expect(true.B)
      dut.clock.step()
      dut.io.pageWalkResponse.valid.poke(true.B)
      dut.io.pageWalkResponse.bits.poke(0.U.asTypeOf(dut.io.pageWalkResponse.bits))
      dut.io.pageWalkResponse.bits.physicalPageNumber.poke(0xabcde.U)
      dut.io.pageWalkResponse.bits.executable.poke(true.B)
      dut.clock.step()
      dut.io.pageWalkResponse.valid.poke(false.B)
      dut.io.physicalRequest.valid.expect(true.B)
      dut.io.physicalRequest.bits.pc.expect("habcde004".U)
      dut.clock.step()

      // Start a miss to a different VPN and leave its walk outstanding.
      request(0, 0x56789000L)
      dut.io.pageWalkRequest.valid.expect(true.B)
      dut.clock.step()

      // Warp 1's hit must bypass that page walk and reach the ICache now.
      dut.io.in.valid.poke(true.B)
      dut.io.in.bits.warpId.poke(1.U)
      dut.io.in.bits.pc.poke(0x1234500cL.U)
      dut.io.in.ready.expect(true.B)
      dut.io.physicalRequest.valid.expect(true.B)
      dut.io.physicalRequest.bits.warpId.expect(1.U)
      dut.io.physicalRequest.bits.pc.expect("habcde00c".U)
      dut.clock.step()
      dut.io.in.valid.poke(false.B)

      // The original miss still completes and is replayed precisely.
      dut.io.pageWalkResponse.valid.poke(true.B)
      dut.io.pageWalkResponse.bits.physicalPageNumber.poke(0x13579.U)
      dut.io.pageWalkResponse.bits.executable.poke(true.B)
      dut.io.pageWalkResponse.bits.fault.poke(false.B)
      dut.clock.step()
      dut.io.pageWalkResponse.valid.poke(false.B)
      dut.io.physicalRequest.valid.expect(true.B)
      dut.io.physicalRequest.bits.warpId.expect(0.U)
      dut.io.physicalRequest.bits.pc.expect("h13579000".U)
    }
  }
}
