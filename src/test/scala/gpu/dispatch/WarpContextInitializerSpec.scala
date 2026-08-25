package gpu.dispatch

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import gpu.config.GpuConfig
import org.scalatest.flatspec.AnyFlatSpec

class WarpContextInitializerSpec extends AnyFlatSpec {
  behavior of "WarpContextInitializer"

  it should "initialize the selected warp completely before launch" in {
    val config = GpuConfig(lanes = 4, warps = 4)
    simulate(new WarpContextInitializer(config)) { dut =>
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      dut.io.activeWarps.poke("b0101".U) // warp 1 is the lowest free slot
      dut.io.task.valid.poke(true.B)
      dut.io.task.bits.kernelPc.poke(0x1000.U)
      dut.io.task.bits.kernargAddress.poke(0x8000.U)
      (0 until 3).foreach { i =>
        dut.io.task.bits.gridSize(i).poke(1.U)
        dut.io.task.bits.localSize(i).poke(Seq(9, 2, 1)(i).U)
        dut.io.task.bits.groupId(i).poke(Seq(3, 4, 5)(i).U)
      }
      dut.io.task.bits.localLinearBase.poke(8.U)
      dut.io.task.bits.activeMask.poke("b0011".U)
      dut.io.task.bits.firstWarp.poke(false.B)
      dut.io.task.bits.lastWarp.poke(true.B)
      dut.io.scalar.ready.poke(true.B)
      dut.io.vector.ready.poke(false.B)
      dut.io.launch.ready.poke(true.B)
      dut.clock.step(); dut.io.task.valid.poke(false.B)

      val values = Seq(0x8000, 3, 4, 5, 9, 2, 1, 8)
      values.zipWithIndex.foreach { case (value, index) =>
        dut.io.scalar.valid.expect(true.B)
        dut.io.scalar.bits.warpId.expect(1.U)
        dut.io.scalar.bits.rd.expect((index + 1).U)
        dut.io.scalar.bits.data.expect(value.U)
        dut.io.launch.valid.expect(false.B)
        dut.clock.step()
      }

      dut.io.vector.valid.expect(true.B)
      dut.io.vector.bits.warpId.expect(1.U)
      dut.io.vector.bits.vd.expect(1.U)
      (0 until config.lanes).foreach { lane =>
        dut.io.vector.bits.data(lane).expect((8 + lane).U)
      }
      dut.clock.step(2)
      dut.io.launch.valid.expect(false.B)
      dut.io.vector.ready.poke(true.B)
      dut.clock.step()
      dut.io.launch.valid.expect(true.B)
      dut.io.launch.bits.warpId.expect(1.U)
      dut.io.launch.bits.startPc.expect(0x1000.U)
      dut.io.launch.bits.activeMask.expect("b0011".U)
    }
  }

  it should "wait when no hardware warp is free" in {
    val config = GpuConfig(lanes = 4, warps = 2)
    simulate(new WarpContextInitializer(config)) { dut =>
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      dut.io.activeWarps.poke("b11".U)
      dut.io.task.valid.poke(true.B)
      dut.io.scalar.ready.poke(true.B)
      dut.io.vector.ready.poke(true.B)
      dut.io.launch.ready.poke(true.B)
      dut.io.task.ready.expect(false.B)
      dut.clock.step(2)
      dut.io.scalar.valid.expect(false.B)
      dut.io.launch.valid.expect(false.B)
    }
  }
}
