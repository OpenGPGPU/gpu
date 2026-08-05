package gpu.elaboration

import circt.stage.ChiselStage
import gpu.config.GpuConfig
import gpu.core.GpuCore
import gpu.core.backend.FpuBackend
import gpu.core.backend.issue.FpuIssueStage
import gpu.core.backend.register.{
  ScalarRegisterMacroManager,
  ScalarRegisterManager
}
import gpu.core.backend.scoreboard.RegisterScoreboard
import gpu.core.execute.fpu.Fp32FmaLane
import gpu.core.frontend.decode.{DecodePipe, FullInstructionDecoder}
import gpu.core.frontend.warp.WarpScheduler
import gpu.core.memory.BankedSharedMemory
import gpu.core.vector.{
  VectorConfigurationUnit,
  VectorIntegerAlu,
  VectorMultiplyAlu
}

/** Emits the real project decoder RTL used by timing experiments.
  *
  * Generated files are intentionally placed under timing/generated so every
  * ChipAgent input can be inspected and reproduced.
  */
object EmitTimingRtl {
  def main(args: Array[String]): Unit = {
    val targetDir = args.headOption.getOrElse("timing/generated")
    val stageArgs = Array("--target-dir", targetDir)
    val firtoolArgs = Array(
      "--lowering-options=disallowLocalVariables,disallowPackedArrays"
    )

    ChiselStage.emitSystemVerilogFile(
      new FullInstructionDecoder(GpuConfig()),
      stageArgs,
      firtoolArgs
    )
    ChiselStage.emitSystemVerilogFile(
      new DecodePipe(GpuConfig()),
      stageArgs,
      firtoolArgs
    )
    ChiselStage.emitSystemVerilogFile(
      new WarpScheduler(GpuConfig()),
      stageArgs,
      firtoolArgs
    )
    ChiselStage.emitSystemVerilogFile(
      new RegisterScoreboard(GpuConfig()),
      stageArgs,
      firtoolArgs
    )
    ChiselStage.emitSystemVerilogFile(
      new ScalarRegisterManager(GpuConfig()),
      stageArgs,
      firtoolArgs
    )
    ChiselStage.emitSystemVerilogFile(
      new ScalarRegisterMacroManager(GpuConfig(), useBlackBoxes = true),
      stageArgs,
      firtoolArgs
    )
    ChiselStage.emitSystemVerilogFile(
      new GpuCore(GpuConfig(), useBlackBoxes = true, enableFpuBackend = true),
      stageArgs,
      firtoolArgs
    )
    ChiselStage.emitSystemVerilogFile(
      new VectorIntegerAlu(GpuConfig()),
      stageArgs,
      firtoolArgs
    )
    ChiselStage.emitSystemVerilogFile(
      new VectorMultiplyAlu(GpuConfig()),
      stageArgs,
      firtoolArgs
    )
    ChiselStage.emitSystemVerilogFile(
      new VectorConfigurationUnit(GpuConfig()),
      stageArgs,
      firtoolArgs
    )
    ChiselStage.emitSystemVerilogFile(
      new BankedSharedMemory(GpuConfig()),
      stageArgs,
      firtoolArgs
    )
    ChiselStage.emitSystemVerilogFile(
      new FpuIssueStage(GpuConfig()),
      stageArgs,
      firtoolArgs
    )
    ChiselStage.emitSystemVerilogFile(
      new Fp32FmaLane(),
      stageArgs,
      firtoolArgs
    )
    ChiselStage.emitSystemVerilogFile(
      new FpuBackend(GpuConfig()),
      stageArgs,
      firtoolArgs
    )
  }
}
