package gpu.core.memory

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import gpu.config.GpuConfig
import org.scalatest.flatspec.AnyFlatSpec

class L2MshrTableSpec extends AnyFlatSpec {
  behavior of "L2MshrTable"

  it should "allocate different lines, merge sharers, and accept out-of-order refills" in {
    simulate(new L2MshrTable(GpuConfig(lanes = 4), entries = 4)) { dut =>
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      dut.io.request.valid.poke(false.B)
      dut.io.allocation.ready.poke(true.B)
      dut.io.refill.valid.poke(false.B)
      dut.io.fill.ready.poke(true.B)
      dut.io.response.ready.poke(false.B)

      def allocate(line: Int, transaction: Int, cu: Int,
                   set: Int = 0, way: Int = 0): (BigInt, Boolean) = {
        dut.io.request.valid.poke(true.B)
        dut.io.request.bits.lineAddress.poke(line.U)
        dut.io.request.bits.transactionId.poke(transaction.U)
        dut.io.request.bits.requester.poke(cu.U)
        dut.io.request.bits.trackSharer.poke(true.B)
        dut.io.request.bits.victimSet.poke(set.U)
        dut.io.request.bits.victimWay.poke(way.U)
        dut.io.request.ready.expect(true.B)
        dut.clock.step(); dut.io.request.valid.poke(false.B)
        dut.io.allocation.valid.expect(true.B)
        val result = (dut.io.allocation.bits.entryId.peek().litValue,
          dut.io.allocation.bits.merged.peek().litToBoolean)
        dut.clock.step()
        result
      }

      val (entryA, mergedA) = allocate(0x1000, 0, 0)
      assert(!mergedA)
      val (entryB, mergedB) = allocate(0x2000, 2, 0, set = 1)
      assert(!mergedB && entryB != entryA)
      val (mergedEntry, wasMerged) = allocate(0x1000, 4, 1)
      assert(wasMerged && mergedEntry == entryA)
      assert(dut.io.validEntries.peek().litValue.bitCount == 2)

      // Refill B before A. Each refill first produces a cache-fill event.
      dut.io.refill.valid.poke(true.B)
      dut.io.refill.bits.entryId.poke(entryB.U)
      dut.io.refill.bits.readData.poke(0xbb.U)
      dut.io.refill.bits.fault.poke(false.B)
      dut.clock.step(); dut.io.refill.valid.poke(false.B)
      dut.io.fill.valid.expect(true.B)
      dut.io.fill.bits.lineAddress.expect(0x2000.U)
      dut.io.fill.bits.sharers.expect(1.U)
      dut.io.fill.bits.victimSet.expect(1.U)
      dut.io.fill.bits.victimWay.expect(0.U)
      dut.clock.step()

      dut.io.refill.valid.poke(true.B)
      dut.io.refill.bits.entryId.poke(entryA.U)
      dut.io.refill.bits.readData.poke(0xaa.U)
      dut.clock.step(); dut.io.refill.valid.poke(false.B)
      dut.io.fill.valid.expect(true.B)
      dut.io.fill.bits.lineAddress.expect(0x1000.U)
      dut.io.fill.bits.sharers.expect(3.U)
      dut.io.fill.bits.victimSet.expect(0.U)
      dut.io.fill.bits.victimWay.expect(0.U)
      dut.clock.step()

      dut.io.response.ready.poke(true.B)
      var responses = Map.empty[Int, BigInt]
      for (_ <- 0 until 3) {
        dut.io.response.valid.expect(true.B)
        responses += dut.io.response.bits.transactionId.peek().litValue.toInt ->
          dut.io.response.bits.readData.peek().litValue
        dut.clock.step()
      }
      assert(responses == Map(0 -> 0xaa, 2 -> 0xbb, 4 -> 0xaa))
      dut.io.validEntries.expect(0.U)
    }
  }

  it should "backpressure a fifth independent line until an entry retires" in {
    simulate(new L2MshrTable(GpuConfig(lanes = 2), entries = 4)) { dut =>
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      dut.io.allocation.ready.poke(true.B)
      dut.io.refill.valid.poke(false.B)
      dut.io.fill.ready.poke(true.B)
      dut.io.response.ready.poke(true.B)
      for (entry <- 0 until 4) {
        dut.io.request.valid.poke(true.B)
        dut.io.request.bits.lineAddress.poke((entry * 0x1000).U)
        dut.io.request.bits.transactionId.poke(entry.U)
        dut.io.request.bits.requester.poke(0.U)
        dut.io.request.bits.trackSharer.poke(true.B)
        dut.io.request.bits.victimSet.poke(entry.U)
        dut.io.request.bits.victimWay.poke(0.U)
        dut.clock.step(); dut.io.request.valid.poke(false.B)
        dut.clock.step()
      }
      dut.io.request.valid.poke(true.B)
      dut.io.request.bits.lineAddress.poke(0x5000.U)
      dut.io.request.bits.transactionId.poke(7.U)
      dut.io.request.bits.victimSet.poke(5.U)
      dut.io.request.bits.victimWay.poke(0.U)
      dut.io.request.ready.expect(false.B)
    }
  }

  it should "reject a different line targeting an occupied victim slot" in {
    simulate(new L2MshrTable(GpuConfig(lanes = 2), entries = 4)) { dut =>
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      dut.io.allocation.ready.poke(true.B)
      dut.io.refill.valid.poke(false.B)
      dut.io.fill.ready.poke(true.B)
      dut.io.response.ready.poke(true.B)

      def drive(line: Int, transaction: Int, set: Int, way: Int): Unit = {
        dut.io.request.bits.lineAddress.poke(line.U)
        dut.io.request.bits.transactionId.poke(transaction.U)
        dut.io.request.bits.requester.poke(0.U)
        dut.io.request.bits.trackSharer.poke(true.B)
        dut.io.request.bits.victimSet.poke(set.U)
        dut.io.request.bits.victimWay.poke(way.U)
        dut.io.request.valid.poke(true.B)
      }

      drive(0x1000, 0, set = 3, way = 2)
      dut.io.request.ready.expect(true.B)
      dut.clock.step(); dut.io.request.valid.poke(false.B)
      dut.clock.step()

      drive(0x2000, 1, set = 3, way = 2)
      dut.io.request.ready.expect(false.B)
      dut.io.request.valid.poke(false.B)

      // The same set can still use another physical way.
      drive(0x2000, 1, set = 3, way = 1)
      dut.io.request.ready.expect(true.B)
    }
  }
}

class L2MissEngineSpec extends AnyFlatSpec {
  behavior of "L2MissEngine"

  it should "issue one refill per unique line and route lower responses by entry" in {
    simulate(new L2MissEngine(GpuConfig(lanes = 4), entries = 4)) { dut =>
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      dut.io.miss.valid.poke(false.B)
      dut.io.lowerRequest.ready.poke(true.B)
      dut.io.lowerResponse.valid.poke(false.B)
      dut.io.fill.ready.poke(true.B)
      dut.io.response.ready.poke(false.B)
      dut.io.authorize.valid.poke(false.B)

      def miss(line: Int, transaction: Int, cu: Int): Unit = {
        dut.io.miss.bits.lineAddress.poke(line.U)
        dut.io.miss.bits.transactionId.poke(transaction.U)
        dut.io.miss.bits.requester.poke(cu.U)
        dut.io.miss.bits.trackSharer.poke(true.B)
        dut.io.miss.bits.victimSet.poke(((line >> 12) & 0x3f).U)
        dut.io.miss.bits.victimWay.poke(0.U)
        var cycles = 0
        while (!dut.io.miss.ready.peek().litToBoolean && cycles < 12) {
          dut.clock.step(); cycles += 1
        }
        assert(dut.io.miss.ready.peek().litToBoolean,
          f"miss engine did not accept line 0x$line%x")
        dut.io.miss.valid.poke(true.B)
        dut.clock.step(); dut.io.miss.valid.poke(false.B)
      }

      def captureLower(): BigInt = {
        var cycles = 0
        while (!dut.io.lowerRequest.valid.peek().litToBoolean && cycles < 8) {
          dut.clock.step(); cycles += 1
        }
        dut.io.lowerRequest.valid.expect(true.B)
        val id = dut.io.lowerRequest.bits.transactionId.peek().litValue
        dut.clock.step()
        id
      }

      def allocation(expectMerged: Boolean): BigInt = {
        dut.io.allocation.valid.expect(true.B)
        dut.io.allocation.bits.merged.expect(expectMerged.B)
        val entry = dut.io.allocation.bits.entryId.peek().litValue
        if (!expectMerged) {
          dut.io.authorize.bits.poke(entry.U)
          dut.io.authorize.valid.poke(true.B)
        }
        dut.clock.step()
        dut.io.authorize.valid.poke(false.B)
        entry
      }

      miss(0x1000, 0, 0)
      val entryA = allocation(expectMerged = false)
      assert(captureLower() == entryA)
      miss(0x2000, 1, 0)
      val entryB = allocation(expectMerged = false)
      assert(captureLower() == entryB)
      assert(entryA != entryB)
      miss(0x1000, 4, 1)
      assert(allocation(expectMerged = true) == entryA)
      dut.clock.step(3)
      dut.io.lowerRequest.valid.expect(false.B)

      def refill(entry: BigInt, data: Int): Unit = {
        dut.io.lowerResponse.valid.poke(true.B)
        dut.io.lowerResponse.bits.transactionId.poke(entry.U)
        dut.io.lowerResponse.bits.readData.poke(data.U)
        dut.io.lowerResponse.bits.fault.poke(false.B)
        dut.io.lowerResponse.ready.expect(true.B)
        dut.clock.step(); dut.io.lowerResponse.valid.poke(false.B)
        dut.io.fill.valid.expect(true.B)
        dut.clock.step()
      }

      refill(entryB, 0xbb)
      refill(entryA, 0xaa)
      dut.io.response.ready.poke(true.B)
      var responses = Map.empty[Int, BigInt]
      for (_ <- 0 until 3) {
        dut.io.response.valid.expect(true.B)
        responses += dut.io.response.bits.transactionId.peek().litValue.toInt ->
          dut.io.response.bits.readData.peek().litValue
        dut.clock.step()
      }
      assert(responses == Map(0 -> 0xaa, 1 -> 0xbb, 4 -> 0xaa))
      dut.io.validEntries.expect(0.U)
    }
  }
}
