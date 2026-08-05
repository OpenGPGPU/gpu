package gpu.core.memory

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import gpu.config.GpuConfig
import org.scalatest.flatspec.AnyFlatSpec

class L2VictimReservationTableSpec extends AnyFlatSpec {
  behavior of "L2VictimReservationTable"

  it should "serialize identical victim slots and permit reuse after release" in {
    simulate(new L2VictimReservationTable(
      GpuConfig(lanes = 4), entries = 4, sets = 8, ways = 2)) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      dut.io.reserve.valid.poke(false.B)
      dut.io.release.valid.poke(false.B)
      dut.io.query.set.poke(0.U)
      dut.io.query.way.poke(0.U)

      def reserve(entry: Int, set: Int, way: Int, line: Int,
                  expectedReady: Boolean): Unit = {
        dut.io.reserve.bits.entryId.poke(entry.U)
        dut.io.reserve.bits.set.poke(set.U)
        dut.io.reserve.bits.way.poke(way.U)
        dut.io.reserve.bits.lineAddress.poke(line.U)
        dut.io.reserve.valid.poke(true.B)
        dut.io.reserve.ready.expect(expectedReady.B)
        if (expectedReady) {
          dut.clock.step()
        }
        dut.io.reserve.valid.poke(false.B)
      }

      reserve(entry = 0, set = 3, way = 1, line = 0x1000,
        expectedReady = true)
      dut.io.validEntries.expect(1.U)
      dut.io.query.set.poke(3.U)
      dut.io.query.way.poke(1.U)
      dut.io.queryConflict.expect(true.B)
      dut.io.queryOwner.expect(0.U)

      // A different MSHR cannot select the already-reserved physical slot.
      reserve(entry = 1, set = 3, way = 1, line = 0x2000,
        expectedReady = false)
      dut.io.validEntries.expect(1.U)

      // Another way in the same set remains available for parallel refill.
      reserve(entry = 1, set = 3, way = 0, line = 0x2000,
        expectedReady = true)
      dut.io.validEntries.expect(3.U)
      dut.io.query.way.poke(0.U)
      dut.io.queryConflict.expect(true.B)
      dut.io.queryOwner.expect(1.U)

      dut.io.release.bits.poke(0.U)
      dut.io.release.valid.poke(true.B)
      dut.clock.step()
      dut.io.release.valid.poke(false.B)
      dut.io.validEntries.expect(2.U)

      // The original slot becomes immediately reusable after its owner retires.
      reserve(entry = 2, set = 3, way = 1, line = 0x3000,
        expectedReady = true)
      dut.io.validEntries.expect(6.U)
      dut.io.query.way.poke(1.U)
      dut.io.queryConflict.expect(true.B)
      dut.io.queryOwner.expect(2.U)
    }
  }

  it should "allow equal way numbers in independent sets" in {
    simulate(new L2VictimReservationTable(
      GpuConfig(lanes = 2), entries = 4, sets = 8, ways = 2)) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      dut.io.release.valid.poke(false.B)
      dut.io.query.set.poke(0.U)
      dut.io.query.way.poke(0.U)

      for ((entry, set) <- Seq(0 -> 1, 1 -> 2)) {
        dut.io.reserve.bits.entryId.poke(entry.U)
        dut.io.reserve.bits.set.poke(set.U)
        dut.io.reserve.bits.way.poke(0.U)
        dut.io.reserve.bits.lineAddress.poke((0x1000 + entry * 0x1000).U)
        dut.io.reserve.valid.poke(true.B)
        dut.io.reserve.ready.expect(true.B)
        dut.clock.step()
      }
      dut.io.reserve.valid.poke(false.B)
      dut.io.validEntries.expect(3.U)
    }
  }
}
