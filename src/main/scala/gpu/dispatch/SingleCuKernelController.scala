package gpu.dispatch

import chisel3._
import chisel3.util._
import gpu.config.GpuConfig
import gpu.core.backend.register.{ScalarRegisterWrite, VectorRegisterWrite}
import gpu.core.frontend.warp.WarpLaunch

/** Host-launch to initialized-warp control path for one compute unit. */
class SingleCuKernelController(config: GpuConfig = GpuConfig()) extends Module {
  val io = IO(new Bundle {
    val kernel = Flipped(Decoupled(new KernelLaunch(config)))
    val completion = Decoupled(new KernelCompletion)
    val activeWarps = Input(UInt(config.warps.W))
    val scalarInitialize = Decoupled(new ScalarRegisterWrite(config))
    val vectorInitialize = Decoupled(new VectorRegisterWrite(config))
    val launch = Decoupled(new WarpLaunch(config))
    val finish = Flipped(Valid(UInt(config.warpIdWidth.W)))
    val residentWarps = Output(UInt(config.warps.W))
    val workgroupDispatchComplete = Output(Bool())
  })

  private val dispatch = Module(new KernelDispatchPipeline(config))
  private val initialize = Module(new WarpContextInitializer(config))
  private val running = RegInit(0.U(config.warps.W))
  private val dispatchComplete = RegInit(false.B)

  dispatch.io.launch <> io.kernel
  io.completion <> dispatch.io.completion
  initialize.io.task <> dispatch.io.warp
  initialize.io.activeWarps := io.activeWarps
  io.scalarInitialize <> initialize.io.scalar
  io.vectorInitialize <> initialize.io.vector
  io.launch <> initialize.io.launch

  private val finishInRange = io.finish.bits < config.warps.U
  private val finishResident = if (config.warps == 1) running(0)
    else running(io.finish.bits)
  private val matchingFinish = io.finish.valid && finishInRange &&
    finishResident
  dispatch.io.warpCompletion.valid := matchingFinish
  dispatch.io.warpCompletion.bits.success := true.B
  private val launchOH = Mux(
    initialize.io.launch.fire,
    UIntToOH(initialize.io.assignedWarpId.bits, config.warps),
    0.U(config.warps.W)
  )
  private val finishOH = Mux(
    dispatch.io.warpCompletion.fire,
    UIntToOH(io.finish.bits, config.warps),
    0.U(config.warps.W)
  )
  when(launchOH.orR || finishOH.orR) {
    running := (running | launchOH) & ~finishOH
  }
  when(initialize.io.launchedTask.valid && initialize.io.launchedTask.bits.lastWarp) {
    dispatchComplete := true.B
  }
  when(io.completion.fire) {
    dispatchComplete := false.B
  }

  when(io.finish.valid) {
    assert(matchingFinish,
      "completion must identify a resident dispatched warp")
    assert(dispatch.io.warpCompletion.ready,
      "resident warp completion must always be accepted")
  }
  io.residentWarps := running
  io.workgroupDispatchComplete := dispatchComplete
}
