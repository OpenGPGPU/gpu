package gpu.core.execute.fpu

import chisel3._
import chisel3.util._

/** Iterative FP32 square-root lane with RISC-V rounding and flags.
  *
  * The normalized 24-bit significand is scaled into a 54-bit radicand and
  * reduced with one restoring-square-root bit per cycle. 27 iterations
  * produce a 27-bit fixed-point root (one integer bit plus 26 fraction bits),
  * from which the FP32 mantissa plus guard, round, and sticky bits are taken.
  */
class Fp32SqrtLane(tagWidth: Int = 16) extends Module {
  require(tagWidth > 0)

  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new Fp32Request(tagWidth)))
    val out = Decoupled(new Fp32Response(tagWidth))
    val flush = Input(Bool())
    val busy = Output(Bool())
  })

  private val busy = RegInit(false.B)
  private val count = RegInit(0.U(5.W))
  private val radicandReg = RegInit(0.U(54.W))
  private val remainder = RegInit(0.U(58.W))
  private val quotient = RegInit(0.U(27.W))
  private val resultExp = RegInit(0.S(10.W))
  private val resultRound = RegInit(0.U(3.W))
  private val resultTag = RegInit(0.U(tagWidth.W))

  private val outputValid = RegInit(false.B)
  private val outputBits = Reg(new Fp32Response(tagWidth))
  private val outputAvailable = !outputValid || io.out.ready

  private val a = io.in.bits.operandA
  private val aSign = a(31)
  private val aExp = a(30, 23)
  private val aMant = a(22, 0)
  private val aZero = aExp === 0.U && aMant === 0.U
  private val aInf = aExp === 0xff.U && aMant === 0.U
  private val aNaN = aExp === 0xff.U && aMant =/= 0.U
  private val isSubnormal = aExp === 0.U && aMant =/= 0.U
  private val invalid = aNaN || (aSign && !aZero)

  private val specialResult = Mux(
    invalid,
    "h7fc00000".U,
    Mux(aInf || aZero, a, 0.U)
  )
  private val specialFlags = Mux(invalid, "h10".U, 0.U)

  private val normShift = PriorityEncoder(Reverse(aMant))
  private val sint = Mux(
    isSubnormal,
    (aMant << (normShift + 1.U))(23, 0),
    Cat(1.U, aMant)
  )
  private val effectiveExp = Mux(
    isSubnormal,
    (0.S - normShift.zext).asSInt,
    aExp.zext.asSInt
  )
  private val unbiased = effectiveExp.pad(10) - 127.S(10.W)
  private val evenExponent = !unbiased(0)
  private val resultUnbiased = Mux(
    evenExponent,
    unbiased >> 1,
    (unbiased - 1.S) >> 1
  )
  private val radicand = Mux(
    evenExponent,
    Cat(sint, 0.U(29.W)),
    Cat(sint, 0.U(30.W))
  )
  private val initialExp = 127.S(10.W) + resultUnbiased.pad(10)

  io.in.ready := !busy && outputAvailable
  io.out.valid := outputValid
  io.out.bits := outputBits
  io.busy := busy || outputValid

  when(outputAvailable) {
    outputValid := false.B
  }

  when(io.in.fire) {
    when(invalid || aInf || aZero) {
      outputValid := true.B
      outputBits.result := specialResult
      outputBits.status := specialFlags
      outputBits.tag := io.in.bits.tag
    }.otherwise {
      busy := true.B
      count := 0.U
      radicandReg := radicand(53, 0)
      remainder := 0.U
      quotient := 0.U
      resultExp := initialExp
      resultRound := io.in.bits.roundingMode
      resultTag := io.in.bits.tag
    }
  }

  private val nextRemainderFull =
    Cat(remainder, radicandReg(53, 52))
  private val trial = Cat(quotient, 0.U(1.W), 1.U)
  private val subtracts = nextRemainderFull >= trial
  private val nextRemainder = Mux(
    subtracts,
    nextRemainderFull - trial,
    nextRemainderFull
  )
  private val nextQuotient = Cat(quotient(25, 0), subtracts)
  private val nextRadicand = (radicandReg << 2)(53, 0)

  private def roundUp(
    candidate: UInt,
    guard: Bool,
    roundBit: Bool,
    sticky: Bool,
    sign: Bool,
    roundingMode: UInt
  ): Bool = {
    val inexact = guard || roundBit || sticky
    MuxCase(
      false.B,
      Seq(
        (roundingMode === "b000".U) ->
          (guard && (roundBit || sticky || candidate(0))),
        (roundingMode === "b001".U) -> false.B,
        (roundingMode === "b010".U) -> (inexact && sign),
        (roundingMode === "b011".U) -> (inexact && !sign),
        (roundingMode === "b100".U) -> guard
      )
    )
  }

  private val finalQ = Mux(count === 26.U, nextQuotient, quotient)
  private val finalRemainder =
    Mux(count === 26.U, nextRemainder, remainder)
  private val mantissa = finalQ(25, 3)
  private val guard = finalQ(2)
  private val roundBit = finalQ(1)
  private val sticky = finalQ(0) || finalRemainder.orR
  private val rounded = mantissa +& roundUp(
    mantissa, guard, roundBit, sticky, false.B, resultRound)
  private val carry = rounded(23)
  private val finalExp = resultExp + Mux(carry, 1.S, 0.S)
  private val inexact = guard || roundBit || sticky
  private val finiteResult = Cat(
    0.U(1.W),
    finalExp(7, 0),
    Mux(carry, 0.U(23.W), rounded(22, 0))
  )
  private val finiteFlags = Mux(inexact, 1.U, 0.U)

  when(busy) {
    radicandReg := nextRadicand
    remainder := nextRemainder
    quotient := nextQuotient
    count := count + 1.U
    when(count === 26.U) {
      busy := false.B
      outputValid := true.B
      outputBits.result := finiteResult
      outputBits.status := finiteFlags
      outputBits.tag := resultTag
    }
  }

  when(io.flush) {
    busy := false.B
    outputValid := false.B
    count := 0.U
  }

  when(io.in.valid) {
    assert(io.in.bits.operation === Fp32Operation.sqrt,
      "Fp32SqrtLane received a non-sqrt operation")
  }
}
