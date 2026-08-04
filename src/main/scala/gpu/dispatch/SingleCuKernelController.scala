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
  })

  private val dispatch = Module(new KernelDispatchPipeline(config))
  private val initialize = Module(new WarpContextInitializer(config))
  private val running = RegInit(false.B)
  private val runningWarpId = Reg(UInt(config.warpIdWidth.W))

  dispatch.io.launch <> io.kernel
  io.completion <> dispatch.io.completion
  initialize.io.task <> dispatch.io.warp
  initialize.io.activeWarps := io.activeWarps
  io.scalarInitialize <> initialize.io.scalar
  io.vectorInitialize <> initialize.io.vector
  io.launch <> initialize.io.launch

  when(initialize.io.launch.fire) {
    running := true.B
    runningWarpId := initialize.io.assignedWarpId.bits
  }

  private val matchingFinish =
    io.finish.valid && running && io.finish.bits === runningWarpId
  dispatch.io.warpCompletion.valid := matchingFinish
  dispatch.io.warpCompletion.bits.success := true.B
  when(dispatch.io.warpCompletion.fire) {
    running := false.B
  }

  when(io.finish.valid && running) {
    assert(io.finish.bits === runningWarpId,
      "completion must identify the single running dispatched warp")
  }
}
