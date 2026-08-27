package opengpu.core.vector

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.execute.fpu.{Fp32FmaLane, Fp32Operation}

private class VectorFmaMetadata(config: GpuConfig) extends Bundle {
  val warpId = UInt(config.warpIdWidth.W)
  val pc = UInt(config.xLen.W)
  val warpActiveMask = UInt(config.lanes.W)
  val vd = UInt(5.W)
  val enabled = UInt(config.lanes.W)
  val oldVd = Vec(config.lanes, UInt(32.W))
}

/** Lane-local RVV FP32 FMA/add/sub/mul wrapper around the scalar FMA lanes.
  *
  * All lanes are issued and retired as one vector operation so results stay
  * aligned. Each lane uses the existing elastic Fp32FmaLane pipeline. The
  * unit covers vfadd/vfsub/vfmul plus all eight fused add/subtract forms.
  */
class VectorFmaAlu(config: GpuConfig = GpuConfig()) extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new VectorFpuRequest(config)))
    val out = Decoupled(new VectorFpuResult(config))
  })

  private val tagWidth = config.warpIdWidth + 5
  private val lanes = Seq.fill(config.lanes) {
    Module(new Fp32FmaLane(tagWidth))
  }
  private val metadata =
    Module(new Queue(new VectorFmaMetadata(config), 8))
  private val inputValid = RegInit(false.B)
  private val inputBits = Reg(new VectorFpuRequest(config))

  private val isFvf = io.in.bits.operandType === "b101".U
  private val isAdd = io.in.bits.funct6 === "h00".U
  private val isSub = io.in.bits.funct6 === "h02".U
  private val isRsub = io.in.bits.funct6 === "h27".U
  private val isMul = io.in.bits.funct6 === "h24".U
  private val isFma =
    io.in.bits.funct6 >= "h28".U && io.in.bits.funct6 <= "h2f".U
  private val isMacc =
    io.in.bits.funct6 >= "h2c".U && io.in.bits.funct6 <= "h2f".U
  private val isSupported = isAdd || isSub || isRsub || isMul || isFma
  private val fmaNegate = io.in.bits.funct6(0)
  private val fmaSubtract = io.in.bits.funct6(1)
  private val inputIsFvf = inputBits.operandType === "b101".U
  private val inputIsAdd = inputBits.funct6 === "h00".U
  private val inputIsSub = inputBits.funct6 === "h02".U
  private val inputIsRsub = inputBits.funct6 === "h27".U
  private val inputIsMul = inputBits.funct6 === "h24".U
  private val inputIsFma =
    inputBits.funct6 >= "h28".U && inputBits.funct6 <= "h2f".U
  private val inputIsMacc =
    inputBits.funct6 >= "h2c".U && inputBits.funct6 <= "h2f".U
  private val inputFmaNegate = inputBits.funct6(0)
  private val inputFmaSubtract = inputBits.funct6(1)

  private val allInputsReady = lanes.map(_.io.in.ready).reduce(_ && _)
  private val allOutputsValid = lanes.map(_.io.out.valid).reduce(_ && _)
  private val outputValid = RegInit(false.B)
  private val outputBits = Reg(new VectorFpuResult(config))

  private val downstreamReady = allInputsReady && metadata.io.enq.ready
  private val issue = inputValid && downstreamReady
  io.in.ready := (!inputValid || issue) && isSupported
  when(io.in.fire) {
    inputValid := true.B
    inputBits := io.in.bits
  }
  metadata.io.enq.valid := issue
  metadata.io.enq.bits.warpId := inputBits.warpId
  metadata.io.enq.bits.pc := inputBits.pc
  metadata.io.enq.bits.warpActiveMask := inputBits.warpActiveMask
  metadata.io.enq.bits.vd := inputBits.vd
  metadata.io.enq.bits.enabled :=
    inputBits.activeMask &
      Mux(inputBits.vm, Fill(config.lanes, 1.U), inputBits.predicateMask)
  for (lane <- 0 until config.lanes) {
    metadata.io.enq.bits.oldVd(lane) := inputBits.oldVd(lane)
  }
  when(issue) {
    inputValid := false.B
  }
  private val captureReady = !outputValid || io.out.ready
  private val captureValid = metadata.io.deq.valid && allOutputsValid
  io.out.valid := outputValid
  io.out.bits := outputBits
  metadata.io.deq.ready := allOutputsValid && captureReady

  private val laneFlags = Wire(Vec(config.lanes, UInt(5.W)))
  private val capturedData = Wire(Vec(config.lanes, UInt(32.W)))
  for ((lane, index) <- lanes.zipWithIndex) {
    val scalarOperand = Mux(
      inputIsFvf, inputBits.scalarFpData, inputBits.vs1(index))
    val fmaA = Mux(
      inputIsMacc, scalarOperand, inputBits.vs2(index))
    val fmaB = Mux(
      inputIsMacc, inputBits.vs2(index), scalarOperand)
    val fmaC = inputBits.oldVd(index)
    val addC = scalarOperand
    lane.io.in.valid := issue
    lane.io.in.bits.roundingMode := inputBits.roundingMode
    lane.io.in.bits.operation := Mux(
      inputIsFma,
      Mux(inputFmaNegate, Fp32Operation.fnmsub, Fp32Operation.fmadd),
      Mux(inputIsMul, Fp32Operation.mul, Fp32Operation.add)
    )
    lane.io.in.bits.operationModifier := Mux(
      inputIsFma, inputFmaSubtract, inputIsSub || inputIsRsub)
    lane.io.in.bits.exactFunction := 0.U
    lane.io.in.bits.operandA := Mux(inputIsFma, fmaA, inputBits.vs2(index))
    lane.io.in.bits.operandB := Mux(
      inputIsFma,
      fmaB,
      Mux(inputIsMul || inputIsRsub, scalarOperand, inputBits.vs2(index))
    )
    lane.io.in.bits.operandC := Mux(
      inputIsFma,
      fmaC,
      Mux(inputIsRsub, inputBits.vs2(index), addC)
    )
    lane.io.in.bits.tag := Cat(inputBits.warpId, inputBits.vd)
    lane.io.flush := false.B
    lane.io.out.ready := metadata.io.deq.valid && captureReady
    capturedData(index) := Mux(
      metadata.io.deq.bits.enabled(index),
      lane.io.out.bits.result,
      metadata.io.deq.bits.oldVd(index)
    )
    laneFlags(index) := Mux(
      metadata.io.deq.bits.enabled(index),
      lane.io.out.bits.status,
      0.U(5.W)
    )
  }

  when(captureReady) {
    outputValid := captureValid
    when(captureValid) {
      outputBits.warpId := metadata.io.deq.bits.warpId
      outputBits.pc := metadata.io.deq.bits.pc
      outputBits.warpActiveMask := metadata.io.deq.bits.warpActiveMask
      outputBits.vd := metadata.io.deq.bits.vd
      outputBits.data := capturedData
      outputBits.mask := 0.U
      outputBits.writesMask := false.B
      outputBits.saturated := false.B
      outputBits.writesFlags := true.B
      outputBits.flags := laneFlags.reduce(_ | _)
    }
  }

  when(io.in.valid) {
    assert(
      io.in.bits.operandType === "b001".U ||
        io.in.bits.operandType === "b101".U,
      "VectorFmaAlu currently supports FVV and FVF operand forms only"
    )
    assert(isSupported, "VectorFmaAlu received an unsupported funct6")
  }
}
