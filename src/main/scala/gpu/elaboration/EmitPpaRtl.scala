package gpu.elaboration

import circt.stage.ChiselStage
import gpu.command.GpuCommandRouter
import gpu.config.GpuConfig
import gpu.core.memory.SharedL2Slice

/** Emits bounded, real project blocks for ChipAgent physical evaluation. */
object EmitPpaRtl {
  def main(args: Array[String]): Unit = {
    require(args.nonEmpty,
      "usage: EmitPpaRtl <shared-l2-slice|command-router> [target-dir]")
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
            numComputeUnits = 2, transactionsPerCu = 4),
          stageArgs, firtoolArgs)
      case "command-router" =>
        ChiselStage.emitSystemVerilogFile(
          new GpuCommandRouter(
            GpuConfig(lanes = 4), commandIdWidth = 4,
            commandQueueDepth = 8, completionQueueDepth = 8),
          stageArgs, firtoolArgs)
      case other =>
        throw new IllegalArgumentException(s"unknown PPA block: $other")
    }
  }
}
