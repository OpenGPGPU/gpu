package opengpu.core.backend.issue

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.backend.register.{
  FpuRegisterRead,
  ScalarRegisterRead,
  VectorIssueOperands,
  VectorRegisterManager,
  VectorRegisterWrite
}
import opengpu.core.backend.scoreboard.VectorRegisterReservation
import opengpu.core.frontend.decode.VectorDecodeResponse

private class VectorIssueMetadata(config: GpuConfig) extends Bundle {
  val decode = new VectorDecodeResponse(config)
  val scalarRs1Data = UInt(config.xLen.W)
  val scalarRs2Data = UInt(config.xLen.W)
  val scalarFpData = UInt(32.W)
}

class VectorIssuedInstruction(config: GpuConfig) extends Bundle {
  val decode = new VectorDecodeResponse(config)
  val vs1Data = Vec(config.lanes, UInt(config.xLen.W))
  val vs2Data = Vec(config.lanes, UInt(config.xLen.W))
  val oldVdData = Vec(config.lanes, UInt(config.xLen.W))
  val predicateMask = UInt(config.lanes.W)
  val scalarRs1Data = UInt(config.xLen.W)
  val scalarRs2Data = UInt(config.xLen.W)
  val scalarFpData = UInt(32.W)
}

/** Couples vector decode metadata to three-port vector RF operand issue.
  *
  * The scalar read request is exposed so the eventual unified backend can
  * arbitrate the scalar RF ports between scalar and vector issue. Scalar
  * values are captured atomically with the decoded instruction.
  */
class VectorIssueStage(
  config: GpuConfig = GpuConfig(),
  useBlackBox: Boolean = false
) extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new VectorDecodeResponse(config)))
    val out = Decoupled(new VectorIssuedInstruction(config))
    val vectorWriteback = Flipped(Valid(new VectorRegisterWrite(config)))
    val cancel = Flipped(Valid(new opengpu.core.backend.scoreboard.VectorRegisterRelease(config)))
    val scalarRead = Output(new ScalarRegisterRead(config))
    val scalarRs1Data = Input(UInt(config.xLen.W))
    val scalarRs2Data = Input(UInt(config.xLen.W))
    val scalarFpRead = Output(new FpuRegisterRead(config))
    val scalarFpData = Input(UInt(32.W))
    val scalarFpBusy = Input(Vec(config.warps, UInt(32.W)))
    val rawHazard = Output(Bool())
    val wawHazard = Output(Bool())
  })

  private val registers = Module(new VectorRegisterManager(config, useBlackBox))
  private val reservationQueue = Module(
    new Queue(new VectorRegisterReservation(config), 2))
  private val metadata =
    Module(new Queue(new VectorIssueMetadata(config), entries = 4))

  // One-cycle input skid that captures the decoded instruction plus the scalar
  // operand payload before it reaches the metadata / reservation queues.  This
  // breaks the long combinational path from the dispatch output to the queue
  // RAB write ports that dominates the whole-block VectorBackend layout.
  private val instruction = io.in.bits.instruction
  private val vs1 = instruction(19, 15)
  private val vs2 = instruction(24, 20)
  private val vd = instruction(11, 7)

  io.scalarRead.warpId := io.in.bits.warpId
  io.scalarRead.rs1 := vs1
  io.scalarRead.rs2 := vs2
  io.scalarFpRead.warpId := io.in.bits.warpId
  io.scalarFpRead.rs1 := vs1
  io.scalarFpRead.rs2 := 0.U
  io.scalarFpRead.rs3 := 0.U

  private val inValid = RegInit(false.B)
  private val inDecode = Reg(new VectorDecodeResponse(config))
  private val inScalarRs1Data = Reg(UInt(config.xLen.W))
  private val inScalarRs2Data = Reg(UInt(config.xLen.W))
  private val inScalarFpData = Reg(UInt(32.W))
  private val skidVs1 = inDecode.instruction(19, 15)
  private val skidVs2 = inDecode.instruction(24, 20)
  private val skidVd = inDecode.instruction(11, 7)

  io.in.ready := !inValid
  when(io.in.fire) {
    inValid := true.B
    inDecode := io.in.bits
    inScalarRs1Data := io.scalarRs1Data
    inScalarRs2Data := io.scalarRs2Data
    inScalarFpData := io.scalarFpData
  }

  private val metadataCanAccept = metadata.io.enq.ready
  private val selectedFpuBusy =
    if (config.warps == 1) io.scalarFpBusy(0)
    else io.scalarFpBusy(inDecode.warpId)
  private val scalarFpHazard =
    inDecode.decoded.readsFloat && selectedFpuBusy(skidVs1)
  private val skidFire =
    inValid && metadataCanAccept && reservationQueue.io.enq.ready && !scalarFpHazard
  when(skidFire) {
    inValid := false.B
  }
  metadata.io.enq.valid :=
    inValid && reservationQueue.io.enq.ready && !scalarFpHazard
  metadata.io.enq.bits.decode := inDecode
  metadata.io.enq.bits.scalarRs1Data := inScalarRs1Data
  metadata.io.enq.bits.scalarRs2Data := inScalarRs2Data
  metadata.io.enq.bits.scalarFpData := inScalarFpData
  reservationQueue.io.enq.valid :=
    inValid && metadataCanAccept && !scalarFpHazard
  reservationQueue.io.enq.bits.warpId := inDecode.warpId
  reservationQueue.io.enq.bits.vs1 := skidVs1
  reservationQueue.io.enq.bits.vs2 := skidVs2
  reservationQueue.io.enq.bits.vd := skidVd
  reservationQueue.io.enq.bits.useVs1 := inDecode.decoded.readsVs1
  reservationQueue.io.enq.bits.useVs2 := inDecode.decoded.readsVs2
  // RVV stores encode their source vector as vs3 in the vd field.
  reservationQueue.io.enq.bits.readVd :=
    inDecode.decoded.writesVd || inDecode.decoded.memoryWrite
  reservationQueue.io.enq.bits.useMask :=
    !inDecode.decoded.configure && !inDecode.decoded.vm
  reservationQueue.io.enq.bits.writeVd := inDecode.decoded.writesVd
  registers.io.request.valid := reservationQueue.io.deq.valid
  registers.io.request.bits := reservationQueue.io.deq.bits
  reservationQueue.io.deq.ready := registers.io.request.ready

  private val pairedValid =
    metadata.io.deq.valid && registers.io.issue.valid
  private val outputValid = RegInit(false.B)
  private val outputBits = Reg(new VectorIssuedInstruction(config))
  private val canLoadOutput = !outputValid || io.out.ready

  io.out.valid := outputValid
  io.out.bits := outputBits
  metadata.io.deq.ready := canLoadOutput && registers.io.issue.valid
  registers.io.issue.ready := canLoadOutput && metadata.io.deq.valid

  when(canLoadOutput) {
    outputValid := pairedValid
    when(pairedValid) {
      outputBits.decode := metadata.io.deq.bits.decode
      outputBits.scalarRs1Data := metadata.io.deq.bits.scalarRs1Data
      outputBits.scalarRs2Data := metadata.io.deq.bits.scalarRs2Data
      outputBits.scalarFpData := metadata.io.deq.bits.scalarFpData
      outputBits.vs1Data := registers.io.issue.bits.vs1Data
      outputBits.vs2Data := registers.io.issue.bits.vs2Data
      outputBits.oldVdData := registers.io.issue.bits.oldVdData
      outputBits.predicateMask := registers.io.issue.bits.predicateMask
    }
  }

  registers.io.writeback := io.vectorWriteback
  registers.io.cancel := io.cancel
  io.rawHazard := registers.io.rawHazard
  io.wawHazard := registers.io.wawHazard

  when(pairedValid) {
    assert(
      metadata.io.deq.bits.decode.warpId ===
        registers.io.issue.bits.request.warpId
    )
  }
}
