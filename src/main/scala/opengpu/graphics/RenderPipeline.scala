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
    val texMem = new Bundle {
      val req = Decoupled(new OmMemoryRequest)
      val resp = Flipped(Decoupled(new OmMemoryResponse))
    }
    val done = Output(Bool())
  })

  private val geo = Module(new GeometryStage(config))
  private val shader = Module(new RasterShader(config))
  private val om = Module(new OutputMerger(config))
  private val textured =
    Module(new TexturedFragStage(config))

  // Geometry: clip-space -> fixed-point screen-space vertices.
  geo.io.clip := io.draw.bits.clip
  geo.io.color := io.draw.bits.color
  geo.io.screenW := config.screenWidth.U
  geo.io.screenH := config.screenHeight.U

  // Shader consumes the screen-space triangle (sub-pixel fixed-point) directly.
  shader.io.draw.valid := io.draw.valid
  shader.io.draw.bits.v0.x := geo.io.out(0).sx(31, 0).asSInt
  shader.io.draw.bits.v0.y := geo.io.out(0).sy(31, 0).asSInt
  shader.io.draw.bits.v1.x := geo.io.out(1).sx(31, 0).asSInt
  shader.io.draw.bits.v1.y := geo.io.out(1).sy(31, 0).asSInt
  shader.io.draw.bits.v2.x := geo.io.out(2).sx(31, 0).asSInt
  shader.io.draw.bits.v2.y := geo.io.out(2).sy(31, 0).asSInt
  shader.io.colors := io.draw.bits.color
  shader.io.depths := io.draw.bits.depth
  shader.io.cullMode := io.cullMode
  io.draw.ready := shader.io.draw.ready

  // Texture sampling configuration + word port (sampler owns its fetches).
  textured.io.e0 := shader.io.pixel.bits.e0
  textured.io.e1 := shader.io.pixel.bits.e1
  textured.io.e2 := shader.io.pixel.bits.e2
  textured.io.invW0 := geo.io.out(0).invW
  textured.io.invW1 := geo.io.out(1).invW
  textured.io.invW2 := geo.io.out(2).invW
  textured.io.uv0 := io.draw.bits.uv(0)
  textured.io.uv1 := io.draw.bits.uv(1)
  textured.io.uv2 := io.draw.bits.uv(2)
  textured.io.texBase := io.texBase
  textured.io.texWidth := io.texWidth
  textured.io.texHeight := io.texHeight
  textured.io.wrapClamp := io.texWrapClamp
  io.texMem <> textured.io.mem

  // Whole-bundle payload + consumer ready live outside the fragCore branches
  // so both elaborations fully initialize the stage.
  textured.io.fragIn.bits := shader.io.pixel.bits
  textured.io.out.ready := om.io.fragIn.ready

  // Output-merge registers + memory port.
  om.io.colorBase := io.colorBase
  om.io.depthBase := io.depthBase
  om.io.stride := io.stride
  om.io.depthTestEnable := io.depthTestEnable
  om.io.depthFunc := io.depthFunc
  om.io.depthWriteEnable := io.depthWriteEnable
  om.io.blendEnable := false.B
  io.mem <> om.io.mem

  if (fragCore) {
    val kernelFrag = Module(new KernelFragStage(gpuConfig, config))
    // Do not accept the next command-buffer record until the previous draw's
    // batched kernel output has reached the OM.  RasterShader becomes idle as
    // soon as it emits its last pixel, but the core-backed sampler can still
    // be draining that batch; gating here keeps shader descriptors and
    // per-draw kernarg state from overlapping.
    io.draw.ready := shader.io.draw.ready && kernelFrag.io.drained
    kernelFrag.io.fragIn.valid := shader.io.pixel.valid
    kernelFrag.io.fragIn.bits := shader.io.pixel.bits
    kernelFrag.io.fragUv := textured.io.interpolatedUv
    shader.io.pixel.ready := kernelFrag.io.fragIn.ready
    kernelFrag.io.shaderPc := io.draw.bits.shaderPc
    kernelFrag.io.kernargBase := io.draw.bits.shaderKernarg
    kernelFrag.io.texBase := io.texBase
    kernelFrag.io.texWidth := io.texWidth
    kernelFrag.io.texHeight := io.texHeight
    kernelFrag.io.texWrapClamp := io.texWrapClamp
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
      0xff.U(8.W))
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

    // Done only once every rasterized fragment has been flushed, shaded, and
    // handed to the OM: the batch must be empty (drained) so an in-flight batch
    // is never mistaken for an idle pipeline during a draw boundary.
    io.done := shader.io.done && kernelFrag.io.drained && om.io.fragIn.ready
  } else {
    // Fragment stream: sampled-and-modulated when texturing is enabled, else
    // straight from the interpolator.  The bypass path keeps disabled draws
    // free of sampler latency and frees its memory port entirely.
    def packColor(r: UInt, g: UInt, b: UInt): UInt = Cat(r, g, b, 0xff.U(8.W))

    textured.io.fragIn.valid := io.texEnable && shader.io.pixel.valid

    om.io.fragIn.valid := Mux(io.texEnable, textured.io.out.valid,
      shader.io.pixel.valid)
    om.io.fragIn.bits.x := Mux(io.texEnable,
      textured.io.out.bits.x, shader.io.pixel.bits.x)(15, 0).asUInt
    om.io.fragIn.bits.y := Mux(io.texEnable,
      textured.io.out.bits.y, shader.io.pixel.bits.y)(15, 0).asUInt
    om.io.fragIn.bits.color := Mux(io.texEnable,
      packColor(textured.io.out.bits.color.r, textured.io.out.bits.color.g,
        textured.io.out.bits.color.b),
      Cat(shader.io.pixel.bits.color.r, shader.io.pixel.bits.color.g,
        shader.io.pixel.bits.color.b, 0xff.U(8.W)))
    om.io.fragIn.bits.depth := Mux(io.texEnable,
      textured.io.out.bits.depth, shader.io.pixel.bits.depth)(29, 0).asUInt
    shader.io.pixel.ready := Mux(io.texEnable, textured.io.fragIn.ready,
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
    io.done := shader.io.done && om.io.fragIn.ready
  }
}
