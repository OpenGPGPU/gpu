package opengpu.graphics

import chisel3._
import chisel3.util._

/** Configuration decoded from one immutable unified render descriptor. */
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

/** Historical status constants retained for older internal test references. */
object JobQueueStatus {
  val Completed = 0
  val InvalidSampleMode = 1
  val TextureMemoryFault = 2
}
