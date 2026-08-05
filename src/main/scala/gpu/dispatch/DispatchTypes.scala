package gpu.dispatch

import chisel3._
import chisel3.util._
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

/** Host-visible tagged command. The tag is retained outside a compute unit so
  * independently completing CUs never rely on completion order.
  */
class TaggedKernelLaunch(config: GpuConfig, val commandIdWidth: Int) extends Bundle {
  require(commandIdWidth > 0)
  val commandId = UInt(commandIdWidth.W)
  val launch = new KernelLaunch(config)
}

class TaggedKernelCompletion(val commandIdWidth: Int) extends Bundle {
  require(commandIdWidth > 0)
  val commandId = UInt(commandIdWidth.W)
  val success = Bool()
}

/** Host-visible command descriptor. The launch payload remains shared with
  * the internal dispatcher so software queueing does not duplicate execution
  * semantics.
  */
class KernelCommand(config: GpuConfig, val commandIdWidth: Int) extends Bundle {
  require(commandIdWidth > 0)
  val commandId = UInt(commandIdWidth.W)
  val launch = new KernelLaunch(config)
}

object KernelCommandStatus {
  val width = 3
  val success = 0.U(width.W)
  val executionFailed = 1.U(width.W)
  val invalidProgramCounter = 2.U(width.W)
  val invalidGrid = 3.U(width.W)
  val invalidLocalSize = 4.U(width.W)
  val misalignedKernarg = 5.U(width.W)
}

class KernelCommandResult(val commandIdWidth: Int) extends Bundle {
  require(commandIdWidth > 0)
  val commandId = UInt(commandIdWidth.W)
  val status = UInt(KernelCommandStatus.width.W)
  val success = Bool()
}
