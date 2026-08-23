package gpu.core.vector

import chisel3._
import chisel3.util._
import gpu.config.GpuConfig
import gpu.core.execute.fpu.{Fp32DivLane, Fp32Operation}

private class VectorFdivMetadata(config: GpuConfig) extends Bundle {
  val warpId = UInt(config.warpIdWidth.W)
  val pc = UInt(config.xLen.W)
  val warpActiveMask = UInt(config.lanes.W)
  val vd = UInt(5.W)
  val enabled = UInt(config.lanes.W)
  val oldVd = Vec(config.lanes, UInt(32.W))
}

/** Lane-local RVV FP32 divide wrapper around the iterative divider lanes. */
class VectorFdivAlu(config: GpuConfig = GpuConfig()) extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new VectorFpuRequest(config)))
    val out = Decoupled(new VectorFpuResult(config))
  })

  private val tagWidth = config.warpIdWidth + 5
  private val lanes = Seq.fill(config.lanes) {
    Module(new Fp32DivLane(tagWidth))
  }
  private val metadata =
    Module(new Queue(new VectorFdivMetadata(config), 8))
  private val inputValid = RegInit(false.B)
  private val inputBits = Reg(new VectorFpuRequest(config))

  private val isDiv = io.in.bits.funct6 === "h20".U
  private val isRdiv = io.in.bits.funct6 === "h21".U
  private val isSupported = isDiv || isRdiv
  private val isFvf = io.in.bits.operandType === "b101".U
  private val inputIsDiv = inputBits.funct6 === "h20".U
  private val inputIsRdiv = inputBits.funct6 === "h21".U
  private val inputIsFvf = inputBits.operandType === "b101".U

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
    lane.io.in.valid := issue
    lane.io.in.bits.operation := Fp32Operation.div
    lane.io.in.bits.roundingMode := inputBits.roundingMode
    lane.io.in.bits.operationModifier := false.B
    lane.io.in.bits.exactFunction := 0.U
    lane.io.in.bits.operandA := Mux(inputIsRdiv, scalarOperand, inputBits.vs2(index))
    lane.io.in.bits.operandB := Mux(inputIsRdiv, inputBits.vs2(index), scalarOperand)
    lane.io.in.bits.operandC := 0.U
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
      "VectorFdivAlu received an unsupported RVV operand form"
    )
    assert(isSupported, "VectorFdivAlu received an unsupported funct6")
    assert(
      !isRdiv || isFvf,
      "vfrdiv requires the FVF operand form"
    )
  }
}
