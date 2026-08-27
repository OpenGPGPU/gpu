package opengpu.core.backend.issue

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.frontend.decode.ExecutionType

/** Mutually-exclusive scalar execution dispatch.
  *
  * Trap routing has final priority over decoded execution controls. Integer M
  * operations are split before the base integer path so unsupported operations
  * cannot reach `IntegerExecuteStage`.
  */
class ScalarExecutionDispatch(config: GpuConfig = GpuConfig()) extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new ScalarIssuedInstruction(config)))
    val integer = Decoupled(new ScalarIssuedInstruction(config))
    val multiply = Decoupled(new ScalarIssuedInstruction(config))
    val divide = Decoupled(new ScalarIssuedInstruction(config))
    val branch = Decoupled(new ScalarIssuedInstruction(config))
    val memory = Decoupled(new ScalarIssuedInstruction(config))
    val atomic = Decoupled(new ScalarIssuedInstruction(config))
    val system = Decoupled(new ScalarIssuedInstruction(config))
    val trap = Decoupled(new ScalarIssuedInstruction(config))
  })

  private val decoded = io.in.bits.decode
  private val scalar = decoded.decoded
  private val selectTrap =
    decoded.instructionAccessFault || decoded.illegalInstruction
  private val selectMultiply =
    !selectTrap && decoded.executionType === ExecutionType.integer &&
      scalar.multiply
  private val selectDivide =
    !selectTrap && decoded.executionType === ExecutionType.integer &&
      scalar.divide
  private val selectInteger =
    !selectTrap && decoded.executionType === ExecutionType.integer &&
      !scalar.multiply && !scalar.divide
  private val selectBranch =
    !selectTrap && decoded.executionType === ExecutionType.branch
  private val selectMemory =
    !selectTrap && decoded.executionType === ExecutionType.memory &&
      !scalar.atomic
  private val selectAtomic =
    !selectTrap && decoded.executionType === ExecutionType.memory && scalar.atomic
  private val selectSystem =
    !selectTrap && decoded.executionType === ExecutionType.system

  private val outputs = Seq(
    (io.integer, selectInteger),
    (io.multiply, selectMultiply),
    (io.divide, selectDivide),
    (io.branch, selectBranch),
    (io.memory, selectMemory),
    (io.atomic, selectAtomic),
    (io.system, selectSystem),
    (io.trap, selectTrap)
  )

  outputs.foreach { case (output, selected) =>
    output.valid := io.in.valid && selected
    output.bits := io.in.bits
  }

  io.in.ready := outputs
    .map { case (output, selected) => selected && output.ready }
    .reduce(_ || _)

  when(io.in.valid) {
    assert(
      PopCount(VecInit(outputs.map(_._2))) === 1.U,
      "scalar instruction must select exactly one execution destination"
    )
  }
}
