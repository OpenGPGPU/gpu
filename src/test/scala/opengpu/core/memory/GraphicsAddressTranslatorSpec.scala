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

  /** Consume the response held on `out` (ready is normally held low so a test
    * can inspect it before it is retired). */
  private def consumeOut(dut: GraphicsAddressTranslator): Unit = {
    dut.io.out.ready.poke(true.B)
    dut.clock.step()
    dut.io.out.ready.poke(false.B)
  }

  private def waitPageWalk(dut: GraphicsAddressTranslator): Unit = {
    var cycles = 0
    while (!dut.io.pageWalk.valid.peek().litToBoolean && cycles < 20) {
      dut.clock.step(); cycles += 1
    }
    dut.io.pageWalk.valid.expect(true.B)
  }

  /** Pulse a scoped shootdown while the translation port is idle. Both scope
    * bits clear is a full flush, matching the CU TLB contract. */
  private def flushTlb(dut: GraphicsAddressTranslator,
                       vpnValid: Boolean = false, vpn: BigInt = 0,
                       asidValid: Boolean = false, asid: BigInt = 0): Unit = {
    dut.io.flush.bits.virtualPageNumberValid.poke(vpnValid.B)
    dut.io.flush.bits.virtualPageNumber.poke(vpn.U)
    dut.io.flush.bits.asidValid.poke(asidValid.B)
    dut.io.flush.bits.asid.poke(asid.U)
    dut.io.flush.valid.poke(true.B)
    dut.clock.step()
    dut.io.flush.valid.poke(false.B)
  }

  it should "pass a request through with the cached policy when disabled" in {
    simulate(new GraphicsAddressTranslator(
      GpuConfig(lanes = 2, warps = 1), entries = 4, maxOutstanding = 4)) { dut =>
      dut.reset.poke(true.B)
      dut.io.in.valid.poke(false.B)
      dut.io.in.bits.poke(0.U.asTypeOf(dut.io.in.bits))
      dut.io.out.ready.poke(true.B)
      dut.io.faultResponse.ready.poke(true.B)
      dut.io.pageWalk.ready.poke(true.B)
      dut.io.pageWalkResp.valid.poke(false.B)
      dut.io.satp.poke(0.U)
      dut.io.flush.valid.poke(false.B)
      dut.io.flush.bits.poke(0.U.asTypeOf(dut.io.flush.bits))
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
      dut.io.faultResponse.ready.poke(true.B)
      dut.io.pageWalk.ready.poke(true.B)
      dut.io.pageWalkResp.valid.poke(false.B)
      // Sv32 enabled, root PPN 0x80.
      dut.io.satp.poke((BigInt(1) << 31 | 0x80).U)
      dut.io.flush.valid.poke(false.B)
      dut.io.flush.bits.poke(0.U.asTypeOf(dut.io.flush.bits))
      dut.io.pageWalkTransactionId.poke(3.U)
      dut.clock.step(); dut.reset.poke(false.B)

      request(dut, 0x2000, 1)
      // Identity superpage PPN 0; VPN[0] supplies the 0x2000 offset.
      waitPageWalk(dut)
      dut.io.pageWalk.bits.address.expect(0x80000.U)
      dut.io.pageWalk.bits.cachePolicy.expect(2.U)
      dut.io.pageWalk.bits.sizeLog2.expect(2.U)
      dut.io.pageWalkResp.valid.poke(true.B)
      dut.io.pageWalkResp.bits.readData.poke(
        ((BigInt(2) << 8) | 0xcf).U)
      dut.io.pageWalkResp.bits.fault.poke(false.B)
      waitOut(dut)
      dut.io.pageWalkResp.valid.poke(false.B)

      dut.io.out.bits.address.expect(0x2000.U)
      dut.io.out.bits.cachePolicy.expect(2.U)
      dut.io.out.bits.transactionId.expect(1.U)
      dut.io.faultResponse.valid.expect(false.B)
    }
  }

  for ((name, pte, busFault) <- Seq(
    ("invalid PTE", BigInt(0), false),
    ("misaligned superpage", (BigInt(2) << 10) | 0xcf, false),
    ("unreadable page", BigInt(0xc9), false),
    ("page-table bus error", BigInt(0xcf), true))) {
    it should s"return a fault without physical access for $name" in {
      simulate(new GraphicsAddressTranslator(
        GpuConfig(lanes = 2, warps = 1), entries = 4, maxOutstanding = 4)) { dut =>
        dut.io.in.valid.poke(false.B)
        dut.io.out.ready.poke(true.B)
        dut.io.faultResponse.ready.poke(false.B)
        dut.io.pageWalk.ready.poke(true.B)
        dut.io.pageWalkResp.valid.poke(false.B)
        dut.io.satp.poke((BigInt(1) << 31 | 0x80).U)
        dut.io.flush.valid.poke(false.B)
        dut.io.flush.bits.poke(0.U.asTypeOf(dut.io.flush.bits))
        dut.io.pageWalkTransactionId.poke(3.U)
        dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)

        request(dut, 0x2000, 2)
        waitPageWalk(dut)
        dut.clock.step()
        dut.io.pageWalkResp.bits.readData.poke(pte.U)
        dut.io.pageWalkResp.bits.fault.poke(busFault.B)
        dut.io.pageWalkResp.valid.poke(true.B)
        dut.io.pageWalkResp.ready.expect(true.B)
        dut.clock.step()
        dut.io.pageWalkResp.valid.poke(false.B)
        dut.clock.step()
        for (_ <- 0 until 4) {
          dut.io.out.valid.expect(false.B)
          dut.io.faultResponse.valid.expect(true.B)
          dut.io.faultResponse.bits.fault.expect(true.B)
          dut.io.faultResponse.bits.transactionId.expect(2.U)
          dut.io.in.ready.expect(false.B)
          dut.clock.step()
        }
        dut.io.faultResponse.ready.poke(true.B)
        dut.clock.step()
        dut.io.in.ready.expect(true.B)
        dut.io.faultResponse.valid.expect(false.B)
        // Faults must not fill the TLB: retry walks again.
        request(dut, 0x2000, 1)
        waitPageWalk(dut)
      }
    }
  }

  it should "translate a nonidentity page and enforce cached write permission" in {
    simulate(new GraphicsAddressTranslator(
      GpuConfig(lanes = 2, warps = 1), entries = 4, maxOutstanding = 4)) { dut =>
      dut.io.in.valid.poke(false.B)
      dut.io.out.ready.poke(false.B)
      dut.io.faultResponse.ready.poke(false.B)
      dut.io.pageWalk.ready.poke(true.B)
      dut.io.pageWalkResp.valid.poke(false.B)
      dut.io.satp.poke((BigInt(1) << 31 | 0x80).U)
      dut.io.flush.valid.poke(false.B)
      dut.io.flush.bits.poke(0.U.asTypeOf(dut.io.flush.bits))
      dut.io.pageWalkTransactionId.poke(3.U)
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      request(dut, 0x2000, 1)
      waitPageWalk(dut)
      dut.clock.step()
      // Read-only superpage at PA 0x400000, with accessed set.
      dut.io.pageWalkResp.bits.readData.poke(((BigInt(0x400) << 10) | 0x43).U)
      dut.io.pageWalkResp.bits.fault.poke(false.B)
      dut.io.pageWalkResp.valid.poke(true.B)
      dut.clock.step()
      dut.io.pageWalkResp.valid.poke(false.B)
      waitOut(dut)
      dut.io.out.bits.address.expect(0x402000.U)
      dut.io.out.ready.poke(true.B); dut.clock.step()

      dut.io.in.bits.isWrite.poke(true.B)
      dut.io.in.valid.poke(true.B)
      dut.clock.step()
      dut.io.in.valid.poke(false.B)
      dut.clock.step()
      dut.io.out.valid.expect(false.B)
      dut.io.pageWalk.valid.expect(false.B)
      dut.io.faultResponse.valid.expect(true.B)
      dut.io.faultResponse.bits.fault.expect(true.B)
    }
  }

  /** Drive a cached miss to a fresh leaf at `ppn` (superpage at PA `ppn<<12`).
    * Leaves the translated response pending on `out` for the caller to check
    * and retire with `consumeOut`. */
  private def fillPage(dut: GraphicsAddressTranslator, address: BigInt,
                       id: Int, ppn: BigInt, global: Boolean): Unit = {
    request(dut, address, id)
    waitPageWalk(dut)
    val leaf = (ppn << 10) | (if (global) BigInt(0xef) else BigInt(0xcf))
    dut.io.pageWalkResp.bits.readData.poke(leaf.U)
    dut.io.pageWalkResp.bits.fault.poke(false.B)
    dut.io.pageWalkResp.valid.poke(true.B)
    waitOut(dut)
    dut.io.pageWalkResp.valid.poke(false.B)
  }

  it should "not reuse a private page across an ASID switch" in {
    simulate(new GraphicsAddressTranslator(
      GpuConfig(lanes = 2, warps = 1), entries = 4, maxOutstanding = 4)) { dut =>
      dut.io.in.valid.poke(false.B)
      dut.io.in.bits.poke(0.U.asTypeOf(dut.io.in.bits))
      // Hold `out` until the test inspects it, so a response cannot retire
      // before its values are checked.
      dut.io.out.ready.poke(false.B)
      dut.io.faultResponse.ready.poke(true.B)
      dut.io.pageWalk.ready.poke(true.B)
      dut.io.pageWalkResp.valid.poke(false.B)
      // ASID 0, Sv32 enabled, root PPN 0x80.
      dut.io.satp.poke((BigInt(1) << 31 | 0x80).U)
      dut.io.flush.valid.poke(false.B)
      dut.io.flush.bits.poke(0.U.asTypeOf(dut.io.flush.bits))
      dut.io.pageWalkTransactionId.poke(3.U)
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)

      fillPage(dut, 0x2000, 1, 0x400, global = false)
      dut.io.out.bits.address.expect(0x402000.U)
      consumeOut(dut)

      // Same ASID hits the private entry: no new walk.
      request(dut, 0x2000, 1)
      waitOut(dut)
      dut.io.pageWalk.valid.expect(false.B)
      dut.io.out.bits.address.expect(0x402000.U)
      consumeOut(dut)

      // A different ASID must not see ASID 0's private entry.
      dut.io.satp.poke((BigInt(1) << 31 | (BigInt(1) << 22) | 0x80).U)
      fillPage(dut, 0x2000, 2, 0x800, global = false)
      dut.io.out.bits.address.expect(0x802000.U)
      consumeOut(dut)
    }
  }

  it should "reuse a global page across an ASID switch" in {
    simulate(new GraphicsAddressTranslator(
      GpuConfig(lanes = 2, warps = 1), entries = 4, maxOutstanding = 4)) { dut =>
      dut.io.in.valid.poke(false.B)
      dut.io.in.bits.poke(0.U.asTypeOf(dut.io.in.bits))
      dut.io.out.ready.poke(false.B)
      dut.io.faultResponse.ready.poke(true.B)
      dut.io.pageWalk.ready.poke(true.B)
      dut.io.pageWalkResp.valid.poke(false.B)
      dut.io.satp.poke((BigInt(1) << 31 | 0x80).U)
      dut.io.flush.valid.poke(false.B)
      dut.io.flush.bits.poke(0.U.asTypeOf(dut.io.flush.bits))
      dut.io.pageWalkTransactionId.poke(3.U)
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)

      fillPage(dut, 0x6000, 1, 0xc00, global = true)
      dut.io.out.bits.address.expect(0xc06000.U)
      consumeOut(dut)

      dut.io.satp.poke((BigInt(1) << 31 | (BigInt(1) << 22) | 0x80).U)
      request(dut, 0x6000, 2)
      waitOut(dut)
      dut.io.pageWalk.valid.expect(false.B)
      dut.io.out.bits.address.expect(0xc06000.U)
      consumeOut(dut)
    }
  }

  it should "drop only a matching private ASID on an ASID-scoped flush" in {
    simulate(new GraphicsAddressTranslator(
      GpuConfig(lanes = 2, warps = 1), entries = 4, maxOutstanding = 4)) { dut =>
      dut.io.in.valid.poke(false.B)
      dut.io.in.bits.poke(0.U.asTypeOf(dut.io.in.bits))
      dut.io.out.ready.poke(false.B)
      dut.io.faultResponse.ready.poke(true.B)
      dut.io.pageWalk.ready.poke(true.B)
      dut.io.pageWalkResp.valid.poke(false.B)
      dut.io.satp.poke((BigInt(1) << 31 | 0x80).U)
      dut.io.flush.valid.poke(false.B)
      dut.io.flush.bits.poke(0.U.asTypeOf(dut.io.flush.bits))
      dut.io.pageWalkTransactionId.poke(3.U)
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)

      fillPage(dut, 0x2000, 1, 0x400, global = false)
      consumeOut(dut)
      fillPage(dut, 0x6000, 1, 0xc00, global = true)
      consumeOut(dut)

      dut.io.satp.poke((BigInt(1) << 31 | (BigInt(1) << 22) | 0x80).U)
      fillPage(dut, 0x2000, 2, 0x800, global = false)
      consumeOut(dut)

      flushTlb(dut, asidValid = true, asid = 0)

      // ASID 1's private mapping and the ASID-0 global page stay warm.
      dut.io.satp.poke((BigInt(1) << 31 | (BigInt(1) << 22) | 0x80).U)
      request(dut, 0x2000, 3)
      waitOut(dut)
      dut.io.pageWalk.valid.expect(false.B)
      dut.io.out.bits.address.expect(0x802000.U)
      consumeOut(dut)

      dut.io.satp.poke((BigInt(1) << 31 | 0x80).U)
      request(dut, 0x6000, 4)
      waitOut(dut)
      dut.io.pageWalk.valid.expect(false.B)
      dut.io.out.bits.address.expect(0xc06000.U)
      consumeOut(dut)

      // ASID 0's private mapping was invalidated: it must walk again.
      fillPage(dut, 0x2000, 5, 0x1000, global = false)
      dut.io.out.bits.address.expect(0x1002000.U)
      consumeOut(dut)
    }
  }

  it should "drop only a matching VPN on a VPN-scoped flush" in {
    simulate(new GraphicsAddressTranslator(
      GpuConfig(lanes = 2, warps = 1), entries = 4, maxOutstanding = 4)) { dut =>
      dut.io.in.valid.poke(false.B)
      dut.io.in.bits.poke(0.U.asTypeOf(dut.io.in.bits))
      dut.io.out.ready.poke(false.B)
      dut.io.faultResponse.ready.poke(true.B)
      dut.io.pageWalk.ready.poke(true.B)
      dut.io.pageWalkResp.valid.poke(false.B)
      dut.io.satp.poke((BigInt(1) << 31 | 0x80).U)
      dut.io.flush.valid.poke(false.B)
      dut.io.flush.bits.poke(0.U.asTypeOf(dut.io.flush.bits))
      dut.io.pageWalkTransactionId.poke(3.U)
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)

      fillPage(dut, 0x2000, 1, 0x400, global = false)
      consumeOut(dut)
      fillPage(dut, 0x6000, 1, 0xc00, global = false)
      consumeOut(dut)

      flushTlb(dut, vpnValid = true, vpn = (BigInt(0x2000) >> 12))

      // The other VPN stays warm.
      request(dut, 0x6000, 2)
      waitOut(dut)
      dut.io.pageWalk.valid.expect(false.B)
      dut.io.out.bits.address.expect(0xc06000.U)
      consumeOut(dut)

      // The flushed VPN must walk again.
      fillPage(dut, 0x2000, 3, 0x800, global = false)
      dut.io.out.bits.address.expect(0x802000.U)
      consumeOut(dut)
    }
  }

  it should "drop every entry on a full flush" in {
    simulate(new GraphicsAddressTranslator(
      GpuConfig(lanes = 2, warps = 1), entries = 4, maxOutstanding = 4)) { dut =>
      dut.io.in.valid.poke(false.B)
      dut.io.in.bits.poke(0.U.asTypeOf(dut.io.in.bits))
      dut.io.out.ready.poke(false.B)
      dut.io.faultResponse.ready.poke(true.B)
      dut.io.pageWalk.ready.poke(true.B)
      dut.io.pageWalkResp.valid.poke(false.B)
      dut.io.satp.poke((BigInt(1) << 31 | 0x80).U)
      dut.io.flush.valid.poke(false.B)
      dut.io.flush.bits.poke(0.U.asTypeOf(dut.io.flush.bits))
      dut.io.pageWalkTransactionId.poke(3.U)
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)

      fillPage(dut, 0x2000, 1, 0x400, global = false)
      consumeOut(dut)
      fillPage(dut, 0x6000, 1, 0xc00, global = true)
      consumeOut(dut)

      flushTlb(dut)

      fillPage(dut, 0x2000, 2, 0x800, global = false)
      dut.io.out.bits.address.expect(0x802000.U)
      consumeOut(dut)
      fillPage(dut, 0x6000, 3, 0x1000, global = true)
      dut.io.out.bits.address.expect(0x1006000.U)
      consumeOut(dut)
    }
  }

  it should "queue TLB hits while the translated port is backed up" in {
    simulate(new GraphicsAddressTranslator(
      GpuConfig(lanes = 2, warps = 1), entries = 4, maxOutstanding = 4)) { dut =>
      dut.io.in.valid.poke(false.B)
      dut.io.in.bits.poke(0.U.asTypeOf(dut.io.in.bits))
      dut.io.out.ready.poke(false.B)
      dut.io.faultResponse.ready.poke(true.B)
      dut.io.pageWalk.ready.poke(true.B)
      dut.io.pageWalkResp.valid.poke(false.B)
      dut.io.satp.poke((BigInt(1) << 31 | 0x80).U)
      dut.io.flush.valid.poke(false.B)
      dut.io.flush.bits.poke(0.U.asTypeOf(dut.io.flush.bits))
      dut.io.pageWalkTransactionId.poke(3.U)
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)

      fillPage(dut, 0x2000, 0, 0x400, global = false)
      // First translated line is held on `out`; further hits must still accept.
      for (id <- 1 until 4) {
        dut.io.in.ready.expect(true.B)
        request(dut, 0x2000, id)
        dut.io.pageWalk.valid.expect(false.B)
      }
      dut.io.in.ready.expect(false.B)

      for (id <- 0 until 4) {
        waitOut(dut)
        dut.io.out.bits.transactionId.expect(id.U)
        dut.io.out.bits.address.expect(0x402000.U)
        consumeOut(dut)
      }
      dut.io.in.ready.expect(true.B)
      dut.io.out.valid.expect(false.B)
    }
  }
}
