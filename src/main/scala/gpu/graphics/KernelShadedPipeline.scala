package gpu.graphics

import chisel3._
import chisel3.util._
import gpu.config.GpuConfig
import gpu.core.memory.{ComputeMemoryRequest, ComputeMemoryResponse}

/** Rasterize -> core-kernel-shade -> output-merge for one screen-space triangle.
  *
  * This is `ShadedPipeline` with the fixed-function `ShaderFragStage` replaced by
  * the core-backed `KernelFragStage` (the phase-D unified-shader path): the
  * fragment colour is computed by a compiled RV32 kernel launched on the compute
  * unit's SIMT warps, reading the draw's varyings/uniforms from its kernarg
  * buffer and writing its output back.  The shader descriptor (entry PC + kernarg
  * address) comes from the draw's `shaderPc`/`shaderKernarg`.
  *
  * Memory: the shared line-based memory behind this stage serves three clients
  * that the enclosing system must arbitrate onto one physical memory —
  *   `kernelMemReq/Resp` (the compute unit),
  *   `kernelWordMemReq/Resp` (the word->line bridge staging kernarg),
  *   `mem` (the Output Merger's word framebuffer/depth traffic).
  */
class KernelShadedPipeline(
  config: GpuConfig = GpuConfig(),
  gfxConfig: GraphicsConfig = GraphicsConfig()
) extends Module {
  val io = IO(new Bundle {
    val draw = Flipped(Decoupled(new TriangleVertices(gfxConfig)))
    val colors = Input(Vec(3, new Varyings))
    val depths = Input(Vec(3, SInt(32.W)))
    val cullMode = Input(UInt(2.W))
    val shaderPc = Input(UInt(32.W))
    val kernargBase = Input(UInt(32.W))
    val mem = new Bundle {
      val req = Decoupled(new OmMemoryRequest)
      val resp = Flipped(Decoupled(new OmMemoryResponse))
    }
    val kernelMemReq = Decoupled(new ComputeMemoryRequest(config))
    val kernelMemResp = Flipped(Decoupled(new ComputeMemoryResponse()))
    val kernelWordMemReq = Decoupled(new ComputeMemoryRequest(config))
    val kernelWordMemResp = Flipped(Decoupled(new ComputeMemoryResponse()))
    val colorBase = Input(UInt(32.W))
    val depthBase = Input(UInt(32.W))
    val stride = Input(UInt(32.W))
    val depthTestEnable = Input(Bool())
    val depthFunc = Input(UInt(3.W))
    val depthWriteEnable = Input(Bool())
    val done = Output(Bool())
  })

  private val shader = Module(new RasterShader(gfxConfig))
  private val kernelFrag = Module(new KernelFragStage(config, gfxConfig))
  private val om = Module(new OutputMerger(gfxConfig))

  shader.io.draw <> io.draw
  shader.io.colors := io.colors
  shader.io.depths := io.depths
  shader.io.cullMode := io.cullMode

  kernelFrag.io.fragIn.valid := shader.io.pixel.valid
  kernelFrag.io.fragIn.bits := shader.io.pixel.bits
  shader.io.pixel.ready := kernelFrag.io.fragIn.ready
  kernelFrag.io.shaderPc := io.shaderPc
  kernelFrag.io.kernargBase := io.kernargBase

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

  om.io.colorBase := io.colorBase
  om.io.depthBase := io.depthBase
  om.io.stride := io.stride
  om.io.depthTestEnable := io.depthTestEnable
  om.io.depthFunc := io.depthFunc
  om.io.depthWriteEnable := io.depthWriteEnable
  om.io.blendEnable := false.B
  io.mem <> om.io.mem

  io.kernelMemReq <> kernelFrag.io.memReq
  kernelFrag.io.memResp <> io.kernelMemResp
  io.kernelWordMemReq <> kernelFrag.io.wordMemReq
  kernelFrag.io.wordMemResp <> io.kernelWordMemResp

  io.done := shader.io.done && kernelFrag.io.fragIn.ready && om.io.fragIn.ready
}
