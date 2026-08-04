package gpu.core.backend.register

import chisel3._
import chisel3.util._
import gpu.config.GpuConfig
import gpu.core.backend.scoreboard.{
  VectorRegisterReservation,
  VectorRegisterScoreboard
}

class VectorIssueOperands(config: GpuConfig) extends Bundle {
  val request = new VectorRegisterReservation(config)
  val vs1Data = Vec(config.lanes, UInt(config.xLen.W))
  val vs2Data = Vec(config.lanes, UInt(config.xLen.W))
  val oldVdData = Vec(config.lanes, UInt(config.xLen.W))
  val predicateMask = UInt(config.lanes.W)
}

/** Two-stage elastic vector RF/scoreboard issue boundary. */
class VectorRegisterManager(config: GpuConfig = GpuConfig()) extends Module {
  val io = IO(new Bundle {
    val request = Flipped(Decoupled(new VectorRegisterReservation(config)))
    val issue = Decoupled(new VectorIssueOperands(config))
    val writeback = Flipped(Valid(new VectorRegisterWrite(config)))
    val cancel = Flipped(Valid(new gpu.core.backend.scoreboard.VectorRegisterRelease(config)))
    val rawHazard = Output(Bool())
    val wawHazard = Output(Bool())
  })

  private val registerFile = Module(new VectorRegisterFile(config))
  private val scoreboard = Module(new VectorRegisterScoreboard(config))
  private val requestValid = RegInit(false.B)
  private val requestBits = Reg(new VectorRegisterReservation(config))
  private val issueValid = RegInit(false.B)
  private val issueBits = Reg(new VectorIssueOperands(config))
  private val canLoadIssue = !issueValid || io.issue.ready

  scoreboard.io.reserve.valid := requestValid && canLoadIssue
  scoreboard.io.reserve.bits := requestBits
  scoreboard.io.release.valid := io.writeback.valid
  scoreboard.io.release.bits.warpId := io.writeback.bits.warpId
  scoreboard.io.release.bits.vd := io.writeback.bits.vd
  scoreboard.io.cancel := io.cancel

  registerFile.io.read.warpId := requestBits.warpId
  registerFile.io.read.vs1 := requestBits.vs1
  registerFile.io.read.vs2 := requestBits.vs2
  registerFile.io.read.vd := requestBits.vd
  registerFile.io.write := io.writeback

  private val moveRequest = scoreboard.io.reserve.fire
  io.request.ready := !requestValid || moveRequest
  io.issue.valid := issueValid
  io.issue.bits := issueBits

  when(io.request.fire) {
    requestValid := true.B
    requestBits := io.request.bits
  }.elsewhen(moveRequest) {
    requestValid := false.B
  }

  when(canLoadIssue) {
    issueValid := moveRequest
    when(moveRequest) {
      issueBits.request := requestBits
      issueBits.vs1Data := registerFile.io.vs1Data
      issueBits.vs2Data := registerFile.io.vs2Data
      issueBits.oldVdData := registerFile.io.oldVdData
      issueBits.predicateMask := registerFile.io.predicateMask
    }
  }

  io.rawHazard := scoreboard.io.rawHazard
  io.wawHazard := scoreboard.io.wawHazard
}
