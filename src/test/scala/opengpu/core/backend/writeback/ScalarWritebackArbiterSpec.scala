package opengpu.core.backend.writeback

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import opengpu.config.GpuConfig
import org.scalatest.flatspec.AnyFlatSpec

class ScalarWritebackArbiterSpec extends AnyFlatSpec {
  behavior of "ScalarWritebackArbiter"

  private def drive(
    dut: ScalarWritebackArbiter,
    source: Int,
    rd: Int,
    data: Int
  ): Unit = {
    dut.io.in(source).valid.poke(true.B)
    dut.io.in(source).bits.warpId.poke(source.U)
    dut.io.in(source).bits.rd.poke(rd.U)
    dut.io.in(source).bits.data.poke(data.U)
  }

  it should "serve continuously valid sources fairly and discard x0 writes" in {
    val config = GpuConfig(warps = 2)
    simulate(new ScalarWritebackArbiter(config, sourceCount = 2)) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      dut.io.out.ready.poke(true.B)
      drive(dut, source = 0, rd = 3, data = 0x30)
      drive(dut, source = 1, rd = 4, data = 0x40)

      val first = dut.io.selectedSource.peek().litValue
      dut.io.out.valid.expect(true.B)
      dut.clock.step()
      val second = dut.io.selectedSource.peek().litValue
      dut.io.out.valid.expect(true.B)
      assert(Set(first, second) == Set(BigInt(0), BigInt(1)))

      dut.io.in(0).valid.poke(false.B)
      dut.io.in(1).bits.rd.poke(0.U)
      dut.io.out.ready.poke(false.B)
      dut.io.in(1).ready.expect(true.B)
      dut.io.out.valid.expect(false.B)
    }
  }
}
