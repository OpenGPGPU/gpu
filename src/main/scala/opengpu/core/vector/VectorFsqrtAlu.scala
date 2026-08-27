package opengpu.core.vector

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.execute.fpu.{Fp32Operation, Fp32SqrtLane}

private class VectorFsqrtMetadata(config: GpuConfig) extends Bundle {
  val warpId = UInt(config.warpIdWidth.W)
  val pc = UInt(config.xLen.W)
  val warpActiveMask = UInt(config.lanes.W)
  val vd = UInt(5.W)
  val enabled = UInt(config.lanes.W)
  val oldVd = Vec(config.lanes, UInt(32.W))
}

/** Lane-local RVV FP32 vfsqrt.v wrapper around the iterative sqrt lanes. */
class VectorFsqrtAlu(config: GpuConfig = GpuConfig()) extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new VectorFpuRequest(config)))
    val out = Decoupled(new VectorFpuResult(config))
  })

  private val tagWidth = config.warpIdWidth + 5
  private val lanes = Seq.fill(config.lanes) {
    Module(new Fp32SqrtLane(tagWidth))
  }
  private val metadata =
    Module(new Queue(new VectorFsqrtMetadata(config), 8))
  private val inputValid = RegInit(false.B)
  private val inputBits = Reg(new VectorFpuRequest(config))

  private val isSqrt = io.in.bits.vs1Field === "b00000".U
  private val isSupported = isSqrt

  private val allInputsReady = lanes.map(_.io.in.ready).reduce(_ && _)
  private val allOutputsValid = lanes.map(_.io.out.valid).reduce(_ && _)

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
  io.out.valid := metadata.io.deq.valid && allOutputsValid
  metadata.io.deq.ready := io.out.ready && allOutputsValid
  io.out.bits.warpId := metadata.io.deq.bits.warpId
  io.out.bits.pc := metadata.io.deq.bits.pc
  io.out.bits.warpActiveMask := metadata.io.deq.bits.warpActiveMask
  io.out.bits.vd := metadata.io.deq.bits.vd
  io.out.bits.mask := 0.U
  io.out.bits.writesMask := false.B
  io.out.bits.saturated := false.B
  io.out.bits.writesFlags := true.B

  private val laneFlags = Wire(Vec(config.lanes, UInt(5.W)))
  for ((lane, index) <- lanes.zipWithIndex) {
    lane.io.in.valid := issue
    lane.io.in.bits.operation := Fp32Operation.sqrt
    lane.io.in.bits.roundingMode := inputBits.roundingMode
    lane.io.in.bits.operationModifier := false.B
    lane.io.in.bits.exactFunction := 0.U
    lane.io.in.bits.operandA := inputBits.vs2(index)
    lane.io.in.bits.operandB := 0.U
    lane.io.in.bits.operandC := 0.U
    lane.io.in.bits.tag := Cat(inputBits.warpId, inputBits.vd)
    lane.io.flush := false.B
    lane.io.out.ready := metadata.io.deq.valid && io.out.ready
    io.out.bits.data(index) := Mux(
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
  io.out.bits.flags := laneFlags.reduce(_ | _)

  when(io.in.valid) {
    assert(
      io.in.bits.operandType === "b001".U,
      "VectorFsqrtAlu received a non-FVV operand form"
    )
    assert(isSupported, "VectorFsqrtAlu received an unsupported vs1 opcode")
  }
}
