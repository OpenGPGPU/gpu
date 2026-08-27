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
}
