package opengpu.core.backend

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.backend.issue._
import opengpu.core.backend.register.ScalarRegisterWrite
import opengpu.core.backend.scoreboard.RegisterReservation
import opengpu.core.backend.writeback._
import opengpu.core.execute.control.{
  ScalarBranchExecuteStage,
  SimtPath
}
import opengpu.core.execute.integer.{
  DivideExecuteStage,
  IntegerExecuteStage,
  MultiplyExecuteStage
}
import opengpu.core.execute.memory.SharedAtomicExecuteStage
import opengpu.core.frontend.decode.ScalarDecodeResponse
import opengpu.core.memory.{
  ScalarMemoryFault,
  ScalarMemoryUnit,
  SharedAtomicRequest,
  SharedAtomicResponse,
  VectorCacheLineRequest,
  VectorCacheLineResponse
}

/** Implemented scalar issue/execute/commit pipeline.
  *
  * RV32I ALU and scalar branch/jump paths are complete. Other classes remain
  * explicit decoupled outputs, so they backpressure safely until their
  * execution units are connected.
  */
class ScalarBackend(
  config: GpuConfig = GpuConfig(),
  useBlackBoxes: Boolean = false
) extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new ScalarDecodeResponse(config)))
    val initialize = Flipped(Decoupled(new ScalarRegisterWrite(config)))
    val redirect = Decoupled(new SimtPath(config))
    val committedWriteback = Valid(new ScalarRegisterWrite(config))
    val externalWriteback =
      Flipped(Decoupled(new ScalarRegisterWrite(config)))
    val externalReserve =
      Flipped(Decoupled(new RegisterReservation(config)))
    val appliedWriteback = Valid(new ScalarRegisterWrite(config))
    val cacheRequest = Decoupled(new VectorCacheLineRequest(config))
    val cacheResponse = Flipped(Decoupled(new VectorCacheLineResponse()))
    val memoryFault = Decoupled(new ScalarMemoryFault(config))
    val sharedAtomicRequest = Decoupled(new SharedAtomicRequest(config))
    val sharedAtomicResponse = Flipped(Decoupled(new SharedAtomicResponse(config)))
    val globalAtomicRequest = Decoupled(new SharedAtomicRequest(config))
    val globalAtomicResponse = Flipped(Decoupled(new SharedAtomicResponse(config)))

    val memory = Decoupled(new ScalarIssuedInstruction(config))
    val system = Decoupled(new ScalarIssuedInstruction(config))
    /** External sampler result committed through the pipeline's own commit
      * path (the unit builds the full ScalarCommitRequest, incl. nextPc). */
    val texCommit = Flipped(Decoupled(new ScalarCommitRequest(config)))
    val trap = Decoupled(new ScalarIssuedInstruction(config))

    val rawHazard = Output(Bool())
    val wawHazard = Output(Bool())
  })

  private val issue = Module(new ScalarIssueStage(config, useBlackBoxes))
  private val dispatch = Module(new ScalarExecutionDispatch(config))
  private val integer = Module(new IntegerExecuteStage(config))
  private val multiply = Module(new MultiplyExecuteStage(config))
  private val divide = Module(new DivideExecuteStage(config))
  private val branch = Module(new ScalarBranchExecuteStage(config))
  private val memoryUnit = Module(new ScalarMemoryUnit(config))
  private val atomic = Module(new SharedAtomicExecuteStage(config))
  private val integerAdapter = Module(new IntegerCommitAdapter(config))
  private val multiplyAdapter = Module(new IntegerCommitAdapter(config))
  private val divideAdapter = Module(new IntegerCommitAdapter(config))
  private val branchAdapter = Module(new BranchCommitAdapter(config))
  private val commitArbiter =
    Module(new RRArbiter(new ScalarCommitRequest(config), 7))
  private val commit = Module(new ScalarCommitStage(config))

  private val externalBusy =
    RegInit(VecInit(Seq.fill(config.warps)(0.U(32.W))))
  private val inputWarpValid = io.in.bits.warpId < config.warps.U
  private val selectedExternalBusy =
    if (config.warps == 1) externalBusy(0)
    else Mux(inputWarpValid, externalBusy(io.in.bits.warpId), 0.U)
  private def externallyBusy(register: UInt): Bool =
    register =/= 0.U && selectedExternalBusy(register)
  private val externalRawHazard =
    (io.in.bits.decoded.useRs1 &&
      externallyBusy(io.in.bits.decoded.rs1)) ||
      (io.in.bits.decoded.useRs2 &&
        externallyBusy(io.in.bits.decoded.rs2))
  private val externalWawHazard =
    io.in.bits.decoded.writeRd &&
      externallyBusy(io.in.bits.decoded.rd)
  private val externalHazard =
    externalRawHazard || externalWawHazard

  issue.io.in.valid := io.in.valid && !externalHazard
  issue.io.in.bits := io.in.bits
  io.in.ready := issue.io.in.ready && !externalHazard
  dispatch.io.in <> issue.io.out
  integer.io.in <> dispatch.io.integer
  multiply.io.in <> dispatch.io.multiply
  divide.io.in <> dispatch.io.divide
  branch.io.in <> dispatch.io.branch
  memoryUnit.io.in <> dispatch.io.memory
  atomic.io.in <> dispatch.io.atomic
  io.sharedAtomicRequest <> atomic.io.atomicRequest
  atomic.io.atomicResponse <> io.sharedAtomicResponse
  io.globalAtomicRequest <> atomic.io.globalAtomicRequest
  atomic.io.globalAtomicResponse <> io.globalAtomicResponse
  io.cacheRequest <> memoryUnit.io.cacheRequest
  memoryUnit.io.cacheResponse <> io.cacheResponse
  private val memoryFaultArbiter = Module(new RRArbiter(
    new ScalarMemoryFault(config), 2))
  memoryFaultArbiter.io.in(0) <> memoryUnit.io.fault
  memoryFaultArbiter.io.in(1) <> atomic.io.fault
  io.memoryFault <> memoryFaultArbiter.io.out
  integerAdapter.io.in <> integer.io.out
  multiplyAdapter.io.in <> multiply.io.out
  divideAdapter.io.in <> divide.io.out
  branchAdapter.io.in <> branch.io.out
  commitArbiter.io.in(0) <> integerAdapter.io.out
  commitArbiter.io.in(1) <> multiplyAdapter.io.out
  commitArbiter.io.in(2) <> divideAdapter.io.out
  commitArbiter.io.in(3) <> branchAdapter.io.out
  commitArbiter.io.in(4) <> memoryUnit.io.commit
  commitArbiter.io.in(5) <> atomic.io.out
  commitArbiter.io.in(6) <> io.texCommit
  commit.io.in <> commitArbiter.io.out

  io.redirect <> commit.io.redirect
  commit.io.writeback.ready := true.B
  io.externalWriteback.ready := !commit.io.writeback.valid
  io.initialize.ready :=
    !commit.io.writeback.valid && !io.externalWriteback.valid
  io.externalReserve.ready :=
    io.externalReserve.bits.warpId < config.warps.U
  issue.io.writeback.valid :=
    commit.io.writeback.fire || io.externalWriteback.fire ||
      io.initialize.fire
  issue.io.writeback.bits := Mux(
    commit.io.writeback.fire,
    commit.io.writeback.bits,
    Mux(io.externalWriteback.fire, io.externalWriteback.bits, io.initialize.bits)
  )
  issue.io.cancel.valid := io.memoryFault.fire && io.memoryFault.bits.writeRd
  issue.io.cancel.bits.warpId := io.memoryFault.bits.warpId
  issue.io.cancel.bits.rd := io.memoryFault.bits.rd
  io.committedWriteback.valid := commit.io.writeback.fire
  io.committedWriteback.bits := commit.io.writeback.bits
  io.appliedWriteback.valid :=
    commit.io.writeback.fire || io.externalWriteback.fire ||
      io.initialize.fire
  io.appliedWriteback.bits := issue.io.writeback.bits

  private val reserveEnabled =
    io.externalReserve.fire && io.externalReserve.bits.writeRd &&
      io.externalReserve.bits.rd =/= 0.U
  private val releaseEnabled =
    io.externalWriteback.fire && io.externalWriteback.bits.rd =/= 0.U
  private val reserveOH = UIntToOH(io.externalReserve.bits.rd, 32)
  private val releaseOH = UIntToOH(io.externalWriteback.bits.rd, 32)
  for (warp <- 0 until config.warps) {
    val afterRelease = Mux(
      releaseEnabled && io.externalWriteback.bits.warpId === warp.U,
      externalBusy(warp) & ~releaseOH,
      externalBusy(warp)
    )
    externalBusy(warp) := Mux(
      reserveEnabled && io.externalReserve.bits.warpId === warp.U,
      afterRelease | reserveOH,
      afterRelease
    )
  }

  io.memory <> atomic.io.unimplemented
  io.system <> dispatch.io.system
  io.trap <> dispatch.io.trap
  io.rawHazard := issue.io.rawHazard || externalRawHazard
  io.wawHazard := issue.io.wawHazard || externalWawHazard
}
