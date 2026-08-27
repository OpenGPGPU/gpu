package opengpu.core.frontend

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.execute.control.{SimtBranchRequest, SimtPath}
import opengpu.core.frontend.decode._
import opengpu.core.frontend.warp.WarpLaunch

/** Warp scheduling, in-order instruction fetch, and unified decode.
  *
  * The fetch port permits one outstanding request. It can retire the current
  * response and launch the next warp request in the same cycle, so a
  * one-cycle-latency instruction memory can sustain one instruction per cycle.
  * Request metadata is retained until the response enters `DecodePipe`.
  */
class GpuFrontend(config: GpuConfig = GpuConfig()) extends Module {
  val io = IO(new Bundle {
    val launch = Flipped(Decoupled(new WarpLaunch(config)))
    val fetchRequest = Decoupled(new InstructionFetchRequest(config))
    val fetchResponse = Flipped(Decoupled(new InstructionFetchResponse(config)))

    val scalarOut = Decoupled(new ScalarDecodeResponse(config))
    val fpuOut = Decoupled(new FpuDecodeResponse(config))
    val vectorOut = Decoupled(new VectorDecodeResponse(config))

    val branch = Flipped(Decoupled(new SimtBranchRequest(config)))
    val scalarRedirect = Flipped(Decoupled(new SimtPath(config)))
    val restore = Flipped(Decoupled(UInt(config.warpIdWidth.W)))
    val finish = Flipped(Valid(UInt(config.warpIdWidth.W)))
    val active = Output(UInt(config.warps.W))
    val blocked = Output(UInt(config.warps.W))
  })

  private val control = Module(new SimtFrontendControl(config))
  private val decode = Module(new DecodePipe(config))

  control.io.launch <> io.launch
  control.io.branch <> io.branch
  control.io.scalarRedirect <> io.scalarRedirect
  control.io.restore <> io.restore
  control.io.finish <> io.finish
  io.active := control.io.active
  io.blocked := control.io.blocked

  private val fetchPending =
    RegInit(VecInit(Seq.fill(config.warps)(false.B)))
  private val fetchMetadata = Reg(Vec(config.warps, new Bundle {
    val pc = UInt(config.xLen.W)
    val activeMask = UInt(config.lanes.W)
  }))
  private val responseWarpValid = io.fetchResponse.bits.warpId < config.warps.U
  private val selectedResponsePending = if (config.warps == 1) {
    fetchPending(0)
  } else {
    Mux(responseWarpValid, fetchPending(io.fetchResponse.bits.warpId), false.B)
  }
  private val selectedMetadata = if (config.warps == 1) {
    fetchMetadata(0)
  } else {
    fetchMetadata(io.fetchResponse.bits.warpId)
  }

  decode.io.in.valid :=
    selectedResponsePending && responseWarpValid && io.fetchResponse.valid
  decode.io.in.bits.instruction := io.fetchResponse.bits.instruction
  decode.io.in.bits.pc := selectedMetadata.pc
  decode.io.in.bits.warpId := io.fetchResponse.bits.warpId
  decode.io.in.bits.activeMask := selectedMetadata.activeMask
  decode.io.in.bits.instructionAccessFault :=
    io.fetchResponse.bits.accessFault
  io.fetchResponse.ready :=
    selectedResponsePending && responseWarpValid && decode.io.in.ready

  private val responseFire = io.fetchResponse.fire
  private val issueWarpPending = if (config.warps == 1) {
    fetchPending(0)
  } else {
    fetchPending(control.io.issue.bits.warpId)
  }
  private val canLaunchFetch = !issueWarpPending
  io.fetchRequest.valid := control.io.issue.valid && canLaunchFetch
  io.fetchRequest.bits.warpId := control.io.issue.bits.warpId
  io.fetchRequest.bits.pc := control.io.issue.bits.pc
  control.io.issue.ready := io.fetchRequest.ready && canLaunchFetch

  private val requestFire = io.fetchRequest.fire
  when(requestFire) {
    if (config.warps == 1) {
      fetchPending(0) := true.B
      fetchMetadata(0).pc := control.io.issue.bits.pc
      fetchMetadata(0).activeMask := control.io.issue.bits.activeMask
    } else {
      fetchPending(control.io.issue.bits.warpId) := true.B
      fetchMetadata(control.io.issue.bits.warpId).pc := control.io.issue.bits.pc
      fetchMetadata(control.io.issue.bits.warpId).activeMask :=
        control.io.issue.bits.activeMask
    }
  }
  when(responseFire) {
    if (config.warps == 1) fetchPending(0) := false.B
    else fetchPending(io.fetchResponse.bits.warpId) := false.B
  }

  io.scalarOut <> decode.io.scalarOut
  io.fpuOut <> decode.io.fpuOut
  io.vectorOut <> decode.io.vectorOut
}
