package opengpu.elaboration

import circt.stage.ChiselStage
import opengpu.config.GpuConfig
import opengpu.graphics.{GpuHostAxi, GraphicsConfig}

/** Emits the M6 host-control interface (`GpuHostAxi`) as SystemVerilog.
  *
  * This is the single top the ARTI framework (RTL-to-QEMU integration) parses:
  * it exposes the standard AXI4 memory-mapped control channels
  * (`s_axi_aw*`, w, b, `s_axi_ar*`) and a completion interrupt (`m_irq`),
  * so ARTI's naming-based inferencer detects an AXI4 slave and generates the
  * embedded QEMU device model and device-tree node.  The renderer's shared
  * memory ports are passed through; ARTI treats them as unknown ports and the
  * SoC attaches them to the coherent off-chip hierarchy separately.
  */
object EmitGpuHostAxi {
  def main(args: Array[String]): Unit = {
    val targetDir = args.headOption.getOrElse("generated/host")
    val fragCore = args.drop(1).contains("--frag-core")
    val vertCore = args.drop(1).contains("--vert-core")
    require(!vertCore || fragCore,
      "--vert-core requires --frag-core because both stages share the shader CU")
    val stageArgs = Array("--target-dir", targetDir)
    val firtoolArgs = Array(
      "--lowering-options=disallowLocalVariables,disallowPackedArrays"
    )
    val gfx = GraphicsConfig(screenWidth = 16, screenHeight = 16, subPixelBits = 8)
    ChiselStage.emitSystemVerilogFile(
      new GpuHostAxi(
        gfx,
        GpuConfig(lanes = 4, warps = 2),
        fragCore = fragCore,
        vertCore = vertCore),
      stageArgs,
      firtoolArgs
    )
  }
}
