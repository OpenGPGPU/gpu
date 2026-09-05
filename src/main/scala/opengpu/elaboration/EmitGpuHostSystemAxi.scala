package opengpu.elaboration

import circt.stage.ChiselStage
import opengpu.config.GpuConfig
import opengpu.graphics.GraphicsConfig
import opengpu.system.GpuHostSystemAxi

/** Emits the AXI graphics + compute/DMA shared-L2 integration top. */
object EmitGpuHostSystemAxi {
  def main(args: Array[String]): Unit = {
    val targetDir = args.headOption.getOrElse("generated/host-system")
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
    val computeUnits = intOption("--compute-units").getOrElse(1)
    def isPow2(n: Int): Boolean = n > 0 && (n & (n - 1)) == 0
    require(isPow2(width) && isPow2(height),
      s"resolution must be powers of two, got ${width}x${height}")
    require(width >= 16 && height >= 16,
      s"resolution must be at least 16x16, got ${width}x${height}")
    require(computeUnits > 0,
      s"compute-unit count must be positive, got $computeUnits")

    ChiselStage.emitSystemVerilogFile(
      new GpuHostSystemAxi(
        GraphicsConfig(
          screenWidth = width, screenHeight = height, subPixelBits = 8),
        GpuConfig(lanes = 4, warps = 2),
        numComputeUnits = computeUnits,
        fragCore = fragCore,
        vertCore = vertCore),
      Array("--target-dir", targetDir),
      Array("--lowering-options=disallowLocalVariables,disallowPackedArrays")
    )
  }
}
