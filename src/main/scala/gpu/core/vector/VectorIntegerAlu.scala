package gpu.core.vector

import chisel3._
import chisel3.util._
import gpu.config.GpuConfig

private class NormalizedVectorIntegerRequest(config: GpuConfig) extends Bundle {
  val warpId = UInt(config.warpIdWidth.W)
  val pc = UInt(config.xLen.W)
  val warpActiveMask = UInt(config.lanes.W)
  val vd = UInt(5.W)
  val oldVd = Vec(config.lanes, UInt(config.xLen.W))
  val lhs = Vec(config.lanes, UInt(config.xLen.W))
  val rhs = Vec(config.lanes, UInt(config.xLen.W))
  val enabled = UInt(config.lanes.W)
  val funct6 = UInt(6.W)
}

private class VectorIntegerCandidates(config: GpuConfig) extends Bundle {
  val warpId = UInt(config.warpIdWidth.W)
  val pc = UInt(config.xLen.W)
  val warpActiveMask = UInt(config.lanes.W)
  val vd = UInt(5.W)
  val oldVd = Vec(config.lanes, UInt(config.xLen.W))
  val enabled = UInt(config.lanes.W)
  val funct6 = UInt(6.W)
  val basic = Vec(config.lanes, UInt(config.xLen.W))
  val saturating = Vec(config.lanes, UInt(config.xLen.W))
  val saturationLimit = Vec(config.lanes, UInt(config.xLen.W))
  val shift = Vec(config.lanes, UInt(config.xLen.W))
  val comparison = UInt(config.lanes.W)
  val saturated = UInt(config.lanes.W)
}

/** Lane-local RVV integer ALU for the fixed SEW=32, LMUL=1 GPU profile.
  *
  * The unit implements the precise funct6 encodings accepted by VectorDecoder.
  * Inactive or masked-off lanes preserve oldVd. Results are held under output
  * backpressure and the unit sustains one operation per cycle when unstalled.
  */
class VectorIntegerAlu(config: GpuConfig = GpuConfig()) extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new VectorIntegerRequest(config)))
    val out = Decoupled(new VectorIntegerResult(config))
  })

  private val inputValid = RegInit(false.B)
  private val inputBits = Reg(new NormalizedVectorIntegerRequest(config))
  private val candidateValid = RegInit(false.B)
  private val candidateBits = Reg(new VectorIntegerCandidates(config))
  private val outputValid = RegInit(false.B)
  private val outputBits = Reg(new VectorIntegerResult(config))
  private val outputReady = !outputValid || io.out.ready
  private val candidateReady = !candidateValid || outputReady
  private val inputReady = !inputValid || candidateReady

  private val basicCandidates = Wire(Vec(config.lanes, UInt(config.xLen.W)))
  private val saturatingCandidates =
    Wire(Vec(config.lanes, UInt(config.xLen.W)))
  private val saturationLimits =
    Wire(Vec(config.lanes, UInt(config.xLen.W)))
  private val shiftCandidates = Wire(Vec(config.lanes, UInt(config.xLen.W)))
  private val laneComparisons = Wire(Vec(config.lanes, Bool()))
  private val laneSaturated = Wire(Vec(config.lanes, Bool()))

  for (lane <- 0 until config.lanes) {
    val lhs = inputBits.lhs(lane)
    val rhs = inputBits.rhs(lane)
    val enabled = inputBits.enabled(lane)
    val shiftAmount = rhs(4, 0)

    val unsignedAdd = lhs +& rhs
    val addResult = unsignedAdd(31, 0)
    val subResult = lhs - rhs
    val signedMax = ((BigInt(1) << 31) - 1).U(32.W)
    val signedMin = (BigInt(1) << 31).U(32.W)
    // Detect signed overflow from operand/result sign bits.  This is exactly
    // equivalent to comparing a 33-bit signed result against INT_MIN/MAX, but
    // avoids putting a wide magnitude comparator after the adder.
    val signedAddOverflow =
      !lhs(31) && !rhs(31) && addResult(31)
    val signedAddUnderflow =
      lhs(31) && rhs(31) && !addResult(31)
    val signedSubOverflow =
      !lhs(31) && rhs(31) && subResult(31)
    val signedSubUnderflow =
      lhs(31) && !rhs(31) && !subResult(31)

    val basicResult = MuxLookup(inputBits.funct6, 0.U(32.W))(Seq(
      "h00".U -> addResult,
      "h02".U -> subResult,
      "h03".U -> (rhs - lhs),
      "h04".U -> Mux(lhs < rhs, lhs, rhs),
      "h05".U -> Mux(lhs.asSInt < rhs.asSInt, lhs, rhs),
      "h06".U -> Mux(lhs > rhs, lhs, rhs),
      "h07".U -> Mux(lhs.asSInt > rhs.asSInt, lhs, rhs),
      "h09".U -> (lhs & rhs),
      "h0a".U -> (lhs | rhs),
      "h0b".U -> (lhs ^ rhs)
    ))
    // Register the raw arithmetic result here and apply the saturation clamp
    // in the existing output stage.  This keeps carry propagation and result
    // clamping on opposite sides of the candidate register.
    val saturatingResult = Mux(
      inputBits.funct6 === "h20".U || inputBits.funct6 === "h21".U,
      addResult,
      subResult
    )
    val saturationLimit = MuxLookup(inputBits.funct6, 0.U(32.W))(Seq(
      "h20".U -> Fill(32, 1.U),
      "h21".U -> Mux(lhs(31), signedMin, signedMax),
      "h22".U -> 0.U,
      "h23".U -> Mux(lhs(31), signedMin, signedMax)
    ))
    val shiftResult = MuxLookup(inputBits.funct6, 0.U(32.W))(Seq(
      "h25".U -> (lhs << shiftAmount)(31, 0),
      "h28".U -> (lhs >> shiftAmount),
      "h29".U -> (lhs.asSInt >> shiftAmount).asUInt
    ))

    val predicate = MuxLookup(inputBits.funct6, false.B)(Seq(
      "h18".U -> (lhs === rhs),
      "h19".U -> (lhs =/= rhs),
      "h1a".U -> (lhs < rhs),
      "h1b".U -> (lhs.asSInt < rhs.asSInt),
      "h1c".U -> (lhs <= rhs),
      "h1d".U -> (lhs.asSInt <= rhs.asSInt),
      "h1e".U -> (lhs > rhs),
      "h1f".U -> (lhs.asSInt > rhs.asSInt)
    ))

    val saturatedLane = MuxLookup(inputBits.funct6, false.B)(Seq(
      "h20".U -> unsignedAdd(32),
      "h21".U -> (signedAddOverflow || signedAddUnderflow),
      "h22".U -> (lhs < rhs),
      "h23".U -> (signedSubOverflow || signedSubUnderflow)
    ))

    basicCandidates(lane) := basicResult
    saturatingCandidates(lane) := saturatingResult
    saturationLimits(lane) := saturationLimit
    shiftCandidates(lane) := shiftResult
    laneComparisons(lane) := predicate
    laneSaturated(lane) := saturatedLane
  }

  private val outputComparison =
    candidateBits.funct6 >= "h18".U && candidateBits.funct6 <= "h1f".U
  private val outputSaturating =
    candidateBits.funct6 >= "h20".U && candidateBits.funct6 <= "h23".U
  private val outputShift =
    candidateBits.funct6 === "h25".U ||
      candidateBits.funct6 === "h28".U ||
      candidateBits.funct6 === "h29".U
  private val selectedResults = Wire(Vec(config.lanes, UInt(config.xLen.W)))
  for (lane <- 0 until config.lanes) {
    val saturatedResult = Mux(
      candidateBits.saturated(lane),
      candidateBits.saturationLimit(lane),
      candidateBits.saturating(lane)
    )
    val selected = Mux(
      outputSaturating,
      saturatedResult,
      Mux(outputShift, candidateBits.shift(lane), candidateBits.basic(lane))
    )
    selectedResults(lane) := Mux(
      candidateBits.enabled(lane) && !outputComparison,
      selected,
      candidateBits.oldVd(lane)
    )
  }

  io.in.ready := inputReady
  io.out.valid := outputValid
  io.out.bits := outputBits

  when(outputReady) {
    outputValid := candidateValid
    when(candidateValid) {
      outputBits.warpId := candidateBits.warpId
      outputBits.pc := candidateBits.pc
      outputBits.warpActiveMask := candidateBits.warpActiveMask
      outputBits.vd := candidateBits.vd
      outputBits.data := selectedResults
      outputBits.mask :=
        candidateBits.comparison & candidateBits.enabled
      outputBits.writesMask := outputComparison
      outputBits.saturated :=
        outputSaturating &&
          (candidateBits.saturated & candidateBits.enabled).orR
    }
  }

  when(candidateReady) {
    candidateValid := inputValid
    when(inputValid) {
      candidateBits.warpId := inputBits.warpId
      candidateBits.pc := inputBits.pc
      candidateBits.warpActiveMask := inputBits.warpActiveMask
      candidateBits.vd := inputBits.vd
      candidateBits.oldVd := inputBits.oldVd
      candidateBits.enabled := inputBits.enabled
      candidateBits.funct6 := inputBits.funct6
      candidateBits.basic := basicCandidates
      candidateBits.saturating := saturatingCandidates
      candidateBits.saturationLimit := saturationLimits
      candidateBits.shift := shiftCandidates
      candidateBits.comparison := laneComparisons.asUInt
      candidateBits.saturated := laneSaturated.asUInt
    }
  }

  when(inputReady) {
    inputValid := io.in.valid
    when(io.in.valid) {
      val inputIsVv = io.in.bits.operandType === "b000".U
      val inputIsVx = io.in.bits.operandType === "b100".U
      val inputImmediate =
        Cat(
          Fill(config.xLen - 5, io.in.bits.immediate(4)),
          io.in.bits.immediate
        )
      inputBits.warpId := io.in.bits.warpId
      inputBits.pc := io.in.bits.pc
      inputBits.warpActiveMask := io.in.bits.warpActiveMask
      inputBits.vd := io.in.bits.vd
      inputBits.funct6 := io.in.bits.funct6
      inputBits.enabled :=
        io.in.bits.activeMask &
          Mux(io.in.bits.vm, Fill(config.lanes, 1.U), io.in.bits.predicateMask)
      for (lane <- 0 until config.lanes) {
        inputBits.oldVd(lane) := io.in.bits.oldVd(lane)
        inputBits.lhs(lane) := io.in.bits.vs2(lane)
        inputBits.rhs(lane) := Mux(
          inputIsVv,
          io.in.bits.vs1(lane),
          Mux(inputIsVx, io.in.bits.scalar, inputImmediate)
        )
      }
    }
  }

  when(io.in.valid) {
    assert(
      io.in.bits.operandType === "b000".U ||
        io.in.bits.operandType === "b011".U ||
        io.in.bits.operandType === "b100".U,
      "VectorIntegerAlu received an unsupported RVV operand form"
    )
  }
}
