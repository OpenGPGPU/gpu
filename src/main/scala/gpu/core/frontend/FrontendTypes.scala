package gpu.core.frontend

import chisel3._
import gpu.config.GpuConfig

class InstructionFetchRequest(config: GpuConfig) extends Bundle {
  val warpId = UInt(config.warpIdWidth.W)
  val pc = UInt(config.xLen.W)
}

/** In-order response for the single-outstanding frontend fetch port. */
class InstructionFetchResponse(config: GpuConfig) extends Bundle {
  val warpId = UInt(config.warpIdWidth.W)
  val instruction = UInt(32.W)
  val accessFault = Bool()
}
