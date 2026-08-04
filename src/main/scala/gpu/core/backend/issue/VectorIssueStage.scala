package gpu.core.backend.issue

import chisel3._
import chisel3.util._
import gpu.config.GpuConfig
import gpu.core.backend.register.{
  ScalarRegisterRead,
  VectorIssueOperands,
  VectorRegisterManager,
  VectorRegisterWrite
}
import gpu.core.frontend.decode.VectorDecodeResponse

private class VectorIssueMetadata(config: GpuConfig) extends Bundle {
  val decode = new VectorDecodeResponse(config)
  val scalarRs1Data = UInt(config.xLen.W)
  val scalarRs2Data = UInt(config.xLen.W)
}

class VectorIssuedInstruction(config: GpuConfig) extends Bundle {
  val decode = new VectorDecodeResponse(config)
  val vs1Data = Vec(config.lanes, UInt(config.xLen.W))
  val vs2Data = Vec(config.lanes, UInt(config.xLen.W))
  val oldVdData = Vec(config.lanes, UInt(config.xLen.W))
  val predicateMask = UInt(config.lanes.W)
  val scalarRs1Data = UInt(config.xLen.W)
  val scalarRs2Data = UInt(config.xLen.W)
}

/** Couples vector decode metadata to three-port vector RF operand issue.
  *
  * The scalar read request is exposed so the eventual unified backend can
  * arbitrate the scalar RF ports between scalar and vector issue. Scalar
  * values are captured atomically with the decoded instruction.
  */
class VectorIssueStage(config: GpuConfig = GpuConfig()) extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new VectorDecodeResponse(config)))
    val out = Decoupled(new VectorIssuedInstruction(config))
    val vectorWriteback = Flipped(Valid(new VectorRegisterWrite(config)))
    val cancel = Flipped(Valid(new gpu.core.backend.scoreboard.VectorRegisterRelease(config)))
    val scalarRead = Output(new ScalarRegisterRead(config))
    val scalarRs1Data = Input(UInt(config.xLen.W))
    val scalarRs2Data = Input(UInt(config.xLen.W))
    val rawHazard = Output(Bool())
    val wawHazard = Output(Bool())
  })

  private val registers = Module(new VectorRegisterManager(config))
  private val metadata =
    Module(new Queue(new VectorIssueMetadata(config), entries = 4))
  private val instruction = io.in.bits.instruction
  private val vs1 = instruction(19, 15)
  private val vs2 = instruction(24, 20)
  private val vd = instruction(11, 7)

  io.scalarRead.warpId := io.in.bits.warpId
  io.scalarRead.rs1 := vs1
  io.scalarRead.rs2 := vs2

  private val metadataCanAccept = metadata.io.enq.ready
  private val registersCanAccept = registers.io.request.ready
  io.in.ready := metadataCanAccept && registersCanAccept
  metadata.io.enq.valid := io.in.valid && registersCanAccept
  metadata.io.enq.bits.decode := io.in.bits
  metadata.io.enq.bits.scalarRs1Data := io.scalarRs1Data
  metadata.io.enq.bits.scalarRs2Data := io.scalarRs2Data
  registers.io.request.valid := io.in.valid && metadataCanAccept
  registers.io.request.bits.warpId := io.in.bits.warpId
  registers.io.request.bits.vs1 := vs1
  registers.io.request.bits.vs2 := vs2
  registers.io.request.bits.vd := vd
  registers.io.request.bits.useVs1 := io.in.bits.decoded.readsVs1
  registers.io.request.bits.useVs2 := io.in.bits.decoded.readsVs2
  // RVV stores encode their source vector as vs3 in the vd field.
  registers.io.request.bits.readVd :=
    io.in.bits.decoded.writesVd || io.in.bits.decoded.memoryWrite
  registers.io.request.bits.useMask :=
    !io.in.bits.decoded.configure && !io.in.bits.decoded.vm
  registers.io.request.bits.writeVd := io.in.bits.decoded.writesVd

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
