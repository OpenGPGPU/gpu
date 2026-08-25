package gpu.dispatch

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import gpu.config.GpuConfig
import org.scalatest.flatspec.AnyFlatSpec

class SingleCuKernelControllerSpec extends AnyFlatSpec {
  behavior of "SingleCuKernelController"

  it should "initialize, launch, and account for every warp in a kernel" in {
    val config = GpuConfig(lanes = 4, warps = 2)
    simulate(new SingleCuKernelController(config)) { dut =>
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      dut.io.kernel.valid.poke(false.B)
      dut.io.completion.ready.poke(true.B)
      dut.io.activeWarps.poke(0.U)
      dut.io.scalarInitialize.ready.poke(true.B)
      dut.io.vectorInitialize.ready.poke(true.B)
      dut.io.launch.ready.poke(true.B)
      dut.io.finish.valid.poke(false.B)

      dut.io.kernel.valid.poke(true.B)
      dut.io.kernel.bits.kernelPc.poke(0x1000.U)
      dut.io.kernel.bits.kernargAddress.poke(0x8000.U)
      (0 until 3).foreach { i =>
        dut.io.kernel.bits.gridSize(i).poke(1.U)
        dut.io.kernel.bits.localSize(i).poke(Seq(5, 1, 1)(i).U)
      }
      dut.clock.step(); dut.io.kernel.valid.poke(false.B)

      Seq((0xf, 0), (0x1, 1)).foreach { case (expectedMask, warpId) =>
        var cycles = 0
        while (!dut.io.launch.valid.peek().litToBoolean && cycles < 32) {
          dut.clock.step(); cycles += 1
        }
        assert(dut.io.launch.valid.peek().litToBoolean)
        dut.io.launch.bits.warpId.expect(warpId.U)
        dut.io.launch.bits.startPc.expect(0x1000.U)
        dut.io.launch.bits.activeMask.expect(expectedMask.U)
        dut.clock.step()

        // Model the scheduler active bitmap and the eventual cease event.
        dut.io.activeWarps.poke((1 << warpId).U)
        dut.clock.step()
        dut.io.finish.valid.poke(true.B)
        dut.io.finish.bits.poke(warpId.U)
        dut.clock.step()
        dut.io.finish.valid.poke(false.B)
        dut.io.activeWarps.poke(0.U)
      }

      var cycles = 0
      while (!dut.io.completion.valid.peek().litToBoolean && cycles < 16) {
        dut.clock.step(); cycles += 1
      }
      dut.io.completion.valid.expect(true.B)
      dut.io.completion.bits.success.expect(true.B)
    }
  }
}
