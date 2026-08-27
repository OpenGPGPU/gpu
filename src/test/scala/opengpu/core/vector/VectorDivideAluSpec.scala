package opengpu.core.vector

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import opengpu.config.GpuConfig
import org.scalatest.flatspec.AnyFlatSpec

class VectorDivideAluSpec extends AnyFlatSpec {
  behavior of "VectorDivideAlu"

  private val config = GpuConfig(lanes = 4, warps = 2)

  private def defaults(dut: VectorDivideAlu): Unit = {
    dut.io.in.valid.poke(false.B)
    dut.io.out.ready.poke(true.B)
    dut.io.in.bits.warpId.poke(1.U)
    dut.io.in.bits.pc.poke("h200".U)
    dut.io.in.bits.warpActiveMask.poke("b1111".U)
    dut.io.in.bits.vd.poke(7.U)
    dut.io.in.bits.activeMask.poke("b1111".U)
    dut.io.in.bits.predicateMask.poke("b1111".U)
    dut.io.in.bits.scalar.poke(0.U)
    dut.io.in.bits.immediate.poke(0.U)
    dut.io.in.bits.funct6.poke("h20".U)
    dut.io.in.bits.operandType.poke("b010".U)
    dut.io.in.bits.vm.poke(true.B)
    for (lane <- 0 until config.lanes) {
      dut.io.in.bits.oldVd(lane).poke((0x100 + lane).U)
      dut.io.in.bits.vs1(lane).poke(0.U)
      dut.io.in.bits.vs2(lane).poke(0.U)
    }
  }

  private def waitForResult(dut: VectorDivideAlu): Unit = {
    var cycles = 0
    while (!dut.io.out.valid.peek().litToBoolean && cycles < 40) {
      dut.clock.step()
      cycles += 1
    }
    assert(dut.io.out.valid.peek().litToBoolean)
  }

  it should "execute unsigned and signed divide variants" in {
    simulate(new VectorDivideAlu(config)) { dut =>
      dut.reset.poke(true.B)
      defaults(dut)
      dut.clock.step()
      dut.reset.poke(false.B)

      val cases = Seq(
        // funct6, vs2, rhs, operand form, expected
        (0x20, 0xfffffff0L, 4L, 0x2, 0x3ffffffcL), // vdivu.vv
        (0x21, 0xfffffff9L, 3L, 0x6, 0xfffffffeL), // vdiv.vx (-7/3)
        (0x22, 7L, 3L, 0x2, 1L),                   // vremu.vv
        (0x23, 0xfffffff9L, 3L, 0x6, 0xffffffffL)  // vrem.vx (-7%3)
      )

      for ((funct6, lhs, rhs, form, expected) <- cases) {
        defaults(dut)
        dut.io.in.valid.poke(true.B)
        dut.io.in.bits.funct6.poke(funct6.U)
        dut.io.in.bits.operandType.poke(form.U)
        dut.io.in.bits.scalar.poke(rhs.U)
        for (lane <- 0 until config.lanes) {
          dut.io.in.bits.vs2(lane).poke(lhs.U)
          dut.io.in.bits.vs1(lane).poke(rhs.U)
        }
        dut.io.in.ready.expect(true.B)
        dut.clock.step()
        dut.io.in.valid.poke(false.B)
        waitForResult(dut)
        for (lane <- 0 until config.lanes) {
          dut.io.out.bits.data(lane).expect(expected.U)
        }
        dut.io.out.bits.warpId.expect(1.U)
        dut.io.out.bits.vd.expect(7.U)
        dut.clock.step()
      }
    }
  }

  it should "follow RVV divide-by-zero and signed-overflow results" in {
    simulate(new VectorDivideAlu(config)) { dut =>
      dut.reset.poke(true.B)
      defaults(dut)
      dut.clock.step()
      dut.reset.poke(false.B)

      defaults(dut)
      dut.io.in.valid.poke(true.B)
      dut.io.in.bits.funct6.poke("h20".U) // vdivu
      for (lane <- 0 until config.lanes) {
        dut.io.in.bits.vs2(lane).poke(7.U)
      }
      dut.clock.step()
      dut.io.in.valid.poke(false.B)
      waitForResult(dut)
      for (lane <- 0 until config.lanes) {
        dut.io.out.bits.data(lane).expect("hffffffff".U)
      }
      dut.clock.step()

      defaults(dut)
      dut.io.in.valid.poke(true.B)
      dut.io.in.bits.funct6.poke("h22".U) // vremu by zero
      for (lane <- 0 until config.lanes) {
        dut.io.in.bits.vs2(lane).poke(7.U)
      }
      dut.clock.step()
      dut.io.in.valid.poke(false.B)
      waitForResult(dut)
      for (lane <- 0 until config.lanes) {
        dut.io.out.bits.data(lane).expect(7.U)
      }
      dut.clock.step()

      defaults(dut)
      dut.io.in.valid.poke(true.B)
      dut.io.in.bits.funct6.poke("h21".U) // vdiv INT_MIN / -1
      dut.io.in.bits.operandType.poke("b110".U)
      dut.io.in.bits.scalar.poke("hffffffff".U)
      for (lane <- 0 until config.lanes) {
        dut.io.in.bits.vs2(lane).poke("h80000000".U)
      }
      dut.clock.step()
      dut.io.in.valid.poke(false.B)
      waitForResult(dut)
      for (lane <- 0 until config.lanes) {
        dut.io.out.bits.data(lane).expect("h80000000".U)
      }
    }
  }

  it should "preserve masked-off lanes" in {
    simulate(new VectorDivideAlu(config)) { dut =>
      dut.reset.poke(true.B)
      defaults(dut)
      dut.clock.step()
      dut.reset.poke(false.B)

      defaults(dut)
      dut.io.in.valid.poke(true.B)
      dut.io.in.bits.activeMask.poke("b1111".U)
      dut.io.in.bits.predicateMask.poke("b0101".U)
      dut.io.in.bits.vm.poke(false.B)
      for (lane <- 0 until config.lanes) {
        dut.io.in.bits.vs2(lane).poke((10 + lane).U)
        dut.io.in.bits.vs1(lane).poke(5.U)
      }
      dut.clock.step()
      dut.io.in.valid.poke(false.B)
      waitForResult(dut)
      dut.io.out.bits.data(0).expect(2.U)
      dut.io.out.bits.data(1).expect(0x101.U)
      dut.io.out.bits.data(2).expect(2.U)
      dut.io.out.bits.data(3).expect(0x103.U)
    }
  }
}
