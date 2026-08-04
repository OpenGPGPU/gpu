package gpu.core.backend.writeback

import chisel3._
import chisel3.util._
import gpu.config.GpuConfig
import gpu.core.backend.register.ScalarRegisterWrite

/** Fair arbitration across scalar execution writeback sources.
  *
  * Writes to x0 are consumed locally and never occupy the RF port because x0
  * is not reserved in the scoreboard. Other requests use round-robin
  * arbitration to prevent a continuously active low-index source from starving
  * another execution unit.
  */
class ScalarWritebackArbiter(
  config: GpuConfig = GpuConfig(),
  sourceCount: Int = 6
) extends Module {
  require(sourceCount > 0)
  private val sourceWidth = math.max(1, log2Ceil(sourceCount))

  val io = IO(new Bundle {
    val in =
      Flipped(Vec(sourceCount, Decoupled(new ScalarRegisterWrite(config))))
    val out = Decoupled(new ScalarRegisterWrite(config))
    val selectedSource = Output(UInt(sourceWidth.W))
  })

  private val arbiter =
    Module(new RRArbiter(new ScalarRegisterWrite(config), sourceCount))

  for (index <- 0 until sourceCount) {
    val dropX0 =
      io.in(index).valid && io.in(index).bits.rd === 0.U
    arbiter.io.in(index).valid := io.in(index).valid && !dropX0
    arbiter.io.in(index).bits := io.in(index).bits
    io.in(index).ready := dropX0 || arbiter.io.in(index).ready
  }

  io.out <> arbiter.io.out
  io.selectedSource := arbiter.io.chosen

  when(io.out.valid) {
    assert(io.out.bits.rd =/= 0.U)
  }
}
