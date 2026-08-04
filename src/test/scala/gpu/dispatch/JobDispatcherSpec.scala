package gpu.dispatch

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import gpu.config.GpuConfig
import org.scalatest.flatspec.AnyFlatSpec

class JobDispatcherSpec extends AnyFlatSpec {
  private val config = GpuConfig(lanes = 8, warps = 4)

  behavior of "JobDispatcher"

  it should "walk a 3D grid only after each workgroup completes" in {
    simulate(new JobDispatcher(config)) { dut =>
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      dut.io.launch.valid.poke(false.B)
      dut.io.workgroup.ready.poke(true.B)
      dut.io.workgroupCompletion.valid.poke(false.B)
      dut.io.completion.ready.poke(true.B)

      dut.io.launch.valid.poke(true.B)
      dut.io.launch.bits.kernelPc.poke(0x1000.U)
      dut.io.launch.bits.kernargAddress.poke(0x8000.U)
      Seq(2, 2, 1).zipWithIndex.foreach { case (v, i) =>
        dut.io.launch.bits.gridSize(i).poke(v.U)
      }
      Seq(17, 1, 1).zipWithIndex.foreach { case (v, i) =>
        dut.io.launch.bits.localSize(i).poke(v.U)
      }
      dut.clock.step(); dut.io.launch.valid.poke(false.B)

      val expected = Seq((0, 0, 0), (1, 0, 0), (0, 1, 0), (1, 1, 0))
      expected.foreach { case (x, y, z) =>
        dut.io.workgroup.valid.expect(true.B)
        dut.io.workgroup.bits.groupId(0).expect(x.U)
        dut.io.workgroup.bits.groupId(1).expect(y.U)
        dut.io.workgroup.bits.groupId(2).expect(z.U)
        dut.clock.step()
        dut.io.workgroup.valid.expect(false.B)
        dut.io.workgroupCompletion.valid.poke(true.B)
        dut.io.workgroupCompletion.bits.success.poke(true.B)
        dut.clock.step()
        dut.io.workgroupCompletion.valid.poke(false.B)
      }
      dut.io.completion.valid.expect(true.B)
      dut.io.completion.bits.success.expect(true.B)
    }
  }

  it should "reject an empty grid without underflowing its counters" in {
    simulate(new JobDispatcher(config)) { dut =>
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      dut.io.workgroup.ready.poke(true.B)
      dut.io.workgroupCompletion.valid.poke(false.B)
      dut.io.completion.ready.poke(false.B)
      dut.io.launch.valid.poke(true.B)
      dut.io.launch.bits.kernelPc.poke(0.U)
      dut.io.launch.bits.kernargAddress.poke(0.U)
      Seq(0, 2, 1).zipWithIndex.foreach { case (v, i) =>
        dut.io.launch.bits.gridSize(i).poke(v.U)
        dut.io.launch.bits.localSize(i).poke(1.U)
      }
      dut.clock.step(); dut.io.launch.valid.poke(false.B)
      dut.io.workgroup.valid.expect(false.B)
      dut.io.completion.valid.expect(true.B)
      dut.io.completion.bits.success.expect(false.B)
    }
  }
}
