package gpu.core.frontend.warp

import chisel3._
import gpu.config.GpuConfig

/** Command that allocates a hardware warp slot.
  *
  * The dispatcher (WarpContextInitializer) snapshots the target slot before
  * initializing its registers and names it explicitly here. The scheduler must
  * launch exactly this slot: a concurrent `finish` can free a lower slot while
  * initialization is in flight, so re-deriving the lowest free slot at
  * `launch.fire` would mismatch the initialized context.
  */
class WarpLaunch(config: GpuConfig) extends Bundle {
  val warpId = UInt(config.warpIdWidth.W)
  val startPc = UInt(config.xLen.W)
  val activeMask = UInt(config.lanes.W)
}

/** A runnable warp selected for the next frontend transaction. */
class WarpIssue(config: GpuConfig) extends Bundle {
  val warpId = UInt(config.warpIdWidth.W)
  val pc = UInt(config.xLen.W)
  val activeMask = UInt(config.lanes.W)
}

/** Unblocks a warp after decode/execute and supplies its next PC. */
class WarpResume(config: GpuConfig) extends Bundle {
  val warpId = UInt(config.warpIdWidth.W)
  val nextPc = UInt(config.xLen.W)
  val activeMask = UInt(config.lanes.W)
}
