package opengpu.graphics

import chisel3._
import chisel3.util._

/** Per-job engine configuration carried by one render submission.
  *
  * The legacy register file and the retired job-ring descriptor snapshot both
  * populate this bundle; the unified render command packs the same fields out
  * of the 16-word render descriptor.  It mirrors the register fields the engine
  * consumes at launch, so a submission renders exactly what the equivalent
  * register-programmed submission would have rendered.
  */
class JobConfig extends Bundle with HasResolvedDrawState {
  val cmdBase = UInt(32.W)
  val cmdCount = UInt(16.W)
  val colorBase = UInt(32.W)
  val depthBase = UInt(32.W)
  val stride = UInt(32.W)
  val sampleMode = UInt(2.W)
  val texBase = UInt(32.W)
  val texWidth = UInt(14.W)
  val texHeight = UInt(14.W)
}

/** Status codes shared with GPU_IH_STATUS_* in driver/gpu_abi.h.  The IH ring
  * was retired with the job ring; the encodings remain part of the ABI. */
object JobQueueStatus {
  val Completed = 0
  val InvalidSampleMode = 1
  val TextureMemoryFault = 2
}
