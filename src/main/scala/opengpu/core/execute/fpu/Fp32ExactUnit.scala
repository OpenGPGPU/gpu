package opengpu.core.execute.fpu

import chisel3._
import chisel3.util._

private class Fp32ExactPre(tagWidth: Int) extends Bundle {
  val tag = UInt(tagWidth.W)
  val operation = Fp32Operation()
  val exactFunction = UInt(3.W)
  val roundingMode = UInt(3.W)
  val simpleData = UInt(32.W)
  val simpleStatus = UInt(5.W)
  val fpToIntRoundedLow = UInt(32.W)
  val fpToIntOverflowSigned = Bool()
  val fpToIntOverflowUnsigned = Bool()
  val fpToIntNan = Bool()
  val fpToIntInfinity = Bool()
  val fpToIntInvalid = Bool()
  val fpToIntInexact = Bool()
  val fpToIntUnsigned = Bool()
  val fpToIntNegative = Bool()
  val intToFpSign = Bool()
  val intToFpMagnitude = UInt(32.W)
  val intToFpTop = UInt(5.W)
  val intToFpZero = Bool()
}

private class Fp32ExactMid(tagWidth: Int) extends Bundle {
  val tag = UInt(tagWidth.W)
  val data = UInt(32.W)
  val status = UInt(5.W)
}

private class Fp32ExactRound(tagWidth: Int) extends Bundle {
  val tag = UInt(tagWidth.W)
  val operation = Fp32Operation()
  val data = UInt(32.W)
  val status = UInt(5.W)
  val intToFpSign = Bool()
  val intToFpTop = UInt(5.W)
  val intToFpRoundedSig = UInt(24.W)
  val intToFpCarry = Bool()
  val intToFpInexact = Bool()
  val intToFpZero = Bool()
}

private class Fp32ExactShift(tagWidth: Int) extends Bundle {
  val tag = UInt(tagWidth.W)
  val operation = Fp32Operation()
  val exactFunction = UInt(3.W)
  val roundingMode = UInt(3.W)
  val simpleData = UInt(32.W)
  val simpleStatus = UInt(5.W)
  val fpToIntTruncated = UInt(32.W)
  val fpToIntFraction = UInt(24.W)
  val fpToIntShiftRight = UInt(8.W)
  val fpToIntShiftNonzero = Bool()
  val fpToIntNegative = Bool()
  val fpToIntNan = Bool()
  val fpToIntInfinity = Bool()
  val fpToIntUnsigned = Bool()
  val intToFpSign = Bool()
  val intToFpMagnitude = UInt(32.W)
  val intToFpTop = UInt(5.W)
  val intToFpZero = Bool()
}

/** Five-stage exact FP32 operations not covered by the FMA datapath.
  *
  * Float-to-integer shifts and rounding are split from final saturation;
  * integer-to-float leading-one detection is split from the shift/round
  * stage, which is split again from exponent/result formation.  Float-to-
  * integer alignment shifting is split from its rounding/overflow decision.
  * Simple sign/min-max/compare/classify operations pass through with the
  * same latency.
  */
class Fp32ExactUnit(tagWidth: Int = 16) extends Module {
  require(tagWidth > 0)

  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new Fp32Request(tagWidth)))
    val out = Decoupled(new Fp32Response(tagWidth))
  })

  private def isNaN(value: UInt): Bool =
    value(30, 23) === 0xff.U && value(22, 0) =/= 0.U

  private val a = io.in.bits.operandA
  private val b = io.in.bits.operandB
  private val aNaN = isNaN(a)
  private val bNaN = isNaN(b)
  private val anyNaN = aNaN || bNaN

  private val aNegative = a(31)
  private val bNegative = b(31)
  private val aMagnitude = a(30, 0)
  private val bMagnitude = b(30, 0)
  private val aLess = Mux(
    aNegative === bNegative,
    Mux(aNegative, aMagnitude > bMagnitude, aMagnitude < bMagnitude),
    aNegative && !bNegative
  )
  private val aGreater = Mux(
    aNegative === bNegative,
    Mux(aNegative, aMagnitude < bMagnitude, aMagnitude > bMagnitude),
    !aNegative && bNegative
  )
  private val equal = aMagnitude === bMagnitude && aNegative === bNegative
  private val isMin = io.in.bits.exactFunction === 0.U
  private val minMaxData = Mux(
    equal,
    b,
    Mux(Mux(isMin, aLess, aGreater), a, b)
  )
  private val compareResult = MuxLookup(io.in.bits.exactFunction, equal)(
    Seq(
      0.U -> equal,
      1.U -> aLess,
      2.U -> (aLess || equal)
    )
  )
  private val compareData = Mux(compareResult, 1.U, 0.U)

  private val sgnjSign = MuxLookup(io.in.bits.exactFunction, b(31))(
    Seq(
      0.U -> b(31),
      1.U -> ~b(31),
      2.U -> (a(31) ^ b(31))
    )
  )
  private val sgnjData = Cat(sgnjSign, a(30, 0))

  private val exponent = a(30, 23)
  private val mantissa = a(22, 0)
  private val isInfinity = exponent === 0xff.U && mantissa === 0.U
  private val isZeroValue = exponent === 0.U && mantissa === 0.U
  private val isSubnormal = exponent === 0.U && mantissa =/= 0.U
  private val classBit = Mux(
    aNaN,
    Mux(mantissa(22), 9.U, 8.U),
    Mux(
      isInfinity,
      Mux(aNegative, 0.U, 7.U),
      Mux(
        isZeroValue,
        Mux(aNegative, 3.U, 4.U),
        Mux(
          isSubnormal,
          Mux(aNegative, 2.U, 5.U),
          Mux(aNegative, 1.U, 6.U)
        )
      )
    )
  )
  private val classData = UIntToOH(classBit, 10)

  private val simpleData = Mux(
    io.in.bits.operation === Fp32Operation.sgnj,
    sgnjData,
    Mux(
      io.in.bits.operation === Fp32Operation.minmax,
      Mux(anyNaN, "h7fc00000".U, minMaxData),
      Mux(
        io.in.bits.operation === Fp32Operation.compare,
        Mux(anyNaN, 0.U, compareData),
        Mux(
          io.in.bits.operation === Fp32Operation.classify,
          classData,
          a
        )
      )
    )
  )
  private val simpleStatus = Mux(
    anyNaN && (io.in.bits.operation === Fp32Operation.minmax ||
      io.in.bits.operation === Fp32Operation.compare),
    "h10".U,
    0.U
  )

  private val fpExp = a(30, 23)
  private val fpMant = a(22, 0)
  private val fpInfinity = fpExp === 0xff.U && fpMant === 0.U
  private val fpToIntUnsigned = io.in.bits.exactFunction === 1.U
  private val significand = Mux(
    fpExp === 0.U, fpMant, Cat(1.U, fpMant))
  private val magnitudeShiftRight = Mux(
    fpExp === 0.U,
    149.U,
    Mux(fpExp >= 150.U, 0.U, 150.U - fpExp)
  )
  private val magnitudeShiftLeft =
    Mux(fpExp >= 150.U, fpExp - 150.U, 0.U)
  private val magnitude = Mux(
    fpExp >= 150.U,
    significand << magnitudeShiftLeft,
    significand >> magnitudeShiftRight
  )
  private val truncated = magnitude(31, 0)
  private val fraction = Mux(
    magnitudeShiftRight > 0.U,
    significand & ((1.U << magnitudeShiftRight) - 1.U),
    0.U
  )

  private val intNegative =
    io.in.bits.operation === Fp32Operation.intToFp &&
      io.in.bits.exactFunction === 0.U && a(31)
  private val intMagnitude = Mux(intNegative, ~a + 1.U, a)
  private val intZero = intMagnitude === 0.U
  private val intTop = Mux(
    intZero,
    0.U,
    31.U - PriorityEncoder(Reverse(intMagnitude))
  )

  private val shiftValid = RegInit(false.B)
  private val shiftBits = Reg(new Fp32ExactShift(tagWidth))
  private val preValid = RegInit(false.B)
  private val preBits = Reg(new Fp32ExactPre(tagWidth))
  private val roundValid = RegInit(false.B)
  private val roundBits = Reg(new Fp32ExactRound(tagWidth))
  private val tailValid = RegInit(false.B)
  private val tailBits = Reg(new Fp32ExactMid(tagWidth))
  private val resultValid = RegInit(false.B)
  private val resultTag = Reg(UInt(tagWidth.W))
  private val resultData = Reg(UInt(32.W))
  private val resultStatus = Reg(UInt(5.W))

  private val tailReady = !resultValid || io.out.ready
  private val roundReady = !tailValid || tailReady
  private val preReady = !roundValid || roundReady
  private val shiftReady = !preValid || preReady
  io.in.ready := !shiftValid || shiftReady
  private val shiftAdvance = shiftValid && shiftReady
  private val preAdvance = preValid && preReady
  private val roundAdvance = roundValid && roundReady
  private val tailAdvance = tailValid && tailReady

  when(io.in.fire) {
    shiftValid := true.B
    shiftBits.tag := io.in.bits.tag
    shiftBits.operation := io.in.bits.operation
    shiftBits.exactFunction := io.in.bits.exactFunction
    shiftBits.roundingMode := io.in.bits.roundingMode
    shiftBits.simpleData := simpleData
    shiftBits.simpleStatus := simpleStatus
    shiftBits.fpToIntTruncated := truncated
    shiftBits.fpToIntFraction := fraction
    shiftBits.fpToIntShiftRight := magnitudeShiftRight
    shiftBits.fpToIntShiftNonzero := magnitudeShiftRight > 0.U
    shiftBits.fpToIntNegative := aNegative
    shiftBits.fpToIntNan := aNaN
    shiftBits.fpToIntInfinity := fpInfinity
    shiftBits.fpToIntUnsigned := fpToIntUnsigned
    shiftBits.intToFpSign := intNegative
    shiftBits.intToFpMagnitude := intMagnitude
    shiftBits.intToFpTop := intTop
    shiftBits.intToFpZero := intZero
  }.elsewhen(shiftAdvance) {
    shiftValid := false.B
  }

  private val preHalfFraction = Mux(
    shiftBits.fpToIntShiftNonzero,
    1.U << (shiftBits.fpToIntShiftRight - 1.U),
    0.U
  )
  private val preRoundUpNearestEven =
    shiftBits.fpToIntShiftNonzero &&
      (shiftBits.fpToIntFraction > preHalfFraction ||
        (shiftBits.fpToIntFraction === preHalfFraction &&
          shiftBits.fpToIntTruncated(0)))
  private val preRoundUpMaxMagnitude =
    shiftBits.fpToIntShiftNonzero &&
      shiftBits.fpToIntFraction >= preHalfFraction
  private val preAnyFraction = shiftBits.fpToIntFraction =/= 0.U
  private val preRoundUp = MuxLookup(
    shiftBits.roundingMode, preRoundUpNearestEven
  )(Seq(
    "b000".U -> preRoundUpNearestEven,
    "b001".U -> false.B,
    "b010".U -> Mux(shiftBits.fpToIntNegative, preAnyFraction, false.B),
    "b011".U -> Mux(shiftBits.fpToIntNegative, false.B, preAnyFraction),
    "b100".U -> preRoundUpMaxMagnitude
  ))
  private val preRounded = shiftBits.fpToIntTruncated +& preRoundUp
  private val preRoundedLow = preRounded(31, 0)
  private val preSignedOverflow = Mux(
    shiftBits.fpToIntNegative,
    preRounded > "h80000000".U,
    preRounded > "h7fffffff".U
  )
  private val preUnsignedOverflow = preRounded > "hffffffff".U
  private val preFpToIntInexact = preAnyFraction
  private val preFpToIntInvalid = shiftBits.fpToIntNan ||
    shiftBits.fpToIntInfinity || preSignedOverflow || preUnsignedOverflow ||
    (shiftBits.fpToIntUnsigned && shiftBits.fpToIntNegative)

  when(shiftAdvance) {
    preValid := true.B
    preBits.tag := shiftBits.tag
    preBits.operation := shiftBits.operation
    preBits.exactFunction := shiftBits.exactFunction
    preBits.roundingMode := shiftBits.roundingMode
    preBits.simpleData := shiftBits.simpleData
    preBits.simpleStatus := shiftBits.simpleStatus
    preBits.fpToIntRoundedLow := preRoundedLow
    preBits.fpToIntOverflowSigned := preSignedOverflow
    preBits.fpToIntOverflowUnsigned := preUnsignedOverflow
    preBits.fpToIntNan := shiftBits.fpToIntNan
    preBits.fpToIntInfinity := shiftBits.fpToIntInfinity
    preBits.fpToIntInvalid := preFpToIntInvalid
    preBits.fpToIntInexact := preFpToIntInexact
    preBits.fpToIntUnsigned := shiftBits.fpToIntUnsigned
    preBits.fpToIntNegative := shiftBits.fpToIntNegative
    preBits.intToFpSign := shiftBits.intToFpSign
    preBits.intToFpMagnitude := shiftBits.intToFpMagnitude
    preBits.intToFpTop := shiftBits.intToFpTop
    preBits.intToFpZero := shiftBits.intToFpZero
  }.elsewhen(preAdvance) {
    preValid := false.B
  }

  private val fpToIntSignedData = Mux(
    preBits.fpToIntNan,
    0.U,
    Mux(
      preBits.fpToIntInfinity || preBits.fpToIntOverflowSigned,
      Mux(
        preBits.fpToIntNegative,
        "h80000000".U,
        "h7fffffff".U
      ),
      Mux(
        preBits.fpToIntNegative,
        ~preBits.fpToIntRoundedLow + 1.U,
        preBits.fpToIntRoundedLow
      )
    )
  )
  private val fpToIntUnsignedData = Mux(
    preBits.fpToIntNan || preBits.fpToIntNegative,
    0.U,
    Mux(
      preBits.fpToIntInfinity || preBits.fpToIntOverflowUnsigned,
      "hffffffff".U,
      preBits.fpToIntRoundedLow
    )
  )
  private val fpToIntData = Mux(
    preBits.fpToIntUnsigned,
    fpToIntUnsignedData,
    fpToIntSignedData
  )
  private val fpToIntStatus =
    Mux(preBits.fpToIntInvalid, "h10".U, 0.U) |
      Mux(preBits.fpToIntInexact, "h01".U, 0.U)

  private val midShiftRight = Mux(
    preBits.intToFpTop >= 23.U,
    preBits.intToFpTop - 23.U,
    0.U
  )
  private val midShiftLeft = Mux(
    preBits.intToFpTop < 23.U,
    23.U - preBits.intToFpTop,
    0.U
  )
  private val midIntSig = Mux(
    preBits.intToFpTop >= 23.U,
    preBits.intToFpMagnitude >> midShiftRight,
    preBits.intToFpMagnitude << midShiftLeft
  )
  private val midDroppedMask = Mux(
    midShiftRight > 0.U, (1.U << midShiftRight) - 1.U, 0.U)
  private val midDropped = preBits.intToFpMagnitude & midDroppedMask
  private val midHalfDropped = Mux(
    midShiftRight > 0.U, 1.U << (midShiftRight - 1.U), 0.U)
  private val midRoundUpNearestEven =
    midShiftRight > 0.U &&
      (midDropped > midHalfDropped ||
        (midDropped === midHalfDropped && midIntSig(22)))
  private val midRoundUpMaxMagnitude =
    midShiftRight > 0.U && midDropped >= midHalfDropped
  private val midRoundUpAny = midShiftRight > 0.U && midDropped =/= 0.U
  private val midRoundUp = MuxLookup(
    preBits.roundingMode, midRoundUpNearestEven
  )(Seq(
    "b000".U -> midRoundUpNearestEven,
    "b001".U -> false.B,
    "b010".U -> Mux(preBits.intToFpSign, midRoundUpAny, false.B),
    "b011".U -> Mux(preBits.intToFpSign, false.B, midRoundUpAny),
    "b100".U -> midRoundUpMaxMagnitude
  ))
  private val midRoundedSig = midIntSig(23, 0) +& midRoundUp
  private val midIntCarry = midRoundedSig(24)
  private val midIntToFpInexact = midDropped =/= 0.U

  when(preAdvance) {
    roundValid := true.B
    roundBits.tag := preBits.tag
    roundBits.operation := preBits.operation
    roundBits.data := Mux(
      preBits.operation === Fp32Operation.fpToInt,
      fpToIntData,
      preBits.simpleData
    )
    roundBits.status := Mux(
      preBits.operation === Fp32Operation.fpToInt,
      fpToIntStatus,
      preBits.simpleStatus
    )
    roundBits.intToFpSign := preBits.intToFpSign
    roundBits.intToFpTop := preBits.intToFpTop
    roundBits.intToFpRoundedSig := midRoundedSig(23, 0)
    roundBits.intToFpCarry := midIntCarry
    roundBits.intToFpInexact := midIntToFpInexact
    roundBits.intToFpZero := preBits.intToFpZero
  }.elsewhen(roundAdvance) {
    roundValid := false.B
  }

  private val tailIntExp =
    127.U(8.W) + roundBits.intToFpTop +
      Mux(roundBits.intToFpCarry, 1.U, 0.U)
  private val tailIntMant = Mux(
    roundBits.intToFpCarry,
    0.U,
    roundBits.intToFpRoundedSig(22, 0)
  )
  private val tailIntToFpData = Mux(
    roundBits.intToFpZero,
    0.U,
    Cat(roundBits.intToFpSign, tailIntExp, tailIntMant)
  )
  private val tailIntToFpStatus =
    Mux(roundBits.intToFpInexact, "h01".U, 0.U)
  private val tailData = Mux(
    roundBits.operation === Fp32Operation.intToFp,
    tailIntToFpData,
    roundBits.data
  )
  private val tailStatus = Mux(
    roundBits.operation === Fp32Operation.intToFp,
    tailIntToFpStatus,
    roundBits.status
  )

  when(roundAdvance) {
    tailValid := true.B
    tailBits.tag := roundBits.tag
    tailBits.data := tailData
    tailBits.status := tailStatus
  }.elsewhen(tailAdvance) {
    tailValid := false.B
  }

  when(tailAdvance) {
    resultValid := true.B
    resultTag := tailBits.tag
    resultData := tailBits.data
    resultStatus := tailBits.status
  }.elsewhen(io.out.fire) {
    resultValid := false.B
  }

  io.out.valid := resultValid
  io.out.bits.tag := resultTag
  io.out.bits.result := resultData
  io.out.bits.status := resultStatus
}
