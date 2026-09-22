package opengpu.core.memory

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import opengpu.config.{CachePolicy, GpuConfig}
import org.scalatest.flatspec.AnyFlatSpec

class TranslatedLineClientSpec extends AnyFlatSpec {
  behavior of "TranslatedLineClient"

  private def initialize(dut: TranslatedLineClient, satp: BigInt): Unit = {
    dut.io.in.valid.poke(false.B)
    dut.io.in.bits.poke(0.U.asTypeOf(dut.io.in.bits))
    dut.io.out.ready.poke(true.B)
    dut.io.memReq.ready.poke(true.B)
    dut.io.memResp.valid.poke(false.B)
    dut.io.memResp.bits.poke(0.U.asTypeOf(dut.io.memResp.bits))
    dut.io.pageWalk.ready.poke(true.B)
    dut.io.pageWalkResp.valid.poke(false.B)
    dut.io.pageWalkResp.bits.poke(0.U.asTypeOf(dut.io.pageWalkResp.bits))
    dut.io.satp.poke(satp.U)
    dut.io.flush.valid.poke(false.B)
    dut.io.flush.bits.poke(0.U.asTypeOf(dut.io.flush.bits))
    dut.reset.poke(true.B)
    dut.clock.step()
    dut.reset.poke(false.B)
  }

  private def request(dut: TranslatedLineClient, address: BigInt): Unit = {
    dut.io.in.bits.poke(0.U.asTypeOf(dut.io.in.bits))
    dut.io.in.bits.address.poke(address.U)
    dut.io.in.bits.sizeLog2.poke(6.U)
    dut.io.in.bits.cachePolicy.poke(CachePolicy.uncached)
    dut.io.in.bits.transactionId.poke(1.U)
    dut.io.in.valid.poke(true.B)
    dut.clock.step()
    dut.io.in.valid.poke(false.B)
  }

  private def waitFor(valid: => Boolean, dut: TranslatedLineClient): Unit = {
    var cycles = 0
    while (!valid && cycles < 20) {
      dut.clock.step()
      cycles += 1
    }
    assert(valid)
  }

  it should "preserve an uncached staging request with translation disabled" in {
    simulate(new TranslatedLineClient(
      GpuConfig(lanes = 2, warps = 1), outstanding = 4,
      preserveCachePolicy = true)) { dut =>
      initialize(dut, 0)
      request(dut, 0x2000)
      waitFor(dut.io.memReq.valid.peek().litToBoolean, dut)
      dut.io.memReq.bits.address.expect(0x2000.U)
      dut.io.memReq.bits.cachePolicy.expect(CachePolicy.uncached)
      dut.io.pageWalk.valid.expect(false.B)
    }
  }

  it should "preserve an uncached staging request across Sv32 translation" in {
    simulate(new TranslatedLineClient(
      GpuConfig(lanes = 2, warps = 1), outstanding = 4,
      preserveCachePolicy = true)) { dut =>
      initialize(dut, (BigInt(1) << 31) | 0x80)
      request(dut, 0x2000)
      waitFor(dut.io.pageWalk.valid.peek().litToBoolean, dut)
      dut.io.pageWalk.bits.address.expect(0x80000.U)
      dut.clock.step()
      // A cacheable PTE must not override staging's uncached request policy.
      dut.io.pageWalkResp.bits.readData.poke(
        ((BigInt(0x400) << 10) | 0xcf).U)
      dut.io.pageWalkResp.valid.poke(true.B)
      dut.clock.step()
      dut.io.pageWalkResp.valid.poke(false.B)
      waitFor(dut.io.memReq.valid.peek().litToBoolean, dut)
      dut.io.memReq.bits.address.expect(0x402000.U)
      dut.io.memReq.bits.cachePolicy.expect(CachePolicy.uncached)
    }
  }
}
