package gpu.core.execute.control

import chisel3._
import chisel3.util.Decoupled
import gpu.config.GpuConfig
import gpu.core.backend.issue.ScalarIssuedInstruction

class ScalarBranchExecutionResult(config: GpuConfig) extends Bundle {
  val warpId = UInt(config.warpIdWidth.W)
  val nextPc = UInt(config.xLen.W)
  val activeMask = UInt(config.lanes.W)
  val taken = Bool()
  val rd = UInt(5.W)
  val writeLink = Bool()
  val linkPc = UInt(config.xLen.W)
}

/** Scalar branch target resolution with metadata-preserving backpressure. */
class ScalarBranchExecuteStage(config: GpuConfig = GpuConfig()) extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new ScalarIssuedInstruction(config)))
    val out = Decoupled(new ScalarBranchExecutionResult(config))
  })

  private val resolver = Module(new ScalarBranchResolver(config))
  io.in.ready := resolver.io.in.ready
  resolver.io.in.valid := io.in.valid

  private val decoded = io.in.bits.decode.decoded
  resolver.io.in.bits.warpId := io.in.bits.decode.warpId
  resolver.io.in.bits.pc := io.in.bits.decode.pc
  resolver.io.in.bits.activeMask := io.in.bits.decode.activeMask
  resolver.io.in.bits.rd := decoded.rd
  resolver.io.in.bits.writeLink := decoded.jump && decoded.writeRd
  resolver.io.in.bits.immediate := decoded.immediate
  resolver.io.in.bits.lhs := io.in.bits.rs1Data
  resolver.io.in.bits.rhs := io.in.bits.rs2Data
  resolver.io.in.bits.branchOp := decoded.branchOp
  resolver.io.in.bits.kind := Mux(
    decoded.jump,
    Mux(
      decoded.useRs1,
      ControlFlowKind.jalr,
      ControlFlowKind.jal
    ),
    ControlFlowKind.conditional
  )

  io.out.valid := resolver.io.out.valid
  io.out.bits.warpId := resolver.io.out.bits.warpId
  io.out.bits.nextPc := resolver.io.out.bits.targetPc
  io.out.bits.activeMask := resolver.io.out.bits.activeMask
  io.out.bits.taken := resolver.io.out.bits.taken
  io.out.bits.rd := resolver.io.out.bits.rd
  io.out.bits.writeLink := resolver.io.out.bits.writeLink
  io.out.bits.linkPc := resolver.io.out.bits.linkPc

  resolver.io.out.ready := io.out.ready
}
