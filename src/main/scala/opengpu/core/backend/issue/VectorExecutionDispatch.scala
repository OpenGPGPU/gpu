package opengpu.core.backend.issue

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.frontend.decode.VectorUnit

/** Exclusive vector execution-family router with selected-output backpressure. */
class VectorExecutionDispatch(config: GpuConfig = GpuConfig()) extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new VectorIssuedInstruction(config)))
    val alu = Decoupled(new VectorIssuedInstruction(config))
    val multiply = Decoupled(new VectorIssuedInstruction(config))
    val divide = Decoupled(new VectorIssuedInstruction(config))
    val fpu = Decoupled(new VectorIssuedInstruction(config))
    val configuration = Decoupled(new VectorIssuedInstruction(config))
    val memory = Decoupled(new VectorIssuedInstruction(config))
    val unimplemented = Decoupled(new VectorIssuedInstruction(config))
  })

  private val unit = io.in.bits.decode.decoded.unit
  private val toAlu = unit === VectorUnit.alu || unit === VectorUnit.mask
  private val toMultiply = unit === VectorUnit.multiply
  private val toDivide = unit === VectorUnit.divide
  private val toFpu = unit === VectorUnit.floatingPoint
  private val toConfiguration = unit === VectorUnit.configuration
  private val toMemory = unit === VectorUnit.loadStore
  private val toUnimplemented =
    !toAlu && !toMultiply && !toDivide && !toFpu &&
      !toConfiguration && !toMemory

  io.alu.valid := io.in.valid && toAlu
  io.multiply.valid := io.in.valid && toMultiply
  io.divide.valid := io.in.valid && toDivide
  io.fpu.valid := io.in.valid && toFpu
  io.configuration.valid := io.in.valid && toConfiguration
  io.memory.valid := io.in.valid && toMemory
  io.unimplemented.valid := io.in.valid && toUnimplemented
  io.alu.bits := io.in.bits
  io.multiply.bits := io.in.bits
  io.divide.bits := io.in.bits
  io.fpu.bits := io.in.bits
  io.configuration.bits := io.in.bits
  io.memory.bits := io.in.bits
  io.unimplemented.bits := io.in.bits

  io.in.ready := Mux(
    toAlu,
    io.alu.ready,
    Mux(
      toMultiply,
      io.multiply.ready,
      Mux(
        toDivide,
        io.divide.ready,
        Mux(
          toFpu,
          io.fpu.ready,
          Mux(
            toConfiguration,
            io.configuration.ready,
            Mux(toMemory, io.memory.ready, io.unimplemented.ready)
          )
        )
      )
    )
  )
}
