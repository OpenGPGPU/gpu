package opengpu.core.backend.writeback

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.execute.control.ScalarBranchExecutionResult
import opengpu.core.execute.integer.{IntegerExecutionResult, Rv32CarrySelect}

class IntegerCommitAdapter(config: GpuConfig = GpuConfig()) extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new IntegerExecutionResult(config)))
    val out = Decoupled(new ScalarCommitRequest(config))
  })

  io.out.valid := io.in.valid
  io.out.bits.warpId := io.in.bits.warpId
  io.out.bits.nextPc :=
    Rv32CarrySelect(io.in.bits.pc, 4.U(config.xLen.W))
  io.out.bits.activeMask := io.in.bits.activeMask
  io.out.bits.writeRd := io.in.bits.writeRd
  io.out.bits.rd := io.in.bits.rd
  io.out.bits.data := io.in.bits.data
  io.in.ready := io.out.ready
}

class BranchCommitAdapter(config: GpuConfig = GpuConfig()) extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new ScalarBranchExecutionResult(config)))
    val out = Decoupled(new ScalarCommitRequest(config))
  })

  io.out.valid := io.in.valid
  io.out.bits.warpId := io.in.bits.warpId
  io.out.bits.nextPc := io.in.bits.nextPc
  io.out.bits.activeMask := io.in.bits.activeMask
  io.out.bits.writeRd := io.in.bits.writeLink
  io.out.bits.rd := io.in.bits.rd
  io.out.bits.data := io.in.bits.linkPc
  io.in.ready := io.out.ready
}
