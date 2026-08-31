package opengpu.core.vector

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import opengpu.config.GpuConfig
import org.scalatest.flatspec.AnyFlatSpec

class VectorIntegerAluSpec extends AnyFlatSpec {
  behavior of "VectorIntegerAlu"

  private val config = GpuConfig(lanes = 4, warps = 2)

  private def defaults(dut: VectorIntegerAlu): Unit = {
    dut.reset.poke(true.B)
    dut.clock.step()
    dut.reset.poke(false.B)
    dut.io.in.valid.poke(false.B)
    dut.io.out.ready.poke(true.B)
    dut.io.in.bits.warpId.poke(0.U)
    dut.io.in.bits.vd.poke(3.U)
    dut.io.in.bits.activeMask.poke("b1111".U)
    dut.io.in.bits.predicateMask.poke("b1111".U)
    dut.io.in.bits.scalar.poke(0.U)
    dut.io.in.bits.immediate.poke(0.U)
    dut.io.in.bits.funct6.poke(0.U)
    dut.io.in.bits.operandType.poke(0.U)
    dut.io.in.bits.vm.poke(true.B)
    for (lane <- 0 until config.lanes) {
      dut.io.in.bits.oldVd(lane).poke((100 + lane).U)
      dut.io.in.bits.vs1(lane).poke(0.U)
      dut.io.in.bits.vs2(lane).poke(0.U)
    }
  }

  it should "execute vv, vx, and signed vi operations per lane" in {
    simulate(new VectorIntegerAlu(config)) { dut =>
      defaults(dut)
      dut.io.in.valid.poke(true.B)
      dut.io.in.bits.funct6.poke("h00".U)
      for (lane <- 0 until config.lanes) {
        dut.io.in.bits.vs2(lane).poke((10 + lane).U)
        dut.io.in.bits.vs1(lane).poke((lane + 1).U)
      }
      dut.clock.step()
      dut.io.in.valid.poke(false.B)
      dut.clock.step(3)
      for (lane <- 0 until config.lanes) {
        dut.io.out.bits.data(lane).expect((11 + 2 * lane).U)
      }

      dut.io.in.valid.poke(true.B)
      dut.io.in.bits.funct6.poke("h02".U)
      dut.io.in.bits.operandType.poke("b100".U)
      dut.io.in.bits.scalar.poke(3.U)
      dut.clock.step()
      dut.io.in.valid.poke(false.B)
      dut.clock.step(3)
      for (lane <- 0 until config.lanes) {
        dut.io.out.bits.data(lane).expect((7 + lane).U)
      }

      dut.io.in.valid.poke(true.B)
      dut.io.in.bits.funct6.poke("h00".U)
      dut.io.in.bits.operandType.poke("b011".U)
      dut.io.in.bits.immediate.poke("b11111".U)
      dut.clock.step()
      dut.io.in.valid.poke(false.B)
      dut.clock.step(3)
      for (lane <- 0 until config.lanes) {
        dut.io.out.bits.data(lane).expect((9 + lane).U)
      }
    }
  }

  it should "preserve inactive lanes and produce precise mask results" in {
    simulate(new VectorIntegerAlu(config)) { dut =>
      defaults(dut)
      dut.io.in.valid.poke(true.B)
      dut.io.in.bits.vm.poke(false.B)
      dut.io.in.bits.activeMask.poke("b0111".U)
      dut.io.in.bits.predicateMask.poke("b0101".U)
      dut.io.in.bits.funct6.poke("h18".U)
      for (lane <- 0 until config.lanes) {
        dut.io.in.bits.vs2(lane).poke(lane.U)
        dut.io.in.bits.vs1(lane).poke((if (lane == 2) 2 else 9).U)
      }
      dut.clock.step()
      dut.io.in.valid.poke(false.B)
      dut.clock.step(3)
      dut.io.out.bits.writesMask.expect(true.B)
      dut.io.out.bits.mask.expect("b0100".U)
      dut.io.out.bits.data(1).expect(101.U)
      dut.io.out.bits.data(3).expect(103.U)
    }
  }

  it should "replicate 2x2 quad derivatives across each row and column" in {
    simulate(new VectorIntegerAlu(config)) { dut =>
      defaults(dut)
      val values = Seq(10, 14, 21, 29) // TL, TR, BL, BR
      values.zipWithIndex.foreach { case (value, lane) =>
        dut.io.in.bits.vs2(lane).poke(value.U)
      }

      dut.io.in.bits.funct6.poke("h0c".U)
      dut.io.in.valid.poke(true.B)
      dut.clock.step()
      dut.io.in.valid.poke(false.B)
      dut.clock.step(3)
      Seq(4, 4, 8, 8).zipWithIndex.foreach { case (value, lane) =>
        dut.io.out.bits.data(lane).expect(value.U)
      }

      dut.io.in.bits.funct6.poke("h0d".U)
      dut.io.in.valid.poke(true.B)
      dut.clock.step()
      dut.io.in.valid.poke(false.B)
      dut.clock.step(3)
      Seq(11, 15, 11, 15).zipWithIndex.foreach { case (value, lane) =>
        dut.io.out.bits.data(lane).expect(value.U)
      }
    }
  }

  it should "saturate signed and unsigned operations and hold backpressure" in {
    simulate(new VectorIntegerAlu(config)) { dut =>
      defaults(dut)
      dut.io.in.valid.poke(true.B)
      dut.io.in.bits.funct6.poke("h20".U)
      dut.io.in.bits.operandType.poke("b100".U)
      dut.io.in.bits.scalar.poke(1.U)
      dut.io.in.bits.vs2(0).poke("hffffffff".U)
      dut.clock.step()
      dut.io.in.valid.poke(false.B)
      dut.io.out.ready.poke(false.B)
      dut.clock.step(3)
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.data(0).expect("hffffffff".U)
      dut.io.out.bits.saturated.expect(true.B)
      dut.clock.step(3)
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.data(0).expect("hffffffff".U)

      dut.io.out.ready.poke(true.B)
      dut.io.in.valid.poke(true.B)
      dut.io.in.bits.funct6.poke("h21".U)
      dut.io.in.bits.scalar.poke(1.U)
      dut.io.in.bits.vs2(0).poke("h7fffffff".U)
      dut.clock.step()
      dut.io.in.valid.poke(false.B)
      dut.clock.step(3)
      dut.io.out.bits.data(0).expect("h7fffffff".U)
      dut.io.out.bits.saturated.expect(true.B)

      dut.io.in.valid.poke(true.B)
      dut.io.in.bits.funct6.poke("h23".U)
      dut.io.in.bits.vs2(0).poke("h80000000".U)
      dut.clock.step()
      dut.io.in.valid.poke(false.B)
      dut.clock.step(3)
      dut.io.out.bits.data(0).expect("h80000000".U)
      dut.io.out.bits.saturated.expect(true.B)
    }
  }
}
