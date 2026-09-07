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
    dut.io.in.bits.quad.poke(false.B)
    for (lane <- 0 until config.lanes) {
      dut.io.in.bits.oldVd(lane).poke((100 + lane).U)
      dut.io.in.bits.vs1(lane).poke(0.U)
      dut.io.in.bits.vs2(lane).poke(0.U)
      dut.io.in.bits.vs2Odd(lane).poke(0.U)
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

  it should "sign- and zero-extend fixed-profile 16-bit lanes" in {
    simulate(new VectorIntegerAlu(config)) { dut =>
      defaults(dut)
      dut.io.in.valid.poke(true.B)
      dut.io.in.bits.funct6.poke("h12".U)
      dut.io.in.bits.operandType.poke("b010".U)
      dut.io.in.bits.immediate.poke(7.U) // vsext.vf2
      dut.io.in.bits.vs2(0).poke("h00008001".U)
      dut.io.in.bits.vs2(1).poke("h00007fff".U)
      dut.io.in.bits.vs2(2).poke("hffff8000".U)
      dut.io.in.bits.vs2(3).poke("h12345678".U)
      dut.clock.step()
      dut.io.in.valid.poke(false.B)
      dut.clock.step(3)
      dut.io.out.bits.data(0).expect("hffff8001".U)
      dut.io.out.bits.data(1).expect("h00007fff".U)
      dut.io.out.bits.data(2).expect("hffff8000".U)
      dut.io.out.bits.data(3).expect("h00005678".U)

      dut.io.in.valid.poke(true.B)
      dut.io.in.bits.immediate.poke(6.U) // vzext.vf2
      dut.clock.step()
      dut.io.in.valid.poke(false.B)
      dut.clock.step(3)
      dut.io.out.bits.data(0).expect("h00008001".U)
      dut.io.out.bits.data(2).expect("h00008000".U)
      dut.io.out.bits.data(3).expect("h00005678".U)

      dut.io.in.valid.poke(true.B)
      dut.io.in.bits.immediate.poke(5.U) // vsext.vf4
      dut.io.in.bits.vs2(0).poke("h00000080".U)
      dut.io.in.bits.vs2(1).poke("h0000007f".U)
      dut.clock.step()
      dut.io.in.valid.poke(false.B)
      dut.clock.step(3)
      dut.io.out.bits.data(0).expect("hffffff80".U)
      dut.io.out.bits.data(1).expect("h0000007f".U)

      dut.io.in.valid.poke(true.B)
      dut.io.in.bits.immediate.poke(2.U) // vzext.vf8
      dut.io.in.bits.vs2(0).poke("h0000000f".U)
      dut.clock.step()
      dut.io.in.valid.poke(false.B)
      dut.clock.step(3)
      dut.io.out.bits.data(0).expect("h0000000f".U)
    }
  }

  it should "mask every integer extension scale and hold results under backpressure" in {
    simulate(new VectorIntegerAlu(config)) { dut =>
      defaults(dut)
      val inputs = Seq(BigInt("abcd8008", 16), BigInt("ffff7f77", 16),
        BigInt("1234ffff", 16), BigInt("ffff8080", 16))
      dut.io.in.bits.funct6.poke("h12".U)
      dut.io.in.bits.operandType.poke(2.U)
      dut.io.in.bits.vm.poke(false.B)
      for (selector <- 2 to 7; mask <- Seq(0, 5, 15)) {
        dut.io.in.bits.immediate.poke(selector.U)
        dut.io.in.bits.activeMask.poke(7.U)
        dut.io.in.bits.predicateMask.poke(mask.U)
        inputs.zipWithIndex.foreach { case (value, lane) =>
          dut.io.in.bits.vs2(lane).poke(value.U)
        }
        dut.io.out.ready.poke(false.B)
        dut.io.in.valid.poke(true.B)
        dut.io.in.ready.expect(true.B)
        dut.clock.step()
        dut.io.in.valid.poke(false.B)
        dut.clock.step(3)
        val width = 1 << (selector / 2 + 1)
        val expected = inputs.zipWithIndex.map { case (value, lane) =>
          val low = value & ((BigInt(1) << width) - 1)
          val extended = if (selector % 2 == 1 && low.testBit(width - 1))
            low - (BigInt(1) << width) else low
          if (((mask & 7) & (1 << lane)) != 0)
            extended & BigInt("ffffffff", 16) else BigInt(100 + lane)
        }
        for (_ <- 0 until 3) {
          dut.io.out.valid.expect(true.B)
          dut.io.out.bits.writesMask.expect(false.B)
          dut.io.out.bits.saturated.expect(false.B)
          expected.zipWithIndex.foreach { case (value, lane) =>
            dut.io.out.bits.data(lane).expect(value.U)
          }
          dut.clock.step()
        }
        dut.io.out.ready.poke(true.B)
        dut.clock.step()
        dut.io.out.valid.expect(false.B)
      }
    }
  }

  it should "narrow 64-bit register pairs with logical and arithmetic shifts" in {
    simulate(new VectorIntegerAlu(config)) { dut =>
      defaults(dut)
      val lows = Seq(BigInt("80000000", 16), BigInt("12345678", 16),
        BigInt("ffffffff", 16), BigInt(0))
      val highs = Seq(BigInt("00000001", 16), BigInt("deadbeef", 16),
        BigInt("ffffffff", 16), BigInt("7fffffff", 16))

      def issue(
        funct6: Int,
        form: Int,
        shiftAmounts: Seq[Long],
        masked: Boolean = false,
        predicate: Int = 0xf,
        stall: Boolean = false
      ): Unit = {
        dut.io.in.bits.funct6.poke(funct6.U)
        dut.io.in.bits.operandType.poke(form.U)
        dut.io.in.bits.vm.poke((!masked).B)
        dut.io.in.bits.predicateMask.poke(predicate.U)
        dut.io.in.bits.scalar.poke(shiftAmounts(0).U)
        dut.io.in.bits.immediate.poke((shiftAmounts(0) & 31).U)
        lows.zip(highs).zipWithIndex.foreach { case ((low, high), lane) =>
          dut.io.in.bits.vs2(lane).poke(low.U)
          dut.io.in.bits.vs2Odd(lane).poke(high.U)
          dut.io.in.bits.vs1(lane).poke(shiftAmounts(lane).U)
        }
        if (stall) dut.io.out.ready.poke(false.B)
        dut.io.in.valid.poke(true.B)
        dut.clock.step()
        dut.io.in.valid.poke(false.B)
        dut.clock.step(3)
        val expected = lows.zip(highs).zipWithIndex.map {
          case ((low, high), lane) =>
            val wide = (high << 32) | low
            val narrowed = funct6 match {
              case 0x2c =>
                (wide >> (shiftAmounts(lane) & 63).toInt) &
                  BigInt("ffffffff", 16)
              case _ =>
                val signed = wide - (if (wide.testBit(63)) BigInt(1) << 64
                                     else BigInt(0))
                (signed >> (shiftAmounts(lane) & 63).toInt) &
                  BigInt("ffffffff", 16)
            }
            if (masked && (predicate & (1 << lane)) == 0) BigInt(100 + lane)
            else narrowed
        }
        val checks = if (stall) 3 else 1
        for (_ <- 0 until checks) {
          dut.io.out.valid.expect(true.B)
          dut.io.out.bits.writesMask.expect(false.B)
          expected.zipWithIndex.foreach { case (value, lane) =>
            dut.io.out.bits.data(lane).expect(value.U)
          }
          dut.clock.step()
        }
        if (stall) {
          dut.io.out.ready.poke(true.B)
          dut.clock.step()
          dut.io.out.valid.expect(false.B)
        }
      }

      // vnsrl.wv: per-lane shift amounts, including 0 and 31.
      issue(0x2c, 0, Seq(1, 16, 0, 31))
      // vnsra.wv: the 0xdeadbeef pair and both sign-extreme pairs.
      issue(0x2d, 0, Seq(1, 16, 31, 5))
      issue(0x2c, 0, Seq(32, 33, 63, 64))
      issue(0x2d, 0, Seq(63, 32, 65, 127))
      issue(0x2c, 4, Seq.fill(4)(63L))
      issue(0x2d, 4, Seq.fill(4)(63L))
      // vnsrl.wx/vnsra.wx: one scalar shift amount for every lane.
      issue(0x2c, 4, Seq(4, 4, 4, 4))
      issue(0x2d, 4, Seq(31, 31, 31, 31))
      // vnsrl.wi/vnsra.wi: the immediate supplies the shift amount.
      issue(0x2c, 3, Seq(31, 31, 31, 31))
      issue(0x2d, 3, Seq(0, 0, 0, 0))
      // Masked lanes keep their old destination under output backpressure.
      issue(0x2c, 0, Seq(8, 8, 8, 8), masked = true, predicate = 5,
        stall = true)
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
      dut.io.in.bits.quad.poke(true.B)
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

  it should "gather vector elements with vv, vx, and vi indices" in {
    simulate(new VectorIntegerAlu(config)) { dut =>
      defaults(dut)
      Seq(11, 22, 33, 44).zipWithIndex.foreach { case (value, lane) =>
        dut.io.in.bits.vs2(lane).poke(value.U)
      }

      dut.io.in.bits.funct6.poke("h0c".U)
      dut.io.in.bits.operandType.poke("b000".U)
      Seq(3, 0, 4, 1).zipWithIndex.foreach { case (index, lane) =>
        dut.io.in.bits.vs1(lane).poke(index.U)
      }
      dut.io.in.valid.poke(true.B)
      dut.clock.step()
      dut.io.in.valid.poke(false.B)
      dut.clock.step(3)
      Seq(44, 11, 0, 22).zipWithIndex.foreach { case (value, lane) =>
        dut.io.out.bits.data(lane).expect(value.U)
      }

      dut.io.in.bits.operandType.poke("b100".U)
      dut.io.in.bits.scalar.poke(2.U)
      dut.io.in.valid.poke(true.B)
      dut.clock.step()
      dut.io.in.valid.poke(false.B)
      dut.clock.step(3)
      for (lane <- 0 until config.lanes)
        dut.io.out.bits.data(lane).expect(33.U)

      dut.io.in.bits.operandType.poke("b011".U)
      dut.io.in.bits.immediate.poke(1.U)
      dut.io.in.bits.activeMask.poke("b0011".U)
      dut.io.in.valid.poke(true.B)
      dut.clock.step()
      dut.io.in.valid.poke(false.B)
      dut.clock.step(3)
      dut.io.out.bits.data(0).expect(22.U)
      dut.io.out.bits.data(1).expect(22.U)
      dut.io.out.bits.data(2).expect(102.U)
      dut.io.out.bits.data(3).expect(103.U)
    }
  }

  it should "slide elements up and down with scalar and immediate offsets" in {
    simulate(new VectorIntegerAlu(config)) { dut =>
      defaults(dut)
      Seq(11, 22, 33, 44).zipWithIndex.foreach { case (value, lane) =>
        dut.io.in.bits.vs2(lane).poke(value.U)
      }

      dut.io.in.bits.funct6.poke("h0e".U)
      dut.io.in.bits.operandType.poke("b011".U)
      dut.io.in.bits.immediate.poke(1.U)
      dut.io.in.valid.poke(true.B)
      dut.clock.step()
      dut.io.in.valid.poke(false.B)
      dut.clock.step(3)
      Seq(100, 11, 22, 33).zipWithIndex.foreach { case (value, lane) =>
        dut.io.out.bits.data(lane).expect(value.U)
      }

      dut.io.in.bits.operandType.poke("b100".U)
      dut.io.in.bits.scalar.poke(2.U)
      dut.io.in.valid.poke(true.B)
      dut.clock.step()
      dut.io.in.valid.poke(false.B)
      dut.clock.step(3)
      Seq(100, 101, 11, 22).zipWithIndex.foreach { case (value, lane) =>
        dut.io.out.bits.data(lane).expect(value.U)
      }

      dut.io.in.bits.funct6.poke("h0f".U)
      dut.io.in.bits.operandType.poke("b011".U)
      dut.io.in.bits.immediate.poke(1.U)
      dut.io.in.valid.poke(true.B)
      dut.clock.step()
      dut.io.in.valid.poke(false.B)
      dut.clock.step(3)
      Seq(22, 33, 44, 0).zipWithIndex.foreach { case (value, lane) =>
        dut.io.out.bits.data(lane).expect(value.U)
      }

      dut.io.in.bits.operandType.poke("b100".U)
      dut.io.in.bits.scalar.poke(4.U)
      dut.io.in.valid.poke(true.B)
      dut.clock.step()
      dut.io.in.valid.poke(false.B)
      dut.clock.step(3)
      for (lane <- 0 until config.lanes)
        dut.io.out.bits.data(lane).expect(0.U)
    }
  }

  it should "reduce active elements into destination element zero" in {
    simulate(new VectorIntegerAlu(config)) { dut =>
      defaults(dut)

      def issue(
        funct6: Int,
        initial: Long,
        values: Seq[Long],
        expected: Long,
        vm: Boolean = true,
        predicate: Int = 0xf
      ): Unit = {
        dut.io.in.bits.funct6.poke(funct6.U)
        dut.io.in.bits.operandType.poke("b010".U)
        dut.io.in.bits.vs1(0).poke(initial.U)
        values.zipWithIndex.foreach { case (value, lane) =>
          dut.io.in.bits.vs2(lane).poke(value.U)
        }
        dut.io.in.bits.vm.poke(vm.B)
        dut.io.in.bits.predicateMask.poke(predicate.U)
        dut.io.in.valid.poke(true.B)
        dut.clock.step()
        dut.io.in.valid.poke(false.B)
        dut.clock.step(3)
        dut.io.out.bits.data(0).expect(expected.U)
        for (lane <- 1 until config.lanes)
          dut.io.out.bits.data(lane).expect((100 + lane).U)
      }

      issue(0x00, 10, Seq(1, 2, 3, 4), 20)
      issue(0x01, 0xff, Seq(0xf0, 0xcc, 0xaa, 0x0f), 0)
      issue(0x02, 0x10, Seq(1, 2, 4, 8), 0x1f)
      issue(0x03, 0x0f, Seq(1, 2, 4, 8), 0)
      issue(0x04, 10, Seq(7, 20, 3, 9), 3)
      issue(0x05, 0, Seq(0xfffffffeL, 5, 0xfffffffbL, 1), 0xfffffffbL)
      issue(0x06, 10, Seq(7, 20, 3, 9), 20)
      issue(0x07, 0, Seq(0xfffffffeL, 5, 0xfffffffbL, 1), 5)

      // The scalar seed is always included, even if every vector lane is masked.
      issue(0x00, 10, Seq(1, 2, 3, 4), 10, vm = false, predicate = 0)
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
