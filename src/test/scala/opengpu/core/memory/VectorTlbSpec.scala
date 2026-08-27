package opengpu.core.memory

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import opengpu.config.GpuConfig
import org.scalatest.flatspec.AnyFlatSpec

class VectorTlbSpec extends AnyFlatSpec {
  behavior of "VectorTlb"

  it should "walk on a miss and reuse the translation on a hit" in {
    val config = GpuConfig(lanes = 4, warps = 1)
    simulate(new VectorTlb(config, entries = 4, lineBytes = 64)) { dut =>
      dut.reset.poke(true.B)
      dut.io.in.valid.poke(false.B)
      dut.io.translationEnabled.poke(true.B)
      dut.io.asid.poke(3.U)
      dut.io.flush.valid.poke(false.B)
      dut.io.out.ready.poke(true.B)
      dut.io.physicalRequest.ready.poke(false.B)
      dut.io.physicalResponse.valid.poke(false.B)
      dut.io.pageWalkRequest.ready.poke(true.B)
      dut.io.pageWalkResponse.valid.poke(false.B)
      dut.clock.step()
      dut.reset.poke(false.B)

      def request(address: BigInt, store: Boolean = false): Unit = {
        dut.io.in.valid.poke(true.B)
        dut.io.in.bits.warpId.poke(0.U)
        dut.io.in.bits.lineAddress.poke(address.U)
        dut.io.in.bits.writeData.poke(0.U)
        dut.io.in.bits.byteMask.poke(1.U)
        dut.io.in.bits.isStore.poke(store.B)
        dut.clock.step()
        dut.io.in.valid.poke(false.B)
        dut.clock.step()
      }

      request(0x12345040L)
      dut.io.pageWalkRequest.valid.expect(true.B)
      dut.io.pageWalkRequest.bits.virtualPageNumber.expect(0x12345.U)
      dut.clock.step()
      dut.io.pageWalkResponse.valid.poke(true.B)
      dut.io.pageWalkResponse.bits.physicalPageNumber.poke(0xabcde.U)
      dut.io.pageWalkResponse.bits.readable.poke(true.B)
      dut.io.pageWalkResponse.bits.writable.poke(false.B)
      dut.io.pageWalkResponse.bits.global.poke(false.B)
      dut.io.pageWalkResponse.bits.fault.poke(false.B)
      dut.clock.step()
      dut.io.pageWalkResponse.valid.poke(false.B)
      dut.io.physicalRequest.valid.expect(true.B)
      dut.io.physicalRequest.bits.lineAddress.expect("habcde040".U)
      dut.io.physicalRequest.ready.poke(true.B)
      dut.clock.step()
      dut.io.physicalResponse.valid.poke(true.B)
      dut.io.physicalResponse.bits.readData.poke("h55aa".U)
      dut.io.physicalResponse.bits.fault.poke(false.B)
      dut.io.physicalResponse.bits.pageFault.poke(false.B)
      dut.io.out.bits.readData.expect("h55aa".U)
      dut.clock.step()
      dut.io.physicalResponse.valid.poke(false.B)

      dut.io.physicalRequest.ready.poke(false.B)
      request(0x12345080L)
      dut.io.pageWalkRequest.valid.expect(false.B)
      dut.io.physicalRequest.valid.expect(true.B)
      dut.io.physicalRequest.bits.lineAddress.expect("habcde080".U)
    }
  }

  it should "reject a store without write permission before DCache" in {
    val config = GpuConfig(lanes = 2, warps = 1)
    simulate(new VectorTlb(config, entries = 2)) { dut =>
      dut.reset.poke(true.B)
      dut.io.in.valid.poke(false.B)
      dut.io.translationEnabled.poke(true.B)
      dut.io.asid.poke(0.U)
      dut.io.flush.valid.poke(false.B)
      dut.io.out.ready.poke(false.B)
      dut.io.physicalRequest.ready.poke(true.B)
      dut.io.physicalResponse.valid.poke(false.B)
      dut.io.pageWalkRequest.ready.poke(true.B)
      dut.io.pageWalkResponse.valid.poke(false.B)
      dut.clock.step()
      dut.reset.poke(false.B)

      dut.io.in.valid.poke(true.B)
      dut.io.in.bits.warpId.poke(0.U)
      dut.io.in.bits.lineAddress.poke("h4000".U)
      dut.io.in.bits.writeData.poke(0.U)
      dut.io.in.bits.byteMask.poke(1.U)
      dut.io.in.bits.isStore.poke(true.B)
      dut.clock.step()
      dut.io.in.valid.poke(false.B)
      dut.clock.step(2)
      dut.io.pageWalkResponse.valid.poke(true.B)
      dut.io.pageWalkResponse.bits.physicalPageNumber.poke(8.U)
      dut.io.pageWalkResponse.bits.readable.poke(true.B)
      dut.io.pageWalkResponse.bits.writable.poke(false.B)
      dut.io.pageWalkResponse.bits.global.poke(false.B)
      dut.io.pageWalkResponse.bits.fault.poke(false.B)
      dut.clock.step()
      dut.io.pageWalkResponse.valid.poke(false.B)
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.fault.expect(true.B)
      dut.io.out.bits.pageFault.expect(true.B)
      dut.io.physicalRequest.valid.expect(false.B)
      dut.clock.step(2)
      dut.io.out.valid.expect(true.B)
      dut.io.out.ready.poke(true.B)
      dut.clock.step()
    }
  }

  it should "bypass in Bare mode and selectively flush an ASID mapping" in {
    val config = GpuConfig(lanes = 2, warps = 1)
    simulate(new VectorTlb(config, entries = 4)) { dut =>
      dut.reset.poke(true.B)
      dut.io.in.valid.poke(false.B)
      dut.io.translationEnabled.poke(false.B)
      dut.io.asid.poke(5.U)
      dut.io.flush.valid.poke(false.B)
      dut.io.out.ready.poke(true.B)
      dut.io.physicalRequest.ready.poke(false.B)
      dut.io.physicalResponse.valid.poke(false.B)
      dut.io.pageWalkRequest.ready.poke(true.B)
      dut.io.pageWalkResponse.valid.poke(false.B)
      dut.clock.step()
      dut.reset.poke(false.B)

      def begin(address: BigInt): Unit = {
        dut.io.in.valid.poke(true.B)
        dut.io.in.bits.warpId.poke(0.U)
        dut.io.in.bits.lineAddress.poke(address.U)
        dut.io.in.bits.writeData.poke(0.U)
        dut.io.in.bits.byteMask.poke(1.U)
        dut.io.in.bits.isStore.poke(false.B)
        dut.clock.step()
        dut.io.in.valid.poke(false.B)
        dut.clock.step()
      }

      begin(0x34567040L)
      dut.io.pageWalkRequest.valid.expect(false.B)
      dut.io.physicalRequest.valid.expect(true.B)
      dut.io.physicalRequest.bits.lineAddress.expect("h34567040".U)
      dut.io.physicalRequest.ready.poke(true.B)
      dut.clock.step()
      dut.io.physicalResponse.valid.poke(true.B)
      dut.io.physicalResponse.bits.readData.poke(0.U)
      dut.io.physicalResponse.bits.fault.poke(false.B)
      dut.io.physicalResponse.bits.pageFault.poke(false.B)
      dut.clock.step()
      dut.io.physicalResponse.valid.poke(false.B)

      dut.io.translationEnabled.poke(true.B)
      dut.io.physicalRequest.ready.poke(false.B)
      begin(0x34567080L)
      dut.io.pageWalkRequest.valid.expect(true.B)
      dut.clock.step()
      dut.io.pageWalkResponse.valid.poke(true.B)
      dut.io.pageWalkResponse.bits.physicalPageNumber.poke(0x22222.U)
      dut.io.pageWalkResponse.bits.readable.poke(true.B)
      dut.io.pageWalkResponse.bits.writable.poke(true.B)
      dut.io.pageWalkResponse.bits.global.poke(false.B)
      dut.io.pageWalkResponse.bits.fault.poke(false.B)
      dut.clock.step()
      dut.io.pageWalkResponse.valid.poke(false.B)
      dut.io.physicalRequest.ready.poke(true.B)
      dut.clock.step()
      dut.io.physicalResponse.valid.poke(true.B)
      dut.clock.step()
      dut.io.physicalResponse.valid.poke(false.B)

      dut.io.flush.valid.poke(true.B)
      dut.io.flush.bits.virtualPageNumberValid.poke(true.B)
      dut.io.flush.bits.virtualPageNumber.poke(0x34567.U)
      dut.io.flush.bits.asidValid.poke(true.B)
      dut.io.flush.bits.asid.poke(5.U)
      dut.clock.step()
      dut.io.flush.valid.poke(false.B)

      dut.io.physicalRequest.ready.poke(false.B)
      begin(0x345670c0L)
      dut.io.pageWalkRequest.valid.expect(true.B)
    }
  }
}
