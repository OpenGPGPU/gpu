package opengpu.core.execute.integer

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.backend.issue.ScalarIssuedInstruction
import opengpu.core.frontend.decode.ExecutionType

class IntegerExecutionResult(config: GpuConfig) extends Bundle {
  val warpId = UInt(config.warpIdWidth.W)
  val pc = UInt(config.xLen.W)
  val activeMask = UInt(config.lanes.W)
  val rd = UInt(5.W)
  val writeRd = Bool()
  val data = UInt(config.xLen.W)
}

private class PreparedIntegerOperation(config: GpuConfig) extends Bundle {
  val warpId = UInt(config.warpIdWidth.W)
  val pc = UInt(config.xLen.W)
  val activeMask = UInt(config.lanes.W)
  val rd = UInt(5.W)
  val writeRd = Bool()
  val operation = UInt(AluOp.width.W)
  val lhs = UInt(config.xLen.W)
  val rhs = UInt(config.xLen.W)
}

/** Operand-preparation plus RV32I ALU elastic execution boundary.
  *
  * M-extension operations are intentionally excluded until dedicated
  * multiply/divide units exist; accepting them here would silently execute the
  * decoder's default ALU operation. The first stage registers the final ALU
  * operands and metadata, isolating execution from issue/dispatch selection.
  * The second stage performs the complete ALU operation. Both stages are
  * elastic and sustain one instruction per cycle.
  */
class IntegerExecuteStage(config: GpuConfig = GpuConfig()) extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new ScalarIssuedInstruction(config)))
    val out = Decoupled(new IntegerExecutionResult(config))
  })

  private val preparedValid = RegInit(false.B)
  private val preparedBits = Reg(new PreparedIntegerOperation(config))
  private val outputValid = RegInit(false.B)
  private val outputBits = Reg(new IntegerExecutionResult(config))
  private val canLoadOutput = !outputValid || io.out.ready
  private val movePrepared = preparedValid && canLoadOutput
  private val canLoadPrepared = !preparedValid || movePrepared

  private val decoded = io.in.bits.decode.decoded
  private val supported =
    io.in.bits.decode.executionType === ExecutionType.integer &&
      !decoded.multiply && !decoded.divide &&
      !io.in.bits.decode.illegalInstruction &&
      !io.in.bits.decode.instructionAccessFault

  private val lhs =
    Mux(decoded.usePc, io.in.bits.decode.pc, io.in.bits.rs1Data)
  private val rhs =
    Mux(decoded.useImmediate, decoded.immediate, io.in.bits.rs2Data)

  io.in.ready := canLoadPrepared && supported
  io.out.valid := outputValid
  io.out.bits := outputBits

  when(canLoadPrepared) {
    preparedValid := io.in.valid && supported
    when(io.in.valid && supported) {
      preparedBits.warpId := io.in.bits.decode.warpId
      preparedBits.pc := io.in.bits.decode.pc
      preparedBits.activeMask := io.in.bits.decode.activeMask
      preparedBits.rd := decoded.rd
      preparedBits.writeRd := decoded.writeRd
      preparedBits.operation := decoded.aluOp
      preparedBits.lhs := lhs
      preparedBits.rhs := rhs
    }
  }

  private val alu = Module(new IntegerAlu(config.xLen))
  alu.io.lhs := preparedBits.lhs
  alu.io.rhs := preparedBits.rhs
  alu.io.operation := preparedBits.operation

  when(canLoadOutput) {
    outputValid := preparedValid
    when(preparedValid) {
      outputBits.warpId := preparedBits.warpId
      outputBits.pc := preparedBits.pc
      outputBits.activeMask := preparedBits.activeMask
      outputBits.rd := preparedBits.rd
      outputBits.writeRd := preparedBits.writeRd
      outputBits.data := alu.io.result
    }
  }

  when(io.in.valid) {
    assert(
      supported,
      "IntegerExecuteStage received a non-RV32I-ALU instruction"
    )
  }
}
