package opengpu.core.frontend

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.backend.ScalarBackend
import opengpu.core.backend.register.ScalarRegisterWrite
import opengpu.core.frontend.warp.WarpLaunch
import opengpu.core.memory.{InstructionLineRequest, InstructionLineResponse}

/** FrontendICache closed through the scalar execute pipe.
  *
  * Fetch, decode, RV32I execute, and warp redirect stay on-chip. IMEM refill
  * stays at the DUT boundary. FPU/vector decode and scalar memory/atomics are
  * tied off so the slice stays FlashSim-sized.
  */
class FrontendScalar(
  config: GpuConfig,
  sets: Int,
  ways: Int,
  lineBytes: Int,
  missEntries: Int
) extends Module {
  val io = IO(new Bundle {
    val launch = Flipped(Decoupled(new WarpLaunch(config)))
    val initialize = Flipped(Decoupled(new ScalarRegisterWrite(config)))
    val finish = Flipped(Valid(UInt(config.warpIdWidth.W)))
    val committedWriteback = Valid(new ScalarRegisterWrite(config))
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

  fetch.io.launch <> io.launch
  fetch.io.finish <> io.finish
  io.active := fetch.io.active
  io.blocked := fetch.io.blocked
  io.lowerRequest <> fetch.io.lowerRequest
  fetch.io.lowerResponse <> io.lowerResponse
  fetch.io.invalidate := io.invalidate

  scalar.io.in <> fetch.io.scalarOut
  fetch.io.scalarRedirect <> scalar.io.redirect
  scalar.io.initialize <> io.initialize
  io.committedWriteback := scalar.io.committedWriteback
  io.rawHazard := scalar.io.rawHazard
  io.wawHazard := scalar.io.wawHazard

  fetch.io.fpuOut.ready := true.B
  fetch.io.vectorOut.ready := true.B
  fetch.io.branch.valid := false.B
  fetch.io.branch.bits := 0.U.asTypeOf(fetch.io.branch.bits)
  fetch.io.restore.valid := false.B
  fetch.io.restore.bits := 0.U

  scalar.io.externalWriteback.valid := false.B
  scalar.io.externalWriteback.bits :=
    0.U.asTypeOf(scalar.io.externalWriteback.bits)
  scalar.io.externalReserve.valid := false.B
  scalar.io.externalReserve.bits :=
    0.U.asTypeOf(scalar.io.externalReserve.bits)
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
