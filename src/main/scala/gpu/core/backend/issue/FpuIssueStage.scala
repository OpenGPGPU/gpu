package gpu.core.backend.issue

import chisel3._
import chisel3.util._
import gpu.config.GpuConfig
import gpu.core.backend.register.{
  FpuRegisterFile,
  FpuRegisterRead,
  FpuRegisterWrite,
  ScalarRegisterRead
}
import gpu.core.backend.scoreboard.{FpuRelease, FpuReservation, FpuScoreboard}
import gpu.core.frontend.decode.FpuDecodeResponse

class FpuIssuedInstruction(config: GpuConfig) extends Bundle {
  val decode = new FpuDecodeResponse(config)
  val rs1Data = UInt(32.W)
  val rs2Data = UInt(32.W)
  val rs3Data = UInt(32.W)
  val scalarRs1Data = UInt(32.W)
}

/** Elastic three-source FP register-read and dependency issue stage. */
class FpuIssueStage(config: GpuConfig = GpuConfig()) extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new FpuDecodeResponse(config)))
    val out = Decoupled(new FpuIssuedInstruction(config))
    val writeback = Flipped(Valid(new FpuRegisterWrite(config)))
    val scalarRead = Output(new ScalarRegisterRead(config))
    val scalarRs1Data = Input(UInt(32.W))
    val fvfRead = Input(new FpuRegisterRead(config))
    val fvfData = Output(UInt(32.W))
    val busyByWarp = Output(Vec(config.warps, UInt(32.W)))
    val rawHazard = Output(Bool())
    val wawHazard = Output(Bool())
  })

  private val rf = Module(new FpuRegisterFile(config))
  private val scoreboard = Module(new FpuScoreboard(config))
  private val metadata = Module(new Queue(new FpuDecodeResponse(config), 4))
  private val operandQueue = Module(new Queue(new FpuIssuedInstruction(config), 2))

  io.scalarRead.warpId := io.in.bits.warpId
  io.scalarRead.rs1 := io.in.bits.instruction(19, 15)
  io.scalarRead.rs2 := 0.U

  private val reservation = Wire(new FpuReservation(config))
  reservation.warpId := io.in.bits.warpId
  reservation.rs1 := io.in.bits.instruction(19, 15)
  reservation.rs2 := io.in.bits.instruction(24, 20)
  reservation.rs3 := io.in.bits.instruction(31, 27)
  reservation.useRs1 := io.in.bits.decoded.readsRs1
  reservation.useRs2 := io.in.bits.decoded.readsRs2
  reservation.useRs3 := io.in.bits.decoded.readsRs3
  reservation.rd := io.in.bits.instruction(11, 7)
  reservation.writeRd := io.in.bits.decoded.writesFp

  private val allReady = metadata.io.enq.ready && operandQueue.io.enq.ready &&
    scoreboard.io.reserve.ready
  io.in.ready := allReady
  scoreboard.io.reserve.valid := io.in.valid && metadata.io.enq.ready &&
    operandQueue.io.enq.ready
  scoreboard.io.reserve.bits := reservation
  metadata.io.enq.valid := io.in.valid && operandQueue.io.enq.ready &&
    scoreboard.io.reserve.ready
  metadata.io.enq.bits := io.in.bits
  operandQueue.io.enq.valid := io.in.valid && metadata.io.enq.ready &&
    scoreboard.io.reserve.ready
  operandQueue.io.enq.bits.decode := io.in.bits
  operandQueue.io.enq.bits.rs1Data := rf.io.rs1Data
  operandQueue.io.enq.bits.rs2Data := rf.io.rs2Data
  operandQueue.io.enq.bits.rs3Data := rf.io.rs3Data
  operandQueue.io.enq.bits.scalarRs1Data := io.scalarRs1Data

  rf.io.read.warpId := reservation.warpId
  rf.io.read.rs1 := reservation.rs1
  rf.io.read.rs2 := reservation.rs2
  rf.io.read.rs3 := reservation.rs3
  rf.io.fvfRead := io.fvfRead
  io.fvfData := rf.io.fvfData
  rf.io.write := io.writeback
  io.busyByWarp := scoreboard.io.busyByWarp
  scoreboard.io.release.valid := io.writeback.valid
  scoreboard.io.release.bits.warpId := io.writeback.bits.warpId
  scoreboard.io.release.bits.rd := io.writeback.bits.rd

  // Metadata queue is an association check; operands carry the same snapshot.
  io.out.valid := operandQueue.io.deq.valid && metadata.io.deq.valid
  io.out.bits := operandQueue.io.deq.bits
  operandQueue.io.deq.ready := io.out.ready && metadata.io.deq.valid
  metadata.io.deq.ready := io.out.ready && operandQueue.io.deq.valid
  io.rawHazard := scoreboard.io.rawHazard
  io.wawHazard := scoreboard.io.wawHazard

  when(io.out.valid) {
    assert(metadata.io.deq.bits.warpId === operandQueue.io.deq.bits.decode.warpId)
    assert(metadata.io.deq.bits.instruction === operandQueue.io.deq.bits.decode.instruction)
  }
}
