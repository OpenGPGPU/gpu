package opengpu.core.execute.control

import chisel3._
import opengpu.config.GpuConfig
import opengpu.core.frontend.simt.SimtStackEntry

object ControlFlowKind {
  val width = 2
  val conditional = 0.U(width.W)
  val jal = 1.U(width.W)
  val jalr = 2.U(width.W)
}

class ScalarBranchRequest(config: GpuConfig) extends Bundle {
  val warpId = UInt(config.warpIdWidth.W)
  val pc = UInt(config.xLen.W)
  val activeMask = UInt(config.lanes.W)
  val rd = UInt(5.W)
  val writeLink = Bool()
  val immediate = UInt(config.xLen.W)
  val lhs = UInt(config.xLen.W)
  val rhs = UInt(config.xLen.W)
  val branchOp = UInt(3.W)
  val kind = UInt(ControlFlowKind.width.W)
}

class ScalarBranchResult(config: GpuConfig) extends Bundle {
  val warpId = UInt(config.warpIdWidth.W)
  val activeMask = UInt(config.lanes.W)
  val rd = UInt(5.W)
  val writeLink = Bool()
  val targetPc = UInt(config.xLen.W)
  val linkPc = UInt(config.xLen.W)
  val taken = Bool()
}

class SimtBranchRequest(config: GpuConfig) extends Bundle {
  val warpId = UInt(config.warpIdWidth.W)
  val pc = UInt(config.xLen.W)
  val targetPc = UInt(config.xLen.W)
  val fallthroughPc = UInt(config.xLen.W)
  val reconvergePc = UInt(config.xLen.W)
  val activeMask = UInt(config.lanes.W)
  val takenMask = UInt(config.lanes.W)
}

class SimtBranchResult(config: GpuConfig) extends Bundle {
  val warpId = UInt(config.warpIdWidth.W)
  val currentPc = UInt(config.xLen.W)
  val currentMask = UInt(config.lanes.W)
  val divergent = Bool()
  val hasAlternate = Bool()
  val alternate = new SimtStackEntry(config)
  val hasReconvergence = Bool()
  val reconvergence = new SimtStackEntry(config)
}

class SimtPath(config: GpuConfig) extends Bundle {
  val warpId = UInt(config.warpIdWidth.W)
  val pc = UInt(config.xLen.W)
  val activeMask = UInt(config.lanes.W)
}
