package gpu.core.backend.register

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import gpu.config.GpuConfig
import org.scalatest.flatspec.AnyFlatSpec

class VectorRegisterFileSpec extends AnyFlatSpec {
  behavior of "VectorRegisterFile"

  it should "isolate warps, keep v0 writable, and bypass same-cycle writes" in {
    val config = GpuConfig(lanes = 4, warps = 2)
    simulate(new VectorRegisterFile(config)) { dut =>
      dut.io.read.warpId.poke(0.U)
      dut.io.read.vs1.poke(0.U)
      dut.io.read.vs2.poke(3.U)
      dut.io.read.vd.poke(0.U)
      dut.io.write.valid.poke(false.B)
      dut.clock.step()

      dut.io.write.valid.poke(true.B)
      dut.io.write.bits.warpId.poke(0.U)
      dut.io.write.bits.vd.poke(0.U)
      for (lane <- 0 until config.lanes) {
        dut.io.write.bits.data(lane).poke((0x10 + lane).U)
      }
      for (lane <- 0 until config.lanes) {
        dut.io.vs1Data(lane).expect((0x10 + lane).U)
        dut.io.oldVdData(lane).expect((0x10 + lane).U)
      }
      dut.io.predicateMask.expect(0.U)
      dut.clock.step()

      dut.io.write.bits.warpId.poke(1.U)
      for (lane <- 0 until config.lanes) {
        dut.io.write.bits.data(lane).poke((0x20 + lane).U)
      }
      dut.clock.step()
      dut.io.write.valid.poke(false.B)

      dut.io.read.warpId.poke(0.U)
      for (lane <- 0 until config.lanes) {
        dut.io.vs1Data(lane).expect((0x10 + lane).U)
      }
      dut.io.read.warpId.poke(1.U)
      for (lane <- 0 until config.lanes) {
        dut.io.vs1Data(lane).expect((0x20 + lane).U)
      }
      dut.io.predicateMask.expect(0.U)
    }
  }
}
