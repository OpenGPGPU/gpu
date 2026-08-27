package opengpu.core.memory

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import opengpu.config.GpuConfig
import org.scalatest.flatspec.AnyFlatSpec

class L2StoreTransactionTableSpec extends AnyFlatSpec {
  behavior of "L2StoreTransactionTable"

  private def initialize(dut: L2StoreTransactionTable): Unit = {
    dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
    dut.io.allocate.valid.poke(false.B)
    dut.io.allocate.bits.poke(0.U.asTypeOf(dut.io.allocate.bits))
    dut.io.lowerRequest.ready.poke(true.B)
    dut.io.lowerResponse.valid.poke(false.B)
    dut.io.lowerResponse.bits.poke(0.U.asTypeOf(dut.io.lowerResponse.bits))
    dut.io.response.ready.poke(false.B)
  }

  private def allocate(dut: L2StoreTransactionTable, address: BigInt,
                       id: Int): Unit = {
    dut.io.allocate.bits.address.poke(address.U)
    dut.io.allocate.bits.transactionId.poke(id.U)
    dut.io.allocate.bits.isWrite.poke(true.B)
    dut.io.allocate.valid.poke(true.B)
    dut.io.allocate.ready.expect(true.B)
    dut.clock.step(); dut.io.allocate.valid.poke(false.B)
  }

  private def lowerRequest(dut: L2StoreTransactionTable): BigInt = {
    if (!dut.io.lowerRequest.valid.peek().litToBoolean) dut.clock.step()
    dut.io.lowerRequest.valid.expect(true.B)
    val id = dut.io.lowerRequest.bits.transactionId.peek().litValue
    dut.clock.step()
    id
  }

  private def acknowledge(dut: L2StoreTransactionTable, id: BigInt,
                          fault: Boolean = false): Unit = {
    dut.io.lowerResponse.bits.transactionId.poke(id.U)
    dut.io.lowerResponse.bits.fault.poke(fault.B)
    dut.io.lowerResponse.valid.poke(true.B)
    dut.io.lowerResponse.ready.expect(true.B)
    dut.clock.step(); dut.io.lowerResponse.valid.poke(false.B)
  }

  it should "restore original IDs after out-of-order write acknowledgements" in {
    simulate(new L2StoreTransactionTable(
      GpuConfig(lanes = 4), entries = 2)) { dut =>
      initialize(dut)
      allocate(dut, 0x1000, 1)
      val first = lowerRequest(dut)
      allocate(dut, 0x1080, 6)
      val second = lowerRequest(dut)
      assert(first != second)
      acknowledge(dut, second)
      acknowledge(dut, first, fault = true)

      dut.io.response.valid.expect(true.B)
      dut.io.response.bits.transactionId.expect(1.U)
      dut.io.response.bits.fault.expect(true.B)
      dut.io.response.ready.poke(true.B); dut.clock.step()
      dut.io.response.bits.transactionId.expect(6.U)
      dut.io.response.bits.fault.expect(false.B)
      dut.clock.step()
      dut.io.active.expect(false.B)
    }
  }

  it should "serialize stores targeting the same cache line" in {
    simulate(new L2StoreTransactionTable(
      GpuConfig(lanes = 4), entries = 2)) { dut =>
      initialize(dut)
      allocate(dut, 0x2000, 2)
      lowerRequest(dut)
      dut.io.allocate.bits.address.poke(0x203c.U)
      dut.io.allocate.bits.isWrite.poke(true.B)
      dut.io.allocate.valid.poke(true.B)
      dut.io.allocate.ready.expect(false.B)
      dut.io.allocate.bits.address.poke(0x2040.U)
      dut.io.allocate.ready.expect(true.B)
    }
  }

  it should "accept independent stores in consecutive cycles" in {
    simulate(new L2StoreTransactionTable(
      GpuConfig(lanes = 4), entries = 2)) { dut =>
      initialize(dut)
      dut.io.lowerRequest.ready.poke(false.B)

      dut.io.allocate.bits.address.poke(0x3000.U)
      dut.io.allocate.bits.transactionId.poke(3.U)
      dut.io.allocate.bits.isWrite.poke(true.B)
      dut.io.allocate.valid.poke(true.B)
      dut.io.allocate.ready.expect(true.B)
      dut.clock.step()

      dut.io.allocate.bits.address.poke(0x3080.U)
      dut.io.allocate.bits.transactionId.poke(4.U)
      dut.io.allocate.ready.expect(true.B)
      dut.clock.step()
      dut.io.allocate.valid.poke(false.B)

      dut.io.active.expect(true.B)
      dut.io.allocate.ready.expect(false.B)
      dut.io.lowerRequest.valid.expect(true.B)
    }
  }
}
