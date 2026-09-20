package opengpu.graphics

import chisel3._

/** Per-draw fixed-function state carried by every command record.
  *
  * `SceneTriangle` (fixed-function records) and `VertexDrawCommand`
  * (vertex-core records) both mix this in, so the pipeline can resolve the
  * draw's depth/stencil/blend/cull/sampler state from either source with one
  * helper instead of duplicating the field-by-field copy.
  */
trait HasDrawState extends Bundle {
  /** When set, this record's state fields override the global registers. */
  val stateOverride = Bool()
  val depthTestEnable = Bool()
  val depthFunc = UInt(3.W)
  val depthWriteEnable = Bool()
  val blendEnable = Bool()
  val blendCfgEnable = Bool()
  val blendSrcFactor = UInt(4.W)
  val blendDstFactor = UInt(4.W)
  val blendEquation = UInt(3.W)
  val stencilTestEnable = Bool()
  val stencilFunc = UInt(3.W)
  val stencilRef = UInt(8.W)
  val stencilReadMask = UInt(8.W)
  val stencilWriteMask = UInt(8.W)
  val stencilFailOp = UInt(3.W)
  val stencilZFailOp = UInt(3.W)
  val stencilZPassOp = UInt(3.W)
  val cullMode = UInt(2.W)
  val texEnable = Bool()
  val texWrapClamp = Bool()
  val texMaxLevel = UInt(4.W)
  val texLodBias = SInt(5.W)
  val texMinLevel = UInt(4.W)
}
