package opengpu.graphics

/** Bit positions of the read-only capability word advertised through
  * `RenderHostRegs.CAPABILITIES`.  These must stay in lock-step with the
  * `GPU_CAP_*` definitions in `driver/gpu_abi.h`; `GpuAbiLayoutSpec` parses the
  * header and fails the build if the two drift apart.
  */
object GpuCapabilities {
  /** Fragment-core executables are accepted (bit0). */
  val FragmentCore = 0
  /** The host-memory job ring is implemented (bit1). */
  val JobQueue = 1
  /** Vertex-core draw records are accepted (bit2). */
  val VertexCore = 2
  /** Patterned hardware fill engine (bit3). */
  val ClearEngine = 3
  /** Aligned hardware colour blit engine (bit4). */
  val BlitEngine = 4
  /** Two-dimensional hardware strided copy engine (bit5). */
  val StridedEngine = 5
  /** The unified-command MMIO block is wired (bit6). */
  val UnifiedCommands = 6
  /** Fixed-function MSAA is supported (bit7). */
  val Msaa = 7
  /** Shift of the fragment batch capacity in bits 15:8. */
  val FragmentBatchShift = 8
  /** Shift of the maximum sample mode (log2 count) in bits 17:16. */
  val MsaaMaxModeShift = 16
  /** Safe unified-command reset is supported (bit18). */
  val UnifiedReset = 18
}
