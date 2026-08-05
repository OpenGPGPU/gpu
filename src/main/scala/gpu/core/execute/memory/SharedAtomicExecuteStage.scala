package gpu.core.execute.memory

import chisel3._
import chisel3.util._
import gpu.config.GpuConfig
import gpu.core.backend.issue.ScalarIssuedInstruction
import gpu.core.backend.writeback.ScalarCommitRequest
import gpu.core.memory.{ScalarMemoryFault, SharedAtomicRequest, SharedAtomicResponse}

/** Executes RV32A word AMOs only for the CU-local shared-memory window.
  * Global addresses remain explicit so they can later be routed to the shared
  * L2 atomic serialization point without pretending that a private CU is
  * globally coherent.
  */
class SharedAtomicExecuteStage(config: GpuConfig = GpuConfig()) extends Module {
  private val Seq(idle, localRequest, localResponse, globalRequest,
    globalResponse, commit, fault, unsupported) = Enum(8)
  private val state = RegInit(idle)
  private val instruction = Reg(new ScalarIssuedInstruction(config))
  private val oldValue = Reg(UInt(32.W))
  private val faultMisaligned = RegInit(false.B)
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new ScalarIssuedInstruction(config)))
    val atomicRequest = Decoupled(new SharedAtomicRequest(config))
    val atomicResponse = Flipped(Decoupled(new SharedAtomicResponse(config)))
    val globalAtomicRequest = Decoupled(new SharedAtomicRequest(config))
    val globalAtomicResponse = Flipped(Decoupled(new SharedAtomicResponse(config)))
    val out = Decoupled(new ScalarCommitRequest(config))
    val fault = Decoupled(new ScalarMemoryFault(config))
    val unimplemented = Decoupled(new ScalarIssuedInstruction(config))
  })

  private def isShared(address: UInt): Bool =
    address >= config.sharedMemoryBase.U &&
      address < (config.sharedMemoryBase + config.sharedMemoryBytes).U

  io.in.ready := state === idle
  when(io.in.fire) {
    instruction := io.in.bits
    faultMisaligned := io.in.bits.rs1Data(1, 0).orR
    state := Mux(io.in.bits.rs1Data(1, 0).orR, fault,
      Mux(isShared(io.in.bits.rs1Data), localRequest, globalRequest))
  }

  io.atomicRequest.valid := state === localRequest
  io.atomicRequest.bits.warpId := instruction.decode.warpId
  io.atomicRequest.bits.address := instruction.rs1Data
  io.atomicRequest.bits.operand := instruction.rs2Data
  io.atomicRequest.bits.operation := instruction.decode.decoded.atomicOp
  when(io.atomicRequest.fire) { state := localResponse }

  io.atomicResponse.ready := state === localResponse
  when(io.atomicResponse.fire) {
    oldValue := io.atomicResponse.bits.oldValue
    faultMisaligned := false.B
    state := Mux(io.atomicResponse.bits.fault, fault, commit)
  }

  io.globalAtomicRequest.valid := state === globalRequest
  io.globalAtomicRequest.bits.warpId := instruction.decode.warpId
  io.globalAtomicRequest.bits.address := instruction.rs1Data
  io.globalAtomicRequest.bits.operand := instruction.rs2Data
  io.globalAtomicRequest.bits.operation := instruction.decode.decoded.atomicOp
  when(io.globalAtomicRequest.fire) { state := globalResponse }

  io.globalAtomicResponse.ready := state === globalResponse
  when(io.globalAtomicResponse.fire) {
    oldValue := io.globalAtomicResponse.bits.oldValue
    faultMisaligned := false.B
    state := Mux(io.globalAtomicResponse.bits.fault, fault, commit)
  }

  io.out.valid := state === commit
  io.out.bits.warpId := instruction.decode.warpId
  io.out.bits.nextPc := instruction.decode.pc + 4.U
  io.out.bits.activeMask := instruction.decode.activeMask
  io.out.bits.writeRd := true.B
  io.out.bits.rd := instruction.decode.decoded.rd
  io.out.bits.data := oldValue
  when(io.out.fire) { state := idle }

  io.fault.valid := state === fault
  io.fault.bits.warpId := instruction.decode.warpId
  io.fault.bits.pc := instruction.decode.pc
  io.fault.bits.activeMask := instruction.decode.activeMask
  io.fault.bits.address := instruction.rs1Data
  io.fault.bits.isStore := true.B
  io.fault.bits.misaligned := faultMisaligned
  io.fault.bits.pageFault := false.B
  io.fault.bits.rd := instruction.decode.decoded.rd
  io.fault.bits.writeRd := instruction.decode.decoded.writeRd
  when(io.fault.fire) { state := idle }

  io.unimplemented.valid := state === unsupported
  io.unimplemented.bits := instruction
  when(io.unimplemented.fire) { state := idle }
}
