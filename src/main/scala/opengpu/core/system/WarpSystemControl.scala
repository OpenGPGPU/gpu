package opengpu.core.system

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.backend.issue.ScalarIssuedInstruction
import opengpu.core.execute.control.SimtPath
import opengpu.core.vector.VectorCsrWrite

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
    val barrier = Decoupled(new SimtPath(config))
    /** tex.sample: full issued instruction (rs1Data=u, rs2Data=v, decode.rd).
      * Executed externally; its result returns directly to the scalar
      * backend's commit path. */
    val texSample = Decoupled(new ScalarIssuedInstruction(config))
    val unsupported = Decoupled(new ScalarIssuedInstruction(config))
    val vxrmWrite = Valid(new VectorCsrWrite(config))
  })

  private val decoded = io.in.bits.decode.decoded
  private val cease = decoded.cease
  private val join = decoded.join
  private val fence = decoded.fence
  private val barrier = decoded.barrier
  private val texSample = decoded.texSample
  // Shader ABI: only csrrwi x0, vxrm, zimm[1:0] is supported. Other CSR
  // forms still trap, so no CSR read or read-modify-write semantics leak in.
  private val instruction = io.in.bits.decode.instruction
  private val setVxrm = decoded.csr &&
    (instruction & "hfff07fff".U) === "h00a05073".U &&
    instruction(19, 17) === 0.U

  // `valid` must not depend on `ready`: finish feeds scheduler state and can
  // otherwise close a combinational loop through the completion arbiter.
  io.finish.valid := io.in.valid && cease
  io.finish.bits := io.in.bits.decode.warpId

  io.restore.valid := io.in.valid && join
  io.restore.bits := io.in.bits.decode.warpId

  io.resume.valid := io.in.valid && (fence || setVxrm)
  io.resume.bits.warpId := io.in.bits.decode.warpId
  io.resume.bits.pc := io.in.bits.decode.pc + 4.U
  io.resume.bits.activeMask := io.in.bits.decode.activeMask

  io.barrier.valid := io.in.valid && barrier
  io.barrier.bits.warpId := io.in.bits.decode.warpId
  io.barrier.bits.pc := io.in.bits.decode.pc + 4.U
  io.barrier.bits.activeMask := io.in.bits.decode.activeMask

  io.texSample.valid := io.in.valid && texSample
  io.texSample.bits := io.in.bits

  io.vxrmWrite.valid := io.in.fire && setVxrm
  io.vxrmWrite.bits.warpId := io.in.bits.decode.warpId
  io.vxrmWrite.bits.address := "h00a".U
  io.vxrmWrite.bits.data := instruction(16, 15)

  private val known = cease || join || fence || barrier || texSample || setVxrm
  io.unsupported.valid := io.in.valid && !known
  io.unsupported.bits := io.in.bits

  io.in.ready := MuxCase(
    io.unsupported.ready,
    Seq(
      cease -> true.B,
      join -> io.restore.ready,
      fence -> io.resume.ready,
      setVxrm -> io.resume.ready,
      barrier -> io.barrier.ready,
      texSample -> io.texSample.ready
    )
  )
}
