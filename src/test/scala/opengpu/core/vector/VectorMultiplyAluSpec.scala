package opengpu.core.vector

import chisel3._
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class VectorMultiplyAluSpec extends AnyFlatSpec {
  behavior of "VectorMultiplyAlu"

  private val config = GpuConfig(lanes = 4, warps = 2)

  private def defaults(dut: VectorMultiplyAlu): Unit = {
    dut.io.in.valid.poke(false.B)
    dut.io.out.ready.poke(true.B)
    dut.io.in.bits.warpId.poke(1.U)
    dut.io.in.bits.vd.poke(7.U)
    dut.io.in.bits.activeMask.poke("b1111".U)
    dut.io.in.bits.predicateMask.poke("b1111".U)
    dut.io.in.bits.scalar.poke(0.U)
    dut.io.in.bits.funct6.poke("h25".U)
    dut.io.in.bits.operandType.poke("b010".U)
    dut.io.in.bits.vm.poke(true.B)
    dut.io.in.bits.vxrm.poke(0.U)
    for (lane <- 0 until config.lanes) {
      dut.io.in.bits.oldVd(lane).poke((0x100 + lane).U)
      dut.io.in.bits.vs1(lane).poke(0.U)
      dut.io.in.bits.vs2(lane).poke(0.U)
    }
  }

  it should "round and saturate signed fractional vsmul results" in {
    simulate(new VectorMultiplyAlu(config)) { dut =>
      dut.reset.poke(true.B)
      defaults(dut)
      dut.clock.step()
      dut.reset.poke(false.B)

      // 1 * 2^30 represents exactly 0.5 after the fractional right shift.
      defaults(dut)
      dut.io.in.valid.poke(true.B)
      dut.io.in.bits.funct6.poke("h27".U)
      dut.io.in.bits.operandType.poke("b000".U)
      dut.io.in.bits.vxrm.poke("b00".U) // RNU
      for (lane <- 0 until config.lanes) {
        dut.io.in.bits.vs2(lane).poke(1.U)
        dut.io.in.bits.vs1(lane).poke("h40000000".U)
      }
      dut.clock.step()
      dut.io.in.valid.poke(false.B)
      waitForResult(dut)
      dut.io.out.bits.data(0).expect(1.U)
      dut.io.out.bits.saturated.expect(false.B)
      dut.clock.step()

      // The one overflowing fractional product saturates to INT_MAX.
      defaults(dut)
      dut.io.in.valid.poke(true.B)
      dut.io.in.bits.funct6.poke("h27".U)
      dut.io.in.bits.operandType.poke("b100".U)
      dut.io.in.bits.scalar.poke("h80000000".U)
      for (lane <- 0 until config.lanes) {
        dut.io.in.bits.vs2(lane).poke("h80000000".U)
      }
      dut.clock.step()
      dut.io.in.valid.poke(false.B)
      waitForResult(dut)
      dut.io.out.bits.data(0).expect("h7fffffff".U)
      dut.io.out.bits.saturated.expect(true.B)
    }
  }

  // vmacc/vnmsac accumulate into the destination, vmadd/vnmsub into vs2. Only
  // the low half of the product is visible, so lane 0 is the signed case:
  // 0x40000000 * 0x40000000 has the same low word either way.
  it should "add and subtract the low product into the accumulate addend" in {
    simulate(new VectorMultiplyAlu(config)) { dut =>
      dut.reset.poke(true.B)
      defaults(dut)
      dut.clock.step()
      dut.reset.poke(false.B)

      def run(funct6: Int, form: Int, expected: Seq[BigInt]): Unit = {
        defaults(dut)
        dut.io.in.valid.poke(true.B)
        dut.io.in.bits.funct6.poke(funct6.U)
        dut.io.in.bits.operandType.poke(form.U)
        for (lane <- 0 until config.lanes) {
          // old vd = 0x100 + lane, vs1 = 4, vs2 = 8.
          dut.io.in.bits.oldVd(lane).poke((0x100 + lane).U)
          dut.io.in.bits.vs1(lane).poke(4.U)
          dut.io.in.bits.vs2(lane).poke(8.U)
        }
        dut.clock.step()
        dut.io.in.valid.poke(false.B)
        waitForResult(dut)
        expected.zipWithIndex.foreach { case (value, lane) =>
          dut.io.out.bits.data(lane).expect(value.U)
        }
        dut.io.out.bits.saturated.expect(false.B)
        dut.clock.step()
      }

      // vmacc/vnmsac accumulate into the destination, 4*8 = 32 either way.
      run(0x2d, 0b010, Seq(0x120, 0x121, 0x122, 0x123))
      run(0x2f, 0b010, Seq(0x0e0, 0x0e1, 0x0e2, 0x0e3))
      // vmadd/vnmsub accumulate into vs2 with the destination as the
      // multiplicand, so lane 0 adds 8 + 4*0x100 and subtracts it again.
      run(0x29, 0b010, Seq(0x408, 0x40c, 0x410, 0x414))
      run(0x2b, 0b010, Seq(BigInt("fffffc08", 16), BigInt("fffffc04", 16),
        BigInt("fffffc00", 16), BigInt("fffffbfc", 16)))

      // The vector-scalar forms take the scalar in place of vs1.
      defaults(dut)
      dut.io.in.valid.poke(true.B)
      dut.io.in.bits.funct6.poke("h2d".U)
      dut.io.in.bits.operandType.poke("b110".U)
      dut.io.in.bits.scalar.poke(4.U)
      for (lane <- 0 until config.lanes) {
        dut.io.in.bits.oldVd(lane).poke((0x100 + lane).U)
        dut.io.in.bits.vs2(lane).poke(8.U)
      }
      dut.clock.step()
      dut.io.in.valid.poke(false.B)
      waitForResult(dut)
      dut.io.out.bits.data(0).expect(0x120.U)
      dut.io.out.bits.data(3).expect(0x123.U)
      dut.clock.step()
    }
  }

  // The destination is a source of the multiply-accumulate family, so a
  // masked-off element keeps the old destination rather than a product.
  it should "leave masked accumulate elements at the old destination" in {
    simulate(new VectorMultiplyAlu(config)) { dut =>
      dut.reset.poke(true.B)
      defaults(dut)
      dut.clock.step()
      dut.reset.poke(false.B)

      dut.io.in.valid.poke(true.B)
      dut.io.in.bits.funct6.poke("h2d".U)
      dut.io.in.bits.operandType.poke("b010".U)
      dut.io.in.bits.vm.poke(false.B)
      dut.io.in.bits.predicateMask.poke("b0101".U)
      for (lane <- 0 until config.lanes) {
        dut.io.in.bits.oldVd(lane).poke((0x100 + lane).U)
        dut.io.in.bits.vs1(lane).poke(4.U)
        dut.io.in.bits.vs2(lane).poke(8.U)
      }
      dut.clock.step()
      dut.io.in.valid.poke(false.B)
      waitForResult(dut)
      dut.io.out.bits.data(0).expect(0x120.U)
      dut.io.out.bits.data(1).expect(0x101.U)
      dut.io.out.bits.data(2).expect(0x122.U)
      dut.io.out.bits.data(3).expect(0x103.U)
    }
  }

  // The low half is what the architecture keeps, so a signed product and an
  // unsigned one agree: 3 * 0x7fffffff has the low word 0x7ffffffd either way.
  it should "accumulate the low product for signed and unsigned operands" in {
    simulate(new VectorMultiplyAlu(config)) { dut =>
      dut.reset.poke(true.B)
      defaults(dut)
      dut.clock.step()
      dut.reset.poke(false.B)

      dut.io.in.valid.poke(true.B)
      dut.io.in.bits.funct6.poke("h2d".U)
      dut.io.in.bits.operandType.poke("b010".U)
      for (lane <- 0 until config.lanes) {
        dut.io.in.bits.oldVd(lane).poke(7.U)
        dut.io.in.bits.vs1(lane).poke(3.U)
        dut.io.in.bits.vs2(lane).poke("h7fffffff".U)
      }
      dut.clock.step()
      dut.io.in.valid.poke(false.B)
      waitForResult(dut)
      dut.io.out.bits.data(0).expect("h80000004".U)
      dut.clock.step()
    }
  }

  private def waitForResult(dut: VectorMultiplyAlu): Unit = {
    var cycles = 0
    while (!dut.io.out.valid.peek().litToBoolean && cycles < 7) {
      dut.clock.step()
      cycles += 1
    }
    assert(dut.io.out.valid.peek().litToBoolean)
  }

  it should "execute all RVV multiply signedness variants" in {
    simulate(new VectorMultiplyAlu(config)) { dut =>
      dut.reset.poke(true.B)
      defaults(dut)
      dut.clock.step()
      dut.reset.poke(false.B)

      val cases = Seq(
        // funct6, vs2, rhs, operand form, expected
        (0x25, 0xfffffffeL, 3L, 0x2, 0xfffffffaL), // vmul.vv
        (0x24, 0xffffffffL, 2L, 0x6, 0x00000001L), // vmulhu.vx
        (0x26, 0xfffffffeL, 0xffffffffL, 0x2, 0xfffffffeL), // vmulhsu
        (0x27, 0xfffffffeL, 3L, 0x6, 0xffffffffL) // vmulh.vx
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

  it should "preserve disabled lanes and hold its result under backpressure" in {
    simulate(new VectorMultiplyAlu(config)) { dut =>
      dut.reset.poke(true.B)
      defaults(dut)
      dut.clock.step()
      dut.reset.poke(false.B)

      defaults(dut)
      dut.io.out.ready.poke(false.B)
      dut.io.in.valid.poke(true.B)
      dut.io.in.bits.activeMask.poke("b1111".U)
      dut.io.in.bits.predicateMask.poke("b0101".U)
      dut.io.in.bits.vm.poke(false.B)
      for (lane <- 0 until config.lanes) {
        dut.io.in.bits.vs2(lane).poke((lane + 2).U)
        dut.io.in.bits.vs1(lane).poke(4.U)
      }
      dut.clock.step()
      dut.io.in.valid.poke(false.B)
      waitForResult(dut)

      dut.io.out.bits.data(0).expect(8.U)
      dut.io.out.bits.data(1).expect(0x101.U)
      dut.io.out.bits.data(2).expect(16.U)
      dut.io.out.bits.data(3).expect(0x103.U)
      dut.clock.step(3)
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.data(2).expect(16.U)
    }
  }
}
