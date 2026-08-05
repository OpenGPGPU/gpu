package gpu.dispatch

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import gpu.config.GpuConfig
import org.scalatest.flatspec.AnyFlatSpec

class MultiCuKernelDispatcherSpec extends AnyFlatSpec {
  behavior of "MultiCuKernelDispatcher"

  it should "run tagged kernels concurrently and preserve completion identity" in {
    val config = GpuConfig(lanes = 4, warps = 2)
    simulate(new MultiCuKernelDispatcher(config, 2, 8)) { dut =>
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      dut.io.launch.valid.poke(false.B)
      dut.io.completion.ready.poke(false.B)
      (0 until 2).foreach { cu =>
        dut.io.cuLaunch(cu).ready.poke(true.B)
        dut.io.cuCompletion(cu).valid.poke(false.B)
        dut.io.cuCompletion(cu).bits.success.poke(true.B)
      }

      def launch(id: Int, pc: Int): Unit = {
        dut.io.launch.valid.poke(true.B)
        dut.io.launch.bits.commandId.poke(id.U)
        dut.io.launch.bits.launch.kernelPc.poke(pc.U)
        dut.io.launch.bits.launch.kernargAddress.poke(0.U)
        (0 until 3).foreach { dimension =>
          dut.io.launch.bits.launch.gridSize(dimension).poke(1.U)
          dut.io.launch.bits.launch.localSize(dimension).poke(1.U)
        }
        dut.io.launch.ready.expect(true.B)
        dut.clock.step()
        dut.io.launch.valid.poke(false.B)
      }

      launch(0x31, 0x1000)
      launch(0x52, 0x2000)
      dut.io.busy.expect("b11".U)
      dut.io.launch.ready.expect(false.B)

      // CU1 completes first; output backpressure must retain its tag.
      dut.io.cuCompletion(1).valid.poke(true.B)
      dut.clock.step(2)
      dut.io.completion.valid.expect(true.B)
      dut.io.completion.bits.commandId.expect(0x52.U)
      dut.io.busy.expect("b11".U)
      dut.io.completion.ready.poke(true.B)
      dut.clock.step()
      dut.io.cuCompletion(1).valid.poke(false.B)
      dut.io.busy.expect("b01".U)

      dut.io.cuCompletion(0).valid.poke(true.B)
      dut.io.completion.valid.expect(true.B)
      dut.io.completion.bits.commandId.expect(0x31.U)
      dut.clock.step()
      dut.io.cuCompletion(0).valid.poke(false.B)
      dut.io.busy.expect(0.U)
    }
  }
}
