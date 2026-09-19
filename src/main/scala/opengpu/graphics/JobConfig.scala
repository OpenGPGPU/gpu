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
class JobConfig extends Bundle {
  val cmdBase = UInt(32.W)
  val cmdCount = UInt(16.W)
  val colorBase = UInt(32.W)
  val depthBase = UInt(32.W)
  val stride = UInt(32.W)
  val depthTestEnable = Bool()
  val depthFunc = UInt(3.W)
  val depthWriteEnable = Bool()
  val cullMode = UInt(2.W)
  val sampleMode = UInt(2.W)
  val blendCfgEnable = Bool()
  val blendSrcFactor = UInt(4.W)
  val blendDstFactor = UInt(4.W)
  val blendEquation = UInt(3.W)
  val stencilTestEnable = Bool()
  val stencilFunc = UInt(3.W)
  val stencilRef = UInt(8.W)
  val stencilReadMask = UInt(8.W)
  val stencilWriteMask = UInt(8.W)
  val stencilFailOp = UInt(3.W)
  val stencilZFailOp = UInt(3.W)
  val stencilZPassOp = UInt(3.W)
  val texEnable = Bool()
  val texBase = UInt(32.W)
  val texWidth = UInt(14.W)
  val texHeight = UInt(14.W)
  val texWrapClamp = Bool()
  val texMaxLevel = UInt(4.W)
}

/** Status codes shared with GPU_IH_STATUS_* in driver/gpu_abi.h.  The IH ring
  * was retired with the job ring; the encodings remain part of the ABI. */
object JobQueueStatus {
  val Completed = 0
  val InvalidSampleMode = 1
  val TextureMemoryFault = 2
}
