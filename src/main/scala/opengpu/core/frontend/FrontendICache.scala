package opengpu.core.frontend

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.execute.control.{SimtBranchRequest, SimtPath}
import opengpu.core.frontend.decode.{FpuDecodeResponse, ScalarDecodeResponse, VectorDecodeResponse}
import opengpu.core.frontend.warp.WarpLaunch
import opengpu.core.memory.{InstructionCache, InstructionLineRequest, InstructionLineResponse}

/** GpuFrontend fetch port wired to a FlashSim-sized instruction cache.
  *
  * Lower-level refill stays at the DUT boundary so the harness can model IMEM
  * without pulling in the interconnect.
  */
class FrontendICache(
  config: GpuConfig,
  sets: Int,
  ways: Int,
  lineBytes: Int,
  missEntries: Int
) extends Module {
  val io = IO(new Bundle {
    val launch = Flipped(Decoupled(new WarpLaunch(config)))
    val scalarOut = Decoupled(new ScalarDecodeResponse(config))
    val fpuOut = Decoupled(new FpuDecodeResponse(config))
    val vectorOut = Decoupled(new VectorDecodeResponse(config))
    val branch = Flipped(Decoupled(new SimtBranchRequest(config)))
    val scalarRedirect = Flipped(Decoupled(new SimtPath(config)))
    val restore = Flipped(Decoupled(UInt(config.warpIdWidth.W)))
    val finish = Flipped(Valid(UInt(config.warpIdWidth.W)))
    val active = Output(UInt(config.warps.W))
    val blocked = Output(UInt(config.warps.W))
    val lowerRequest =
      Decoupled(new InstructionLineRequest(config, lineBytes, missEntries))
    val lowerResponse = Flipped(
      Decoupled(new InstructionLineResponse(lineBytes, missEntries)))
    val invalidate = Input(Bool())
  })

  private val frontend = Module(new GpuFrontend(config))
  private val icache = Module(
    new InstructionCache(config, sets, ways, lineBytes, missEntries))

  frontend.io.launch <> io.launch
  icache.io.fetch <> frontend.io.fetchRequest
  frontend.io.fetchResponse <> icache.io.response
  io.scalarOut <> frontend.io.scalarOut
  io.fpuOut <> frontend.io.fpuOut
  io.vectorOut <> frontend.io.vectorOut
  frontend.io.branch <> io.branch
  frontend.io.scalarRedirect <> io.scalarRedirect
  frontend.io.restore <> io.restore
  frontend.io.finish <> io.finish
  io.active := frontend.io.active
  io.blocked := frontend.io.blocked
  io.lowerRequest <> icache.io.lowerRequest
  icache.io.lowerResponse <> io.lowerResponse
  icache.io.invalidate := io.invalidate
}
