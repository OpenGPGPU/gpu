package opengpu.core.frontend

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.execute.control.{SimtBranchRequest, SimtPath}
import opengpu.core.frontend.simt.SimtControlFlow
import opengpu.core.frontend.warp._

/** Connects warp scheduling to SIMT branch and restore state.
  *
  * Branch completion and stack restore both update the scheduler's PC and
  * active mask. A branch completion has priority if it coincides with an
  * explicit restore request; the restore remains backpressured for that cycle.
  * Finishing a warp also clears its private divergence stack before reuse.
  */
class SimtFrontendControl(config: GpuConfig = GpuConfig()) extends Module {
  private val countWidth = math.max(1, log2Ceil(config.simtStackDepth + 1))

  val io = IO(new Bundle {
    val launch = Flipped(Decoupled(new WarpLaunch(config)))
    val issue = Decoupled(new WarpIssue(config))
    val branch = Flipped(Decoupled(new SimtBranchRequest(config)))
    val scalarRedirect = Flipped(Decoupled(new SimtPath(config)))
    val restore = Flipped(Decoupled(UInt(config.warpIdWidth.W)))
    val finish = Flipped(Valid(UInt(config.warpIdWidth.W)))
    val active = Output(UInt(config.warps.W))
    val blocked = Output(UInt(config.warps.W))
    val stackCounts = Output(Vec(config.warps, UInt(countWidth.W)))
  })

  private val scheduler = Module(new WarpScheduler(config))
  private val controlFlow = Module(new SimtControlFlow(config))

  scheduler.io.launch <> io.launch
  io.issue <> scheduler.io.issue
  controlFlow.io.branch <> io.branch

  controlFlow.io.restoreWarpId := io.restore.bits
  private val restorePathValid =
    io.restore.valid && controlFlow.io.restore.valid &&
      !controlFlow.io.current.valid
  io.restore.ready :=
    controlFlow.io.restore.valid && !controlFlow.io.current.valid
  controlFlow.io.restore.ready := restorePathValid

  controlFlow.io.current.ready := true.B
  io.scalarRedirect.ready :=
    !controlFlow.io.current.valid && !restorePathValid
  scheduler.io.resume.valid :=
    controlFlow.io.current.valid || restorePathValid ||
      io.scalarRedirect.valid
  scheduler.io.resume.bits.warpId :=
    Mux(
      controlFlow.io.current.valid,
      controlFlow.io.current.bits.warpId,
      Mux(
        restorePathValid,
        controlFlow.io.restore.bits.warpId,
        io.scalarRedirect.bits.warpId
      )
    )
  scheduler.io.resume.bits.nextPc :=
    Mux(
      controlFlow.io.current.valid,
      controlFlow.io.current.bits.pc,
      Mux(
        restorePathValid,
        controlFlow.io.restore.bits.pc,
        io.scalarRedirect.bits.pc
      )
    )
  scheduler.io.resume.bits.activeMask :=
    Mux(
      controlFlow.io.current.valid,
      controlFlow.io.current.bits.activeMask,
      Mux(
        restorePathValid,
        controlFlow.io.restore.bits.activeMask,
        io.scalarRedirect.bits.activeMask
      )
    )

  scheduler.io.finish <> io.finish
  controlFlow.io.clear <> io.finish

  io.active := scheduler.io.active
  io.blocked := scheduler.io.blocked
  io.stackCounts := controlFlow.io.stackCounts
}
