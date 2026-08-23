package gpu.core.vector

import chisel3._
import chisel3.util._
import gpu.config.GpuConfig
import gpu.core.execute.fpu.{Fp32ExactUnit, Fp32Operation}

private class VectorFcvtMetadata(config: GpuConfig) extends Bundle {
  val warpId = UInt(config.warpIdWidth.W)
  val pc = UInt(config.xLen.W)
  val warpActiveMask = UInt(config.lanes.W)
  val vd = UInt(5.W)
  val enabled = UInt(config.lanes.W)
  val oldVd = Vec(config.lanes, UInt(32.W))
}

/** Lane-local RVV FP32/SEW=32 conversion and classification wrapper.
  *
  * Implements vfcvt.xu.f.v, vfcvt.x.f.v, vfcvt.f.xu.v, vfcvt.f.x.v, and the
  * two rtz float-to-integer variants, plus vfclass.v.  The non-rtz conversion
  * forms use the per-warp dynamic rounding mode supplied on the request.
  */
class VectorFcvtAlu(config: GpuConfig = GpuConfig()) extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new VectorFpuRequest(config)))
    val out = Decoupled(new VectorFpuResult(config))
  })

  private val tagWidth = config.warpIdWidth + 5
  private val lanes = Seq.fill(config.lanes) {
    Module(new Fp32ExactUnit(tagWidth))
  }
  private val metadata =
    Module(new Queue(new VectorFcvtMetadata(config), 8))
  private val inputValid = RegInit(false.B)
  private val inputBits = Reg(new VectorFpuRequest(config))

  private val isFpToInt =
    io.in.bits.vs1Field <= "b00001".U ||
      (io.in.bits.vs1Field >= "b00110".U &&
        io.in.bits.vs1Field <= "b00111".U)
  private val isIntToFp =
    io.in.bits.vs1Field >= "b00010".U &&
      io.in.bits.vs1Field <= "b00011".U
  private val isClassify = io.in.bits.vs1Field === "b10000".U
  private val isRtz =
    io.in.bits.vs1Field === "b00110".U ||
      io.in.bits.vs1Field === "b00111".U
  private val isUnsigned = !io.in.bits.vs1Field(0)
  private val isSupported = isFpToInt || isIntToFp || isClassify
  private val inputIsFpToInt =
    inputBits.vs1Field <= "b00001".U ||
      (inputBits.vs1Field >= "b00110".U &&
        inputBits.vs1Field <= "b00111".U)
  private val inputIsIntToFp =
    inputBits.vs1Field >= "b00010".U &&
      inputBits.vs1Field <= "b00011".U
  private val inputIsClassify = inputBits.vs1Field === "b10000".U
  private val inputIsRtz =
    inputBits.vs1Field === "b00110".U ||
      inputBits.vs1Field === "b00111".U
  private val inputIsUnsigned = !inputBits.vs1Field(0)

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
    lane.io.in.valid := issue
    lane.io.in.bits.operation := Mux(
      inputIsClassify,
      Fp32Operation.classify,
      Mux(inputIsFpToInt, Fp32Operation.fpToInt, Fp32Operation.intToFp)
    )
    lane.io.in.bits.roundingMode :=
      Mux(inputIsRtz, "b001".U, inputBits.roundingMode)
    lane.io.in.bits.operationModifier := false.B
    lane.io.in.bits.exactFunction := Mux(inputIsUnsigned, 1.U, 0.U)
    lane.io.in.bits.operandA := inputBits.vs2(index)
    lane.io.in.bits.operandB := 0.U
    lane.io.in.bits.operandC := 0.U
    lane.io.in.bits.tag := Cat(inputBits.warpId, inputBits.vd)
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
      io.in.bits.operandType === "b001".U,
      "VectorFcvtAlu received a non-FVV operand form"
    )
    assert(isSupported, "VectorFcvtAlu received an unsupported vs1 opcode")
  }
}
