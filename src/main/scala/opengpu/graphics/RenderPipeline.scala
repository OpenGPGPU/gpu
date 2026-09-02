package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.{
  CacheLineInvalidate,
  ComputeMemoryRequest,
  ComputeMemoryResponse,
  SharedAtomicRequest,
  SharedAtomicResponse
}

/** A scene triangle in clip space (Q16.16) with per-vertex colour and depth.
  *
  * `shaderPc`/`shaderKernarg` carry the draw's shader descriptor (Phase D): the
  * fragment/vertex shader entry PC and the kernel-arg buffer address, decoded
  * from the draw-call record.  A core-backed shader reads varyings/uniforms
  * from the kernarg buffer and writes its output through the same memory.
  */
class SceneTriangle(config: GraphicsConfig) extends Bundle {
  val clip = Vec(3, new ClipVertex)
  val color = Vec(3, new Varyings)
  val depth = Vec(3, SInt(32.W))
  /** Per-vertex texture coordinates (unsigned Q16.16; see TexUV). */
  val uv = Vec(3, new TexUV)
  val shaderPc = UInt(32.W)
  val shaderKernarg = UInt(32.W)
  val stateOverride = Bool()
  val depthTestEnable = Bool()
  val depthFunc = UInt(3.W)
  val depthWriteEnable = Bool()
  /** Source-over RGBA8888 blending, valid when stateOverride is set. */
  val blendEnable = Bool()
  val cullMode = UInt(2.W)
  val texEnable = Bool()
  val texWrapClamp = Bool()
  val texMaxLevel = UInt(4.W)
  val texLodBias = SInt(5.W)
  val texMinLevel = UInt(4.W)
  val kernargBankStride = UInt(32.W)
}

/** Fixed-function and render-target state sampled at a draw boundary. */
private class DrawRenderState extends Bundle {
  val colorBase = UInt(32.W)
  val depthBase = UInt(32.W)
  val stride = UInt(32.W)
  val depthTestEnable = Bool()
  val depthFunc = UInt(3.W)
  val depthWriteEnable = Bool()
  val blendEnable = Bool()
  val cullMode = UInt(2.W)
  val texEnable = Bool()
  val texBase = UInt(32.W)
  val texWidth = UInt(14.W)
  val texHeight = UInt(14.W)
  val texWrapClamp = Bool()
  val texMaxLevel = UInt(4.W)
  val texLodBias = SInt(5.W)
  val texMinLevel = UInt(4.W)
}

/** Top-of-pipeline renderer.
  *
  * Chains GeometryStage (perspective divide + viewport) -> RasterShader
  * (rasterize + interpolate) -> OutputMerger (depth test + write) for one
  * triangle at a time, writing the depth-tested result through the shared
  * memory port into the software-allocated colour/depth buffers.  Draw calls
  * are driven by `draw`; `done` pulses once the triangle has been fully
  * rasterized and the last read-modify-write has retired.
  *
  * `fragCore` selects the shading backend inserted between the raster/interp
  * stage and the OM.  With `fragCore = false` the interpolated colour passes
  * straight to the OM (the fixed-function path).  With `fragCore = true` the
  * fragment is shaded by a compiled RV32 kernel launched on the compute unit's
  * SIMT warps via `KernelFragStage`, reading the draw's shader descriptor
  * (`draw.shaderPc`/`draw.shaderKernarg`); the kernel's program, kernarg and
  * output live in the line-based memory behind the exposed
  * `kernelMemReq/Resp` and `kernelWordMemReq/Resp` ports.
  *
  * Near-plane clipping is hooked in before the viewport stage; for now a
  * triangle with far-w vertices is passed through by the clip stage unchanged.
  */
class RenderPipeline(
  config: GraphicsConfig = GraphicsConfig(),
  gpuConfig: GpuConfig = GpuConfig(),
  fragCore: Boolean = false
) extends Module {
  val io = IO(new Bundle {
    val draw = Flipped(Decoupled(new SceneTriangle(config)))
    val mem = new Bundle {
      val req = Decoupled(new OmMemoryRequest)
      val resp = Flipped(Decoupled(new OmMemoryResponse))
    }
    val kernelMemReq = Decoupled(new ComputeMemoryRequest(gpuConfig))
    val kernelMemResp = Flipped(Decoupled(new ComputeMemoryResponse()))
    val kernelWordMemReq = Decoupled(new ComputeMemoryRequest(gpuConfig))
    val kernelWordMemResp = Flipped(Decoupled(new ComputeMemoryResponse()))
    val kernelL1Invalidate = Flipped(Decoupled(new CacheLineInvalidate(gpuConfig)))
    val kernelL1InvalidateDone = Decoupled(new CacheLineInvalidate(gpuConfig))
    val kernelGlobalAtomicRequest = Decoupled(new SharedAtomicRequest(gpuConfig))
    val kernelGlobalAtomicResponse = Flipped(Decoupled(new SharedAtomicResponse(gpuConfig)))
    val colorBase = Input(UInt(32.W))
    val depthBase = Input(UInt(32.W))
    val stride = Input(UInt(32.W))
    val depthTestEnable = Input(Bool())
    val depthFunc = Input(UInt(3.W))
    val depthWriteEnable = Input(Bool())
    val cullMode = Input(UInt(2.W))
    /** Texture sampling (fixed-function path; ignored with fragCore). */
    val texEnable = Input(Bool())
    val texBase = Input(UInt(32.W))
    val texWidth = Input(UInt(14.W))
    val texHeight = Input(UInt(14.W))
    val texWrapClamp = Input(Bool())
    val texMaxLevel = Input(UInt(4.W))
    val texMem = new Bundle {
      val req = Decoupled(new OmMemoryRequest)
      val resp = Flipped(Decoupled(new OmMemoryResponse))
    }
    val done = Output(Bool())
  })

  private val geo = Module(new GeometryStage(config))
  private val shader = Module(new RasterShader(config, quadMode = fragCore))
  private val om = Module(new OutputMerger(config))
  private val textured =
    Module(new TexturedFragStage(config))

  // The command FIFO is allowed to advance to the next draw as soon as this
  // module accepts the current one.  Keep every per-draw field stable until
  // RasterShader has captured the draw; otherwise the next FIFO entry could
  // change geometry, varyings, or the core-shader descriptor mid-render.
  private val drawHold = RegInit(0.U.asTypeOf(new SceneTriangle(config)))
  private val drawHoldValid = RegInit(false.B)
  private val drawState = RegInit(0.U.asTypeOf(new DrawRenderState))
  when(io.draw.fire) {
    drawHold := io.draw.bits
    drawHoldValid := true.B
    drawState.colorBase := io.colorBase
    drawState.depthBase := io.depthBase
    drawState.stride := io.stride
    drawState.depthTestEnable := Mux(io.draw.bits.stateOverride,
      io.draw.bits.depthTestEnable, io.depthTestEnable)
    drawState.depthFunc := Mux(io.draw.bits.stateOverride,
      io.draw.bits.depthFunc, io.depthFunc)
    drawState.depthWriteEnable := Mux(io.draw.bits.stateOverride,
      io.draw.bits.depthWriteEnable, io.depthWriteEnable)
    // Blending currently has no global host register: it is deliberately
    // opt-in per draw so legacy command streams remain bit-identical.
    drawState.blendEnable := io.draw.bits.stateOverride && io.draw.bits.blendEnable
    drawState.cullMode := Mux(io.draw.bits.stateOverride,
      io.draw.bits.cullMode, io.cullMode)
    drawState.texEnable := Mux(io.draw.bits.stateOverride,
      io.draw.bits.texEnable, io.texEnable)
    drawState.texBase := io.texBase
    drawState.texWidth := io.texWidth
    drawState.texHeight := io.texHeight
    drawState.texWrapClamp := Mux(io.draw.bits.stateOverride,
      io.draw.bits.texWrapClamp, io.texWrapClamp)
    drawState.texMaxLevel := Mux(io.draw.bits.stateOverride,
      io.draw.bits.texMaxLevel, io.texMaxLevel)
    drawState.texLodBias := Mux(io.draw.bits.stateOverride,
      io.draw.bits.texLodBias, 0.S)
    drawState.texMinLevel := Mux(io.draw.bits.stateOverride,
      io.draw.bits.texMinLevel, 0.U)
  }
  when(shader.io.draw.fire && !io.draw.fire) {
    drawHoldValid := false.B
  }

  // Geometry: clip-space -> fixed-point screen-space vertices.
  geo.io.clip := drawHold.clip
  geo.io.color := drawHold.color
  geo.io.screenW := config.screenWidth.U
  geo.io.screenH := config.screenHeight.U

  // Shader consumes the screen-space triangle (sub-pixel fixed-point) directly.
  shader.io.draw.valid := drawHoldValid
  shader.io.draw.bits.v0.x := geo.io.out(0).sx(31, 0).asSInt
  shader.io.draw.bits.v0.y := geo.io.out(0).sy(31, 0).asSInt
  shader.io.draw.bits.v1.x := geo.io.out(1).sx(31, 0).asSInt
  shader.io.draw.bits.v1.y := geo.io.out(1).sy(31, 0).asSInt
  shader.io.draw.bits.v2.x := geo.io.out(2).sx(31, 0).asSInt
  shader.io.draw.bits.v2.y := geo.io.out(2).sy(31, 0).asSInt
  shader.io.colors := drawHold.color
  shader.io.depths := drawHold.depth
  shader.io.cullMode := drawState.cullMode
  io.draw.ready := !drawHoldValid && shader.io.draw.ready

  // Texture sampling configuration + word port (sampler owns its fetches).
  textured.io.e0 := shader.io.pixel.bits.e0
  textured.io.e1 := shader.io.pixel.bits.e1
  textured.io.e2 := shader.io.pixel.bits.e2
  textured.io.invW0 := geo.io.out(0).invW
  textured.io.invW1 := geo.io.out(1).invW
  textured.io.invW2 := geo.io.out(2).invW
  textured.io.uv0 := drawHold.uv(0)
  textured.io.uv1 := drawHold.uv(1)
  textured.io.uv2 := drawHold.uv(2)
  textured.io.texBase := drawState.texBase
  textured.io.texWidth := drawState.texWidth
  textured.io.texHeight := drawState.texHeight
  textured.io.wrapClamp := drawState.texWrapClamp
  textured.io.texMaxLevel := drawState.texMaxLevel
  io.texMem <> textured.io.mem

  // Whole-bundle payload + consumer ready live outside the fragCore branches
  // so both elaborations fully initialize the stage.
  textured.io.fragIn.bits := shader.io.pixel.bits
  textured.io.out.ready := om.io.fragIn.ready

  io.mem <> om.io.mem

  if (fragCore) {
    val kernelFrag = Module(new KernelFragStage(gpuConfig, config))
    val ctxFifo = Module(new DrawContextFifo(2))
    ctxFifo.io.enq.valid := io.draw.fire
    ctxFifo.io.enq.bits.shaderPc := io.draw.bits.shaderPc
    ctxFifo.io.enq.bits.kernargBase := io.draw.bits.shaderKernarg
    ctxFifo.io.enq.bits.kernargBankStride := io.draw.bits.kernargBankStride
    ctxFifo.io.enq.bits.texBase := io.texBase
    ctxFifo.io.enq.bits.texWidth := io.texWidth
    ctxFifo.io.enq.bits.texHeight := io.texHeight
    ctxFifo.io.enq.bits.texWrapClamp := Mux(io.draw.bits.stateOverride, io.draw.bits.texWrapClamp, io.texWrapClamp)
    ctxFifo.io.enq.bits.texMaxLevel := Mux(io.draw.bits.stateOverride, io.draw.bits.texMaxLevel, io.texMaxLevel)
    ctxFifo.io.enq.bits.texLodBias := Mux(io.draw.bits.stateOverride, io.draw.bits.texLodBias, 0.S)
    ctxFifo.io.enq.bits.texMinLevel := Mux(io.draw.bits.stateOverride, io.draw.bits.texMinLevel, 0.U)
    ctxFifo.io.enq.bits.colorBase := io.colorBase
    ctxFifo.io.enq.bits.depthBase := io.depthBase
    ctxFifo.io.enq.bits.stride := io.stride
    ctxFifo.io.enq.bits.depthTestEnable := Mux(io.draw.bits.stateOverride, io.draw.bits.depthTestEnable, io.depthTestEnable)
    ctxFifo.io.enq.bits.depthFunc := Mux(io.draw.bits.stateOverride, io.draw.bits.depthFunc, io.depthFunc)
    ctxFifo.io.enq.bits.depthWriteEnable := Mux(io.draw.bits.stateOverride, io.draw.bits.depthWriteEnable, io.depthWriteEnable)
    ctxFifo.io.enq.bits.blendEnable := io.draw.bits.stateOverride && io.draw.bits.blendEnable
    // Ordered retire handshake: the stage presents one completion event per
    // draw boundary in submission order and holds it until the owner accepts
    // it.  Every OM entry snapshots its render-target state at fragment
    // acceptance, so the matching head context may pop once all of that
    // draw's shader outputs have been handed to the OM; the OM itself need
    // not be globally idle.
    ctxFifo.io.retire := kernelFrag.io.drawRetire.valid && om.io.fragIn.ready
    kernelFrag.io.drawRetire.ready := ctxFifo.io.retire
    // Per-draw overlap: the rasterizer is the only shared front-end resource,
    // so the next draw is admitted as soon as it is idle and the fragment
    // producer's staging slot can take fragments.  Batch N+1 accumulates
    // (and later executes) while batch N's kernel is still running; per-draw
    // state is carried by the context FIFO and the dual staging slots, and
    // the ordered drawRetire handshake retires contexts in submission order.
    io.draw.ready := (!drawHoldValid || shader.io.draw.fire) &&
      shader.io.draw.ready && kernelFrag.io.fragIn.ready && ctxFifo.io.enq.ready
    kernelFrag.io.fragIn.valid := shader.io.pixel.valid
    kernelFrag.io.fragIn.bits := shader.io.pixel.bits
    kernelFrag.io.fragUv := textured.io.interpolatedUv
    shader.io.pixel.ready := kernelFrag.io.fragIn.ready
    kernelFrag.io.shaderPc := ctxFifo.io.tail.shaderPc
    kernelFrag.io.kernargBase := ctxFifo.io.tail.kernargBase
    kernelFrag.io.kernargBankStride := ctxFifo.io.tail.kernargBankStride
    kernelFrag.io.texBase := ctxFifo.io.tail.texBase
    kernelFrag.io.texWidth := ctxFifo.io.tail.texWidth
    kernelFrag.io.texHeight := ctxFifo.io.tail.texHeight
    kernelFrag.io.texWrapClamp := ctxFifo.io.tail.texWrapClamp
    kernelFrag.io.texMaxLevel := ctxFifo.io.tail.texMaxLevel
    kernelFrag.io.texLodBias := ctxFifo.io.tail.texLodBias
    kernelFrag.io.texMinLevel := ctxFifo.io.tail.texMinLevel
    // The batch must be flushed exactly at the draw boundary: once the
    // rasterizer has gone idle (all pixels of the current draw emitted), any
    // accumulated-but-unlaunched fragments are launched as one kernel.  The
    // batch is empty at that same point iff the draw produced no fragments, in
    // which case flush is a no-op.
    kernelFrag.io.flush := shader.io.done

    om.io.fragIn.valid := kernelFrag.io.out.valid
    om.io.fragIn.bits.x := kernelFrag.io.out.bits.x(15, 0).asUInt
    om.io.fragIn.bits.y := kernelFrag.io.out.bits.y(15, 0).asUInt
    om.io.fragIn.bits.color := Cat(
      kernelFrag.io.out.bits.color.r,
      kernelFrag.io.out.bits.color.g,
      kernelFrag.io.out.bits.color.b,
      kernelFrag.io.out.bits.alpha)
    om.io.fragIn.bits.depth := kernelFrag.io.out.bits.depth(29, 0).asUInt
    kernelFrag.io.out.ready := om.io.fragIn.ready

    io.kernelMemReq <> kernelFrag.io.memReq
    kernelFrag.io.memResp <> io.kernelMemResp
    io.kernelWordMemReq <> kernelFrag.io.wordMemReq
    kernelFrag.io.wordMemResp <> io.kernelWordMemResp
    textured.io.fragIn.valid := false.B // texture path only on fixed-func branch
    kernelFrag.io.l1Invalidate <> io.kernelL1Invalidate
    io.kernelL1InvalidateDone <> kernelFrag.io.l1InvalidateDone
    io.kernelGlobalAtomicRequest <> kernelFrag.io.globalAtomicRequest
    kernelFrag.io.globalAtomicResponse <> io.kernelGlobalAtomicResponse

    om.io.colorBase := ctxFifo.io.head.colorBase
    om.io.depthBase := ctxFifo.io.head.depthBase
    om.io.stride := ctxFifo.io.head.stride
    om.io.depthTestEnable := ctxFifo.io.head.depthTestEnable
    om.io.depthFunc := ctxFifo.io.head.depthFunc
    om.io.depthWriteEnable := ctxFifo.io.head.depthWriteEnable
    om.io.blendEnable := ctxFifo.io.head.blendEnable

    // Done only once every rasterized fragment has been flushed, shaded, and
    // handed to the OM AND the OM's in-flight entries have drained (their
    // writes issued): fragIn.ready only reports slot availability.
    io.done := !drawHoldValid && shader.io.done && kernelFrag.io.drained &&
      om.io.drained && !ctxFifo.io.headValid
  } else {
    om.io.colorBase := drawState.colorBase
    om.io.depthBase := drawState.depthBase
    om.io.stride := drawState.stride
    om.io.depthTestEnable := drawState.depthTestEnable
    om.io.depthFunc := drawState.depthFunc
    om.io.depthWriteEnable := drawState.depthWriteEnable
    om.io.blendEnable := drawState.blendEnable
    // As in the core-backed path, wait for the final fragment's serialized
    // depth/color RMW to retire (in-flight entries drained) before declaring
    // done; admission stays additionally gated on OM slot availability.
    io.draw.ready := (!drawHoldValid || shader.io.draw.fire) &&
      shader.io.draw.ready && shader.io.done &&
      om.io.fragIn.ready
    // Fragment stream: sampled-and-modulated when texturing is enabled, else
    // straight from the interpolator.  The bypass path keeps disabled draws
    // free of sampler latency and frees its memory port entirely.
    def packColor(r: UInt, g: UInt, b: UInt, a: UInt): UInt = Cat(r, g, b, a)

    textured.io.fragIn.valid := drawState.texEnable && shader.io.pixel.valid

    om.io.fragIn.valid := Mux(drawState.texEnable, textured.io.out.valid,
      shader.io.pixel.valid)
    om.io.fragIn.bits.x := Mux(drawState.texEnable,
      textured.io.out.bits.x, shader.io.pixel.bits.x)(15, 0).asUInt
    om.io.fragIn.bits.y := Mux(drawState.texEnable,
      textured.io.out.bits.y, shader.io.pixel.bits.y)(15, 0).asUInt
    om.io.fragIn.bits.color := Mux(drawState.texEnable,
      packColor(textured.io.out.bits.color.r, textured.io.out.bits.color.g,
        textured.io.out.bits.color.b, textured.io.out.bits.alpha),
      Cat(shader.io.pixel.bits.color.r, shader.io.pixel.bits.color.g,
        shader.io.pixel.bits.color.b, shader.io.pixel.bits.alpha))
    om.io.fragIn.bits.depth := Mux(drawState.texEnable,
      textured.io.out.bits.depth, shader.io.pixel.bits.depth)(29, 0).asUInt
    shader.io.pixel.ready := Mux(drawState.texEnable, textured.io.fragIn.ready,
      om.io.fragIn.ready)

    io.kernelMemReq.valid := false.B
    io.kernelMemReq.bits := 0.U.asTypeOf(io.kernelMemReq.bits)
    io.kernelMemResp.ready := false.B
    io.kernelWordMemReq.valid := false.B
    io.kernelWordMemReq.bits := 0.U.asTypeOf(io.kernelWordMemReq.bits)
    io.kernelWordMemResp.ready := false.B
    io.kernelL1Invalidate.ready := true.B
    io.kernelL1InvalidateDone.valid := false.B
    io.kernelL1InvalidateDone.bits :=
      0.U.asTypeOf(io.kernelL1InvalidateDone.bits)
    io.kernelGlobalAtomicRequest.valid := false.B
    io.kernelGlobalAtomicRequest.bits :=
      0.U.asTypeOf(io.kernelGlobalAtomicRequest.bits)
    io.kernelGlobalAtomicResponse.ready := true.B
    io.done := !drawHoldValid && shader.io.done && om.io.drained
  }
}
