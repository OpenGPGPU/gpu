package opengpu.core.frontend.decode

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig

/** One-stage decoupled decode pipe with backpressure. */
class DecodePipe(config: GpuConfig = GpuConfig()) extends Module {
  val io = IO(new DecodePipeIO(config))

  val decoder = Module(new FullInstructionDecoder(config))
  decoder.io.instruction := io.in.bits.instruction

  val outputValid = RegInit(false.B)
  val outputBits = Reg(new DecodeResponse(config))
  val outputIsFpu =
    !outputBits.instructionAccessFault &&
      outputBits.decoded.executionType === ExecutionType.fpu
  val outputIsVector =
    !outputBits.instructionAccessFault &&
      outputBits.decoded.executionType === ExecutionType.vector
  val selectedReady = Mux(
    outputIsFpu,
    io.fpuOut.ready,
    Mux(outputIsVector, io.vectorOut.ready, io.scalarOut.ready)
  )
  val canAccept = !outputValid || selectedReady

  io.in.ready := canAccept
  when(canAccept) {
    outputValid := io.in.valid
    when(io.in.valid) {
      outputBits.instruction := io.in.bits.instruction
      outputBits.pc := io.in.bits.pc
      outputBits.warpId := io.in.bits.warpId
      outputBits.activeMask := io.in.bits.activeMask
      outputBits.instructionAccessFault := io.in.bits.instructionAccessFault
      outputBits.decoded := decoder.io.decoded
    }
  }

  io.scalarOut.valid := outputValid && !outputIsFpu && !outputIsVector
  io.scalarOut.bits.instruction := outputBits.instruction
  io.scalarOut.bits.pc := outputBits.pc
  io.scalarOut.bits.warpId := outputBits.warpId
  io.scalarOut.bits.activeMask := outputBits.activeMask
  io.scalarOut.bits.instructionAccessFault :=
    outputBits.instructionAccessFault
  io.scalarOut.bits.executionType := outputBits.decoded.executionType
  io.scalarOut.bits.illegalInstruction :=
    outputBits.decoded.illegalInstruction && !outputBits.instructionAccessFault
  io.scalarOut.bits.decoded := outputBits.decoded.scalar

  io.fpuOut.valid := outputValid && outputIsFpu
  io.fpuOut.bits.instruction := outputBits.instruction
  io.fpuOut.bits.pc := outputBits.pc
  io.fpuOut.bits.warpId := outputBits.warpId
  io.fpuOut.bits.activeMask := outputBits.activeMask
  io.fpuOut.bits.decoded := outputBits.decoded.fpu

  io.vectorOut.valid := outputValid && outputIsVector
  io.vectorOut.bits.instruction := outputBits.instruction
  io.vectorOut.bits.pc := outputBits.pc
  io.vectorOut.bits.warpId := outputBits.warpId
  io.vectorOut.bits.activeMask := outputBits.activeMask
  io.vectorOut.bits.decoded := outputBits.decoded.vector
}
