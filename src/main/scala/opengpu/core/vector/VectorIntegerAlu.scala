package opengpu.core.vector

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig

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

private class VectorIntegerPartial(config: GpuConfig) extends Bundle {
  val warpId = UInt(config.warpIdWidth.W)
  val pc = UInt(config.xLen.W)
  val warpActiveMask = UInt(config.lanes.W)
  val vd = UInt(5.W)
  val oldVd = Vec(config.lanes, UInt(config.xLen.W))
  val enabled = UInt(config.lanes.W)
  val funct6 = UInt(6.W)
  val lhs = Vec(config.lanes, UInt(config.xLen.W))
  val rhs = Vec(config.lanes, UInt(config.xLen.W))
  val add = Vec(config.lanes, UInt(config.xLen.W))
  val sub = Vec(config.lanes, UInt(config.xLen.W))
  val shift = Vec(config.lanes, UInt(config.xLen.W))
  val less = Vec(config.lanes, Bool())
  val lessSigned = Vec(config.lanes, Bool())
  val greater = Vec(config.lanes, Bool())
  val greaterSigned = Vec(config.lanes, Bool())
  val equal = Vec(config.lanes, Bool())
  val saturated = Vec(config.lanes, Bool())
  val saturationLimit = Vec(config.lanes, UInt(config.xLen.W))
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
  private val partialValid = RegInit(false.B)
  private val partialBits = Reg(new VectorIntegerPartial(config))
  private val candidateValid = RegInit(false.B)
  private val candidateBits = Reg(new VectorIntegerCandidates(config))
  private val outputValid = RegInit(false.B)
  private val outputBits = Reg(new VectorIntegerResult(config))
  private val outputReady = !outputValid || io.out.ready
  private val candidateReady = !candidateValid || outputReady
  private val partialReady = !partialValid || candidateReady
  private val inputReady = !inputValid || partialReady

  private val basicCandidates = Wire(Vec(config.lanes, UInt(config.xLen.W)))
  private val saturatingCandidates =
    Wire(Vec(config.lanes, UInt(config.xLen.W)))
  private val saturationLimits =
    Wire(Vec(config.lanes, UInt(config.xLen.W)))
  private val shiftCandidates = Wire(Vec(config.lanes, UInt(config.xLen.W)))
  private val laneComparisons = Wire(Vec(config.lanes, Bool()))
  private val laneSaturated = Wire(Vec(config.lanes, Bool()))

  for (lane <- 0 until config.lanes) {
    val lhs = partialBits.lhs(lane)
    val rhs = partialBits.rhs(lane)
    val enabled = partialBits.enabled(lane)
    val shiftAmount = rhs(4, 0)

    val signedMax = ((BigInt(1) << 31) - 1).U(32.W)
    val signedMin = (BigInt(1) << 31).U(32.W)

    val quadBase = (lane / 4) * 4
    val quadDx = if (quadBase + 3 < config.lanes) {
      val left = if (lane % 4 < 2) quadBase else quadBase + 2
      partialBits.lhs(left + 1) - partialBits.lhs(left)
    } else 0.U(32.W)
    val quadDy = if (quadBase + 3 < config.lanes) {
      val top = if (lane % 2 == 0) quadBase else quadBase + 1
      partialBits.lhs(top + 2) - partialBits.lhs(top)
    } else 0.U(32.W)

    val basicResult = MuxLookup(partialBits.funct6, 0.U(32.W))(Seq(
      "h00".U -> partialBits.add(lane),
      "h02".U -> partialBits.sub(lane),
      "h03".U -> (0.U - partialBits.sub(lane)),
      "h04".U -> Mux(partialBits.less(lane), lhs, rhs),
      "h05".U -> Mux(partialBits.lessSigned(lane), lhs, rhs),
      "h06".U -> Mux(partialBits.greater(lane), lhs, rhs),
      "h07".U -> Mux(partialBits.greaterSigned(lane), lhs, rhs),
      "h09".U -> (lhs & rhs),
      "h0a".U -> (lhs | rhs),
      "h0b".U -> (lhs ^ rhs),
      "h0c".U -> quadDx,
      "h0d".U -> quadDy
    ))
    val saturatingResult = Mux(
      partialBits.funct6 === "h20".U || partialBits.funct6 === "h21".U,
      partialBits.add(lane),
      partialBits.sub(lane)
    )

    val predicate = MuxLookup(partialBits.funct6, false.B)(Seq(
      "h18".U -> partialBits.equal(lane),
      "h19".U -> !partialBits.equal(lane),
      "h1a".U -> partialBits.less(lane),
      "h1b".U -> partialBits.lessSigned(lane),
      "h1c".U -> (partialBits.less(lane) || partialBits.equal(lane)),
      "h1d".U -> (partialBits.lessSigned(lane) || partialBits.equal(lane)),
      "h1e".U -> partialBits.greater(lane),
      "h1f".U -> partialBits.greaterSigned(lane)
    ))

    basicCandidates(lane) := basicResult
    saturatingCandidates(lane) := saturatingResult
    saturationLimits(lane) := partialBits.saturationLimit(lane)
    shiftCandidates(lane) := partialBits.shift(lane)
    laneComparisons(lane) := predicate
    laneSaturated(lane) := partialBits.saturated(lane)
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
    candidateValid := partialValid
    when(partialValid) {
      candidateBits.warpId := partialBits.warpId
      candidateBits.pc := partialBits.pc
      candidateBits.warpActiveMask := partialBits.warpActiveMask
      candidateBits.vd := partialBits.vd
      candidateBits.oldVd := partialBits.oldVd
      candidateBits.enabled := partialBits.enabled
      candidateBits.funct6 := partialBits.funct6
      candidateBits.basic := basicCandidates
      candidateBits.saturating := saturatingCandidates
      candidateBits.saturationLimit := saturationLimits
      candidateBits.shift := shiftCandidates
      candidateBits.comparison := laneComparisons.asUInt
      candidateBits.saturated := laneSaturated.asUInt
    }
  }

  when(partialReady) {
    partialValid := inputValid
    when(inputValid) {
      partialBits.warpId := inputBits.warpId
      partialBits.pc := inputBits.pc
      partialBits.warpActiveMask := inputBits.warpActiveMask
      partialBits.vd := inputBits.vd
      partialBits.oldVd := inputBits.oldVd
      partialBits.enabled := inputBits.enabled
      partialBits.funct6 := inputBits.funct6
      for (lane <- 0 until config.lanes) {
        val lhs = inputBits.lhs(lane)
        val rhs = inputBits.rhs(lane)
        val shiftAmount = rhs(4, 0)
        val unsignedAdd = lhs +& rhs
        val addResult = unsignedAdd(31, 0)
        val subResult = lhs - rhs
        val signedAddOverflow =
          !lhs(31) && !rhs(31) && addResult(31)
        val signedAddUnderflow =
          lhs(31) && rhs(31) && !addResult(31)
        val signedSubOverflow =
          !lhs(31) && rhs(31) && subResult(31)
        val signedSubUnderflow =
          lhs(31) && !rhs(31) && !subResult(31)
        val less = lhs < rhs
        val lessSigned = lhs.asSInt < rhs.asSInt
        val greater = lhs > rhs
        val greaterSigned = lhs.asSInt > rhs.asSInt
        val equal = lhs === rhs
        partialBits.lhs(lane) := lhs
        partialBits.rhs(lane) := rhs
        partialBits.add(lane) := addResult
        partialBits.sub(lane) := subResult
        partialBits.shift(lane) := MuxLookup(inputBits.funct6, 0.U(32.W))(Seq(
          "h25".U -> (lhs << shiftAmount)(31, 0),
          "h28".U -> (lhs >> shiftAmount),
          "h29".U -> (lhs.asSInt >> shiftAmount).asUInt
        ))
        partialBits.less(lane) := less
        partialBits.lessSigned(lane) := lessSigned
        partialBits.greater(lane) := greater
        partialBits.greaterSigned(lane) := greaterSigned
        partialBits.equal(lane) := equal
        partialBits.saturated(lane) := MuxLookup(
          inputBits.funct6, false.B
        )(Seq(
          "h20".U -> unsignedAdd(32),
          "h21".U -> (signedAddOverflow || signedAddUnderflow),
          "h22".U -> less,
          "h23".U -> (signedSubOverflow || signedSubUnderflow)
        ))
        partialBits.saturationLimit(lane) :=
          MuxLookup(inputBits.funct6, 0.U(32.W))(Seq(
            "h20".U -> Fill(32, 1.U),
            "h21".U -> Mux(
              lhs(31),
              (BigInt(1) << 31).U(32.W),
              ((BigInt(1) << 31) - 1).U(32.W)
            ),
            "h22".U -> 0.U,
            "h23".U -> Mux(
              lhs(31),
              (BigInt(1) << 31).U(32.W),
              ((BigInt(1) << 31) - 1).U(32.W)
            )
          ))
      }
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
