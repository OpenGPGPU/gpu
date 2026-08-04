package gpu.config

import chisel3.util.log2Ceil

/** Architectural parameters shared by the GPU core.
  *
  * A lane corresponds to one RISC-V thread in a SIMT warp.
  */
case class GpuConfig(
  xLen: Int = 32,
  lanes: Int = 8,
  warps: Int = 4,
  simtStackDepth: Int = 8,
  enableFpu: Boolean = true,
  enableVector: Boolean = true
) {
  require(xLen == 32, "the initial implementation supports RV32 only")
  require(lanes > 0, "a warp must contain at least one lane")
  require(warps > 0, "the core must contain at least one warp")
  require(simtStackDepth > 0, "the SIMT divergence stack must not be empty")

  def warpIdWidth: Int = math.max(1, log2Ceil(warps))
}
