package opengpu.core.execute.fpu

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec

class Fp32DivLaneSpec extends AnyFlatSpec {
  behavior of "Fp32DivLane"

  private def run(
    a: BigInt,
    b: BigInt,
    roundingMode: Int = 0
  ): (BigInt, BigInt) = {
    var result = BigInt(0)
    var flags = BigInt(0)
    simulate(new Fp32DivLane(tagWidth = 8)) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)
      dut.io.in.valid.poke(false.B)
      dut.io.in.bits.poke(0.U.asTypeOf(dut.io.in.bits))
      dut.io.out.ready.poke(true.B)
      dut.io.flush.poke(false.B)

      dut.io.in.bits.operation.poke(Fp32Operation.div)
      dut.io.in.bits.operandA.poke(a.U)
      dut.io.in.bits.operandB.poke(b.U)
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

  it should "divide finite FP32 operands" in {
    val (result, flags) = run(
      BigInt("40c00000", 16), // 6.0
      BigInt("40400000", 16)  // 3.0
    )
    assert(result == BigInt("40000000", 16)) // 2.0
    assert(flags == 0)
  }

  it should "divide by four" in {
    val (result, flags) = run(
      BigInt("3f800000", 16), // 1.0
      BigInt("40800000", 16)  // 4.0
    )
    assert(result == BigInt("3e800000", 16)) // 0.25
    assert(flags == 0)
  }

  it should "round one third to nearest even" in {
    val (result, flags) = run(
      BigInt("3f800000", 16),
      BigInt("40400000", 16)
    )
    assert(result == BigInt("3eaaaaab", 16))
    assert((flags & 1) != 0) // NX
  }

  it should "raise DZ for finite divided by zero" in {
    val (result, flags) = run(BigInt("3f800000", 16), 0)
    assert(result == BigInt("7f800000", 16))
    assert((flags & BigInt("8", 16)) != 0)
  }

  it should "raise NV for zero divided by zero" in {
    val (result, flags) = run(0, 0)
    assert(result == BigInt("7fc00000", 16))
    assert((flags & BigInt("10", 16)) != 0)
  }

  it should "return signed zero for finite divided by infinity" in {
    val (result, flags) = run(
      BigInt("bf800000", 16),
      BigInt("7f800000", 16)
    )
    assert(result == BigInt("80000000", 16))
    assert(flags == 0)
  }

  it should "preserve the sign of a negative quotient" in {
    val (result, flags) = run(
      BigInt("c0c00000", 16), // -6.0
      BigInt("40400000", 16)  // 3.0
    )
    assert(result == BigInt("c0000000", 16)) // -2.0
    assert(flags == 0)
  }

  it should "produce an exact subnormal result" in {
    val (result, flags) = run(
      BigInt("00800000", 16), // 2^-126
      BigInt("40000000", 16)  // 2.0
    )
    assert(result == BigInt("00400000", 16)) // 2^-127
    assert(flags == 0)
  }

  it should "normalize a subnormal dividend" in {
    val (result, flags) = run(
      BigInt("00000001", 16), // 2^-149
      BigInt("3f800000", 16)  // 1.0
    )
    assert(result == BigInt("00000001", 16))
    assert(flags == 0)
  }

  it should "report overflow when the quotient exceeds max finite" in {
    val (result, flags) = run(
      BigInt("7f7fffff", 16),
      BigInt("00800000", 16)
    )
    assert(result == BigInt("7f800000", 16))
    assert((flags & BigInt("4", 16)) != 0) // OF
  }

  it should "honor directed rounding modes" in {
    val (towardZero, _) = run(
      BigInt("3f800000", 16),
      BigInt("40400000", 16),
      roundingMode = 1
    )
    assert(towardZero == BigInt("3eaaaaaa", 16))

    val (up, _) = run(
      BigInt("3f800000", 16),
      BigInt("40400000", 16),
      roundingMode = 3
    )
    assert(up == BigInt("3eaaaaab", 16))
  }
}
