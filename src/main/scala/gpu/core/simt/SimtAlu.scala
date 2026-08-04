package gpu.core.simt

import chisel3._
import gpu.config.GpuConfig
import gpu.core.execute.integer.{AluOp, IntegerAlu}

/** Broadcasts one decoded integer operation across all active lanes of a warp. */
class SimtAlu(config: GpuConfig = GpuConfig()) extends Module {
  val io = IO(new Bundle {
    val activeMask = Input(Vec(config.lanes, Bool()))
    val lhs = Input(Vec(config.lanes, UInt(config.xLen.W)))
    val rhs = Input(Vec(config.lanes, UInt(config.xLen.W)))
    val operation = Input(UInt(AluOp.width.W))
    val result = Output(Vec(config.lanes, UInt(config.xLen.W)))
  })

  for (lane <- 0 until config.lanes) {
    val alu = Module(new IntegerAlu(config.xLen))
    alu.io.lhs := io.lhs(lane)
    alu.io.rhs := io.rhs(lane)
    alu.io.operation := io.operation
    io.result(lane) := Mux(io.activeMask(lane), alu.io.result, 0.U)
  }
}
