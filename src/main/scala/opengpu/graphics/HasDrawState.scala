package opengpu.graphics

import chisel3._

/** Resolved per-draw fixed-function state: depth, blend, stencil, cull and
  * sampler fields with no per-record override flag.  `DrawRenderState` and any
  * other bundle that stores the state a draw actually runs with mixes this in,
  * so the fields are declared once.
  */
trait HasResolvedDrawState extends Bundle {
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

/** A command record's per-draw state: the resolved fields plus the record's
  * override flag and the record-only source-over/lod inputs.  `SceneTriangle`
  * (fixed-function records) and `VertexDrawCommand` (vertex-core records)
  * both mix this in, so the pipeline resolves either with one helper.
  */
trait HasDrawState extends HasResolvedDrawState {
  /** When set, this record's state fields override the global registers. */
  val stateOverride = Bool()
}
