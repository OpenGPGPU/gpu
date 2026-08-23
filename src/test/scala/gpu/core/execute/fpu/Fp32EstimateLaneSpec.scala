package gpu.core.execute.fpu

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec

class Fp32EstimateLaneSpec extends AnyFlatSpec {
  behavior of "Fp32EstimateLane"

  private def run(
    operation: Fp32Operation.Type,
    a: BigInt,
    roundingMode: Int = 0
  ): (BigInt, BigInt) = {
    var result = BigInt(0)
    var flags = BigInt(0)
    simulate(new Fp32EstimateLane(tagWidth = 8)) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)
      dut.io.in.valid.poke(false.B)
      dut.io.in.bits.poke(0.U.asTypeOf(dut.io.in.bits))
      dut.io.out.ready.poke(true.B)

      dut.io.in.bits.operation.poke(operation)
      dut.io.in.bits.operandA.poke(a.U)
      dut.io.in.bits.roundingMode.poke(roundingMode.U)
      dut.io.in.bits.tag.poke("h12".U)
      dut.io.in.valid.poke(true.B)
      while (!dut.io.in.ready.peek().litToBoolean) { dut.clock.step() }
      dut.clock.step()
      dut.io.in.valid.poke(false.B)

      var cycles = 0
      while (!dut.io.out.valid.peek().litToBoolean && cycles < 4) {
        dut.clock.step()
        cycles += 1
      }
      assert(dut.io.out.valid.peek().litToBoolean)
      result = dut.io.out.bits.result.peek().litValue
      flags = dut.io.out.bits.status.peek().litValue
      dut.io.out.bits.tag.expect("h12".U)
    }
    (result, flags)
  }

  it should "produce the RVV 7-bit reciprocal estimates" in {
    val one = run(Fp32Operation.recip7, BigInt("3f800000", 16))
    assert(one._1 == BigInt("3f7f0000", 16))
    assert(one._2 == 0)

    val three = run(Fp32Operation.recip7, BigInt("40400000", 16))
    assert(three._1 == BigInt("3eaa0000", 16))
    assert(three._2 == 0)

    val negative = run(Fp32Operation.recip7, BigInt("bf800000", 16))
    assert(negative._1 == BigInt("bf7f0000", 16))
    assert(negative._2 == 0)
  }

  it should "produce the RVV 7-bit reciprocal-square-root estimates" in {
    val one = run(Fp32Operation.rsqrt7, BigInt("3f800000", 16))
    assert(one._1 == BigInt("3f7f0000", 16))
    assert(one._2 == 0)

    val four = run(Fp32Operation.rsqrt7, BigInt("40800000", 16))
    assert(four._1 == BigInt("3eff0000", 16))
    assert(four._2 == 0)

    val oneHalf = run(Fp32Operation.rsqrt7, BigInt("3fc00000", 16))
    assert(oneHalf._1 == BigInt("3f500000", 16))
    assert(oneHalf._2 == 0)
  }

  it should "apply reciprocal special cases with RISC-V flags" in {
    val posInf = run(Fp32Operation.recip7, BigInt("7f800000", 16))
    assert(posInf._1 == 0)
    assert(posInf._2 == 0)

    val negInf = run(Fp32Operation.recip7, BigInt("ff800000", 16))
    assert(negInf._1 == BigInt("80000000", 16))
    assert(negInf._2 == 0)

    val posZero = run(Fp32Operation.recip7, BigInt("00000000", 16))
    assert(posZero._1 == BigInt("7f800000", 16))
    assert(posZero._2 == BigInt("08", 16))

    val negZero = run(Fp32Operation.recip7, BigInt("80000000", 16))
    assert(negZero._1 == BigInt("ff800000", 16))
    assert(negZero._2 == BigInt("08", 16))

    val sNaN = run(Fp32Operation.recip7, BigInt("7f800001", 16))
    assert(sNaN._1 == BigInt("7fc00000", 16))
    assert(sNaN._2 == BigInt("10", 16))
  }

  it should "apply reciprocal-square-root special cases with RISC-V flags" in {
    val negative = run(Fp32Operation.rsqrt7, BigInt("c0000000", 16))
    assert(negative._1 == BigInt("7fc00000", 16))
    assert(negative._2 == BigInt("10", 16))

    val negInf = run(Fp32Operation.rsqrt7, BigInt("ff800000", 16))
    assert(negInf._1 == BigInt("7fc00000", 16))
    assert(negInf._2 == BigInt("10", 16))

    val posInf = run(Fp32Operation.rsqrt7, BigInt("7f800000", 16))
    assert(posInf._1 == 0)
    assert(posInf._2 == 0)

    val negZero = run(Fp32Operation.rsqrt7, BigInt("80000000", 16))
    assert(negZero._1 == BigInt("ff800000", 16))
    assert(negZero._2 == BigInt("08", 16))
  }

  it should "normalize subnormal inputs and overflow vfrec7 under RNE" in {
    val nearMax = run(Fp32Operation.recip7, BigInt("00400000", 16))
    assert(nearMax._1 == BigInt("7eff0000", 16))
    assert(nearMax._2 == 0)

    val minSub = run(Fp32Operation.recip7, BigInt("00000001", 16))
    assert(minSub._1 == BigInt("7f800000", 16))
    assert(minSub._2 == BigInt("05", 16))

    val rsqrtMin = run(Fp32Operation.rsqrt7, BigInt("00000001", 16))
    assert(rsqrtMin._1 == BigInt("64b40000", 16))
    assert(rsqrtMin._2 == 0)
  }

  it should "saturate vfrec7 overflow under RTZ" in {
    val towardZero = run(
      Fp32Operation.recip7,
      BigInt("00000001", 16),
      roundingMode = 1
    )
    assert(towardZero._1 == BigInt("7f7fffff", 16))
    assert(towardZero._2 == BigInt("05", 16))
  }
}
