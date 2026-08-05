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
  sharedMemoryBytes: Int = 16 * 1024,
  sharedMemoryBanks: Int = 8,
  sharedMemoryBase: Long = 0x10000000L,
  l2Sets: Int = 128,
  l2Ways: Int = 4,
  l2Banks: Int = 2,
  l2RequestQueueDepth: Int = 2,
  commandQueueDepth: Int = 8,
  completionQueueDepth: Int = 8,
  copyDescriptorQueueDepth: Int = 4,
  fillDescriptorQueueDepth: Int = 4,
  enableFpu: Boolean = true,
  enableVector: Boolean = true
) {
  require(xLen == 32, "the initial implementation supports RV32 only")
  require(lanes > 0, "a warp must contain at least one lane")
  require(warps > 0, "the core must contain at least one warp")
  require(simtStackDepth > 0, "the SIMT divergence stack must not be empty")
  require(sharedMemoryBytes > 0 && (sharedMemoryBytes & (sharedMemoryBytes - 1)) == 0,
    "shared memory size must be a power of two")
  require(sharedMemoryBanks > 0 && (sharedMemoryBanks & (sharedMemoryBanks - 1)) == 0,
    "shared memory bank count must be a power of two")
  require(sharedMemoryBanks >= 4,
    "32-bit shared atomics require at least four byte-interleaved banks")
  require(sharedMemoryBytes % sharedMemoryBanks == 0)
  require(l2Sets > 1 && (l2Sets & (l2Sets - 1)) == 0,
    "L2 set count must be a power of two")
  require(l2Ways > 0 && (l2Ways & (l2Ways - 1)) == 0,
    "L2 way count must be a power of two")
  require(l2Banks > 0 && (l2Banks & (l2Banks - 1)) == 0,
    "L2 bank count must be a power of two")
  require(l2Sets % l2Banks == 0 && l2Sets / l2Banks > 1,
    "each L2 bank must contain at least two sets")
  require(l2RequestQueueDepth > 0,
    "each L2 bank request queue must contain at least one entry")
  require(commandQueueDepth > 0,
    "the kernel command queue must contain at least one entry")
  require(completionQueueDepth > 0,
    "the kernel completion queue must contain at least one entry")
  require(copyDescriptorQueueDepth > 0,
    "the copy descriptor queue must contain at least one entry")
  require(fillDescriptorQueueDepth > 0,
    "the fill descriptor queue must contain at least one entry")

  def warpIdWidth: Int = math.max(1, log2Ceil(warps))
}
