package gpu.core.frontend.decode

import chisel3._
import chisel3.util._
import gpu.config.GpuConfig

object ExecutionType extends ChiselEnum {
  val illegal, integer, branch, memory, fpu, vector, system = Value
}

object FpuUnit extends ChiselEnum {
  val none, fast, fma, divide, squareRoot, convert = Value
}

object VectorUnit extends ChiselEnum {
  val none, alu, multiply, divide, floatingPoint, loadStore, configuration, mask = Value
}

/** RV32F controls carried from decode to the floating-point issue path. */
class FpuDecodeSignals extends Bundle {
  val recognized = Bool()
  val valid = Bool()
  val unit = FpuUnit()
  // Kept in the interface for direct mapping to FPnew's format input.  The
  // FP32-only profile requires this to be zero for every legal instruction.
  val format = UInt(2.W)
  val funct5 = UInt(5.W)
  val rm = UInt(3.W)
  val readsRs1 = Bool()
  val readsRs2 = Bool()
  val readsRs3 = Bool()
  val writesFp = Bool()
  val writesInteger = Bool()
  val memoryRead = Bool()
  val memoryWrite = Bool()
  val setsFlags = Bool()
}

/** RVV controls carried from decode to vector issue. */
class VectorDecodeSignals extends Bundle {
  val recognized = Bool()
  val valid = Bool()
  val unit = VectorUnit()
  val funct6 = UInt(6.W)
  val operandType = UInt(3.W)
  val vm = Bool()
  val nf = UInt(3.W)
  val mop = UInt(2.W)
  val elementWidth = UInt(3.W)
  val readsVs1 = Bool()
  val readsVs2 = Bool()
  val readsScalar = Bool()
  val readsFloat = Bool()
  val writesVd = Bool()
  val memoryRead = Bool()
  val memoryWrite = Bool()
  val configure = Bool()
}

class FullDecodeSignals extends Bundle {
  val legal = Bool()
  val illegalInstruction = Bool()
  val executionType = ExecutionType()
  val scalar = new DecodeSignals
  val fpu = new FpuDecodeSignals
  val vector = new VectorDecodeSignals
}

class DecodeRequest(config: GpuConfig) extends Bundle {
  val instruction = UInt(32.W)
  val pc = UInt(config.xLen.W)
  val warpId = UInt(config.warpIdWidth.W)
  val activeMask = UInt(config.lanes.W)
  val instructionAccessFault = Bool()
}

class DecodeResponse(config: GpuConfig) extends Bundle {
  val instruction = UInt(32.W)
  val pc = UInt(config.xLen.W)
  val warpId = UInt(config.warpIdWidth.W)
  val activeMask = UInt(config.lanes.W)
  val instructionAccessFault = Bool()
  val decoded = new FullDecodeSignals
}

class ScalarDecodeResponse(config: GpuConfig) extends Bundle {
  val instruction = UInt(32.W)
  val pc = UInt(config.xLen.W)
  val warpId = UInt(config.warpIdWidth.W)
  val activeMask = UInt(config.lanes.W)
  val instructionAccessFault = Bool()
  val executionType = ExecutionType()
  val illegalInstruction = Bool()
  val decoded = new DecodeSignals
}

class FpuDecodeResponse(config: GpuConfig) extends Bundle {
  val instruction = UInt(32.W)
  val pc = UInt(config.xLen.W)
  val warpId = UInt(config.warpIdWidth.W)
  val activeMask = UInt(config.lanes.W)
  val decoded = new FpuDecodeSignals
}

class VectorDecodeResponse(config: GpuConfig) extends Bundle {
  val instruction = UInt(32.W)
  val pc = UInt(config.xLen.W)
  val warpId = UInt(config.warpIdWidth.W)
  val activeMask = UInt(config.lanes.W)
  val decoded = new VectorDecodeSignals
}

class DecodePipeIO(config: GpuConfig) extends Bundle {
  val in = Flipped(Decoupled(new DecodeRequest(config)))
  val scalarOut = Decoupled(new ScalarDecodeResponse(config))
  val fpuOut = Decoupled(new FpuDecodeResponse(config))
  val vectorOut = Decoupled(new VectorDecodeResponse(config))
}
