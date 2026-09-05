package opengpu.core.frontend

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.backend.{FpuBackend, ScalarBackend}
import opengpu.core.backend.register.{
  FpuRegisterRead,
  FpuRegisterWrite,
  ScalarRegisterFile,
  ScalarRegisterWrite
}
import opengpu.core.execute.control.SimtPath
import opengpu.core.frontend.warp.WarpLaunch
import opengpu.core.memory.{InstructionLineRequest, InstructionLineResponse}

/** FrontendICache closed through scalar integer and FP32 execute.
  *
  * Fetch, decode, RV32I, FP32 FMA/exact, and warp redirect stay on-chip.
  * IMEM refill stays at the DUT boundary. Vector decode and data-memory
  * ports are tied off.
  */
class FrontendScalarFpu(
  config: GpuConfig,
  sets: Int,
  ways: Int,
  lineBytes: Int,
  missEntries: Int
) extends Module {
  val io = IO(new Bundle {
    val launch = Flipped(Decoupled(new WarpLaunch(config)))
    val initialize = Flipped(Decoupled(new ScalarRegisterWrite(config)))
    val fpuInitialize = Flipped(Decoupled(new FpuRegisterWrite(config)))
    val finish = Flipped(Valid(UInt(config.warpIdWidth.W)))
    val committedWriteback = Valid(new ScalarRegisterWrite(config))
    val committedFpuWriteback = Valid(new FpuRegisterWrite(config))
    val active = Output(UInt(config.warps.W))
    val blocked = Output(UInt(config.warps.W))
    val rawHazard = Output(Bool())
    val wawHazard = Output(Bool())
    val lowerRequest =
      Decoupled(new InstructionLineRequest(config, lineBytes, missEntries))
    val lowerResponse = Flipped(
      Decoupled(new InstructionLineResponse(lineBytes, missEntries)))
    val invalidate = Input(Bool())
  })

  private val fetch = Module(
    new FrontendICache(config, sets, ways, lineBytes, missEntries))
  private val scalar = Module(new ScalarBackend(config, useBlackBoxes = false))
  private val fpu = Module(new FpuBackend(config))
  private val scalarFpuRead = Module(new ScalarRegisterFile(config))
  private val completion = Module(new RRArbiter(new SimtPath(config), 2))

  fetch.io.launch <> io.launch
  fetch.io.finish <> io.finish
  io.active := fetch.io.active
  io.blocked := fetch.io.blocked
  io.lowerRequest <> fetch.io.lowerRequest
  fetch.io.lowerResponse <> io.lowerResponse
  fetch.io.invalidate := io.invalidate

  scalar.io.in <> fetch.io.scalarOut
  fpu.io.in <> fetch.io.fpuOut
  fetch.io.vectorOut.ready := true.B
  fetch.io.branch.valid := false.B
  fetch.io.branch.bits := 0.U.asTypeOf(fetch.io.branch.bits)
  fetch.io.restore.valid := false.B
  fetch.io.restore.bits := 0.U

  completion.io.in(0) <> scalar.io.redirect
  completion.io.in(1) <> fpu.io.redirect
  fetch.io.scalarRedirect <> completion.io.out

  scalar.io.initialize <> io.initialize
  fpu.io.initialize <> io.fpuInitialize
  io.committedWriteback := scalar.io.committedWriteback
  io.committedFpuWriteback := fpu.io.committedWriteback
  io.rawHazard := scalar.io.rawHazard || fpu.io.rawHazard
  io.wawHazard := scalar.io.wawHazard || fpu.io.wawHazard

  scalar.io.externalReserve <> fpu.io.scalarReserve
  scalar.io.externalWriteback <> fpu.io.scalarWriteback
  scalarFpuRead.io.read := fpu.io.scalarRead
  fpu.io.scalarRs1Data := scalarFpuRead.io.rs1Data
  scalarFpuRead.io.write.valid := scalar.io.appliedWriteback.valid
  scalarFpuRead.io.write.bits := scalar.io.appliedWriteback.bits

  fpu.io.flush := false.B
  fpu.io.frm := 0.U.asTypeOf(fpu.io.frm)
  fpu.io.fvfRead := 0.U.asTypeOf(new FpuRegisterRead(config))
  fpu.io.unimplemented.ready := true.B
  fpu.io.memoryRequest.ready := true.B
  fpu.io.memoryResponse.valid := false.B
  fpu.io.memoryResponse.bits := 0.U.asTypeOf(fpu.io.memoryResponse.bits)
  fpu.io.memoryFault.ready := true.B

  scalar.io.cacheRequest.ready := true.B
  scalar.io.cacheResponse.valid := false.B
  scalar.io.cacheResponse.bits :=
    0.U.asTypeOf(scalar.io.cacheResponse.bits)
  scalar.io.memoryFault.ready := true.B
  scalar.io.sharedAtomicRequest.ready := true.B
  scalar.io.sharedAtomicResponse.valid := false.B
  scalar.io.sharedAtomicResponse.bits :=
    0.U.asTypeOf(scalar.io.sharedAtomicResponse.bits)
  scalar.io.globalAtomicRequest.ready := true.B
  scalar.io.globalAtomicResponse.valid := false.B
  scalar.io.globalAtomicResponse.bits :=
    0.U.asTypeOf(scalar.io.globalAtomicResponse.bits)
  scalar.io.memory.ready := true.B
  scalar.io.system.ready := true.B
  scalar.io.texCommit.valid := false.B
  scalar.io.texCommit.bits := 0.U.asTypeOf(scalar.io.texCommit.bits)
  scalar.io.trap.ready := true.B
}
