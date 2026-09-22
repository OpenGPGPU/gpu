package opengpu.util

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec

class CarrySaveEventCounterSpec extends AnyFlatSpec {
  behavior of "CarrySaveEventCounter"

  it should "carry across limbs without losing back-to-back events" in {
    simulate(new CarrySaveEventCounter(width = 8)) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      dut.io.clear.poke(false.B)
      dut.io.increment.poke(true.B)
      for (expected <- 1 to 33) {
        dut.clock.step()
        dut.io.value.expect(expected.U)
      }

      dut.io.clear.poke(true.B)
      dut.clock.step()
      dut.io.value.expect(0.U)
    }
  }

  behavior of "CarrySaveAccumulator"

  it should "add multi-bit amounts without losing consecutive updates" in {
    simulate(new CarrySaveAccumulator(width = 8)) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      dut.io.clear.poke(false.B)
      dut.io.addend.poke(5.U)
      dut.clock.step()
      dut.io.value.expect(5.U)
      dut.io.addend.poke(3.U)
      dut.clock.step()
      dut.io.value.expect(8.U)
      dut.io.addend.poke(0.U)
      dut.clock.step()
      dut.io.value.expect(8.U)
      dut.io.clear.poke(true.B)
      dut.clock.step()
      dut.io.value.expect(0.U)
    }
  }
}
