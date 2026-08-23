package gpu.core.execute.fpu

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec

class Fp32ExactUnitSpec extends AnyFlatSpec {
  behavior of "Fp32ExactUnit"

  private def run(request: Fp32Request => Unit): (BigInt, BigInt, BigInt) = {
    var tag = BigInt(0)
    var data = BigInt(0)
    var status = BigInt(0)
    simulate(new Fp32ExactUnit(tagWidth = 8)) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      dut.io.in.valid.poke(false.B)
      dut.io.in.bits.poke(0.U.asTypeOf(dut.io.in.bits))
      dut.io.out.ready.poke(true.B)

      request(dut.io.in.bits)
      dut.io.in.valid.poke(true.B)
      dut.io.in.ready.expect(true.B)
      dut.clock.step()
      dut.io.in.valid.poke(false.B)
      dut.clock.step()
      dut.clock.step()
      dut.clock.step()
      dut.clock.step()
      dut.io.out.valid.expect(true.B)
      tag = dut.io.out.bits.tag.peek().litValue
      data = dut.io.out.bits.result.peek().litValue
      status = dut.io.out.bits.status.peek().litValue
      assert(tag == 0x12)
    }
    (tag, data, status)
  }

  it should "implement the three RISC-V sign-injection variants" in {
    val sign = run { bits =>
      bits.operation.poke(Fp32Operation.sgnj)
      bits.exactFunction.poke(0.U)
      bits.operandA.poke("h3f800000".U)
      bits.operandB.poke("h80000000".U)
      bits.tag.poke(0x12.U)
    }
    assert(sign._2 == BigInt("bf800000", 16))
    assert(sign._3 == 0)

    val invert = run { bits =>
      bits.operation.poke(Fp32Operation.sgnj)
      bits.exactFunction.poke(1.U)
      bits.operandA.poke("h3f800000".U)
      bits.operandB.poke("h80000000".U)
      bits.tag.poke(0x12.U)
    }
    assert(invert._2 == BigInt("3f800000", 16))

    val xor = run { bits =>
      bits.operation.poke(Fp32Operation.sgnj)
      bits.exactFunction.poke(2.U)
      bits.operandA.poke("h3f800000".U)
      bits.operandB.poke("h80000000".U)
      bits.tag.poke(0x12.U)
    }
    assert(xor._2 == BigInt("bf800000", 16))
  }

  it should "select min and max by IEEE ordering" in {
    val min = run { bits =>
      bits.operation.poke(Fp32Operation.minmax)
      bits.exactFunction.poke(0.U)
      bits.operandA.poke("hbf800000".U) // -1.0
      bits.operandB.poke("h3f800000".U) // +1.0
      bits.tag.poke(0x12.U)
    }
    assert(min._2 == BigInt("bf800000", 16))

    val max = run { bits =>
      bits.operation.poke(Fp32Operation.minmax)
      bits.exactFunction.poke(1.U)
      bits.operandA.poke("hbf800000".U)
      bits.operandB.poke("h3f800000".U)
      bits.tag.poke(0x12.U)
    }
    assert(max._2 == BigInt("3f800000", 16))
  }

  it should "report canonical NaN and NV for NaN operands" in {
    val nan = run { bits =>
      bits.operation.poke(Fp32Operation.minmax)
      bits.exactFunction.poke(0.U)
      bits.operandA.poke("h7fc00000".U)
      bits.operandB.poke("h3f800000".U)
      bits.tag.poke(0x12.U)
    }
    assert(nan._2 == BigInt("7fc00000", 16))
    assert(nan._3 == BigInt("10", 16))
  }

  it should "implement feq, flt, and fle comparisons" in {
    val eq = run { bits =>
      bits.operation.poke(Fp32Operation.compare)
      bits.exactFunction.poke(0.U)
      bits.operandA.poke("h3f800000".U)
      bits.operandB.poke("h3f800000".U)
      bits.tag.poke(0x12.U)
    }
    assert(eq._2 == 1)

    val lt = run { bits =>
      bits.operation.poke(Fp32Operation.compare)
      bits.exactFunction.poke(1.U)
      bits.operandA.poke("hbf800000".U)
      bits.operandB.poke("h3f800000".U)
      bits.tag.poke(0x12.U)
    }
    assert(lt._2 == 1)

    val le = run { bits =>
      bits.operation.poke(Fp32Operation.compare)
      bits.exactFunction.poke(2.U)
      bits.operandA.poke("h3f800000".U)
      bits.operandB.poke("h3f800000".U)
      bits.tag.poke(0x12.U)
    }
    assert(le._2 == 1)
  }

  it should "return zero and NV for an unordered comparison" in {
    val unordered = run { bits =>
      bits.operation.poke(Fp32Operation.compare)
      bits.exactFunction.poke(0.U)
      bits.operandA.poke("h7fc00000".U)
      bits.operandB.poke("h3f800000".U)
      bits.tag.poke(0x12.U)
    }
    assert(unordered._2 == 0)
    assert(unordered._3 == BigInt("10", 16))
  }

  it should "classify infinity, negative zero, and quiet NaN" in {
    val inf = run { bits =>
      bits.operation.poke(Fp32Operation.classify)
      bits.operandA.poke("h7f800000".U)
      bits.tag.poke(0x12.U)
    }
    assert(inf._2 == BigInt("80", 16))

    val negZero = run { bits =>
      bits.operation.poke(Fp32Operation.classify)
      bits.operandA.poke("h80000000".U)
      bits.tag.poke(0x12.U)
    }
    assert(negZero._2 == BigInt("8", 16))

    val qnan = run { bits =>
      bits.operation.poke(Fp32Operation.classify)
      bits.operandA.poke("h7fc00000".U)
      bits.tag.poke(0x12.U)
    }
    assert(qnan._2 == BigInt("200", 16))
  }

  it should "move FP bits unchanged to the integer side" in {
    val moved = run { bits =>
      bits.operation.poke(Fp32Operation.fmvX)
      bits.operandA.poke("hdeadbeef".U)
      bits.tag.poke(0x12.U)
    }
    assert(moved._2 == BigInt("deadbeef", 16))
    assert(moved._3 == 0)
  }

  it should "move scalar bits unchanged into the FP side" in {
    val moved = run { bits =>
      bits.operation.poke(Fp32Operation.fmvFromX)
      bits.operandA.poke("hcafebabe".U)
      bits.tag.poke(0x12.U)
    }
    assert(moved._2 == BigInt("cafebabe", 16))
    assert(moved._3 == 0)
  }

  it should "convert FP values to signed and unsigned integers" in {
    val toSigned = run { bits =>
      bits.operation.poke(Fp32Operation.fpToInt)
      bits.exactFunction.poke(0.U)
      bits.operandA.poke("h3f800000".U) // 1.0
      bits.tag.poke(0x12.U)
    }
    assert(toSigned._2 == 1)
    assert(toSigned._3 == 0)

    val negative = run { bits =>
      bits.operation.poke(Fp32Operation.fpToInt)
      bits.exactFunction.poke(0.U)
      bits.operandA.poke("hbf800000".U) // -1.0
      bits.tag.poke(0x12.U)
    }
    assert(negative._2 == BigInt("ffffffff", 16))

    val toUnsigned = run { bits =>
      bits.operation.poke(Fp32Operation.fpToInt)
      bits.exactFunction.poke(1.U)
      bits.operandA.poke("h40000000".U) // 2.0
      bits.tag.poke(0x12.U)
    }
    assert(toUnsigned._2 == 2)
  }

  it should "convert integers to FP32 values" in {
    val toFp = run { bits =>
      bits.operation.poke(Fp32Operation.intToFp)
      bits.exactFunction.poke(0.U)
      bits.operandA.poke(5.U)
      bits.tag.poke(0x12.U)
    }
    assert(toFp._2 == BigInt("40a00000", 16))
    assert(toFp._3 == 0)

    val toFpUnsigned = run { bits =>
      bits.operation.poke(Fp32Operation.intToFp)
      bits.exactFunction.poke(1.U)
      bits.operandA.poke(5.U)
      bits.tag.poke(0x12.U)
    }
    assert(toFpUnsigned._2 == BigInt("40a00000", 16))
  }

  it should "report invalid conversions and saturate on infinity" in {
    val nan = run { bits =>
      bits.operation.poke(Fp32Operation.fpToInt)
      bits.exactFunction.poke(0.U)
      bits.operandA.poke("h7fc00000".U)
      bits.tag.poke(0x12.U)
    }
    assert(nan._2 == 0)
    assert(nan._3 == BigInt("10", 16))

    val inf = run { bits =>
      bits.operation.poke(Fp32Operation.fpToInt)
      bits.exactFunction.poke(0.U)
      bits.operandA.poke("h7f800000".U)
      bits.tag.poke(0x12.U)
    }
    assert(inf._2 == BigInt("7fffffff", 16))
    assert(inf._3 == BigInt("10", 16))
  }

  it should "round to nearest even and set NX for inexact conversions" in {
    val roundUp = run { bits =>
      bits.operation.poke(Fp32Operation.fpToInt)
      bits.exactFunction.poke(0.U)
      bits.operandA.poke("h3fc00000".U) // 1.5
      bits.tag.poke(0x12.U)
    }
    assert(roundUp._2 == 2)
    assert(roundUp._3 == BigInt("1", 16))

    val tieEven = run { bits =>
      bits.operation.poke(Fp32Operation.fpToInt)
      bits.exactFunction.poke(0.U)
      bits.operandA.poke("h40200000".U) // 2.5
      bits.tag.poke(0x12.U)
    }
    assert(tieEven._2 == 2)
    assert(tieEven._3 == BigInt("1", 16))

    val intInexact = run { bits =>
      bits.operation.poke(Fp32Operation.intToFp)
      bits.exactFunction.poke(1.U) // unsigned
      bits.operandA.poke("h80000001".U) // 2^31 + 1
      bits.tag.poke(0x12.U)
    }
    assert(intInexact._2 == BigInt("4f000000", 16))
    assert(intInexact._3 == BigInt("1", 16))
  }

  it should "honor RTZ, RDN, RUP, and RMM for FP-to-integer conversion" in {
    val truncate = run { bits =>
      bits.operation.poke(Fp32Operation.fpToInt)
      bits.exactFunction.poke(0.U)
      bits.roundingMode.poke("b001".U)
      bits.operandA.poke("h3fc00000".U) // 1.5
      bits.tag.poke(0x12.U)
    }
    assert(truncate._2 == 1)
    assert(truncate._3 == BigInt("1", 16))

    val down = run { bits =>
      bits.operation.poke(Fp32Operation.fpToInt)
      bits.exactFunction.poke(0.U)
      bits.roundingMode.poke("b010".U)
      bits.operandA.poke("hbfc00000".U) // -1.5
      bits.tag.poke(0x12.U)
    }
    assert(down._2 == BigInt("fffffffe", 16))
    assert(down._3 == BigInt("1", 16))

    val up = run { bits =>
      bits.operation.poke(Fp32Operation.fpToInt)
      bits.exactFunction.poke(0.U)
      bits.roundingMode.poke("b011".U)
      bits.operandA.poke("hbfc00000".U) // -1.5
      bits.tag.poke(0x12.U)
    }
    assert(up._2 == BigInt("ffffffff", 16))
    assert(up._3 == BigInt("1", 16))

    val away = run { bits =>
      bits.operation.poke(Fp32Operation.fpToInt)
      bits.exactFunction.poke(0.U)
      bits.roundingMode.poke("b100".U)
      bits.operandA.poke("h40200000".U) // 2.5
      bits.tag.poke(0x12.U)
    }
    assert(away._2 == 3)
    assert(away._3 == BigInt("1", 16))

    val unsignedUp = run { bits =>
      bits.operation.poke(Fp32Operation.fpToInt)
      bits.exactFunction.poke(1.U)
      bits.roundingMode.poke("b011".U)
      bits.operandA.poke("h3fc00000".U) // 1.5
      bits.tag.poke(0x12.U)
    }
    assert(unsignedUp._2 == 2)
    assert(unsignedUp._3 == BigInt("1", 16))
  }

  it should "round subnormal FP values to zero with NX" in {
    val signed = run { bits =>
      bits.operation.poke(Fp32Operation.fpToInt)
      bits.exactFunction.poke(0.U)
      bits.operandA.poke("h00400000".U)
      bits.tag.poke(0x12.U)
    }
    assert(signed._2 == 0)
    assert(signed._3 == BigInt("1", 16))
  }

  it should "honor RTZ and RUP for unsigned integer-to-FP conversion" in {
    val truncate = run { bits =>
      bits.operation.poke(Fp32Operation.intToFp)
      bits.exactFunction.poke(1.U)
      bits.roundingMode.poke("b001".U)
      bits.operandA.poke("h80000001".U)
      bits.tag.poke(0x12.U)
    }
    assert(truncate._2 == BigInt("4f000000", 16))
    assert(truncate._3 == BigInt("1", 16))

    val up = run { bits =>
      bits.operation.poke(Fp32Operation.intToFp)
      bits.exactFunction.poke(1.U)
      bits.roundingMode.poke("b011".U)
      bits.operandA.poke("h80000001".U)
      bits.tag.poke(0x12.U)
    }
    assert(up._2 == BigInt("4f000001", 16))
    assert(up._3 == BigInt("1", 16))
  }
}
