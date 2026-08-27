package opengpu.core.vector

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig

private class VectorDivideMetadata(config: GpuConfig) extends Bundle {
  val warpId = UInt(config.warpIdWidth.W)
  val pc = UInt(config.xLen.W)
  val warpActiveMask = UInt(config.lanes.W)
  val vd = UInt(5.W)
  val enabled = UInt(config.lanes.W)
  val oldVd = Vec(config.lanes, UInt(config.xLen.W))
}

/** Lane-parallel RVV integer divide/remainder for the fixed SEW=32 profile.
  *
  * Implements vdivu/vdiv/vremu/vrem in vv and vx forms. All lanes share one
  * 32-cycle radix-2 iteration, mirroring the scalar DivideExecuteStage.
  */
class VectorDivideAlu(config: GpuConfig = GpuConfig()) extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new VectorIntegerRequest(config)))
    val out = Decoupled(new VectorIntegerResult(config))
  })

  private val busy = RegInit(false.B)
  private val count = RegInit(0.U(6.W))
  private val quotient = Reg(Vec(config.lanes, UInt(32.W)))
  private val divisor = Reg(Vec(config.lanes, UInt(32.W)))
  private val remainder = Reg(Vec(config.lanes, UInt(33.W)))
  private val negateQuotient = Reg(Vec(config.lanes, Bool()))
  private val negateRemainder = Reg(Vec(config.lanes, Bool()))
  private val selectRemainder = Reg(Vec(config.lanes, Bool()))
  private val specialLane = Reg(Vec(config.lanes, Bool()))
  private val specialData = Reg(Vec(config.lanes, UInt(32.W)))
  private val metadata = Reg(new VectorDivideMetadata(config))

  private val outputValid = RegInit(false.B)
  private val outputBits = Reg(new VectorIntegerResult(config))
  private val outputAvailable = !outputValid || io.out.ready
  private val inputValid = RegInit(false.B)
  private val inputBits = Reg(new VectorIntegerRequest(config))

  private val isVv = io.in.bits.operandType === "b010".U
  private val isVx = io.in.bits.operandType === "b110".U
  private val isSigned =
    io.in.bits.funct6 === "h21".U || io.in.bits.funct6 === "h23".U
  private val wantsRemainder =
    io.in.bits.funct6 === "h22".U || io.in.bits.funct6 === "h23".U
  private val supported =
    (isVv || isVx) &&
      io.in.bits.funct6 >= "h20".U &&
      io.in.bits.funct6 <= "h23".U
  private val inputIsVv = inputBits.operandType === "b010".U
  private val inputIsVx = inputBits.operandType === "b110".U
  private val inputIsSigned =
    inputBits.funct6 === "h21".U || inputBits.funct6 === "h23".U
  private val inputWantsRemainder =
    inputBits.funct6 === "h22".U || inputBits.funct6 === "h23".U

  private val laneLhs = Wire(Vec(config.lanes, UInt(32.W)))
  private val laneRhs = Wire(Vec(config.lanes, UInt(32.W)))
  private val laneLhsNegative = Wire(Vec(config.lanes, Bool()))
  private val laneRhsNegative = Wire(Vec(config.lanes, Bool()))
  private val laneLhsMagnitude = Wire(Vec(config.lanes, UInt(32.W)))
  private val laneRhsMagnitude = Wire(Vec(config.lanes, UInt(32.W)))
  private val laneDivideByZero = Wire(Vec(config.lanes, Bool()))
  private val laneSignedOverflow = Wire(Vec(config.lanes, Bool()))
  private val laneSpecial = Wire(Vec(config.lanes, Bool()))
  private val laneSpecialData = Wire(Vec(config.lanes, UInt(32.W)))

  for (lane <- 0 until config.lanes) {
    laneLhs(lane) := inputBits.vs2(lane)
    laneRhs(lane) := Mux(inputIsVv, inputBits.vs1(lane), inputBits.scalar)
    laneLhsNegative(lane) := inputIsSigned && laneLhs(lane)(31)
    laneRhsNegative(lane) := inputIsSigned && laneRhs(lane)(31)
    laneLhsMagnitude(lane) := Mux(
      laneLhsNegative(lane),
      0.U - laneLhs(lane),
      laneLhs(lane)
    )
    laneRhsMagnitude(lane) := Mux(
      laneRhsNegative(lane),
      0.U - laneRhs(lane),
      laneRhs(lane)
    )
    laneDivideByZero(lane) := laneRhs(lane) === 0.U
    laneSignedOverflow(lane) :=
      inputIsSigned &&
        laneLhs(lane) === "h80000000".U &&
        laneRhs(lane) === "hffffffff".U
    laneSpecial(lane) :=
      laneDivideByZero(lane) || laneSignedOverflow(lane)
    laneSpecialData(lane) := Mux(
      laneDivideByZero(lane),
      Mux(inputWantsRemainder, laneLhs(lane), "hffffffff".U),
      Mux(
        laneSignedOverflow(lane),
        Mux(inputWantsRemainder, 0.U, "h80000000".U),
        0.U
      )
    )
  }

  private val start = inputValid && !busy && outputAvailable
  io.in.ready := (!inputValid || start) && supported
  io.out.valid := outputValid
  io.out.bits := outputBits

  when(outputAvailable) {
    outputValid := false.B
  }

  when(start) {
    inputValid := false.B
    busy := true.B
    count := 0.U
    metadata.warpId := inputBits.warpId
    metadata.pc := inputBits.pc
    metadata.warpActiveMask := inputBits.warpActiveMask
    metadata.vd := inputBits.vd
    metadata.enabled :=
      inputBits.activeMask &
        Mux(inputBits.vm, Fill(config.lanes, 1.U), inputBits.predicateMask)
    metadata.oldVd := inputBits.oldVd
    for (lane <- 0 until config.lanes) {
      specialLane(lane) := laneSpecial(lane)
      specialData(lane) := laneSpecialData(lane)
      quotient(lane) := Mux(
        laneSpecial(lane),
        0.U,
        laneLhsMagnitude(lane)
      )
      divisor(lane) := Mux(
        laneSpecial(lane),
        0.U,
        laneRhsMagnitude(lane)
      )
      remainder(lane) := 0.U
      negateQuotient(lane) :=
        laneLhsNegative(lane) ^ laneRhsNegative(lane)
      negateRemainder(lane) := laneLhsNegative(lane)
      selectRemainder(lane) := inputWantsRemainder
    }
  }

  when(io.in.fire) {
    inputValid := true.B
    inputBits := io.in.bits
  }

  private val shiftedRemainder = Wire(Vec(config.lanes, UInt(33.W)))
  private val subtracts = Wire(Vec(config.lanes, Bool()))
  private val nextRemainder = Wire(Vec(config.lanes, UInt(33.W)))
  private val nextQuotient = Wire(Vec(config.lanes, UInt(32.W)))
  for (lane <- 0 until config.lanes) {
    shiftedRemainder(lane) :=
      Cat(remainder(lane)(31, 0), quotient(lane)(31))
    subtracts(lane) :=
      shiftedRemainder(lane) >= Cat(0.U, divisor(lane))
    nextRemainder(lane) := Mux(
      subtracts(lane),
      shiftedRemainder(lane) - Cat(0.U, divisor(lane)),
      shiftedRemainder(lane)
    )
    nextQuotient(lane) := Cat(quotient(lane)(30, 0), subtracts(lane))
  }

  private val finalData = Wire(Vec(config.lanes, UInt(32.W)))
  for (lane <- 0 until config.lanes) {
    val quotientResult =
      Mux(negateQuotient(lane), 0.U - nextQuotient(lane), nextQuotient(lane))
    val remainderResult =
      Mux(negateRemainder(lane), 0.U - nextRemainder(lane)(31, 0), nextRemainder(lane)(31, 0))
    val selected = Mux(
      selectRemainder(lane),
      remainderResult,
      quotientResult
    )
    finalData(lane) := Mux(
      specialLane(lane),
      specialData(lane),
      Mux(
        metadata.enabled(lane),
        selected,
        metadata.oldVd(lane)
      )
    )
  }

  when(busy) {
    for (lane <- 0 until config.lanes) {
      quotient(lane) := nextQuotient(lane)
      remainder(lane) := nextRemainder(lane)
    }
    count := count + 1.U
    when(count === 31.U) {
      busy := false.B
      outputValid := true.B
      outputBits.warpId := metadata.warpId
      outputBits.pc := metadata.pc
      outputBits.warpActiveMask := metadata.warpActiveMask
      outputBits.vd := metadata.vd
      outputBits.data := finalData
      outputBits.mask := 0.U
      outputBits.writesMask := false.B
      outputBits.saturated := false.B
    }
  }

  when(io.in.valid) {
    assert(supported, "VectorDivideAlu received an unsupported RVV operation")
    assert(
      io.in.bits.operandType === "b010".U ||
        io.in.bits.operandType === "b110".U,
      "VectorDivideAlu received an unsupported RVV operand form"
    )
  }
}
