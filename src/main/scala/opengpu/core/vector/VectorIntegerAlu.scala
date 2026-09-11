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
  // Raw operands cross the input boundary directly. Cross-lane selection is
  // deliberately deferred to the following registered pipeline stage.
  val lhs = Vec(config.lanes, UInt(config.xLen.W))
  val vs2 = Vec(config.lanes, UInt(config.xLen.W))
  val rhs = Vec(config.lanes, UInt(config.xLen.W))
  val vs1 = Vec(config.lanes, UInt(config.xLen.W))
  // Odd half of the narrowing source pair; lhs carries the even half.
  val vs2Odd = Vec(config.lanes, UInt(config.xLen.W))
  val vxrm = UInt(2.W)
  val enabled = UInt(config.lanes.W)
  val sourceEnabled = UInt(config.lanes.W)
  val funct6 = UInt(6.W)
  val operandType = UInt(3.W)
  val immediate = UInt(5.W)
  val quad = Bool()
  val reduction = Bool()
}

private class VectorIntegerCandidates(config: GpuConfig) extends Bundle {
  val warpId = UInt(config.warpIdWidth.W)
  val pc = UInt(config.xLen.W)
  val warpActiveMask = UInt(config.lanes.W)
  val vd = UInt(5.W)
  val oldVd = Vec(config.lanes, UInt(config.xLen.W))
  val enabled = UInt(config.lanes.W)
  val funct6 = UInt(6.W)
  val immediate = UInt(5.W)
  val quad = Bool()
  val reduction = Bool()
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
  val immediate = UInt(5.W)
  val quad = Bool()
  val reduction = Bool()
  val lhs = Vec(config.lanes, UInt(config.xLen.W))
  val rhs = Vec(config.lanes, UInt(config.xLen.W))
  val add = Vec(config.lanes, UInt(config.xLen.W))
  val sub = Vec(config.lanes, UInt(config.xLen.W))
  val shift = Vec(config.lanes, UInt(config.xLen.W))
  val fixedShift = Vec(config.lanes, UInt(64.W))
  val fixedRound = Vec(config.lanes, Bool())
  val less = Vec(config.lanes, Bool())
  val lessSigned = Vec(config.lanes, Bool())
  val greater = Vec(config.lanes, Bool())
  val greaterSigned = Vec(config.lanes, Bool())
  val equal = Vec(config.lanes, Bool())
  val saturated = Vec(config.lanes, Bool())
  val saturationLimit = Vec(config.lanes, UInt(config.xLen.W))
  val widening = Vec(config.lanes, UInt(config.xLen.W))
}

/** RVV integer ALU for the fixed SEW=32, LMUL=1 GPU profile.
  *
  * The unit implements the precise funct6 encodings accepted by VectorDecoder,
  * including integer reductions, vrgather, and slide cross-lane selection.
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
  // Isolate the cross-lane gather/slide/reduction network from both the
  // external request boundary and the arithmetic/rounding stage.
  private val preparedValid = RegInit(false.B)
  private val preparedBits = Reg(new NormalizedVectorIntegerRequest(config))
  // Reductions are split so no register-to-register path contains the full
  // lane tree. This is particularly important for the 8-lane production
  // configuration, where a single tree dominated the 1 GHz timing target.
  private val reductionPairValid = RegInit(false.B)
  private val reductionPairBits = Reg(new NormalizedVectorIntegerRequest(config))
  private val reductionPairs =
    Reg(Vec((config.lanes + 1) / 2, UInt(config.xLen.W)))
  private val reducedValid = RegInit(false.B)
  private val reducedBits = Reg(new NormalizedVectorIntegerRequest(config))
  private val partialValid = RegInit(false.B)
  private val partialBits = Reg(new VectorIntegerPartial(config))
  private val candidateValid = RegInit(false.B)
  private val candidateBits = Reg(new VectorIntegerCandidates(config))
  private val outputValid = RegInit(false.B)
  private val outputBits = Reg(new VectorIntegerResult(config))
  private val outputReady = !outputValid || io.out.ready
  private val candidateReady = !candidateValid || outputReady
  private val partialReady = !partialValid || candidateReady
  private val reducedReady = !reducedValid || partialReady
  private val reductionPairReady = !reductionPairValid || reducedReady
  private val preparedReady = !preparedValid || reductionPairReady
  private val inputReady = !inputValid || preparedReady

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

    val basicResult = Mux(
      partialBits.reduction,
      lhs,
      MuxLookup(partialBits.funct6, 0.U(32.W))(Seq(
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
        "h0c".U -> Mux(partialBits.quad, quadDx, lhs),
        "h0d".U -> quadDy,
        "h0e".U -> lhs,
        "h0f".U -> lhs,
        // vsext/vzext vf2/vf4/vf8 use vs1=7/6, 5/4, and 3/2.
        "h12".U -> MuxLookup(partialBits.immediate, 0.U(32.W))(Seq(
          7.U -> Cat(Fill(16, lhs(15)), lhs(15, 0)),
          6.U -> Cat(0.U(16.W), lhs(15, 0)),
          5.U -> Cat(Fill(24, lhs(7)), lhs(7, 0)),
          4.U -> Cat(0.U(24.W), lhs(7, 0)),
          3.U -> Cat(Fill(28, lhs(3)), lhs(3, 0)),
          2.U -> Cat(0.U(28.W), lhs(3, 0))
        )),
        // Widening add/subtract/multiply operate on sign-extended 16-bit halves.
        "h30".U -> partialBits.widening(lane),
        "h31".U -> partialBits.widening(lane),
        "h32".U -> partialBits.widening(lane),
        "h33".U -> partialBits.widening(lane),
        "h34".U -> partialBits.widening(lane)
      ))
    )
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

    val clip = partialBits.funct6 === "h2e".U ||
      partialBits.funct6 === "h2f".U
    val signedClip = partialBits.funct6 === "h2f".U
    val scaling = partialBits.funct6 === "h2a".U ||
      partialBits.funct6 === "h2b".U
    // Keep the full shifted value through rounding before testing overflow.
    val shifted = partialBits.fixedShift(lane)
    val signedRounding = signedClip || partialBits.funct6 === "h2b".U
    val rounded = Cat(signedRounding && shifted(63), shifted) +
      partialBits.fixedRound(lane)
    val clipOverflow = Mux(signedClip,
      rounded.asSInt > ((BigInt(1) << 31) - 1).S(65.W) ||
        rounded.asSInt < (-(BigInt(1) << 31)).S(65.W),
      rounded(64, 32).orR)
    val clipLimit = Mux(signedClip,
      Mux(rounded(64), signedMin, signedMax), Fill(32, 1.U))

    basicCandidates(lane) := basicResult
    saturatingCandidates(lane) := Mux(clip, rounded(31, 0), saturatingResult)
    saturationLimits(lane) := Mux(clip, clipLimit, partialBits.saturationLimit(lane))
    shiftCandidates(lane) := Mux(scaling, rounded(31, 0), partialBits.shift(lane))
    laneComparisons(lane) := predicate
    laneSaturated(lane) := Mux(clip, clipOverflow, partialBits.saturated(lane))
  }

  private val outputComparison =
    candidateBits.funct6 >= "h18".U && candidateBits.funct6 <= "h1f".U
  private val outputSaturating =
    (candidateBits.funct6 >= "h20".U && candidateBits.funct6 <= "h23".U) ||
      candidateBits.funct6 === "h2e".U || candidateBits.funct6 === "h2f".U
  private val outputShift =
    candidateBits.funct6 === "h25".U ||
      candidateBits.funct6 === "h28".U ||
      candidateBits.funct6 === "h29".U ||
      candidateBits.funct6 === "h2a".U ||
      candidateBits.funct6 === "h2b".U ||
      candidateBits.funct6 === "h2c".U ||
      candidateBits.funct6 === "h2d".U
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
      candidateBits.immediate := partialBits.immediate
      candidateBits.quad := partialBits.quad
      candidateBits.reduction := partialBits.reduction
      candidateBits.basic := basicCandidates
      candidateBits.saturating := saturatingCandidates
      candidateBits.saturationLimit := saturationLimits
      candidateBits.shift := shiftCandidates
      candidateBits.comparison := laneComparisons.asUInt
      candidateBits.saturated := laneSaturated.asUInt
    }
  }

  when(partialReady) {
    partialValid := reducedValid
    when(reducedValid) {
      val inputBits = reducedBits
      partialBits.warpId := inputBits.warpId
      partialBits.pc := inputBits.pc
      partialBits.warpActiveMask := inputBits.warpActiveMask
      partialBits.vd := inputBits.vd
      partialBits.oldVd := inputBits.oldVd
      partialBits.enabled := inputBits.enabled
      partialBits.funct6 := inputBits.funct6
      partialBits.immediate := inputBits.immediate
      partialBits.quad := inputBits.quad
      partialBits.reduction := inputBits.reduction
      for (lane <- 0 until config.lanes) {
        val lhs = inputBits.lhs(lane)
        val rhs = inputBits.rhs(lane)
        val shiftAmount = rhs(4, 0)
        val unsignedAdd = lhs +& rhs
        val addResult = unsignedAdd(31, 0)
        val subResult = lhs - rhs
        // Widening operations: sign/zero extend lower 16 bits and operate.
        val lhs16se = Cat(Fill(16, lhs(15)), lhs(15, 0)).asSInt
        val rhs16se = Cat(Fill(16, rhs(15)), rhs(15, 0)).asSInt
        val lhs16u = Cat(0.U(16.W), lhs(15, 0))
        val rhs16u = Cat(0.U(16.W), rhs(15, 0))
        val widenAdd = (lhs16se +& rhs16se)(31, 0)
        val widenSub = (lhs16se -& rhs16se)(31, 0)
        val widenMulS = (lhs16se * rhs16se).asUInt
        val widenMulU = (lhs16u * rhs16u)(31, 0)
        val widenMulSU = (lhs16se * rhs16u.asSInt).asUInt
        partialBits.widening(lane) := MuxLookup(
          inputBits.funct6, 0.U(32.W)
        )(Seq(
          "h30".U -> widenAdd,
          "h31".U -> widenSub,
          "h32".U -> widenMulS,
          "h33".U -> widenMulU,
          "h34".U -> widenMulSU
        ))
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
        val scaling = inputBits.funct6 === "h2a".U ||
          inputBits.funct6 === "h2b".U
        val signedScaling = inputBits.funct6 === "h2b".U
        // Single-width scaling shares the rounding datapath, with no odd
        // source dependency and only five significant shift-amount bits.
        val wide = Mux(scaling, Cat(Fill(32, signedScaling && lhs(31)), lhs),
          Cat(inputBits.vs2Odd(lane), lhs))
        val narrowAmount = Mux(scaling, Cat(0.U(1.W), shiftAmount), rhs(5, 0))
        val signedNarrow = signedScaling || inputBits.funct6 === "h2d".U ||
          inputBits.funct6 === "h2f".U
        val fixedShift = Mux(signedNarrow,
          (wide.asSInt >> narrowAmount).asUInt, wide >> narrowAmount)
        // The guard bit and lower discarded bits implement vxrm for any
        // shift, including zero (which must never round).
        val discardedMask = ~(Fill(64, 1.U) << narrowAmount)(63, 0)
        val guardIndex = (narrowAmount - 1.U)(5, 0)
        val guard = narrowAmount =/= 0.U && wide(guardIndex)
        val stickyMask = ~(Fill(64, 1.U) << guardIndex)(63, 0)
        val sticky = (wide & stickyMask).orR
        val discarded = (wide & discardedMask).orR
        partialBits.fixedShift(lane) := fixedShift
        partialBits.fixedRound(lane) := MuxLookup(inputBits.vxrm, false.B)(Seq(
          0.U -> guard,
          1.U -> (guard && (sticky || fixedShift(0))),
          2.U -> false.B,
          3.U -> (!fixedShift(0) && discarded)
        ))
        partialBits.shift(lane) := MuxLookup(inputBits.funct6, 0.U(32.W))(Seq(
          "h25".U -> (lhs << shiftAmount)(31, 0),
          "h28".U -> (lhs >> shiftAmount),
          "h29".U -> (lhs.asSInt >> shiftAmount).asUInt,
          // vnsrl/vnsra narrow the 64-bit pair {vs2+1, vs2} to its low word.
          "h2c".U -> fixedShift(31, 0),
          "h2d".U -> fixedShift(31, 0)
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

  when(preparedReady) {
    preparedValid := inputValid
    when(inputValid) {
      preparedBits := inputBits
      val inputIsVv = inputBits.operandType === "b000".U
      val gatherIndexWidth = math.max(1, log2Ceil(config.lanes))
      for (lane <- 0 until config.lanes) {
        val gatherIndex = Mux(
          inputIsVv,
          inputBits.vs1(lane),
          inputBits.rhs(lane)
        )
        val gathered = Mux(
          gatherIndex < config.lanes.U,
          inputBits.lhs(gatherIndex(gatherIndexWidth - 1, 0)),
          0.U
        )
        val slideOffset = inputBits.rhs(lane)
        val slideUpIndex = lane.U(config.xLen.W) - slideOffset
        val slideUp = Mux(
          slideOffset <= lane.U,
          inputBits.lhs(slideUpIndex(gatherIndexWidth - 1, 0)),
          inputBits.oldVd(lane)
        )
        val slideDownIndex = lane.U(config.xLen.W) +& slideOffset
        val slideDown = Mux(
          slideDownIndex < config.lanes.U,
          inputBits.lhs(slideDownIndex(gatherIndexWidth - 1, 0)),
          0.U
        )
        val crossLane = MuxCase(inputBits.lhs(lane), Seq(
          (inputBits.funct6 === "h0c".U && !inputBits.quad) -> gathered,
          (inputBits.funct6 === "h0e".U) -> slideUp,
          (inputBits.funct6 === "h0f".U) -> slideDown
        ))
        preparedBits.lhs(lane) := crossLane
      }
    }
  }

  when(reductionPairReady) {
    reductionPairValid := preparedValid
    when(preparedValid) {
      reductionPairBits := preparedBits
      def combineReduction(left: UInt, right: UInt): UInt = MuxLookup(
        preparedBits.funct6, left)(Seq(
          "h00".U -> (left + right), "h01".U -> (left & right),
          "h02".U -> (left | right), "h03".U -> (left ^ right),
          "h04".U -> Mux(left < right, left, right),
          "h05".U -> Mux(left.asSInt < right.asSInt, left, right),
          "h06".U -> Mux(left > right, left, right),
          "h07".U -> Mux(left.asSInt > right.asSInt, left, right)
        ))
      val reductionIdentity = MuxLookup(preparedBits.funct6, 0.U(32.W))(Seq(
        "h01".U -> Fill(32, 1.U), "h04".U -> Fill(32, 1.U),
        "h05".U -> ((BigInt(1) << 31) - 1).U(32.W),
        "h07".U -> (BigInt(1) << 31).U(32.W)
      ))
      for (pair <- 0 until (config.lanes + 1) / 2) {
        val firstLane = pair * 2
        val left = Mux(preparedBits.sourceEnabled(firstLane),
          preparedBits.vs2(firstLane), reductionIdentity)
        val right = if (firstLane + 1 < config.lanes) {
          Mux(preparedBits.sourceEnabled(firstLane + 1),
            preparedBits.vs2(firstLane + 1), reductionIdentity)
        } else {
          reductionIdentity
        }
        reductionPairs(pair) := combineReduction(left, right)
      }
    }
  }

  when(reducedReady) {
    reducedValid := reductionPairValid
    when(reductionPairValid) {
      reducedBits := reductionPairBits
      def combineReduction(left: UInt, right: UInt): UInt = MuxLookup(
        reductionPairBits.funct6, left)(Seq(
          "h00".U -> (left + right), "h01".U -> (left & right),
          "h02".U -> (left | right), "h03".U -> (left ^ right),
          "h04".U -> Mux(left < right, left, right),
          "h05".U -> Mux(left.asSInt < right.asSInt, left, right),
          "h06".U -> Mux(left > right, left, right),
          "h07".U -> Mux(left.asSInt > right.asSInt, left, right)
        ))
      def reductionTree(values: Seq[UInt]): UInt = values match {
        case Seq(value) => value
        case _ => reductionTree(values.grouped(2).map {
          case Seq(left, right) => combineReduction(left, right)
          case Seq(left) => left
        }.toSeq)
      }
      reducedBits.lhs(0) := Mux(
        reductionPairBits.reduction,
        combineReduction(reductionPairBits.vs1(0), reductionTree(reductionPairs)),
        reductionPairBits.lhs(0)
      )
    }
  }

  when(inputReady) {
    inputValid := io.in.valid
    when(io.in.valid) {
      val inputIsReduction =
        io.in.bits.operandType === "b010".U && io.in.bits.funct6 <= "h07".U
      val sourceEnabled =
        io.in.bits.activeMask &
          Mux(io.in.bits.vm, Fill(config.lanes, 1.U), io.in.bits.predicateMask)
      val inputNarrowing = io.in.bits.funct6 >= "h2c".U &&
        io.in.bits.funct6 <= "h2f".U
      val inputImmediate =
        Cat(
          Fill(config.xLen - 5, io.in.bits.immediate(4) && !inputNarrowing),
          io.in.bits.immediate
        )
      inputBits.vxrm := io.in.bits.vxrm
      inputBits.warpId := io.in.bits.warpId
      inputBits.pc := io.in.bits.pc
      inputBits.warpActiveMask := io.in.bits.warpActiveMask
      inputBits.vd := io.in.bits.vd
      inputBits.funct6 := io.in.bits.funct6
      inputBits.operandType := io.in.bits.operandType
      inputBits.immediate := io.in.bits.immediate
      inputBits.quad := io.in.bits.quad
      inputBits.reduction := inputIsReduction
      inputBits.sourceEnabled := sourceEnabled
      inputBits.enabled := Mux(
        inputIsReduction,
        io.in.bits.activeMask.orR,
        sourceEnabled
      )
      for (lane <- 0 until config.lanes) {
        inputBits.oldVd(lane) := io.in.bits.oldVd(lane)
        inputBits.vs1(lane) := io.in.bits.vs1(lane)
        inputBits.vs2(lane) := io.in.bits.vs2(lane)
        inputBits.vs2Odd(lane) := io.in.bits.vs2Odd(lane)
        inputBits.lhs(lane) := io.in.bits.vs2(lane)
        inputBits.rhs(lane) := Mux(
          io.in.bits.operandType === "b000".U,
          io.in.bits.vs1(lane),
          Mux(io.in.bits.operandType === "b100".U, io.in.bits.scalar, inputImmediate)
        )
      }
    }
  }

  when(io.in.valid) {
    assert(
      io.in.bits.operandType === "b000".U ||
        io.in.bits.operandType === "b010".U ||
        io.in.bits.operandType === "b011".U ||
        io.in.bits.operandType === "b100".U,
      "VectorIntegerAlu received an unsupported RVV operand form"
    )
  }
}
