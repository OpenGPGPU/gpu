package gpu.core.backend

import chisel3._
import chisel3.util._
import gpu.config.GpuConfig
import gpu.core.backend.issue.{FpuIssueStage, FpuIssuedInstruction}
import gpu.core.backend.register.FpuRegisterWrite
import gpu.core.execute.control.SimtPath
import gpu.core.execute.fpu.{Fp32FmaLane, FpuFastMapper}
import gpu.core.frontend.decode.FpuDecodeResponse

class FpuFlags(config: GpuConfig) extends Bundle {
  val warpId = UInt(config.warpIdWidth.W)
  val flags = UInt(5.W)
}

/** Scalar FP32 fast backend. Compare/convert/memory remain explicit outputs. */
class FpuBackend(config: GpuConfig = GpuConfig(), tagWidth: Int = 16)
    extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new FpuDecodeResponse(config)))
    val redirect = Decoupled(new SimtPath(config))
    val committedWriteback = Valid(new FpuRegisterWrite(config))
    val committedFlags = Valid(new FpuFlags(config))
    val initialize = Flipped(Decoupled(new FpuRegisterWrite(config)))
    val unimplemented = Decoupled(new FpuIssuedInstruction(config))
    val rawHazard = Output(Bool())
    val wawHazard = Output(Bool())
    val flush = Input(Bool())
  })

  private val issue = Module(new FpuIssueStage(config))
  private val mapper = Module(new FpuFastMapper(config, tagWidth))
  private val fma = Module(new Fp32FmaLane(tagWidth))
  private val metadata = Module(new Queue(new FpuIssuedInstruction(config), 8))

  issue.io.in <> io.in
  mapper.io.in := issue.io.out.bits.decode
  mapper.io.rs1Data := issue.io.out.bits.rs1Data
  mapper.io.rs2Data := issue.io.out.bits.rs2Data
  mapper.io.rs3Data := issue.io.out.bits.rs3Data

  private val fast = mapper.io.out.supported
  private val fastReady = fma.io.in.ready && metadata.io.enq.ready
  issue.io.out.ready := Mux(fast, fastReady, io.unimplemented.ready)
  fma.io.in.valid := issue.io.out.valid && fast && metadata.io.enq.ready
  fma.io.in.bits := mapper.io.out.request
  metadata.io.enq.valid := issue.io.out.valid && fast && fma.io.in.ready
  metadata.io.enq.bits := issue.io.out.bits
  io.unimplemented.valid := issue.io.out.valid && !fast
  io.unimplemented.bits := issue.io.out.bits
  fma.io.flush := io.flush

  private val resultValid = fma.io.out.valid && metadata.io.deq.valid
  private val result = metadata.io.deq.bits
  private val rd = result.decode.instruction(11, 7)
  private val needsWrite = result.decode.decoded.writesFp
  private val commitReady = io.redirect.ready
  fma.io.out.ready := metadata.io.deq.valid && commitReady
  metadata.io.deq.ready := fma.io.out.valid && commitReady

  io.redirect.valid := resultValid
  io.redirect.bits.warpId := result.decode.warpId
  io.redirect.bits.pc := result.decode.pc + 4.U
  io.redirect.bits.activeMask := result.decode.activeMask
  io.committedWriteback.valid := resultValid && commitReady && needsWrite
  io.committedWriteback.bits.warpId := result.decode.warpId
  io.committedWriteback.bits.rd := rd
  io.committedWriteback.bits.data := fma.io.out.bits.result
  io.committedFlags.valid := resultValid && commitReady && result.decode.decoded.setsFlags
  io.committedFlags.bits.warpId := result.decode.warpId
  io.committedFlags.bits.flags := fma.io.out.bits.status

  io.initialize.ready := !io.committedWriteback.valid
  issue.io.writeback.valid := io.committedWriteback.valid || io.initialize.fire
  issue.io.writeback.bits := Mux(
    io.committedWriteback.valid,
    io.committedWriteback.bits,
    io.initialize.bits
  )
  io.rawHazard := issue.io.rawHazard
  io.wawHazard := issue.io.wawHazard

  when(fma.io.out.valid && metadata.io.deq.valid) {
    assert(fma.io.out.bits.tag ===
      Cat(result.decode.warpId, result.decode.instruction(11, 7)))
  }
}
