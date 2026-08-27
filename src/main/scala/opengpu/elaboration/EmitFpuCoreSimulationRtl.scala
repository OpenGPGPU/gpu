package opengpu.elaboration

import circt.stage.ChiselStage
import opengpu.config.GpuConfig
import opengpu.core.GpuCore

/** Emits a simulation core with behavioral scalar RF and Chisel FPU backend. */
object EmitFpuCoreSimulationRtl {
  def main(args: Array[String]): Unit = {
    val targetDir = args.headOption.getOrElse("target/gpu_core_fpu_rtl")
    ChiselStage.emitSystemVerilogFile(
      new GpuCore(
        GpuConfig(),
        useBlackBoxes = false,
        enableFpuBackend = true
      ),
      Array("--target-dir", targetDir),
      Array("--lowering-options=disallowLocalVariables,disallowPackedArrays")
    )
  }
}
