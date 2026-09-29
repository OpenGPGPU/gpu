package opengpu.testutil

import chisel3._
import chisel3.util._
import org.scalatest.flatspec.AnyFlatSpec
import GpuSim._

/** Guards the shared simulation harness: a model restored from the cache has to
  * behave exactly like a freshly compiled one, and a failing expectation has to
  * keep failing.
  */
class GpuSimSpec extends AnyFlatSpec {
  behavior of "GpuSim"

  private class Counter(width: Int) extends Module {
    val io = IO(new Bundle {
      val en = Input(Bool())
      val out = Output(UInt(width.W))
    })
    val reg = RegInit(0.U(width.W))
    when(io.en) { reg := reg + 1.U }
    io.out := reg
  }

  private def start(dut: Counter): Unit = {
    dut.reset.poke(true.B)
    dut.io.en.poke(false.B)
    dut.clock.step()
    dut.reset.poke(false.B)
    dut.io.out.expect(0.U)
  }

  it should "count up from a cached model" in {
    simulate(new Counter(8)) { dut =>
      start(dut)
      dut.io.en.poke(true.B)
      for (cycle <- 1 to 4) {
        dut.clock.step()
        dut.io.out.expect(cycle.U)
      }
    }
  }

  it should "report a failing expectation" in {
    intercept[chisel3.simulator.FailedExpectationException[_]] {
      simulate(new Counter(8)) { dut =>
        start(dut)
        dut.io.out.expect(1.U)
      }
    }
  }

  it should "keep separate runs of one model independent" in {
    def run(): BigInt = {
      var out = BigInt(0)
      simulate(new Counter(8)) { dut =>
        start(dut)
        dut.io.en.poke(true.B)
        for (_ <- 1 to 3) { dut.clock.step() }
        out = dut.io.out.peek().litValue
      }
      out
    }
    assert(run() == 3 && run() == 3, s"counter restarted wrong: ${run()}, ${run()}")
  }
}
