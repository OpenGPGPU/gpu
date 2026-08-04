package gpu.core.memory

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import gpu.config.GpuConfig
import org.scalatest.flatspec.AnyFlatSpec

class Sv32PageTableWalkerSpec extends AnyFlatSpec {
  behavior of "Sv32PageTableWalker"

  it should "walk both Sv32 levels and return a writable leaf" in {
    val config = GpuConfig(lanes = 2, warps = 1)
    simulate(new Sv32PageTableWalker(config)) { dut =>
      dut.reset.poke(true.B)
      dut.io.rootPpn.poke(0x100.U)
      dut.io.request.valid.poke(false.B)
      dut.io.response.ready.poke(true.B)
      dut.io.memoryRequest.ready.poke(true.B)
      dut.io.memoryResponse.valid.poke(false.B)
      dut.clock.step()
      dut.reset.poke(false.B)

      dut.io.request.valid.poke(true.B)
      dut.io.request.bits.virtualPageNumber.poke(0x12345.U)
      dut.io.request.bits.isStore.poke(true.B)
      dut.io.request.bits.isInstruction.poke(false.B)
      dut.clock.step()
      dut.io.request.valid.poke(false.B)
      dut.io.memoryRequest.bits.address.expect(0x100120.U)
      dut.clock.step()

      // Non-leaf PTE pointing to page table PPN 0x200.
      dut.io.memoryResponse.valid.poke(true.B)
      dut.io.memoryResponse.bits.pte.poke(((BigInt(0x200) << 10) | 1).U)
      dut.io.memoryResponse.bits.fault.poke(false.B)
      dut.clock.step()
      dut.io.memoryResponse.valid.poke(false.B)
      dut.io.memoryRequest.bits.address.expect(0x200d14.U)
      dut.clock.step()

      // V|R|W|A|D leaf.
      val leaf = (BigInt(0xabcde) << 10) | 0xc7
      dut.io.memoryResponse.valid.poke(true.B)
      dut.io.memoryResponse.bits.pte.poke(leaf.U)
      dut.clock.step()
      dut.io.memoryResponse.valid.poke(false.B)
      dut.io.response.valid.expect(true.B)
      dut.io.response.bits.physicalPageNumber.expect(0xabcde.U)
      dut.io.response.bits.readable.expect(true.B)
      dut.io.response.bits.writable.expect(true.B)
      dut.io.response.bits.fault.expect(false.B)
    }
  }

  it should "form a level-one superpage and reject missing dirty permission" in {
    val config = GpuConfig(lanes = 2, warps = 1)
    simulate(new Sv32PageTableWalker(config)) { dut =>
      dut.reset.poke(true.B)
      dut.io.rootPpn.poke(0x80.U)
      dut.io.request.valid.poke(false.B)
      dut.io.response.ready.poke(false.B)
      dut.io.memoryRequest.ready.poke(true.B)
      dut.io.memoryResponse.valid.poke(false.B)
      dut.clock.step()
      dut.reset.poke(false.B)

      dut.io.request.valid.poke(true.B)
      dut.io.request.bits.virtualPageNumber.poke(0x2a155.U)
      dut.io.request.bits.isStore.poke(true.B)
      dut.io.request.bits.isInstruction.poke(false.B)
      dut.clock.step()
      dut.io.request.valid.poke(false.B)
      dut.clock.step()

      // Aligned superpage PPN with V|R|W|A but D clear.
      val superpagePpn = BigInt(0x2b) << 10
      val leaf = (superpagePpn << 10) | 0x47
      dut.io.memoryResponse.valid.poke(true.B)
      dut.io.memoryResponse.bits.pte.poke(leaf.U)
      dut.io.memoryResponse.bits.fault.poke(false.B)
      dut.clock.step()
      dut.io.memoryResponse.valid.poke(false.B)
      dut.io.response.valid.expect(true.B)
      dut.io.response.bits.physicalPageNumber
        .expect(((BigInt(0x2b) << 10) | 0x155).U)
      dut.io.response.bits.fault.expect(true.B)
      dut.clock.step(2)
      dut.io.response.valid.expect(true.B)
    }
  }

  it should "accept an accessed execute-only instruction superpage" in {
    val config = GpuConfig(lanes = 2, warps = 1)
    simulate(new Sv32PageTableWalker(config)) { dut =>
      dut.reset.poke(true.B)
      dut.io.rootPpn.poke(0x80.U)
      dut.io.request.valid.poke(false.B)
      dut.io.response.ready.poke(true.B)
      dut.io.memoryRequest.ready.poke(true.B)
      dut.io.memoryResponse.valid.poke(false.B)
      dut.clock.step(); dut.reset.poke(false.B)

      dut.io.request.valid.poke(true.B)
      dut.io.request.bits.virtualPageNumber.poke(0x2a155.U)
      dut.io.request.bits.isStore.poke(false.B)
      dut.io.request.bits.isInstruction.poke(true.B)
      dut.clock.step(); dut.io.request.valid.poke(false.B); dut.clock.step()

      val superpagePpn = BigInt(0x2b) << 10
      val executeLeaf = (superpagePpn << 10) | 0x49 // V|X|A
      dut.io.memoryResponse.valid.poke(true.B)
      dut.io.memoryResponse.bits.pte.poke(executeLeaf.U)
      dut.io.memoryResponse.bits.fault.poke(false.B)
      dut.clock.step(); dut.io.memoryResponse.valid.poke(false.B)
      dut.io.response.valid.expect(true.B)
      dut.io.response.bits.executable.expect(true.B)
      dut.io.response.bits.readable.expect(false.B)
      dut.io.response.bits.fault.expect(false.B)
      dut.io.response.bits.physicalPageNumber
        .expect(((BigInt(0x2b) << 10) | 0x155).U)
    }
  }
}
