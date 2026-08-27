package opengpu.elaboration

import circt.stage.ChiselStage
import opengpu.core.execute.fpu.Fp32FmaLane

object EmitFp32FmaLaneTimingRtl {
  def main(args: Array[String]): Unit = {
    val targetDir = args.headOption.getOrElse("timing/generated/fpu/yunsuan_lane")
    ChiselStage.emitSystemVerilogFile(
      new Fp32FmaLane(),
      Array("--target-dir", targetDir),
      Array("--lowering-options=disallowLocalVariables,disallowPackedArrays")
    )
  }
}
