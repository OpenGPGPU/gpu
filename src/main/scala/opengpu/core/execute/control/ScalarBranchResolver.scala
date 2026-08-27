package opengpu.core.execute.control

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.execute.integer.Rv32CarrySelect
import opengpu.core.frontend.decode.BranchOp

/** Registered scalar control-flow resolver.
  *
  * Conditional branches select `pc + immediate` only when their comparison is
  * true. JALR clears target bit zero as required by the RISC-V ISA.
  */
class ScalarBranchResolver(config: GpuConfig = GpuConfig()) extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new ScalarBranchRequest(config)))
    val out = Decoupled(new ScalarBranchResult(config))
  })

  private val outValid = RegInit(false.B)
  private val outBits = Reg(new ScalarBranchResult(config))
  private val canLoad = !outValid || io.out.ready

  private val comparison = MuxLookup(io.in.bits.branchOp, false.B)(Seq(
    BranchOp.eq -> (io.in.bits.lhs === io.in.bits.rhs),
    BranchOp.ne -> (io.in.bits.lhs =/= io.in.bits.rhs),
    BranchOp.lt -> (io.in.bits.lhs.asSInt < io.in.bits.rhs.asSInt),
    BranchOp.ge -> (io.in.bits.lhs.asSInt >= io.in.bits.rhs.asSInt),
    BranchOp.ltu -> (io.in.bits.lhs < io.in.bits.rhs),
    BranchOp.geu -> (io.in.bits.lhs >= io.in.bits.rhs)
  ))
  private val conditionalTarget =
    Rv32CarrySelect(io.in.bits.pc, io.in.bits.immediate)
  private val jalrTarget =
    Rv32CarrySelect(io.in.bits.lhs, io.in.bits.immediate) &
      ~1.U(config.xLen.W)
  private val isConditional =
    io.in.bits.kind === ControlFlowKind.conditional
  private val isJal = io.in.bits.kind === ControlFlowKind.jal
  private val taken = Mux(isConditional, comparison, true.B)
  private val target = Mux(
    isConditional,
    Mux(
      comparison,
      conditionalTarget,
      Rv32CarrySelect(io.in.bits.pc, 4.U(config.xLen.W))
    ),
    Mux(isJal, conditionalTarget, jalrTarget)
  )

  io.in.ready := canLoad
  io.out.valid := outValid
  io.out.bits := outBits

  when(canLoad) {
    outValid := io.in.valid
    when(io.in.valid) {
      outBits.warpId := io.in.bits.warpId
      outBits.activeMask := io.in.bits.activeMask
      outBits.rd := io.in.bits.rd
      outBits.writeLink := io.in.bits.writeLink
      outBits.targetPc := target
      outBits.linkPc :=
        Rv32CarrySelect(io.in.bits.pc, 4.U(config.xLen.W))
      outBits.taken := taken
    }
  }
}
