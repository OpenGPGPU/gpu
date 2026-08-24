package gpu.graphics

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec

class KernelEmitSpec extends AnyFlatSpec {
  behavior of "KernelEmit"

  it should "assemble a KernelLaunch carrying the draw's shader descriptor" in {
    simulate(new KernelEmit()) { dut =>
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      dut.io.kernelPc.poke(0x8000.U)
      dut.io.kernargAddress.poke(0x10000.U)
      dut.io.gridX.poke(16.U); dut.io.gridY.poke(16.U); dut.io.gridZ.poke(1.U)
      dut.io.localX.poke(4.U); dut.io.localY.poke(4.U); dut.io.localZ.poke(1.U)
      dut.clock.step()
      dut.io.kernel.kernelPc.expect(0x8000.U)
      dut.io.kernel.kernargAddress.expect(0x10000.U)
      dut.io.kernel.gridSize(0).expect(16.U)
      dut.io.kernel.gridSize(1).expect(16.U)
      dut.io.kernel.gridSize(2).expect(1.U)
      dut.io.kernel.localSize(0).expect(4.U)
      dut.io.kernel.localSize(1).expect(4.U)
      dut.io.kernel.localSize(2).expect(1.U)
    }
  }
}
