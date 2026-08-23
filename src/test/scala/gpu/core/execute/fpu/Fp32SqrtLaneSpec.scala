package gpu.core.execute.fpu

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec

class Fp32SqrtLaneSpec extends AnyFlatSpec {
  behavior of "Fp32SqrtLane"

  private def run(
    a: BigInt,
    roundingMode: Int = 0
  ): (BigInt, BigInt) = {
    var result = BigInt(0)
    var flags = BigInt(0)
    simulate(new Fp32SqrtLane(tagWidth = 8)) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)
      dut.io.in.valid.poke(false.B)
      dut.io.in.bits.poke(0.U.asTypeOf(dut.io.in.bits))
      dut.io.out.ready.poke(true.B)
      dut.io.flush.poke(false.B)

      dut.io.in.bits.operation.poke(Fp32Operation.sqrt)
      dut.io.in.bits.operandA.poke(a.U)
      dut.io.in.bits.roundingMode.poke(roundingMode.U)
      dut.io.in.bits.tag.poke("h12".U)
      dut.io.in.valid.poke(true.B)
      while (!dut.io.in.ready.peek().litToBoolean) { dut.clock.step() }
      dut.clock.step()
      dut.io.in.valid.poke(false.B)

      var cycles = 0
      while (!dut.io.out.valid.peek().litToBoolean && cycles < 80) {
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

  it should "return exact square roots" in {
    val one = run(BigInt("3f800000", 16)) // 1.0
    assert(one._1 == BigInt("3f800000", 16))
    assert(one._2 == 0)

    val four = run(BigInt("40800000", 16)) // 4.0
    assert(four._1 == BigInt("40000000", 16)) // 2.0
    assert(four._2 == 0)

    val quarter = run(BigInt("3e800000", 16)) // 0.25
    assert(quarter._1 == BigInt("3f000000", 16)) // 0.5
    assert(quarter._2 == 0)
  }

  it should "round an inexact square root to nearest even" in {
    val two = run(BigInt("40000000", 16)) // 2.0
    assert(two._1 == BigInt("3fb504f3", 16))
    assert((two._2 & 1) != 0)

    val oneHalf = run(BigInt("3fc00000", 16)) // 1.5
    assert(oneHalf._1 == BigInt("3f9cc471", 16))
    assert((oneHalf._2 & 1) != 0)
  }

  it should "normalize subnormal inputs before the square root" in {
    val minNormal = run(BigInt("00800000", 16)) // 2^-126
    assert(minNormal._1 == BigInt("20000000", 16)) // 2^-63
    assert(minNormal._2 == 0)

    val minSubnormal = run(BigInt("00000001", 16)) // 2^-149
    assert(minSubnormal._1 == BigInt("1a3504f3", 16))
    assert((minSubnormal._2 & 1) != 0)
  }

  it should "preserve zero and infinity signs and raise NV for invalid roots" in {
    val negZero = run(BigInt("80000000", 16))
    assert(negZero._1 == BigInt("80000000", 16))
    assert(negZero._2 == 0)

    val inf = run(BigInt("7f800000", 16))
    assert(inf._1 == BigInt("7f800000", 16))
    assert(inf._2 == 0)

    val negative = run(BigInt("c0000000", 16))
    assert(negative._1 == BigInt("7fc00000", 16))
    assert((negative._2 & BigInt("10", 16)) != 0)

    val nan = run(BigInt("7fc00000", 16))
    assert(nan._1 == BigInt("7fc00000", 16))
    assert((nan._2 & BigInt("10", 16)) != 0)
  }

  it should "honor directed rounding modes" in {
    val towardZero = run(BigInt("40000000", 16), roundingMode = 1)
    assert(towardZero._1 == BigInt("3fb504f3", 16))
    assert((towardZero._2 & 1) != 0)

    val up = run(BigInt("40000000", 16), roundingMode = 3)
    assert(up._1 == BigInt("3fb504f4", 16))
    assert((up._2 & 1) != 0)
  }
}
