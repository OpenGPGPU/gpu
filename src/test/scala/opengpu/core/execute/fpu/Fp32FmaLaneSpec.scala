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
