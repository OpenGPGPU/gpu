package opengpu.core.memory

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import opengpu.config.GpuConfig
import org.scalatest.flatspec.AnyFlatSpec

class GraphicsAddressTranslatorSpec extends AnyFlatSpec {
  behavior of "GraphicsAddressTranslator"

  private def request(dut: GraphicsAddressTranslator, address: BigInt,
                      id: Int): Unit = {
    dut.io.in.bits.poke(0.U.asTypeOf(dut.io.in.bits))
    dut.io.in.bits.address.poke(address.U)
    dut.io.in.bits.sizeLog2.poke(6.U)
    dut.io.in.bits.transactionId.poke(id.U)
    dut.io.in.valid.poke(true.B)
    dut.clock.step()
    dut.io.in.valid.poke(false.B)
  }

  private def waitOut(dut: GraphicsAddressTranslator): Unit = {
    var cycles = 0
    while (!dut.io.out.valid.peek().litToBoolean && cycles < 20) {
      dut.clock.step(); cycles += 1
    }
    dut.io.out.valid.expect(true.B)
  }

  private def waitPageWalk(dut: GraphicsAddressTranslator): Unit = {
    var cycles = 0
    while (!dut.io.pageWalk.valid.peek().litToBoolean && cycles < 20) {
      dut.clock.step(); cycles += 1
    }
    dut.io.pageWalk.valid.expect(true.B)
  }

  it should "pass a request through with the cached policy when disabled" in {
    simulate(new GraphicsAddressTranslator(
      GpuConfig(lanes = 2, warps = 1), entries = 4, maxOutstanding = 4)) { dut =>
      dut.reset.poke(true.B)
      dut.io.in.valid.poke(false.B)
      dut.io.in.bits.poke(0.U.asTypeOf(dut.io.in.bits))
      dut.io.out.ready.poke(true.B)
      dut.io.pageWalk.ready.poke(true.B)
      dut.io.pageWalkResp.valid.poke(false.B)
      dut.io.satp.poke(0.U)
      dut.io.flush.poke(false.B)
      dut.io.pageWalkTransactionId.poke(3.U)
      dut.clock.step(); dut.reset.poke(false.B)

      request(dut, 0x2000, 1)
      waitOut(dut)
      dut.io.out.bits.address.expect(0x2000.U)
      dut.io.out.bits.cachePolicy.expect(0.U)
      dut.io.out.bits.transactionId.expect(1.U)
      dut.io.pageWalk.valid.expect(false.B)
    }
  }

  it should "attach the identity page's cache policy when enabled" in {
    simulate(new GraphicsAddressTranslator(
      GpuConfig(lanes = 2, warps = 1), entries = 4, maxOutstanding = 4)) { dut =>
      dut.reset.poke(true.B)
      dut.io.in.valid.poke(false.B)
      dut.io.in.bits.poke(0.U.asTypeOf(dut.io.in.bits))
      dut.io.out.ready.poke(true.B)
      dut.io.pageWalk.ready.poke(true.B)
      dut.io.pageWalkResp.valid.poke(false.B)
      // Sv32 enabled, root PPN 0x80.
      dut.io.satp.poke((BigInt(1) << 31 | 0x80).U)
      dut.io.flush.poke(false.B)
      dut.io.pageWalkTransactionId.poke(3.U)
      dut.clock.step(); dut.reset.poke(false.B)

      request(dut, 0x2000, 1)
      // Identity superpage PPN 2 (VA 0x2000 >> 12) with policy 2 (uncached).
      waitPageWalk(dut)
      dut.io.pageWalk.bits.address.expect(0x80000.U)
      dut.io.pageWalk.bits.cachePolicy.expect(2.U)
      dut.io.pageWalk.bits.sizeLog2.expect(2.U)
      dut.io.pageWalkResp.valid.poke(true.B)
      dut.io.pageWalkResp.bits.readData.poke(
        ((BigInt(2) << 10) | (BigInt(2) << 8) | 0xcf).U)
      dut.io.pageWalkResp.bits.fault.poke(false.B)
      waitOut(dut)
      dut.io.pageWalkResp.valid.poke(false.B)

      dut.io.out.bits.address.expect(0x2000.U)
      dut.io.out.bits.cachePolicy.expect(2.U)
      dut.io.out.bits.transactionId.expect(1.U)
    }
  }
}
