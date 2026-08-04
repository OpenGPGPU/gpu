package gpu.core.system

import chisel3._
import chisel3.util._
import gpu.config.GpuConfig
import gpu.core.backend.issue.ScalarIssuedInstruction
import gpu.core.execute.control.SimtPath

/** Executes warp-lifecycle system instructions.
  *
  * The frontend permits only one unresolved instruction per warp, so accepting
  * cease here also proves that all older instructions from that warp have
  * completed. No separate pipeline-drain counter is required.
  */
class WarpSystemControl(config: GpuConfig = GpuConfig()) extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new ScalarIssuedInstruction(config)))
    val finish = Valid(UInt(config.warpIdWidth.W))
    val restore = Decoupled(UInt(config.warpIdWidth.W))
    val resume = Decoupled(new SimtPath(config))
    val unsupported = Decoupled(new ScalarIssuedInstruction(config))
  })

  private val decoded = io.in.bits.decode.decoded
  private val cease = decoded.cease
  private val join = decoded.join
  private val fence = decoded.fence

  // `valid` must not depend on `ready`: finish feeds scheduler state and can
  // otherwise close a combinational loop through the completion arbiter.
  io.finish.valid := io.in.valid && cease
  io.finish.bits := io.in.bits.decode.warpId

  io.restore.valid := io.in.valid && join
  io.restore.bits := io.in.bits.decode.warpId

  io.resume.valid := io.in.valid && fence
  io.resume.bits.warpId := io.in.bits.decode.warpId
  io.resume.bits.pc := io.in.bits.decode.pc + 4.U
  io.resume.bits.activeMask := io.in.bits.decode.activeMask

  private val known = cease || join || fence
  io.unsupported.valid := io.in.valid && !known
  io.unsupported.bits := io.in.bits

  io.in.ready := MuxCase(
    io.unsupported.ready,
    Seq(
      cease -> true.B,
      join -> io.restore.ready,
      fence -> io.resume.ready
    )
  )
}
