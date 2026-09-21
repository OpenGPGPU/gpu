package opengpu.core.execute.fpu

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec

class Fp32FmaLaneSpec extends AnyFlatSpec {
  behavior of "Fp32FmaLane"

  private def initialize(dut: Fp32FmaLane): Unit = {
    dut.reset.poke(true.B)
    dut.io.in.valid.poke(false.B)
    dut.io.out.ready.poke(false.B)
    dut.io.flush.poke(false.B)
    dut.clock.step(2)
    dut.reset.poke(false.B)
  }

  private def send(
    dut: Fp32FmaLane,
    operation: Fp32Operation.Type,
    modifier: Boolean,
    a: BigInt,
    b: BigInt,
    c: BigInt,
    tag: Int,
    roundingMode: Int = 0
  ): Unit = {
    dut.io.in.bits.operandA.poke(a.U)
    dut.io.in.bits.operandB.poke(b.U)
    dut.io.in.bits.operandC.poke(c.U)
    dut.io.in.bits.roundingMode.poke(roundingMode.U)
    dut.io.in.bits.operation.poke(operation)
    dut.io.in.bits.operationModifier.poke(modifier.B)
    dut.io.in.bits.tag.poke(tag.U)
    dut.io.in.valid.poke(true.B)
    while (!dut.io.in.ready.peek().litToBoolean) { dut.clock.step() }
    dut.clock.step()
    dut.io.in.valid.poke(false.B)
  }

  private def expect(
    dut: Fp32FmaLane,
    result: BigInt,
    tag: Int
  ): Unit = {
    var cycles = 0
    while (!dut.io.out.valid.peek().litToBoolean && cycles < 16) {
      dut.clock.step()
      cycles += 1
    }
    assert(dut.io.out.valid.peek().litToBoolean)
    dut.io.out.bits.result.expect(result.U)
    dut.io.out.bits.status.expect(0.U)
    dut.io.out.bits.tag.expect(tag.U)
    dut.io.out.ready.poke(true.B)
    dut.clock.step()
    dut.io.out.ready.poke(false.B)
  }

  private def expectResult(
    dut: Fp32FmaLane,
    result: BigInt,
    tag: Int
  ): Unit = {
    var cycles = 0
    while (!dut.io.out.valid.peek().litToBoolean && cycles < 32) {
      dut.clock.step()
      cycles += 1
    }
    assert(dut.io.out.valid.peek().litToBoolean)
    dut.io.out.bits.result.expect(result.U)
    dut.io.out.bits.tag.expect(tag.U)
    dut.io.out.ready.poke(true.B)
    dut.clock.step()
    dut.io.out.ready.poke(false.B)
  }

  it should "keep back-to-back mixed-sign operations independent" in {
    simulate(new Fp32FmaLane()) { dut =>
      initialize(dut)
      val plusOnePointFive = BigInt("3fc00000", 16)
      val minusOnePointFive = BigInt("bfc00000", 16)
      val two = BigInt("40000000", 16)
      send(dut, Fp32Operation.mul, false, plusOnePointFive, two, 0, 1)
      send(dut, Fp32Operation.mul, false, minusOnePointFive, two, 0, 2)
      expect(dut, BigInt("40400000", 16), 1) // +3
      expect(dut, BigInt("c0400000", 16), 2) // -3
    }
  }

  it should "keep zero and non-zero results independent across back-to-back operations" in {
    simulate(new Fp32FmaLane()) { dut =>
      initialize(dut)
      val onePointFive = BigInt("3fc00000", 16)
      val two = BigInt("40000000", 16)
      val minusThree = BigInt("c0400000", 16)
      send(dut, Fp32Operation.fmadd, false, onePointFive, two, minusThree, 1)
      send(dut, Fp32Operation.fmadd, false, onePointFive, two, two, 2)
      expect(dut, BigInt("00000000", 16), 1) // 1.5*2 - 3 = +0
      expect(dut, BigInt("40a00000", 16), 2) // 1.5*2 + 2 = 5
    }
  }

  it should "keep back-to-back subtraction state independent" in {
    simulate(new Fp32FmaLane()) { dut =>
      initialize(dut)
      val rng = new scala.util.Random(0x15ab)
      def randomFloat(): Float = {
        val sign = if (rng.nextBoolean()) -1.0f else 1.0f
        sign * (0.25f + rng.nextFloat() * 3.75f)
      }
      def bits(value: Float): BigInt =
        BigInt(java.lang.Float.floatToRawIntBits(value) & 0xffffffffL)
      var tag = 0
      for (batch <- 0 until 4) {
        val a = randomFloat()
        val b = randomFloat()
        val c = randomFloat()
        send(dut, Fp32Operation.fmadd, false, bits(a), bits(b), bits(c), tag)
        send(dut, Fp32Operation.fmadd, true, bits(a), bits(b), bits(c), tag + 1)
        expectResult(dut, bits(Math.fma(a, b, c)), tag)
        expectResult(dut, bits(Math.fma(a, b, -c)), tag + 1)
        tag += 2
      }
    }
  }

  it should "match a software FMA reference across back-to-back mixed operands" in {
    simulate(new Fp32FmaLane()) { dut =>
      initialize(dut)
      val rng = new scala.util.Random(0x5eed)
      def randomFloat(): Float = {
        val sign = if (rng.nextBoolean()) -1.0f else 1.0f
        val magnitude = 0.25f + rng.nextFloat() * 3.75f
        sign * magnitude
      }
      def bits(value: Float): BigInt =
        BigInt(java.lang.Float.floatToRawIntBits(value) & 0xffffffffL)
      var tag = 0
      for (batch <- 0 until 3) {
        val a = randomFloat()
        val b = randomFloat()
        val c = randomFloat()
        val d = randomFloat()
        val e = randomFloat()
        val f = randomFloat()
        val h = randomFloat()
        val i = randomFloat()
        val k = randomFloat()
        val l = randomFloat()
        send(dut, Fp32Operation.fmadd, false, bits(a), bits(b), bits(c), tag + 0)
        send(dut, Fp32Operation.fmadd, true, bits(d), bits(e), bits(f), tag + 1)
        send(dut, Fp32Operation.add, false, 0, bits(h), bits(i), tag + 2)
        send(dut, Fp32Operation.add, true, 0, bits(k), bits(l), tag + 3)
        expectResult(dut, bits(Math.fma(a, b, c)), tag + 0)
        expectResult(dut, bits(Math.fma(d, e, -f)), tag + 1)
        expectResult(dut, bits(Math.fma(1.0f, h, i)), tag + 2)
        expectResult(dut, bits(Math.fma(1.0f, k, -l)), tag + 3)
        tag += 4
      }
    }
  }

  it should "keep special and normal results independent across back-to-back operations" in {
    simulate(new Fp32FmaLane()) { dut =>
      initialize(dut)
      val infinity = BigInt("7f800000", 16)
      val onePointFive = BigInt("3fc00000", 16)
      val two = BigInt("40000000", 16)
      send(dut, Fp32Operation.mul, false, 0, infinity, 0, 1)
      send(dut, Fp32Operation.add, false, 0, onePointFive, two, 2)
      send(dut, Fp32Operation.mul, false, infinity, 0, 0, 3)
      send(dut, Fp32Operation.add, false, 0, two, onePointFive, 4)
      var cycles = 0
      while (!dut.io.out.valid.peek().litToBoolean && cycles < 16) {
        dut.clock.step(); cycles += 1
      }
      assert(dut.io.out.valid.peek().litToBoolean)
      dut.io.out.bits.status.expect("h10".U)
      dut.io.out.bits.tag.expect(1.U)
      dut.io.out.ready.poke(true.B); dut.clock.step(); dut.io.out.ready.poke(false.B)
      expect(dut, BigInt("40600000", 16), 2) // 3.5
      var cycles2 = 0
      while (!dut.io.out.valid.peek().litToBoolean && cycles2 < 16) {
        dut.clock.step(); cycles2 += 1
      }
      assert(dut.io.out.valid.peek().litToBoolean)
      dut.io.out.bits.status.expect("h10".U)
      dut.io.out.bits.tag.expect(3.U)
      dut.io.out.ready.poke(true.B); dut.clock.step(); dut.io.out.ready.poke(false.B)
      expect(dut, BigInt("40600000", 16), 4) // 3.5
    }
  }

  it should "map all fast RISC-V sign combinations onto YunSuan" in {
    simulate(new Fp32FmaLane()) { dut =>
      initialize(dut)
      val onePointFive = BigInt("3fc00000", 16)
      val two = BigInt("40000000", 16)
      val half = BigInt("3f000000", 16)

      send(dut, Fp32Operation.fmadd, false, onePointFive, two, half, 1)
      expect(dut, BigInt("40600000", 16), 1) // 3.5
      send(dut, Fp32Operation.fmadd, true, onePointFive, two, half, 2)
      expect(dut, BigInt("40200000", 16), 2) // 2.5
      send(dut, Fp32Operation.fnmsub, false, onePointFive, two, half, 3)
      expect(dut, BigInt("c0200000", 16), 3) // -2.5
      send(dut, Fp32Operation.fnmsub, true, onePointFive, two, half, 4)
      expect(dut, BigInt("c0600000", 16), 4) // -3.5
      send(dut, Fp32Operation.add, false, 0, onePointFive, two, 5)
      expect(dut, BigInt("40600000", 16), 5)
      send(dut, Fp32Operation.add, true, 0, onePointFive, two, 6)
      expect(dut, BigInt("bf000000", 16), 6)
      send(dut, Fp32Operation.mul, false, onePointFive, two, 0, 7)
      expect(dut, BigInt("40400000", 16), 7)
    }
  }

  it should "reserve result storage across downstream backpressure" in {
    simulate(new Fp32FmaLane()) { dut =>
      initialize(dut)
      val onePointFive = BigInt("3fc00000", 16)
      val two = BigInt("40000000", 16)
      val half = BigInt("3f000000", 16)
      for (tag <- 0 until 4) {
        send(dut, Fp32Operation.fmadd, false, onePointFive, two, half, tag)
      }
      dut.clock.step(6)
      for (tag <- 0 until 4) {
        expect(dut, BigInt("40600000", 16), tag)
      }
      dut.io.busy.expect(false.B)
    }
  }

  it should "discard every in-flight result on flush" in {
    simulate(new Fp32FmaLane()) { dut =>
      initialize(dut)
      send(dut, Fp32Operation.fmadd, false,
        BigInt("3fc00000", 16), BigInt("40000000", 16),
        BigInt("3f000000", 16), 9)
      dut.io.flush.poke(true.B)
      dut.clock.step()
      dut.io.flush.poke(false.B)
      dut.clock.step(8)
      dut.io.out.valid.expect(false.B)
      dut.io.busy.expect(false.B)
    }
  }

  it should "report NV for zero times infinity" in {
    simulate(new Fp32FmaLane()) { dut =>
      initialize(dut)
      send(dut, Fp32Operation.mul, false, 0,
        BigInt("7f800000", 16), 0, 9)
      var cycles = 0
      while (!dut.io.out.valid.peek().litToBoolean && cycles < 16) {
        dut.clock.step()
        cycles += 1
      }
      assert(dut.io.out.valid.peek().litToBoolean)
      dut.io.out.bits.status.expect("h10".U)
    }
  }

  it should "report NV for zero times infinity under RUP" in {
    simulate(new Fp32FmaLane()) { dut =>
      initialize(dut)
      send(dut, Fp32Operation.mul, false, 0,
        BigInt("7f800000", 16), 0, 9, roundingMode = 3)
      var cycles = 0
      while (!dut.io.out.valid.peek().litToBoolean && cycles < 16) {
        dut.clock.step()
        cycles += 1
      }
      assert(dut.io.out.valid.peek().litToBoolean)
      dut.io.out.bits.status.expect("h10".U)
    }
  }

}
