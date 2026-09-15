package opengpu.graphics

import chisel3._

/** Fragment output-control contract, mirrored by GPU_FRAGMENT_* in gpu_abi.h.
  * Validated sample mode 0 selects ABI 0; modes 1 and 2 select ABI 1. No
  * independent selector is exposed in host registers or submission records.
  * Programmable MSAA still requires separate capability qualification.
  */
object FragmentShaderAbi {
  val Legacy = 0
  val Multisample = 1
  val EmitBit = 0
  val DepthOverrideBit = 1

  def usesMultisample(sampleMode: UInt): Bool = sampleMode =/= 0.U

  /** ABI 1 reserved bits must be zero; malformed words discard the pixel.
    * ABI 0 preserves the full-width nonzero convention, including bit 31.
    */
  def emits(control: UInt, abi1: Bool): Bool =
    Mux(abi1, !control(31, 2).orR && control(EmitBit), control.orR)

  def overridesDepth(control: UInt, abi1: Bool): Bool =
    !abi1 || control(DepthOverrideBit)
}
