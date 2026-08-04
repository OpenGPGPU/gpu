package gpu.dispatch

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import gpu.config.GpuConfig
import org.scalatest.flatspec.AnyFlatSpec

class KernelDispatchPipelineSpec extends AnyFlatSpec {
  behavior of "KernelDispatchPipeline"

  it should "preserve grid order and wait for every warp completion" in {
    val config = GpuConfig(lanes = 4, warps = 2)
    simulate(new KernelDispatchPipeline(config)) { dut =>
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      dut.io.launch.valid.poke(false.B)
      dut.io.warp.ready.poke(true.B)
      dut.io.warpCompletion.valid.poke(false.B)
      dut.io.completion.ready.poke(true.B)

      dut.io.launch.valid.poke(true.B)
      dut.io.launch.bits.kernelPc.poke(0x400.U)
      dut.io.launch.bits.kernargAddress.poke(0x9000.U)
      Seq(2, 1, 1).zipWithIndex.foreach { case (v, i) =>
        dut.io.launch.bits.gridSize(i).poke(v.U)
      }
      Seq(5, 1, 1).zipWithIndex.foreach { case (v, i) =>
        dut.io.launch.bits.localSize(i).poke(v.U)
      }
      dut.clock.step(); dut.io.launch.valid.poke(false.B)

      val expected = Seq(
        (0, 0, 0xf, false), (0, 4, 0x1, true),
        (1, 0, 0xf, false), (1, 4, 0x1, true)
      )
      expected.foreach { case (groupX, base, mask, last) =>
        var waited = 0
        while (!dut.io.warp.valid.peek().litToBoolean && waited < 8) {
          dut.clock.step(); waited += 1
        }
        dut.io.warp.valid.expect(true.B)
        dut.io.warp.bits.groupId(0).expect(groupX.U)
        dut.io.warp.bits.localLinearBase.expect(base.U)
        dut.io.warp.bits.activeMask.expect(mask.U)
        dut.io.warp.bits.lastWarp.expect(last.B)
        dut.clock.step()
        dut.io.warpCompletion.valid.poke(true.B)
        dut.io.warpCompletion.bits.success.poke(true.B)
        dut.clock.step()
        dut.io.warpCompletion.valid.poke(false.B)
      }

      var waited = 0
      while (!dut.io.completion.valid.peek().litToBoolean && waited < 8) {
        dut.clock.step(); waited += 1
      }
      dut.io.completion.valid.expect(true.B)
      dut.io.completion.bits.success.expect(true.B)
    }
  }
}
