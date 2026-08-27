package opengpu.core.backend.register

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.backend.scoreboard.{
  RegisterReservation,
  RegisterScoreboard,
  RegisterRelease
}

class ScalarIssueOperands(config: GpuConfig) extends Bundle {
  val request = new RegisterReservation(config)
  val rs1Data = UInt(config.xLen.W)
  val rs2Data = UInt(config.xLen.W)
}

/** Two-stage atomic scalar register/scoreboard issue boundary.
  *
  * The request register isolates decoder inputs from the large RF/scoreboard
  * selection network. The registered operand output isolates that network
  * from execution. Both stages are elastic and sustain one request per cycle.
  */
class ScalarRegisterManager(config: GpuConfig = GpuConfig()) extends Module {
  val io = IO(new Bundle {
    val request = Flipped(Decoupled(new RegisterReservation(config)))
    val issue = Decoupled(new ScalarIssueOperands(config))
    val writeback = Flipped(Valid(new ScalarRegisterWrite(config)))
    val cancel = Flipped(Valid(new RegisterRelease(config)))
    val rawHazard = Output(Bool())
    val wawHazard = Output(Bool())
  })

  private val registerFile = Module(new ScalarRegisterFile(config))
  private val scoreboard = Module(new RegisterScoreboard(config))

  private val requestValid = RegInit(false.B)
  private val requestBits = Reg(new RegisterReservation(config))
  private val issueValid = RegInit(false.B)
  private val issueBits = Reg(new ScalarIssueOperands(config))
  private val canLoadIssue = !issueValid || io.issue.ready

  scoreboard.io.reserve.valid := requestValid && canLoadIssue
  scoreboard.io.reserve.bits := requestBits
  scoreboard.io.release.valid := io.writeback.valid
  scoreboard.io.release.bits.warpId := io.writeback.bits.warpId
  scoreboard.io.release.bits.rd := io.writeback.bits.rd
  scoreboard.io.cancel := io.cancel

  registerFile.io.read.warpId := requestBits.warpId
  registerFile.io.read.rs1 := requestBits.rs1
  registerFile.io.read.rs2 := requestBits.rs2
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
      issueBits.rs1Data := registerFile.io.rs1Data
      issueBits.rs2Data := registerFile.io.rs2Data
    }
  }

  io.rawHazard := scoreboard.io.rawHazard
  io.wawHazard := scoreboard.io.wawHazard
}
