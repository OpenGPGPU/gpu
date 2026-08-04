package gpu.core.vector

import chisel3._
import gpu.config.GpuConfig

class VectorIntegerRequest(config: GpuConfig) extends Bundle {
  val warpId = UInt(config.warpIdWidth.W)
  val pc = UInt(config.xLen.W)
  val warpActiveMask = UInt(config.lanes.W)
  val vd = UInt(5.W)
  val activeMask = UInt(config.lanes.W)
  val predicateMask = UInt(config.lanes.W)
  val oldVd = Vec(config.lanes, UInt(config.xLen.W))
  val vs1 = Vec(config.lanes, UInt(config.xLen.W))
  val vs2 = Vec(config.lanes, UInt(config.xLen.W))
  val scalar = UInt(config.xLen.W)
  val immediate = UInt(5.W)
  val funct6 = UInt(6.W)
  val operandType = UInt(3.W)
  val vm = Bool()
}

class VectorIntegerResult(config: GpuConfig) extends Bundle {
  val warpId = UInt(config.warpIdWidth.W)
  val pc = UInt(config.xLen.W)
  val warpActiveMask = UInt(config.lanes.W)
  val vd = UInt(5.W)
  val data = Vec(config.lanes, UInt(config.xLen.W))
  val mask = UInt(config.lanes.W)
  val writesMask = Bool()
  val saturated = Bool()
}

class VectorMultiplyRequest(config: GpuConfig) extends Bundle {
  val warpId = UInt(config.warpIdWidth.W)
  val pc = UInt(config.xLen.W)
  val warpActiveMask = UInt(config.lanes.W)
  val vd = UInt(5.W)
  val activeMask = UInt(config.lanes.W)
  val predicateMask = UInt(config.lanes.W)
  val oldVd = Vec(config.lanes, UInt(config.xLen.W))
  val vs1 = Vec(config.lanes, UInt(config.xLen.W))
  val vs2 = Vec(config.lanes, UInt(config.xLen.W))
  val scalar = UInt(config.xLen.W)
  val funct6 = UInt(6.W)
  val operandType = UInt(3.W)
  val vm = Bool()
  val vxrm = UInt(2.W)
}

class VectorMultiplyResult(config: GpuConfig) extends Bundle {
  val warpId = UInt(config.warpIdWidth.W)
  val pc = UInt(config.xLen.W)
  val warpActiveMask = UInt(config.lanes.W)
  val vd = UInt(5.W)
  val data = Vec(config.lanes, UInt(config.xLen.W))
  val saturated = Bool()
}
