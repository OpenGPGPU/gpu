package opengpu.elaboration

import circt.stage.ChiselStage
import opengpu.config.GpuConfig
import opengpu.core.GpuCore
import opengpu.core.backend.FpuBackend
import opengpu.core.backend.issue.FpuIssueStage
import opengpu.core.backend.register.{
  ScalarRegisterMacroManager,
  ScalarRegisterManager,
  VectorRegisterFile
}
import opengpu.core.backend.scoreboard.RegisterScoreboard
import opengpu.core.execute.fpu.Fp32FmaLane
import opengpu.core.frontend.decode.{DecodePipe, FullInstructionDecoder}
import opengpu.core.frontend.warp.WarpScheduler
import opengpu.core.memory.BankedSharedMemory
import opengpu.core.vector.{
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
      new VectorRegisterFile(GpuConfig(), useBlackBox = true),
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
