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
  fragCore: Boolean = false,
  vertCore: Boolean = false
) extends Module {
  // Vertex and fragment bridges each retain four local IDs.  The shared
  // external port carries the stage-select bit plus that local ID.
  private val kernelWordOutstanding = 8
  val io = IO(new Bundle {
    val draw = Flipped(Decoupled(if (vertCore) new VertexDrawCommand(config) else new SceneTriangle(config)))
    val mem = new Bundle {
      val req = Decoupled(new OmMemoryRequest)
      val resp = Flipped(Decoupled(new OmMemoryResponse))
    }
    val kernelMemReq = Decoupled(new ComputeMemoryRequest(gpuConfig))
    val kernelMemResp = Flipped(Decoupled(new ComputeMemoryResponse()))
    val kernelWordMemReq = Decoupled(new ComputeMemoryRequest(gpuConfig, 64, kernelWordOutstanding))
    val kernelWordMemResp = Flipped(Decoupled(new ComputeMemoryResponse(64, kernelWordOutstanding)))
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
  private val shader = Module(new RasterShader(config, quadMode = fragCore || vertCore))
  private val om = Module(new OutputMerger(config))
  private val textured =
    Module(new TexturedFragStage(config, quadUv = fragCore || vertCore))

  // Triangle source: when vertCore=true, triangles come from KernelVertStage;
  // otherwise they come from io.draw directly.
  private val triSourceValid = Wire(Bool())
  private val triSourceBits = Wire(new SceneTriangle(config))
  private val triSourceReady = Wire(Bool())
  private val triSourceFire = Wire(Bool())
  private val vertDrawCmd = if (vertCore) {
    val cmd = RegInit(0.U.asTypeOf(new VertexDrawCommand(config)))
    when(io.draw.fire) {
      cmd := io.draw.bits.asInstanceOf[VertexDrawCommand]
    }
    Some(cmd)
  } else None

  private val kernelVertOpt = if (vertCore) Some(Module(new KernelVertStage(gpuConfig, config, standaloneKernel = false))) else None

  if (vertCore) {
    val kernelVert = kernelVertOpt.get
    kernelVert.io.start := io.draw.fire
    kernelVert.io.vertBufferBase := io.draw.bits.asInstanceOf[VertexDrawCommand].vertBufferBase
    kernelVert.io.vertCount := io.draw.bits.asInstanceOf[VertexDrawCommand].vertCount
    kernelVert.io.vertStride := io.draw.bits.asInstanceOf[VertexDrawCommand].vertStride
    kernelVert.io.shaderPc := io.draw.bits.asInstanceOf[VertexDrawCommand].vertShaderPc
    kernelVert.io.kernargBase := io.draw.bits.asInstanceOf[VertexDrawCommand].vertKernarg
    kernelVert.io.kernargBankStride := io.draw.bits.asInstanceOf[VertexDrawCommand].vertKernargBankStride
    kernelVert.io.fragShaderPc := io.draw.bits.asInstanceOf[VertexDrawCommand].fragShaderPc
    kernelVert.io.fragKernarg := io.draw.bits.asInstanceOf[VertexDrawCommand].fragKernarg
    kernelVert.io.fragKernargBankStride := io.draw.bits.asInstanceOf[VertexDrawCommand].fragKernargBankStride
    kernelVert.io.stateOverride := io.draw.bits.asInstanceOf[VertexDrawCommand].stateOverride
    kernelVert.io.depthTestEnable := io.draw.bits.asInstanceOf[VertexDrawCommand].depthTestEnable
    kernelVert.io.depthFunc := io.draw.bits.asInstanceOf[VertexDrawCommand].depthFunc
    kernelVert.io.depthWriteEnable := io.draw.bits.asInstanceOf[VertexDrawCommand].depthWriteEnable
    kernelVert.io.blendEnable := io.draw.bits.asInstanceOf[VertexDrawCommand].blendEnable
    kernelVert.io.cullMode := io.draw.bits.asInstanceOf[VertexDrawCommand].cullMode
    kernelVert.io.texEnable := io.draw.bits.asInstanceOf[VertexDrawCommand].texEnable
    kernelVert.io.texWrapClamp := io.draw.bits.asInstanceOf[VertexDrawCommand].texWrapClamp
    kernelVert.io.texMaxLevel := io.draw.bits.asInstanceOf[VertexDrawCommand].texMaxLevel
    kernelVert.io.texLodBias := io.draw.bits.asInstanceOf[VertexDrawCommand].texLodBias
    kernelVert.io.texMinLevel := io.draw.bits.asInstanceOf[VertexDrawCommand].texMinLevel
    kernelVert.io.memReq.ready := false.B
    kernelVert.io.memResp.valid := false.B
    kernelVert.io.memResp.bits := 0.U.asTypeOf(kernelVert.io.memResp.bits)
    kernelVert.io.l1Invalidate.valid := false.B
    kernelVert.io.l1Invalidate.bits := 0.U.asTypeOf(kernelVert.io.l1Invalidate.bits)
    kernelVert.io.l1InvalidateDone.ready := false.B
    kernelVert.io.globalAtomicRequest.ready := false.B
    kernelVert.io.globalAtomicResponse.valid := false.B
    kernelVert.io.globalAtomicResponse.bits := 0.U.asTypeOf(kernelVert.io.globalAtomicResponse.bits)
    kernelVert.io.kernelSimtBranch.valid := false.B
    kernelVert.io.kernelSimtBranch.bits := 0.U.asTypeOf(kernelVert.io.kernelSimtBranch.bits)
    kernelVert.io.kernelTexSample.valid := false.B
    kernelVert.io.kernelTexSample.bits := 0.U.asTypeOf(kernelVert.io.kernelTexSample.bits)
    kernelVert.io.kernelTexWriteback.ready := true.B
    kernelVert.io.kernelVectorTexSample.valid := false.B
    kernelVert.io.kernelVectorTexSample.bits := 0.U.asTypeOf(kernelVert.io.kernelVectorTexSample.bits)
    kernelVert.io.kernelVectorTexWriteback.ready := true.B
    when(io.draw.fire) {
      assert(kernelVert.io.done,
        "vertex draw accepted while KernelVertStage still owns the prior draw")
    }
    triSourceValid := kernelVert.io.vertOut.valid
    triSourceBits := kernelVert.io.vertOut.bits
    kernelVert.io.vertOut.ready := triSourceReady
    triSourceFire := kernelVert.io.vertOut.fire
  } else {
    val drawDecoupled = io.draw.asInstanceOf[DecoupledIO[SceneTriangle]]
    triSourceValid := drawDecoupled.valid
    triSourceBits := drawDecoupled.bits
    triSourceFire := drawDecoupled.fire
  }

  // Helper to create a Decoupled-like interface for triSource
  private object triSource {
    def valid = triSourceValid
    def bits = triSourceBits
    def ready = triSourceReady
    def fire = triSourceFire
  }

  // The command FIFO is allowed to advance to the next draw as soon as this
  // module accepts the current one.  Keep every per-draw field stable until
  // RasterShader has captured the draw; otherwise the next FIFO entry could
  // change geometry, varyings, or the core-shader descriptor mid-render.
  private val drawHold = RegInit(0.U.asTypeOf(new SceneTriangle(config)))
  private val drawHoldValid = RegInit(false.B)
  private val drawState = RegInit(0.U.asTypeOf(new DrawRenderState))
  when(triSource.fire) {
    drawHold := triSource.bits
    drawHoldValid := true.B
    drawState.colorBase := io.colorBase
    drawState.depthBase := io.depthBase
    drawState.stride := io.stride
    if (vertCore) {
      val cmd = vertDrawCmd.get
      drawState.depthTestEnable := cmd.depthTestEnable
      drawState.depthFunc := cmd.depthFunc
      drawState.depthWriteEnable := cmd.depthWriteEnable
      drawState.blendEnable := cmd.blendEnable
      drawState.cullMode := cmd.cullMode
      drawState.texEnable := cmd.texEnable
      drawState.texBase := io.texBase
      drawState.texWidth := io.texWidth
      drawState.texHeight := io.texHeight
      drawState.texWrapClamp := cmd.texWrapClamp
      drawState.texMaxLevel := cmd.texMaxLevel
      drawState.texLodBias := cmd.texLodBias
      drawState.texMinLevel := cmd.texMinLevel
    } else {
      drawState.depthTestEnable := Mux(triSource.bits.stateOverride,
        triSource.bits.depthTestEnable, io.depthTestEnable)
      drawState.depthFunc := Mux(triSource.bits.stateOverride,
        triSource.bits.depthFunc, io.depthFunc)
      drawState.depthWriteEnable := Mux(triSource.bits.stateOverride,
        triSource.bits.depthWriteEnable, io.depthWriteEnable)
      // Blending currently has no global host register: it is deliberately
      // opt-in per draw so legacy command streams remain bit-identical.
      drawState.blendEnable := triSource.bits.stateOverride && triSource.bits.blendEnable
      drawState.cullMode := Mux(triSource.bits.stateOverride,
        triSource.bits.cullMode, io.cullMode)
      drawState.texEnable := Mux(triSource.bits.stateOverride,
        triSource.bits.texEnable, io.texEnable)
      drawState.texBase := io.texBase
      drawState.texWidth := io.texWidth
      drawState.texHeight := io.texHeight
      drawState.texWrapClamp := Mux(triSource.bits.stateOverride,
        triSource.bits.texWrapClamp, io.texWrapClamp)
      drawState.texMaxLevel := Mux(triSource.bits.stateOverride,
        triSource.bits.texMaxLevel, io.texMaxLevel)
      drawState.texLodBias := Mux(triSource.bits.stateOverride,
        triSource.bits.texLodBias, 0.S)
      drawState.texMinLevel := Mux(triSource.bits.stateOverride,
        triSource.bits.texMinLevel, 0.U)
    }
  }
  when(shader.io.draw.fire && !triSource.fire) {
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

  // Drive triSourceReady based on consumer ability to accept
  triSourceReady := !drawHoldValid && shader.io.draw.ready

  // Texture sampling configuration + word port (sampler owns its fetches).
  textured.io.e0 := shader.io.pixel.bits.e0
  textured.io.e1 := shader.io.pixel.bits.e1
  textured.io.e2 := shader.io.pixel.bits.e2
  // Per-lane quad edge values for the core-backed path's parallel UV
  // interpolation (tied-off zeros in the fixed-function configuration).
  for (k <- 0 until 4) {
    textured.io.quadE0(k) := shader.io.quad.bits.lanes(k).e0
    textured.io.quadE1(k) := shader.io.quad.bits.lanes(k).e1
    textured.io.quadE2(k) := shader.io.quad.bits.lanes(k).e2
  }
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

  if (fragCore || vertCore) {
    val kernelFrag = Module(new KernelFragStage(gpuConfig, config))
    val kernelShader = Module(new KernelShaderStage(gpuConfig))

    // A vertex draw is fully transformed before it enters rasterization, so a
    // single SIMT CU can safely serve vertex and fragment launches in turn.
    // `kernelOwnerVert` remains set from a vertex launch until that launch's
    // completion is consumed, which also makes its completion and sidebands
    // unambiguous while the fragment stage begins accumulating quads.
    val kernelOwnerVert = RegInit(false.B)
    val kernelVert = kernelVertOpt
    val vertLaunchValid = kernelVert.map(_.io.kernelLaunch.valid).getOrElse(false.B)
    val fragLaunchValid = kernelFrag.io.kernelLaunch.valid
    val selectVertLaunch = vertLaunchValid

    kernelShader.io.launch.valid := vertLaunchValid || fragLaunchValid
    kernelShader.io.launch.kernelPc := Mux(selectVertLaunch,
      kernelVert.map(_.io.kernelLaunch.kernelPc).getOrElse(0.U),
      kernelFrag.io.kernelLaunch.kernelPc)
    kernelShader.io.launch.kernargAddress := Mux(selectVertLaunch,
      kernelVert.map(_.io.kernelLaunch.kernargAddress).getOrElse(0.U),
      kernelFrag.io.kernelLaunch.kernargAddress)
    kernelShader.io.launch.gridX := Mux(selectVertLaunch,
      kernelVert.map(_.io.kernelLaunch.gridX).getOrElse(1.U), kernelFrag.io.kernelLaunch.gridX)
    kernelShader.io.launch.gridY := Mux(selectVertLaunch,
      kernelVert.map(_.io.kernelLaunch.gridY).getOrElse(1.U), kernelFrag.io.kernelLaunch.gridY)
    kernelShader.io.launch.gridZ := Mux(selectVertLaunch,
      kernelVert.map(_.io.kernelLaunch.gridZ).getOrElse(1.U), kernelFrag.io.kernelLaunch.gridZ)
    kernelShader.io.launch.localX := Mux(selectVertLaunch,
      kernelVert.map(_.io.kernelLaunch.localX).getOrElse(1.U), kernelFrag.io.kernelLaunch.localX)
    kernelShader.io.launch.localY := Mux(selectVertLaunch,
      kernelVert.map(_.io.kernelLaunch.localY).getOrElse(1.U), kernelFrag.io.kernelLaunch.localY)
    kernelShader.io.launch.localZ := Mux(selectVertLaunch,
      kernelVert.map(_.io.kernelLaunch.localZ).getOrElse(1.U), kernelFrag.io.kernelLaunch.localZ)
    kernelFrag.io.kernelLaunch.ready := kernelShader.io.launch.ready && !selectVertLaunch
    kernelVert.foreach(_.io.kernelLaunch.ready := kernelShader.io.launch.ready && selectVertLaunch)
    when(kernelShader.io.launch.valid && kernelShader.io.launch.ready) {
      kernelOwnerVert := selectVertLaunch
    }

    kernelFrag.io.kernelCompletion.valid := kernelShader.io.completion.valid && !kernelOwnerVert
    kernelFrag.io.kernelCompletion.bits := kernelShader.io.completion.bits
    kernelVert.foreach { vert =>
      vert.io.kernelCompletion.valid := kernelShader.io.completion.valid && kernelOwnerVert
      vert.io.kernelCompletion.bits := kernelShader.io.completion.bits
    }
    kernelShader.io.completion.ready := Mux(kernelOwnerVert,
      kernelVert.map(_.io.kernelCompletion.ready).getOrElse(false.B),
      kernelFrag.io.kernelCompletion.ready)
    when(kernelShader.io.completion.fire) { kernelOwnerVert := false.B }

    kernelFrag.io.kernelTrap.valid := kernelShader.io.trap.valid && !kernelOwnerVert
    kernelFrag.io.kernelTrap.bits := kernelShader.io.trap.bits
    kernelVert.foreach { vert =>
      vert.io.kernelTrap.valid := kernelShader.io.trap.valid && kernelOwnerVert
      vert.io.kernelTrap.bits := kernelShader.io.trap.bits
    }
    kernelShader.io.trap.ready := Mux(kernelOwnerVert,
      kernelVert.map(_.io.kernelTrap.ready).getOrElse(false.B), kernelFrag.io.kernelTrap.ready)
    kernelFrag.io.kernelSimtBranch.valid := false.B
    kernelFrag.io.kernelSimtBranch.bits := 0.U.asTypeOf(kernelFrag.io.kernelSimtBranch.bits)
    kernelShader.io.simtBranch.valid := false.B
    kernelShader.io.simtBranch.bits := 0.U.asTypeOf(kernelShader.io.simtBranch.bits)

    // Only fragment shaders access the texture units.  Vertex-side texture
    // ports are deliberately held inactive by KernelVertStage.
    kernelShader.io.texSample <> kernelFrag.io.kernelTexSample
    kernelFrag.io.kernelTexWriteback <> kernelShader.io.texWriteback
    kernelShader.io.vectorTexSample <> kernelFrag.io.kernelVectorTexSample
    kernelFrag.io.kernelVectorTexWriteback <> kernelShader.io.vectorTexWriteback

    // Connect KernelShaderStage memory ports to RenderPipeline IO
    io.kernelMemReq <> kernelShader.io.memoryRequest
    kernelShader.io.memoryResponse <> io.kernelMemResp
    kernelShader.io.l1Invalidate <> io.kernelL1Invalidate
    io.kernelL1InvalidateDone <> kernelShader.io.l1InvalidateDone
    io.kernelGlobalAtomicRequest <> kernelShader.io.globalAtomicRequest
    kernelShader.io.globalAtomicResponse <> io.kernelGlobalAtomicResponse

    val ctxFifo = Module(new DrawContextFifo(2))
    ctxFifo.io.enq.valid := triSource.fire
    if (vertCore) {
      val cmd = vertDrawCmd.get
      ctxFifo.io.enq.bits.shaderPc := cmd.fragShaderPc
      ctxFifo.io.enq.bits.kernargBase := cmd.fragKernarg
      ctxFifo.io.enq.bits.kernargBankStride := cmd.fragKernargBankStride
      ctxFifo.io.enq.bits.texBase := io.texBase
      ctxFifo.io.enq.bits.texWidth := io.texWidth
      ctxFifo.io.enq.bits.texHeight := io.texHeight
      ctxFifo.io.enq.bits.texWrapClamp := cmd.texWrapClamp
      ctxFifo.io.enq.bits.texMaxLevel := cmd.texMaxLevel
      ctxFifo.io.enq.bits.texLodBias := cmd.texLodBias
      ctxFifo.io.enq.bits.texMinLevel := cmd.texMinLevel
      ctxFifo.io.enq.bits.colorBase := io.colorBase
      ctxFifo.io.enq.bits.depthBase := io.depthBase
      ctxFifo.io.enq.bits.stride := io.stride
      ctxFifo.io.enq.bits.depthTestEnable := cmd.depthTestEnable
      ctxFifo.io.enq.bits.depthFunc := cmd.depthFunc
      ctxFifo.io.enq.bits.depthWriteEnable := cmd.depthWriteEnable
      ctxFifo.io.enq.bits.blendEnable := cmd.blendEnable
    } else {
      val drawBits = io.draw.bits.asInstanceOf[SceneTriangle]
      ctxFifo.io.enq.bits.shaderPc := drawBits.shaderPc
      ctxFifo.io.enq.bits.kernargBase := drawBits.shaderKernarg
      ctxFifo.io.enq.bits.kernargBankStride := drawBits.kernargBankStride
      ctxFifo.io.enq.bits.texBase := io.texBase
      ctxFifo.io.enq.bits.texWidth := io.texWidth
      ctxFifo.io.enq.bits.texHeight := io.texHeight
      ctxFifo.io.enq.bits.texWrapClamp := Mux(drawBits.stateOverride, drawBits.texWrapClamp, io.texWrapClamp)
      ctxFifo.io.enq.bits.texMaxLevel := Mux(drawBits.stateOverride, drawBits.texMaxLevel, io.texMaxLevel)
      ctxFifo.io.enq.bits.texLodBias := Mux(drawBits.stateOverride, drawBits.texLodBias, 0.S)
      ctxFifo.io.enq.bits.texMinLevel := Mux(drawBits.stateOverride, drawBits.texMinLevel, 0.U)
      ctxFifo.io.enq.bits.colorBase := io.colorBase
      ctxFifo.io.enq.bits.depthBase := io.depthBase
      ctxFifo.io.enq.bits.stride := io.stride
      ctxFifo.io.enq.bits.depthTestEnable := Mux(drawBits.stateOverride, drawBits.depthTestEnable, io.depthTestEnable)
      ctxFifo.io.enq.bits.depthFunc := Mux(drawBits.stateOverride, drawBits.depthFunc, io.depthFunc)
      ctxFifo.io.enq.bits.depthWriteEnable := Mux(drawBits.stateOverride, drawBits.depthWriteEnable, io.depthWriteEnable)
      ctxFifo.io.enq.bits.blendEnable := drawBits.stateOverride && drawBits.blendEnable
    }
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
      triSource.ready && shader.io.draw.ready && kernelFrag.io.fragIn.ready && ctxFifo.io.enq.ready &&
      kernelVert.map(_.io.done).getOrElse(true.B)
    kernelFrag.io.fragIn.valid := shader.io.quad.valid
    kernelFrag.io.fragIn.bits := shader.io.quad.bits
    kernelFrag.io.fragUv := textured.io.interpolatedUvQuad
    shader.io.quad.ready := kernelFrag.io.fragIn.ready
    shader.io.pixel.ready := false.B // quad mode: scalar port is tied off
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

    // Preserve each bridge's four local IDs by prefixing vertex requests with
    // one.  The resulting eight-ID port allows both bridges to sustain their
    // normal outstanding depth, while the response tag selects its owner.
    val wordArb = Module(new RRArbiter(
      new ComputeMemoryRequest(gpuConfig, 64, kernelWordOutstanding), 2))
    wordArb.io.in(0).valid := kernelFrag.io.wordMemReq.valid
    wordArb.io.in(0).bits := kernelFrag.io.wordMemReq.bits
    wordArb.io.in(0).bits.transactionId := Cat(0.U(1.W), kernelFrag.io.wordMemReq.bits.transactionId)
    kernelFrag.io.wordMemReq.ready := wordArb.io.in(0).ready
    wordArb.io.in(1).valid := kernelVert.map(_.io.wordMemReq.valid).getOrElse(false.B)
    wordArb.io.in(1).bits := kernelVert.map(_.io.wordMemReq.bits)
      .getOrElse(0.U.asTypeOf(wordArb.io.in(1).bits))
    wordArb.io.in(1).bits.transactionId := Cat(1.U(1.W),
      kernelVert.map(_.io.wordMemReq.bits.transactionId).getOrElse(0.U(2.W)))
    kernelVert.foreach { vert =>
      vert.io.wordMemReq.ready := wordArb.io.in(1).ready
      vert.io.wordMemResp.valid := io.kernelWordMemResp.valid && io.kernelWordMemResp.bits.transactionId(2)
      vert.io.wordMemResp.bits := io.kernelWordMemResp.bits
      vert.io.wordMemResp.bits.transactionId := io.kernelWordMemResp.bits.transactionId(1, 0)
    }
    io.kernelWordMemReq <> wordArb.io.out
    kernelFrag.io.wordMemResp.valid := io.kernelWordMemResp.valid && !io.kernelWordMemResp.bits.transactionId(2)
    kernelFrag.io.wordMemResp.bits := io.kernelWordMemResp.bits
    kernelFrag.io.wordMemResp.bits.transactionId := io.kernelWordMemResp.bits.transactionId(1, 0)
    io.kernelWordMemResp.ready := Mux(io.kernelWordMemResp.bits.transactionId(2),
      kernelVert.map(_.io.wordMemResp.ready).getOrElse(false.B),
      kernelFrag.io.wordMemResp.ready)
    textured.io.fragIn.valid := false.B // texture path only on fixed-func branch

    // Tie off kernelFrag's unused memReq/memResp ports
    kernelFrag.io.memReq.ready := false.B
    kernelFrag.io.memResp.valid := false.B
    kernelFrag.io.memResp.bits := 0.U.asTypeOf(kernelFrag.io.memResp.bits)

    // Tie off kernelFrag's l1Invalidate and globalAtomic ports
    kernelFrag.io.l1Invalidate.valid := false.B
    kernelFrag.io.l1Invalidate.bits := 0.U.asTypeOf(kernelFrag.io.l1Invalidate.bits)
    kernelFrag.io.l1InvalidateDone.ready := false.B
    kernelFrag.io.globalAtomicRequest.ready := false.B
    kernelFrag.io.globalAtomicResponse.valid := false.B
    kernelFrag.io.globalAtomicResponse.bits := 0.U.asTypeOf(kernelFrag.io.globalAtomicResponse.bits)

    // kernelFrag's l1Invalidate and globalAtomic ports are tied off internally.
    // The RenderPipeline's corresponding IO ports are driven by kernelShader.

    om.io.colorBase := ctxFifo.io.head.colorBase
    om.io.depthBase := ctxFifo.io.head.depthBase
    om.io.stride := ctxFifo.io.head.stride
    om.io.depthTestEnable := ctxFifo.io.head.depthTestEnable
    om.io.depthFunc := ctxFifo.io.head.depthFunc
    om.io.depthWriteEnable := ctxFifo.io.head.depthWriteEnable
    om.io.blendEnable := ctxFifo.io.head.blendEnable

    // Done only once every rasterized fragment has been flushed, shaded, and
    // handed to the OM: the batch slots must be empty (drained) and every
    // admitted draw retired, so an in-flight batch or an unpresented retire
    // event is never mistaken for an idle pipeline at a draw boundary.
    io.done := !drawHoldValid && shader.io.done && kernelFrag.io.drained &&
      om.io.fragIn.ready && !ctxFifo.io.headValid
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
      triSource.ready && shader.io.draw.ready && shader.io.done &&
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
    shader.io.quad.ready := false.B // scalar mode: quad port is tied off

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
    io.done := !drawHoldValid && shader.io.done && om.io.fragIn.ready
  }
}
