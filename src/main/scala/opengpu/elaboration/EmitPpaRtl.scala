package opengpu.elaboration

import circt.stage.ChiselStage
import opengpu.command.GpuCommandRouter
import opengpu.config.GpuConfig
import opengpu.core.backend.{FpuBackend, ScalarBackend, VectorBackend}
import opengpu.core.frontend.GpuFrontend
import opengpu.core.GpuComputeUnit
import opengpu.core.memory.{
  BankedSharedMemory,
  ComputeUnitMemoryInterconnect,
  InstructionCache,
  SharedL2Slice,
  VectorDataCache,
  VectorMemoryCoalescer
}
import opengpu.core.execute.fpu.Fp32FmaLane
import opengpu.graphics.{GraphicsConfig, TriangleRasterizer}
import opengpu.core.execute.fpu.Fp32ExactUnit
import opengpu.core.execute.fpu.Fp32DivLane
import opengpu.core.vector.{
  VectorFcvtAlu,
  VectorFdivAlu,
  VectorFEstimateAlu,
  VectorFmaAlu,
  VectorFpuAlu,
  VectorFsqrtAlu
}

/** Emits bounded, real project blocks for ChipAgent physical evaluation. */
object EmitPpaRtl {
  def main(args: Array[String]): Unit = {
    require(args.nonEmpty,
      "usage: EmitPpaRtl <block> [target-dir]")
    val targetDir = args.lift(1).getOrElse("generated/ppa")
    val stageArgs = Array("--target-dir", targetDir)
    val firtoolArgs = Array(
      "--lowering-options=disallowLocalVariables,disallowPackedArrays")
    args(0) match {
      case "shared-l2-slice" =>
        ChiselStage.emitSystemVerilogFile(
          new SharedL2Slice(
            GpuConfig(lanes = 4, l2Sets = 8, l2Ways = 2, l2Banks = 1),
            sets = 8, ways = 2, maxOutstanding = 8,
            numComputeUnits = 2, transactionsPerCu = 4,
            useSramBlackBoxes = true),
          stageArgs, firtoolArgs)
      case "command-router" =>
        ChiselStage.emitSystemVerilogFile(
          new GpuCommandRouter(
            GpuConfig(lanes = 4), commandIdWidth = 4,
            commandQueueDepth = 8, completionQueueDepth = 8),
          stageArgs, firtoolArgs)
      case "compute-unit" =>
        ChiselStage.emitSystemVerilogFile(
          new GpuComputeUnit(
            GpuConfig(),
            useBlackBoxes = true,
            enableFpuBackend = true),
          stageArgs, firtoolArgs)
      case "vector-backend" =>
        ChiselStage.emitSystemVerilogFile(
          new VectorBackend(GpuConfig(), useBlackBox = true),
          stageArgs, firtoolArgs)
      case "scalar-backend" =>
        ChiselStage.emitSystemVerilogFile(
          new ScalarBackend(GpuConfig(), useBlackBoxes = true),
          stageArgs, firtoolArgs)
      case "fpu-backend" =>
        ChiselStage.emitSystemVerilogFile(
          new FpuBackend(GpuConfig()),
          stageArgs, firtoolArgs)
      case "fma-lane" =>
        ChiselStage.emitSystemVerilogFile(
          new Fp32FmaLane(),
          stageArgs, firtoolArgs)
      case "exact-unit" =>
        ChiselStage.emitSystemVerilogFile(
          new Fp32ExactUnit(),
          stageArgs, firtoolArgs)
      case "raster-quad" =>
        // M4b: incremental-edge rasterizer (adds-only steady state; the M1
        // per-cycle 64-bit multiply array is gone).  Emitted standalone so the
        // physical flow can report area/timing against the M1 engine.
        ChiselStage.emitSystemVerilogFile(
          new TriangleRasterizer(
            GraphicsConfig(screenWidth = 128, screenHeight = 128, subPixelBits = 8)),
          stageArgs, firtoolArgs)
      case "div-lane" =>
        ChiselStage.emitSystemVerilogFile(
          new Fp32DivLane(),
          stageArgs, firtoolArgs)
      case "vector-fpu-alu" =>
        ChiselStage.emitSystemVerilogFile(
          new VectorFpuAlu(GpuConfig()),
          stageArgs, firtoolArgs)
      case "vector-fma-alu" =>
        ChiselStage.emitSystemVerilogFile(
          new VectorFmaAlu(GpuConfig()),
          stageArgs, firtoolArgs)
      case "vector-fcvt-alu" =>
        ChiselStage.emitSystemVerilogFile(
          new VectorFcvtAlu(GpuConfig()),
          stageArgs, firtoolArgs)
      case "vector-festimate-alu" =>
        ChiselStage.emitSystemVerilogFile(
          new VectorFEstimateAlu(GpuConfig()),
          stageArgs, firtoolArgs)
      case "vector-fsqrt-alu" =>
        ChiselStage.emitSystemVerilogFile(
          new VectorFsqrtAlu(GpuConfig()),
          stageArgs, firtoolArgs)
      case "vector-fdiv-alu" =>
        ChiselStage.emitSystemVerilogFile(
          new VectorFdivAlu(GpuConfig()),
          stageArgs, firtoolArgs)
      case "frontend" =>
        ChiselStage.emitSystemVerilogFile(
          new GpuFrontend(GpuConfig()),
          stageArgs, firtoolArgs)
      case "shared-memory" =>
        ChiselStage.emitSystemVerilogFile(
          new BankedSharedMemory(GpuConfig()),
          stageArgs, firtoolArgs)
      case "vector-memory-coalescer" =>
        ChiselStage.emitSystemVerilogFile(
          new VectorMemoryCoalescer(GpuConfig()),
          stageArgs, firtoolArgs)
      case "instruction-cache" =>
        ChiselStage.emitSystemVerilogFile(
          new InstructionCache(GpuConfig()),
          stageArgs, firtoolArgs)
      case "vector-data-cache" =>
        ChiselStage.emitSystemVerilogFile(
          new VectorDataCache(GpuConfig()),
          stageArgs, firtoolArgs)
      case "memory-interconnect" =>
        ChiselStage.emitSystemVerilogFile(
          new ComputeUnitMemoryInterconnect(GpuConfig()),
          stageArgs, firtoolArgs)
      case other =>
        throw new IllegalArgumentException(s"unknown PPA block: $other")
    }
  }
}
