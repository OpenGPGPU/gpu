package gpu.dispatch

import chisel3._
import gpu.config.GpuConfig

/** One host-visible kernel launch. Grid dimensions count workgroups; local
  * dimensions count work-items within each workgroup.
  */
class KernelLaunch(config: GpuConfig) extends Bundle {
  val kernelPc = UInt(config.xLen.W)
  val kernargAddress = UInt(config.xLen.W)
  val gridSize = Vec(3, UInt(32.W))
  val localSize = Vec(3, UInt(16.W))
}

class WorkgroupTask(config: GpuConfig) extends Bundle {
  val kernelPc = UInt(config.xLen.W)
  val kernargAddress = UInt(config.xLen.W)
  val gridSize = Vec(3, UInt(32.W))
  val localSize = Vec(3, UInt(16.W))
  val groupId = Vec(3, UInt(32.W))
}

/** A contiguous group of lanes belonging to one logical warp. */
class WarpTask(config: GpuConfig) extends Bundle {
  val kernelPc = UInt(config.xLen.W)
  val kernargAddress = UInt(config.xLen.W)
  val gridSize = Vec(3, UInt(32.W))
  val localSize = Vec(3, UInt(16.W))
  val groupId = Vec(3, UInt(32.W))
  val localLinearBase = UInt(48.W)
  val activeMask = UInt(config.lanes.W)
  val firstWarp = Bool()
  val lastWarp = Bool()
}

class WorkgroupCompletion extends Bundle {
  val success = Bool()
}

class WarpCompletion extends Bundle {
  val success = Bool()
}

class KernelCompletion extends Bundle {
  val success = Bool()
}
