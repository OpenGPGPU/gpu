package opengpu.core.execute.fpu

import chisel3._
import chisel3.util._

/** Combinational RVV FP32 vfrec7.v/vfrsqrt7.v estimation lane.
  *
  * The 7-bit mantissa lookup and exponent correction follow the RISC-V V
  * reciprocal/reciprocal-square-root tables used by the YunSuan golden model.
  * Subnormal vfrec7 inputs that normalize below the smallest normal exponent
  * overflow to infinity (or the largest finite value for directed rounding
  * toward zero) and report OF|NX.
  */
class Fp32EstimateLane(tagWidth: Int = 16) extends Module {
  require(tagWidth > 0)

  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new Fp32Request(tagWidth)))
    val out = Decoupled(new Fp32Response(tagWidth))
  })

  private val resultValid = RegInit(false.B)
  private val resultData = Reg(UInt(32.W))
  private val resultStatus = Reg(UInt(5.W))
  private val resultTag = Reg(UInt(tagWidth.W))

  io.in.ready := !resultValid || io.out.ready
  io.out.valid := resultValid
  io.out.bits.result := resultData
  io.out.bits.status := resultStatus
  io.out.bits.tag := resultTag
  when(io.out.fire) { resultValid := false.B }

  private val a = io.in.bits.operandA
  private val sign = a(31)
  private val exponent = a(30, 23)
  private val mantissa = a(22, 0)
  private val isInfinity = exponent === 0xff.U && mantissa === 0.U
  private val isZeroValue = exponent === 0.U && mantissa === 0.U
  private val isNaN = exponent === 0xff.U && mantissa =/= 0.U
  private val isSNaN = isNaN && !mantissa(22)
  private val isSubnormal = exponent === 0.U && mantissa =/= 0.U
  private val isRecip = io.in.bits.operation === Fp32Operation.recip7
  private val isRsqrt = io.in.bits.operation === Fp32Operation.rsqrt7

  private val normShift = PriorityEncoder(Reverse(mantissa))
  private val normExp = Mux(
    isSubnormal,
    0.S(10.W) - normShift.zext.asSInt.pad(10),
    exponent.zext.asSInt.pad(10)
  )
  private val normMantissa = Mux(
    isSubnormal,
    (mantissa << (normShift + 1.U))(22, 0),
    mantissa
  )

  private val recipTable = VecInit(Seq(
    127, 125, 123, 121, 119, 117, 116, 114,
    112, 110, 109, 107, 105, 104, 102, 100,
    99, 97, 96, 94, 93, 91, 90, 88,
    87, 85, 84, 83, 81, 80, 79, 77,
    76, 75, 74, 72, 71, 70, 69, 68,
    66, 65, 64, 63, 62, 61, 60, 59,
    58, 57, 56, 55, 54, 53, 52, 51,
    50, 49, 48, 47, 46, 45, 44, 43,
    42, 41, 40, 40, 39, 38, 37, 36,
    35, 35, 34, 33, 32, 31, 31, 30,
    29, 28, 28, 27, 26, 25, 25, 24,
    23, 23, 22, 21, 21, 20, 19, 19,
    18, 17, 17, 16, 15, 15, 14, 14,
    13, 12, 12, 11, 11, 10, 9, 9,
    8, 8, 7, 7, 6, 5, 5, 4,
    4, 3, 3, 2, 2, 1, 1, 0
  ).map(_.U(7.W)))
  private val rsqrtTable = VecInit(Seq(
    52, 51, 50, 48, 47, 46, 44, 43,
    42, 41, 40, 39, 38, 36, 35, 34,
    33, 32, 31, 30, 30, 29, 28, 27,
    26, 25, 24, 23, 23, 22, 21, 20,
    19, 19, 18, 17, 16, 16, 15, 14,
    14, 13, 12, 12, 11, 10, 10, 9,
    9, 8, 7, 7, 6, 6, 5, 4,
    4, 3, 3, 2, 2, 1, 1, 0,
    127, 125, 123, 121, 119, 118, 116, 114,
    113, 111, 109, 108, 106, 105, 103, 102,
    100, 99, 97, 96, 95, 93, 92, 91,
    90, 88, 87, 86, 85, 84, 83, 82,
    80, 79, 78, 77, 76, 75, 74, 73,
    72, 71, 70, 70, 69, 68, 67, 66,
    65, 64, 63, 63, 62, 61, 60, 59,
    59, 58, 57, 56, 56, 55, 54, 53
  ).map(_.U(7.W)))

  private val recipEstimate = recipTable(normMantissa(22, 16))
  private val rsqrtEstimate =
    rsqrtTable(Cat(normExp(0), normMantissa(22, 17)))
  private val estimateExp = Mux(
    isRecip,
    (253.S(10.W) - normExp).asUInt(7, 0),
    ((380.S(10.W) - normExp) >> 1).asUInt(7, 0)
  )
  private val estimateSignificand = Mux(isRecip, recipEstimate, rsqrtEstimate)
  private val estimateResult =
    Cat(sign, estimateExp, estimateSignificand, 0.U(16.W))

  private val canonicalNaN = "h7fc00000".U
  private val zeroResult = Cat(sign, 0.U(31.W))
  private val infinityResult = Cat(sign, 0xff.U, 0.U(23.W))
  private val maxFiniteResult = Cat(sign, 0xfe.U, Fill(23, 1.U))
  private val nvFlag = "h10".U
  private val dzFlag = "h08".U
  private val overflowFlag = "h05".U

  private val recOverflow =
    isSubnormal && normShift > 0.U
  private val finiteOnOverflow =
    io.in.bits.roundingMode === "b001".U ||
      (io.in.bits.roundingMode === "b010".U && !sign) ||
      (io.in.bits.roundingMode === "b011".U && sign)
  private val recResult = Mux(
    isNaN,
    canonicalNaN,
    Mux(
      isInfinity,
      zeroResult,
      Mux(
        isZeroValue,
        infinityResult,
        Mux(recOverflow, Mux(finiteOnOverflow, maxFiniteResult, infinityResult), estimateResult)
      )
    )
  )
  private val recFlags = Mux(
    isNaN,
    Mux(isSNaN, nvFlag, 0.U),
    Mux(isZeroValue, dzFlag, Mux(recOverflow, overflowFlag, 0.U))
  )

  private val rsqrtResult = Mux(
    isNaN,
    canonicalNaN,
    Mux(
      isZeroValue,
      infinityResult,
      Mux(
        sign,
        canonicalNaN,
        Mux(isInfinity, zeroResult, estimateResult)
      )
    )
  )
  private val rsqrtFlags = Mux(
    isNaN,
    Mux(isSNaN, nvFlag, 0.U),
    Mux(isZeroValue, dzFlag, Mux(sign, nvFlag, 0.U))
  )

  private val result = Mux(isRecip, recResult, rsqrtResult)
  private val status = Mux(isRecip, recFlags, rsqrtFlags)

  when(io.in.fire) {
    resultValid := true.B
    resultData := result
    resultStatus := status
    resultTag := io.in.bits.tag
  }

  when(io.in.valid) {
    assert(
      io.in.bits.operation === Fp32Operation.recip7 ||
        io.in.bits.operation === Fp32Operation.rsqrt7,
      "Fp32EstimateLane received a non-estimate operation"
    )
  }
}
