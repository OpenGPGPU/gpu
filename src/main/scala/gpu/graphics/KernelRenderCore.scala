package gpu.graphics

import chisel3._
import chisel3.util._
import gpu.config.GpuConfig
import gpu.core.memory.{ComputeMemoryRequest, ComputeMemoryResponse}

/** Top-level command-driven renderer with core-backed shading (Phase D).
  *
  * This is `RenderCore` with the fixed-function `ShadedPipeline` replaced by the
  * core-backed `KernelShadedPipeline`: CommandBufferStage reads draw-call records
  * (carrying a shader entry PC + kernarg address), GeometryStage maps clip-space
  * vertices to the screen, every fragment is shaded by a compiled RV32 kernel
  * launched on the compute unit, and the OM depth-tests and writes the colour
  * and depth buffers.
  *
  * The command ring, framebuffer and the kernel's program/kernarg all live in
  * one line-based physical memory, served through four ports the enclosing SoC
  * must arbitrate: `cbMem` (command reads), `fbMem` (OM RMW), `kernelMemReq/Resp`
  * (the compute unit) and `kernelWordMemReq/Resp` (the word->line bridge).
  */
class KernelRenderCore(
  config: GpuConfig = GpuConfig(),
  gfxConfig: GraphicsConfig = GraphicsConfig()
) extends Module {
  val io = IO(new Bundle {
    val cmdBase = Input(UInt(32.W))
    val cmdCount = Input(UInt(16.W))
    val start = Input(Bool())
    val cbMem = new Bundle {
      val req = Decoupled(new OmMemoryRequest)
      val resp = Flipped(Decoupled(new OmMemoryResponse))
    }
    val fbMem = new Bundle {
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
    val cullMode = Input(UInt(2.W))
    val done = Output(Bool())
  })

  private val cb = Module(new CommandBufferStage(gfxConfig))
  private val geo = Module(new GeometryStage(gfxConfig))
  private val rp = Module(new KernelShadedPipeline(config, gfxConfig))

  cb.io.base := io.cmdBase
  cb.io.count := io.cmdCount
  cb.io.start := io.start
  io.cbMem <> cb.io.mem

  // Geometry: clip-space draw -> fixed-point screen-space triangle.
  geo.io.clip := cb.io.draw.bits.clip
  geo.io.color := cb.io.draw.bits.color
  geo.io.screenW := gfxConfig.screenWidth.U
  geo.io.screenH := gfxConfig.screenHeight.U

  // Core-backed shaded pipeline consumes the screen-space triangle.
  rp.io.draw.valid := cb.io.draw.valid
  rp.io.draw.bits.v0.x := geo.io.out(0).sx(31, 0).asSInt
  rp.io.draw.bits.v0.y := geo.io.out(0).sy(31, 0).asSInt
  rp.io.draw.bits.v1.x := geo.io.out(1).sx(31, 0).asSInt
  rp.io.draw.bits.v1.y := geo.io.out(1).sy(31, 0).asSInt
  rp.io.draw.bits.v2.x := geo.io.out(2).sx(31, 0).asSInt
  rp.io.draw.bits.v2.y := geo.io.out(2).sy(31, 0).asSInt
  cb.io.draw.ready := rp.io.draw.ready
  rp.io.colors := cb.io.draw.bits.color
  rp.io.depths := cb.io.draw.bits.depth
  rp.io.shaderPc := cb.io.draw.bits.shaderPc
  rp.io.kernargBase := cb.io.draw.bits.shaderKernarg
  rp.io.cullMode := io.cullMode
  rp.io.colorBase := io.colorBase
  rp.io.depthBase := io.depthBase
  rp.io.stride := io.stride
  rp.io.depthTestEnable := io.depthTestEnable
  rp.io.depthFunc := io.depthFunc
  rp.io.depthWriteEnable := io.depthWriteEnable
  io.fbMem <> rp.io.mem
  io.kernelMemReq <> rp.io.kernelMemReq
  rp.io.kernelMemResp <> io.kernelMemResp
  io.kernelWordMemReq <> rp.io.kernelWordMemReq
  rp.io.kernelWordMemResp <> io.kernelWordMemResp

  io.done := cb.io.done && rp.io.done
}
