package opengpu.core.simt

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import opengpu.config.GpuConfig
import opengpu.core.execute.integer.AluOp
import org.scalatest.flatspec.AnyFlatSpec

class SimtAluSpec extends AnyFlatSpec {
  behavior of "SimtAlu"

  it should "execute one instruction over only the active warp lanes" in {
    simulate(new SimtAlu(GpuConfig(lanes = 4))) { dut =>
      val lhs = Seq(10, 20, 30, 40)
      val rhs = Seq(1, 2, 3, 4)
      val active = Seq(true, false, true, false)

      for (lane <- lhs.indices) {
        dut.io.lhs(lane).poke(lhs(lane).U)
        dut.io.rhs(lane).poke(rhs(lane).U)
        dut.io.activeMask(lane).poke(active(lane).B)
      }
      dut.io.operation.poke(AluOp.add)

      dut.io.result(0).expect(11.U)
      dut.io.result(1).expect(0.U)
      dut.io.result(2).expect(33.U)
      dut.io.result(3).expect(0.U)
    }
  }
}
