package opengpu.core.vector

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig

class VectorFpuRequest(config: GpuConfig) extends Bundle {
  val warpId = UInt(config.warpIdWidth.W)
  val pc = UInt(config.xLen.W)
  val warpActiveMask = UInt(config.lanes.W)
  val vd = UInt(5.W)
  val activeMask = UInt(config.lanes.W)
  val rawActiveMask = UInt(config.lanes.W)
  val predicateMask = UInt(config.lanes.W)
  val oldVd = Vec(config.lanes, UInt(config.xLen.W))
  val vs1 = Vec(config.lanes, UInt(config.xLen.W))
  val vs2 = Vec(config.lanes, UInt(config.xLen.W))
  val vs1Field = UInt(5.W)
  val scalarFpData = UInt(32.W)
  val roundingMode = UInt(3.W)
  val funct6 = UInt(6.W)
  val operandType = UInt(3.W)
  val vm = Bool()
}

class VectorFpuResult(config: GpuConfig) extends Bundle {
  val warpId = UInt(config.warpIdWidth.W)
  val pc = UInt(config.xLen.W)
  val warpActiveMask = UInt(config.lanes.W)
  val vd = UInt(5.W)
  val data = Vec(config.lanes, UInt(config.xLen.W))
  val mask = UInt(config.lanes.W)
  val writesMask = Bool()
  val saturated = Bool()
  val flags = UInt(5.W)
  val writesFlags = Bool()
}

private class VectorFpuCandidate(config: GpuConfig) extends Bundle {
  val warpId = UInt(config.warpIdWidth.W)
  val pc = UInt(config.xLen.W)
  val warpActiveMask = UInt(config.lanes.W)
  val vd = UInt(5.W)
  val funct6 = UInt(6.W)
  val operandType = UInt(3.W)
  val vm = Bool()
  val scalarFpData = UInt(32.W)
  val oldVd = Vec(config.lanes, UInt(config.xLen.W))
  val lhs = Vec(config.lanes, UInt(config.xLen.W))
  val rhs = Vec(config.lanes, UInt(config.xLen.W))
  val enabled = UInt(config.lanes.W)
  val rawActiveMask = UInt(config.lanes.W)
  val predicateMask = UInt(config.lanes.W)
  val lhsLess = Vec(config.lanes, Bool())
  val lhsGreater = Vec(config.lanes, Bool())
  val equal = Vec(config.lanes, Bool())
  val unordered = Vec(config.lanes, Bool())
}

/** Lane-local RVV FP32 exact operations for the fixed SEW=32 profile.
  *
  * Supports sign injection, min/max, and comparisons over FVV operands.
  * Arithmetic (add/mul/fma) is intentionally absent until a vector FMA
  * datapath is connected.
  */
class VectorFpuAlu(config: GpuConfig = GpuConfig()) extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new VectorFpuRequest(config)))
    val out = Decoupled(new VectorFpuResult(config))
  })

  private val inputValid = RegInit(false.B)
  private val inputBits = Reg(new VectorFpuRequest(config))
  private val candidateValid = RegInit(false.B)
  private val candidateBits = Reg(new VectorFpuCandidate(config))
  private val outputValid = RegInit(false.B)
  private val outputBits = Reg(new VectorFpuResult(config))
  private val outputReady = !outputValid || io.out.ready
  private val candidateReady = !candidateValid || outputReady
  private val inputReady = !inputValid || candidateReady

  private val laneData = Wire(Vec(config.lanes, UInt(config.xLen.W)))
  private val laneMask = Wire(Vec(config.lanes, Bool()))
  private val laneFlags = Wire(Vec(config.lanes, Bool()))

  private val isMinMax =
    candidateBits.funct6 === "h04".U || candidateBits.funct6 === "h06".U
  private val isCompare =
    candidateBits.funct6 >= "h18".U && candidateBits.funct6 <= "h1f".U
  private val isFpMerge = candidateBits.funct6 === "h17".U

  for (lane <- 0 until config.lanes) {
    val lhs = candidateBits.lhs(lane)
    val rhs = candidateBits.rhs(lane)
    val enabled = candidateBits.enabled(lane)
    val lhsLess = candidateBits.lhsLess(lane)
    val lhsGreater = candidateBits.lhsGreater(lane)
    val equal = candidateBits.equal(lane)
    val unordered = candidateBits.unordered(lane)
    val lhsNegative = lhs(31)
    val rhsNegative = rhs(31)

    val signData = MuxLookup(candidateBits.funct6, lhs)(Seq(
      "h08".U -> Cat(rhsNegative, lhs(30, 0)),
      "h09".U -> Cat(~rhsNegative, lhs(30, 0)),
      "h0a".U -> Cat(lhsNegative ^ rhsNegative, lhs(30, 0))
    ))
    val minMaxData = Mux(
      unordered,
      "h7fc00000".U,
      Mux(
        isMinMax && candidateBits.funct6 === "h04".U,
        Mux(lhsLess, lhs, rhs),
        Mux(lhsGreater, lhs, rhs)
      )
    )
    val comparison = MuxLookup(candidateBits.funct6, false.B)(Seq(
      "h18".U -> equal,
      "h19".U -> (lhsLess || equal),
      "h1b".U -> lhsLess,
      "h1c".U -> !equal,
      "h1d".U -> lhsGreater,
      "h1f".U -> (lhsGreater || equal)
    )) && !unordered

    val exactData = Mux(
      isMinMax,
      minMaxData,
      signData
    )
    val mergeData = Mux(
      candidateBits.vm,
      Mux(enabled, candidateBits.scalarFpData, candidateBits.oldVd(lane)),
      Mux(
        candidateBits.rawActiveMask(lane) && candidateBits.predicateMask(lane),
        candidateBits.scalarFpData,
        Mux(
          candidateBits.rawActiveMask(lane),
          candidateBits.lhs(lane),
          candidateBits.oldVd(lane)
        )
      )
    )
    laneData(lane) := Mux(
      isFpMerge,
      mergeData,
      Mux(
        isCompare,
        candidateBits.oldVd(lane),
        Mux(enabled, exactData, candidateBits.oldVd(lane))
      )
    )
    laneMask(lane) := enabled && comparison
    laneFlags(lane) :=
      enabled && unordered && (isMinMax || isCompare)
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
      outputBits.data := laneData
      outputBits.mask := laneMask.asUInt
      outputBits.writesMask := isCompare
      outputBits.saturated := false.B
      outputBits.flags :=
        Mux(laneFlags.reduce(_ || _), "h10".U(5.W), 0.U(5.W))
      outputBits.writesFlags := isMinMax || isCompare
    }
  }

  when(candidateReady) {
    candidateValid := inputValid
    when(inputValid) {
      candidateBits.warpId := inputBits.warpId
      candidateBits.pc := inputBits.pc
      candidateBits.warpActiveMask := inputBits.warpActiveMask
      candidateBits.vd := inputBits.vd
      candidateBits.funct6 := inputBits.funct6
      candidateBits.operandType := inputBits.operandType
      candidateBits.vm := inputBits.vm
      candidateBits.scalarFpData := inputBits.scalarFpData
      candidateBits.rawActiveMask := inputBits.rawActiveMask
      candidateBits.predicateMask := inputBits.predicateMask
      candidateBits.enabled := inputBits.activeMask
      for (lane <- 0 until config.lanes) {
        val lhs = inputBits.vs2(lane)
        val isFvf = inputBits.operandType === "b101".U
        val rhs = Mux(isFvf, inputBits.scalarFpData, inputBits.vs1(lane))
        val lhsNaN =
          lhs(30, 23) === 0xff.U && lhs(22, 0) =/= 0.U
        val rhsNaN =
          rhs(30, 23) === 0xff.U && rhs(22, 0) =/= 0.U
        val lhsNegative = lhs(31)
        val rhsNegative = rhs(31)
        val lhsMag = lhs(30, 0)
        val rhsMag = rhs(30, 0)
        val lhsLess = Mux(
          lhsNegative === rhsNegative,
          Mux(lhsNegative, lhsMag > rhsMag, lhsMag < rhsMag),
          lhsNegative && !rhsNegative
        )
        val lhsGreater = Mux(
          lhsNegative === rhsNegative,
          Mux(lhsNegative, lhsMag < rhsMag, lhsMag > rhsMag),
          !lhsNegative && rhsNegative
        )
        candidateBits.lhs(lane) := lhs
        candidateBits.rhs(lane) := rhs
        candidateBits.oldVd(lane) := inputBits.oldVd(lane)
        candidateBits.lhsLess(lane) := lhsLess
        candidateBits.lhsGreater(lane) := lhsGreater
        candidateBits.equal(lane) :=
          lhsMag === rhsMag && lhsNegative === rhsNegative
        candidateBits.unordered(lane) := lhsNaN || rhsNaN
      }
    }
  }

  when(inputReady) {
    inputValid := io.in.valid
    when(io.in.valid) {
      inputBits.warpId := io.in.bits.warpId
      inputBits.pc := io.in.bits.pc
      inputBits.warpActiveMask := io.in.bits.warpActiveMask
      inputBits.vd := io.in.bits.vd
      inputBits.funct6 := io.in.bits.funct6
      inputBits.operandType := io.in.bits.operandType
      inputBits.vm := io.in.bits.vm
      inputBits.scalarFpData := io.in.bits.scalarFpData
      inputBits.vs1Field := io.in.bits.vs1Field
      inputBits.roundingMode := io.in.bits.roundingMode
      inputBits.rawActiveMask := io.in.bits.rawActiveMask
      inputBits.predicateMask := io.in.bits.predicateMask
      inputBits.activeMask :=
        io.in.bits.activeMask &
          Mux(io.in.bits.vm, Fill(config.lanes, 1.U), io.in.bits.predicateMask)
      for (lane <- 0 until config.lanes) {
        inputBits.oldVd(lane) := io.in.bits.oldVd(lane)
        inputBits.vs1(lane) := io.in.bits.vs1(lane)
        inputBits.vs2(lane) := io.in.bits.vs2(lane)
      }
    }
  }

  when(io.in.valid) {
    assert(
      io.in.bits.operandType === "b001".U ||
        io.in.bits.operandType === "b101".U,
      "VectorFpuAlu currently supports FVV and FVF operand forms only"
    )
  }
}
