package opengpu.core.execute.fpu

import chisel3._
import chisel3.util._

private class Fp32DivSetup(tagWidth: Int) extends Bundle {
  val tag = UInt(tagWidth.W)
  val roundingMode = UInt(3.W)
  val sign = Bool()
  val invalid = Bool()
  val divideByZero = Bool()
  val aInf = Bool()
  val bInf = Bool()
  val aZero = Bool()
  val bZero = Bool()
  val aSig = UInt(24.W)
  val bSig = UInt(24.W)
  val aExpVal = SInt(10.W)
  val bExpVal = SInt(10.W)
}

/** Iterative FP32 divide lane with RISC-V rounding and exception flags.
  *
  * The normalized 24-bit significands are divided with one radix-2 quotient
  * bit per cycle. 27 iterations produce a 28-bit fixed-point quotient (one
  * integer bit plus 27 fraction bits), then the result is rounded with guard,
  * round, and sticky bits. Normal and subnormal results are both supported.
  */
class Fp32DivLane(tagWidth: Int = 16) extends Module {
  require(tagWidth > 0)

  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new Fp32Request(tagWidth)))
    val out = Decoupled(new Fp32Response(tagWidth))
    val flush = Input(Bool())
    val busy = Output(Bool())
  })

  private val busy = RegInit(false.B)
  private val count = RegInit(0.U(5.W))
  private val qFull = RegInit(0.U(27.W))
  private val quotientInteger = RegInit(false.B)
  private val remainder = RegInit(0.U(24.W))
  private val resultExp = RegInit(0.S(10.W))
  private val resultSign = RegInit(false.B)
  private val resultRound = RegInit(0.U(3.W))
  private val resultTag = RegInit(0.U(tagWidth.W))

  private val outputValid = RegInit(false.B)
  private val outputBits = Reg(new Fp32Response(tagWidth))
  private val outputAvailable = !outputValid || io.out.ready
  // Registered final-iteration state. The result formation (shift, 25-bit
  // rounding add, and the normal/underflow mux tree) is deep enough to eat
  // the whole 1 GHz cycle when it is computed directly from the iterative
  // registers, so the final quotient is captured here and formed one cycle
  // later into outputBits.
  private val postValid = RegInit(false.B)
  private val postQ = RegInit(0.U(27.W))
  private val postRemainder = RegInit(0.U(24.W))
  private val postInteger = RegInit(false.B)
  private val postExp = RegInit(0.S(10.W))
  private val postSign = RegInit(false.B)
  private val postRound = RegInit(0.U(3.W))
  private val postTag = RegInit(0.U(tagWidth.W))
  private val divisorReg = RegInit(0.U(24.W))
  private val inputValid = RegInit(false.B)
  private val inputBits = Reg(new Fp32Request(tagWidth))
  private val normValid = RegInit(false.B)
  private val normBits = Reg(new Fp32DivSetup(tagWidth))

  private def normalize(request: Fp32Request): Fp32DivSetup = {
    val out = Wire(new Fp32DivSetup(tagWidth))
    val a = request.operandA
    val b = request.operandB
    val aExp = a(30, 23)
    val bExp = b(30, 23)
    val aMant = a(22, 0)
    val bMant = b(22, 0)
    val aZero = aExp === 0.U && aMant === 0.U
    val bZero = bExp === 0.U && bMant === 0.U
    val aInf = aExp === 0xff.U && aMant === 0.U
    val bInf = bExp === 0xff.U && bMant === 0.U
    val aNaN = aExp === 0xff.U && aMant =/= 0.U
    val bNaN = bExp === 0xff.U && bMant =/= 0.U
    val anyNaN = aNaN || bNaN
    val invalid = anyNaN || (aInf && bInf) || (aZero && bZero)
    val divideByZero =
      !anyNaN && !aZero && !aInf && bZero
    val aNormShift = PriorityEncoder(Reverse(aMant))
    val bNormShift = PriorityEncoder(Reverse(bMant))
    out.tag := request.tag
    out.roundingMode := request.roundingMode
    out.sign := a(31) ^ b(31)
    out.invalid := invalid
    out.divideByZero := divideByZero
    out.aInf := aInf
    out.bInf := bInf
    out.aZero := aZero
    out.bZero := bZero
    out.aSig := Mux(
      aExp === 0.U,
      Cat(0.U, (aMant << aNormShift)(22, 0)),
      Cat(1.U, aMant)
    )
    out.bSig := Mux(
      bExp === 0.U,
      Cat(0.U, (bMant << bNormShift)(22, 0)),
      Cat(1.U, bMant)
    )
    out.aExpVal := Mux(
      aExp === 0.U,
      (1.S - aNormShift.zext).asSInt,
      aExp.zext.asSInt
    )
    out.bExpVal := Mux(
      bExp === 0.U,
      (1.S - bNormShift.zext).asSInt,
      bExp.zext.asSInt
    )
    out
  }

  private val canStart = normValid && !busy && outputAvailable && !postValid
  private val stageBReady = !normValid || canStart
  io.in.ready := !inputValid || stageBReady
  io.out.valid := outputValid
  io.out.bits := outputBits
  io.busy := busy || outputValid

  when(outputAvailable) {
    outputValid := false.B
  }

  when(stageBReady) {
    inputValid := false.B
    when(inputValid) {
      normBits := normalize(inputBits)
    }
  }
  when(io.in.fire) {
    inputValid := true.B
    inputBits := io.in.bits
  }
  normValid := Mux(
    stageBReady && inputValid,
    true.B,
    Mux(canStart, false.B, normValid)
  )

  when(canStart) {
    when(normBits.invalid || normBits.divideByZero || normBits.aInf ||
      normBits.bInf || normBits.aZero || normBits.bZero) {
      outputValid := true.B
      outputBits.result := Mux(
        normBits.invalid,
        "h7fc00000".U,
        Mux(
          normBits.divideByZero ||
            (normBits.aInf && !normBits.bInf),
          Cat(normBits.sign, Fill(8, 1.U), 0.U(23.W)),
          Cat(normBits.sign, 0.U(8.W), 0.U(23.W))
        )
      )
      outputBits.status := Mux(
        normBits.invalid,
        "h10".U,
        Mux(normBits.divideByZero, "h08".U, 0.U)
      )
      outputBits.tag := normBits.tag
    }.otherwise {
      val aGteB = normBits.aSig >= normBits.bSig
      busy := true.B
      count := 0.U
      qFull := 0.U
      divisorReg := normBits.bSig
      quotientInteger := aGteB
      remainder := Mux(aGteB, normBits.aSig - normBits.bSig, normBits.aSig)
      resultExp :=
        normBits.aExpVal.pad(10) - normBits.bExpVal.pad(10) +
          Mux(aGteB, 127.S(10.W), 126.S(10.W))
      resultSign := normBits.sign
      resultRound := normBits.roundingMode
      resultTag := normBits.tag
    }
  }

  private val shiftedRemainder = Cat(remainder, 0.U(1.W))
  private val subtracts = shiftedRemainder >= divisorReg
  private val nextRemainder = Mux(
    subtracts,
    shiftedRemainder - divisorReg,
    shiftedRemainder
  )
  private val nextQ = Cat(qFull(25, 0), subtracts)

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

  private val finalQ = Mux(count === 26.U, nextQ, qFull)
  private val finalRemainder =
    Mux(count === 26.U, nextRemainder, remainder)
  private val normalizedQ = Mux(
    postInteger,
    Cat(1.U, postQ),
    Cat(postQ, 0.U)
  )
  private val normalMantissa = normalizedQ(27, 4)
  private val normalGuard = normalizedQ(3)
  private val normalRoundBit = normalizedQ(2)
  private val normalSticky =
    normalizedQ(1) || normalizedQ(0) || postRemainder.orR
  private val normalRounded = normalMantissa +& roundUp(
    normalMantissa, normalGuard, normalRoundBit, normalSticky,
    postSign, postRound)
  private val normalCarry = normalRounded(24)
  private val normalExp = postExp + Mux(normalCarry, 1.S, 0.S)
  private val normalInexact =
    normalGuard || normalRoundBit || normalSticky
  private val normalOverflow = normalExp >= 255.S

  private val underflowShift = (5.S - postExp).asUInt
  private val underflowValidShift = underflowShift < 28.U
  private val underflowCandidate = Mux(
    underflowValidShift,
    (normalizedQ >> underflowShift)(22, 0),
    0.U
  )
  private val underflowGuard = Mux(
    underflowValidShift && underflowShift >= 1.U,
    (normalizedQ >> (underflowShift - 1.U))(0),
    false.B
  )
  private val underflowRoundBit = Mux(
    underflowValidShift && underflowShift >= 2.U,
    (normalizedQ >> (underflowShift - 2.U))(0),
    false.B
  )
  private val clampedUnderflowShift = Mux(
    underflowShift > 32.U, 32.U, underflowShift)
  private val shiftedAway =
    Cat(normalizedQ, 0.U(4.W)) << (32.U - clampedUnderflowShift)
  private val underflowSticky =
    (shiftedAway(31, 0) =/= 0.U) || postRemainder.orR
  private val underflowRounded = underflowCandidate +& roundUp(
    underflowCandidate, underflowGuard, underflowRoundBit,
    underflowSticky, postSign, postRound)
  private val underflowCarry = underflowRounded(23)
  private val underflowInexact =
    underflowGuard || underflowRoundBit || underflowSticky

  private val expInNormalRange =
    normalExp >= 1.S && normalExp <= 254.S
  private val finiteResult = Wire(UInt(32.W))
  private val finiteFlags = Wire(UInt(5.W))
  finiteResult := Mux(
    normalOverflow,
    Cat(postSign, Fill(8, 1.U), 0.U(23.W)),
    Mux(
      expInNormalRange,
      Cat(
        postSign,
        normalExp(7, 0),
        Mux(normalCarry, 0.U(23.W), normalRounded(22, 0))
      ),
      Mux(
        postExp <= 0.S,
        Mux(
          underflowCarry,
          Cat(postSign, 1.U(8.W), 0.U(23.W)),
          Cat(postSign, 0.U(8.W), underflowRounded(22, 0))
        ),
        Cat(postSign, 0.U(8.W), 0.U(23.W))
      )
    )
  )
  finiteFlags := Mux(
    normalOverflow,
    Cat(0.U(2.W), 1.U, 0.U, Mux(normalInexact, 1.U, 0.U)),
    Mux(
      expInNormalRange,
      Mux(normalInexact, 1.U, 0.U),
      Mux(
        postExp <= 0.S,
        Cat(0.U(3.W), Mux(underflowInexact, 1.U, 0.U), Mux(underflowInexact, 1.U, 0.U)),
        0.U
      )
    )
  )
  when(busy) {
    qFull := nextQ
    remainder := nextRemainder
    count := count + 1.U
    when(count === 26.U) {
      busy := false.B
      postValid := true.B
      postQ := finalQ
      postRemainder := finalRemainder
      postInteger := quotientInteger
      postExp := resultExp
      postSign := resultSign
      postRound := resultRound
      postTag := resultTag
    }
  }

  when(postValid && outputAvailable) {
    postValid := false.B
    outputValid := true.B
    outputBits.result := finiteResult
    outputBits.status := finiteFlags
    outputBits.tag := postTag
  }

  when(io.flush) {
    busy := false.B
    outputValid := false.B
    count := 0.U
  }

  when(io.in.valid) {
    assert(io.in.bits.operation === Fp32Operation.div,
      "Fp32DivLane received a non-divide operation")
  }
}
