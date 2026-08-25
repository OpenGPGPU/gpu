package gpu.graphics

import chisel3._
import chisel3.util._
import gpu.config.GpuConfig
import gpu.core.memory.{ComputeMemoryRequest, ComputeMemoryResponse}

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
    val colorBase = Input(UInt(32.W))
    val depthBase = Input(UInt(32.W))
    val stride = Input(UInt(32.W))
    val depthTestEnable = Input(Bool())
    val depthFunc = Input(UInt(3.W))
    val depthWriteEnable = Input(Bool())
    val cullMode = Input(UInt(2.W))
    val done = Output(Bool())
  })

  private val geo = Module(new GeometryStage(config))
  private val shader = Module(new RasterShader(config))
  private val om = Module(new OutputMerger(config))

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
    kernelFrag.io.fragIn.valid := shader.io.pixel.valid
    kernelFrag.io.fragIn.bits := shader.io.pixel.bits
    shader.io.pixel.ready := kernelFrag.io.fragIn.ready
    kernelFrag.io.shaderPc := io.draw.bits.shaderPc
    kernelFrag.io.kernargBase := io.draw.bits.shaderKernarg

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

    io.done := shader.io.done && kernelFrag.io.fragIn.ready && om.io.fragIn.ready
  } else {
    om.io.fragIn.valid := shader.io.pixel.valid
    om.io.fragIn.bits.x := shader.io.pixel.bits.x(15, 0).asUInt
    om.io.fragIn.bits.y := shader.io.pixel.bits.y(15, 0).asUInt
    om.io.fragIn.bits.color := Cat(
      shader.io.pixel.bits.color.r,
      shader.io.pixel.bits.color.g,
      shader.io.pixel.bits.color.b,
      0xff.U(8.W))
    om.io.fragIn.bits.depth := shader.io.pixel.bits.depth(29, 0).asUInt
    shader.io.pixel.ready := om.io.fragIn.ready

    io.kernelMemReq.valid := false.B
    io.kernelMemReq.bits := 0.U.asTypeOf(io.kernelMemReq.bits)
    io.kernelMemResp.ready := false.B
    io.kernelWordMemReq.valid := false.B
    io.kernelWordMemReq.bits := 0.U.asTypeOf(io.kernelWordMemReq.bits)
    io.kernelWordMemResp.ready := false.B
    io.done := shader.io.done && om.io.fragIn.ready
  }
}
