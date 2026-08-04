package gpu.core.execute.integer

import chisel3._
import chisel3.util._
import gpu.config.GpuConfig
import gpu.core.backend.issue.ScalarIssuedInstruction
import gpu.core.frontend.decode.ExecutionType

/** Iterative RV32M divide/remainder execution.
  *
  * One radix-2 quotient bit is produced per cycle. This keeps the critical
  * combinational path to a 33-bit compare/subtract instead of inferring a
  * combinational divider. Divide-by-zero and signed overflow follow the RISC-V
  * architectural results and complete without entering the iteration loop.
  */
class DivideExecuteStage(config: GpuConfig = GpuConfig()) extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new ScalarIssuedInstruction(config)))
    val out = Decoupled(new IntegerExecutionResult(config))
  })

  private val busy = RegInit(false.B)
  private val count = RegInit(0.U(6.W))
  private val quotient = Reg(UInt(32.W))
  private val divisor = Reg(UInt(32.W))
  private val remainder = Reg(UInt(33.W))
  private val negateQuotient = Reg(Bool())
  private val negateRemainder = Reg(Bool())
  private val selectRemainder = Reg(Bool())
  private val saved = Reg(new IntegerExecutionResult(config))

  private val outputValid = RegInit(false.B)
  private val outputBits = Reg(new IntegerExecutionResult(config))
  private val outputAvailable = !outputValid || io.out.ready

  private val decoded = io.in.bits.decode.decoded
  private val funct3 = io.in.bits.decode.instruction(14, 12)
  private val supported =
    io.in.bits.decode.executionType === ExecutionType.integer &&
      decoded.divide && !decoded.multiply &&
      !io.in.bits.decode.illegalInstruction &&
      !io.in.bits.decode.instructionAccessFault &&
      funct3 >= 4.U

  private val signedOperation = funct3 === 4.U || funct3 === 6.U
  private val wantsRemainder = funct3 === 6.U || funct3 === 7.U
  private val lhsNegative = signedOperation && io.in.bits.rs1Data(31)
  private val rhsNegative = signedOperation && io.in.bits.rs2Data(31)
  private val lhsMagnitude =
    Mux(lhsNegative, 0.U(32.W) - io.in.bits.rs1Data, io.in.bits.rs1Data)
  private val rhsMagnitude =
    Mux(rhsNegative, 0.U(32.W) - io.in.bits.rs2Data, io.in.bits.rs2Data)
  private val divideByZero = io.in.bits.rs2Data === 0.U
  private val signedOverflow =
    signedOperation &&
      io.in.bits.rs1Data === "h80000000".U &&
      io.in.bits.rs2Data === "hffffffff".U
  private val specialResult = Mux(
    divideByZero,
    Mux(wantsRemainder, io.in.bits.rs1Data, "hffffffff".U),
    Mux(wantsRemainder, 0.U, "h80000000".U)
  )

  io.in.ready := !busy && outputAvailable && supported
  io.out.valid := outputValid
  io.out.bits := outputBits

  when(outputAvailable) {
    outputValid := false.B
  }

  when(io.in.fire) {
    val metadata = Wire(new IntegerExecutionResult(config))
    metadata.warpId := io.in.bits.decode.warpId
    metadata.pc := io.in.bits.decode.pc
    metadata.activeMask := io.in.bits.decode.activeMask
    metadata.rd := decoded.rd
    metadata.writeRd := decoded.writeRd
    metadata.data := 0.U

    when(divideByZero || signedOverflow) {
      outputValid := true.B
      outputBits := metadata
      outputBits.data := specialResult
    }.otherwise {
      busy := true.B
      count := 0.U
      quotient := lhsMagnitude
      divisor := rhsMagnitude
      remainder := 0.U
      negateQuotient := lhsNegative ^ rhsNegative
      negateRemainder := lhsNegative
      selectRemainder := wantsRemainder
      saved := metadata
    }
  }

  private val shiftedRemainder = Cat(remainder(31, 0), quotient(31))
  private val subtracts = shiftedRemainder >= Cat(0.U(1.W), divisor)
  private val nextRemainder = Mux(
    subtracts,
    shiftedRemainder - Cat(0.U(1.W), divisor),
    shiftedRemainder
  )
  private val nextQuotient = Cat(quotient(30, 0), subtracts)

  when(busy) {
    remainder := nextRemainder
    quotient := nextQuotient
    count := count + 1.U

    when(count === 31.U) {
      val quotientResult =
        Mux(negateQuotient, 0.U(32.W) - nextQuotient, nextQuotient)
      val remainderMagnitude = nextRemainder(31, 0)
      val remainderResult =
        Mux(
          negateRemainder,
          0.U(32.W) - remainderMagnitude,
          remainderMagnitude
        )

      busy := false.B
      outputValid := true.B
      outputBits := saved
      outputBits.data :=
        Mux(selectRemainder, remainderResult, quotientResult)
    }
  }

  when(io.in.valid) {
    assert(
      supported,
      "DivideExecuteStage received a non-divide instruction"
    )
    assert(
      funct3 <= 7.U,
      "DivideExecuteStage received an unsupported RV32M funct3"
    )
  }
}
