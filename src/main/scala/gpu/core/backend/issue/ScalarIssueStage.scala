package gpu.core.backend.issue

import chisel3._
import chisel3.util._
import gpu.config.GpuConfig
import gpu.core.backend.register.{
  ScalarIssueOperands,
  ScalarRegisterMacroManager,
  ScalarRegisterWrite
}
import gpu.core.backend.scoreboard.RegisterRelease
import gpu.core.frontend.decode._

class ScalarIssuedInstruction(config: GpuConfig) extends Bundle {
  val decode = new ScalarDecodeResponse(config)
  val rs1Data = UInt(config.xLen.W)
  val rs2Data = UInt(config.xLen.W)
}

/** Couples decoded scalar metadata to RF/scoreboard operand issue.
  *
  * Metadata and the register reservation are accepted atomically. The FIFO is
  * consumed only with the corresponding operand response, so dependency stalls
  * and downstream backpressure cannot change instruction association.
  */
class ScalarIssueStage(
  config: GpuConfig = GpuConfig(),
  useBlackBoxes: Boolean = false
) extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new ScalarDecodeResponse(config)))
    val out = Decoupled(new ScalarIssuedInstruction(config))
    val writeback = Flipped(Valid(new ScalarRegisterWrite(config)))
    val cancel = Flipped(Valid(new RegisterRelease(config)))
    val rawHazard = Output(Bool())
    val wawHazard = Output(Bool())
  })

  private val registers =
    Module(new ScalarRegisterMacroManager(config, useBlackBoxes))
  private val metadata =
    Module(new Queue(new ScalarDecodeResponse(config), entries = 4))

  private val metadataCanAccept = metadata.io.enq.ready
  private val registersCanAccept = registers.io.request.ready
  io.in.ready := metadataCanAccept && registersCanAccept
  metadata.io.enq.valid := io.in.valid && registersCanAccept
  metadata.io.enq.bits := io.in.bits
  registers.io.request.valid := io.in.valid && metadataCanAccept

  private val executable =
    !io.in.bits.illegalInstruction &&
      !io.in.bits.instructionAccessFault
  registers.io.request.bits.warpId := io.in.bits.warpId
  registers.io.request.bits.rs1 := io.in.bits.decoded.rs1
  registers.io.request.bits.rs2 := io.in.bits.decoded.rs2
  registers.io.request.bits.rd := io.in.bits.decoded.rd
  registers.io.request.bits.useRs1 :=
    executable && io.in.bits.decoded.useRs1
  registers.io.request.bits.useRs2 :=
    executable && io.in.bits.decoded.useRs2
  registers.io.request.bits.writeRd :=
    executable && io.in.bits.decoded.writeRd

  private val pairedValid =
    metadata.io.deq.valid && registers.io.issue.valid
  private val outputValid = RegInit(false.B)
  private val outputBits = Reg(new ScalarIssuedInstruction(config))
  private val canLoadOutput = !outputValid || io.out.ready

  io.out.valid := outputValid
  io.out.bits := outputBits
  metadata.io.deq.ready := canLoadOutput && registers.io.issue.valid
  registers.io.issue.ready := canLoadOutput && metadata.io.deq.valid

  when(canLoadOutput) {
    outputValid := pairedValid
    when(pairedValid) {
      outputBits.decode := metadata.io.deq.bits
      outputBits.rs1Data := registers.io.issue.bits.rs1Data
      outputBits.rs2Data := registers.io.issue.bits.rs2Data
    }
  }

  registers.io.writeback := io.writeback
  registers.io.cancel := io.cancel
  io.rawHazard := registers.io.rawHazard
  io.wawHazard := registers.io.wawHazard

  when(pairedValid) {
    assert(
      metadata.io.deq.bits.warpId ===
        registers.io.issue.bits.request.warpId
    )
  }
}
