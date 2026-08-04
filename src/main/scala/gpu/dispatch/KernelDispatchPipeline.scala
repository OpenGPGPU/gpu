package gpu.dispatch

import chisel3._
import chisel3.util._
import gpu.config.GpuConfig

/** Single-queue, single-CU dispatch hierarchy.
  *
  * The hierarchy intentionally allows one logical warp in flight. Besides
  * being sufficient for the first end-to-end kernel path, this means a warp
  * completion can never be attributed to the wrong workgroup. Concurrency is
  * added later with explicit tags rather than completion-order assumptions.
  */
class KernelDispatchPipeline(config: GpuConfig = GpuConfig()) extends Module {
  val io = IO(new Bundle {
    val launch = Flipped(Decoupled(new KernelLaunch(config)))
    val warp = Decoupled(new WarpTask(config))
    val warpCompletion = Flipped(Decoupled(new WarpCompletion))
    val completion = Decoupled(new KernelCompletion)
  })

  private val jobs = Module(new JobDispatcher(config))
  private val workgroups = Module(new WorkgroupDispatcher(config))

  jobs.io.launch <> io.launch
  workgroups.io.workgroup <> jobs.io.workgroup
  jobs.io.workgroupCompletion <> workgroups.io.completion
  io.warp <> workgroups.io.warp
  workgroups.io.warpCompletion <> io.warpCompletion
  io.completion <> jobs.io.completion
}
