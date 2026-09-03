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
  *
  * Arguments (after the optional target directory):
  *   --frag-core        fragment shader runs on the SIMT core
  *   --vert-core        vertex shader runs on the SIMT core (needs --frag-core)
  *   --width N          render target width in pixels (default 16)
  *   --height N         render target height in pixels (default 16)
  * The resolution must be a power of two (the mip-chain and self-test
  * geometry assume it) and at least 16x16, matching the guest self-test.
  */
object EmitGpuHostAxi {
  def main(args: Array[String]): Unit = {
    val targetDir = args.headOption.getOrElse("generated/host")
    val rest = args.drop(1).toIndexedSeq
    val fragCore = rest.contains("--frag-core")
    val vertCore = rest.contains("--vert-core")
    require(!vertCore || fragCore,
      "--vert-core requires --frag-core because both stages share the shader CU")

    def intOption(name: String): Option[Int] = {
      val i = rest.indexOf(name)
      if (i < 0) None
      else {
        require(i + 1 < rest.size, s"$name needs a value")
        Some(rest(i + 1).toInt)
      }
    }
    val width = intOption("--width").getOrElse(16)
    val height = intOption("--height").getOrElse(16)
    def isPow2(n: Int): Boolean = n > 0 && (n & (n - 1)) == 0
    require(isPow2(width) && isPow2(height),
      s"resolution must be powers of two, got ${width}x${height}")
    require(width >= 16 && height >= 16,
      s"resolution must be at least 16x16, got ${width}x${height}")

    val stageArgs = Array("--target-dir", targetDir)
    val firtoolArgs = Array(
      "--lowering-options=disallowLocalVariables,disallowPackedArrays"
    )
    val gfx = GraphicsConfig(
      screenWidth = width, screenHeight = height, subPixelBits = 8)
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
