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

/** Top-level command-driven renderer.
  *
  * Wires CommandBufferStage (reads draw-call records from host memory) into
  * RenderPipeline (geometry viewport -> rasterize -> shade -> depth test ->
  * write colour/depth buffers).  The command buffer and the framebuffer use
  * two separate memory ports, mirroring a real integrated SoC where the
  * command ring and the render targets are distinct traffic.
  *
  * `fragCore` selects the shading backend (passed to `RenderPipeline`).  With
  * `fragCore = true` fragments are shaded by a compiled RV32 kernel launched on
  * the compute unit's SIMT warps (the phase-D unified-shader path); the
  * kernel's program/kernarg/output live in the line-based memory behind
  * `kernelMemReq/Resp` and `kernelWordMemReq/Resp`.  With `fragCore = false`
  * the fixed-function interpolated colour is used and those ports are idle.
  *
  * A `start` pulse makes the command stage read `cmdCount` records starting at
  * `cmdBase` and, for each, render one triangle; `done` goes high once every
  * record has been consumed and the last read-modify-write has retired.  In
  * vertex-core mode one record references a vertex buffer and may produce
  * multiple triangles.
  */
class RenderCore(
  config: GraphicsConfig = GraphicsConfig(),
  gpuConfig: GpuConfig = GpuConfig(),
  fragCore: Boolean = false,
  vertCore: Boolean = false
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
    val kernelMemReq = Decoupled(new ComputeMemoryRequest(gpuConfig))
    val kernelMemResp = Flipped(Decoupled(new ComputeMemoryResponse()))
    val kernelWordMemReq = Decoupled(new ComputeMemoryRequest(gpuConfig, 64, 8))
    val kernelWordMemResp = Flipped(Decoupled(new ComputeMemoryResponse(64, 8)))
    val kernelL1Invalidate = Flipped(Decoupled(new CacheLineInvalidate(gpuConfig)))
    val kernelL1InvalidateDone = Decoupled(new CacheLineInvalidate(gpuConfig))
    val kernelGlobalAtomicRequest = Decoupled(new SharedAtomicRequest(gpuConfig))
    val kernelGlobalAtomicResponse = Flipped(Decoupled(new SharedAtomicResponse(gpuConfig)))
    val colorBase = Input(UInt(32.W))
    val depthBase = Input(UInt(32.W))
    val stride = Input(UInt(32.W))
    /** Texture sampling (forwarded to the fixed-function fragment stage). */
    val texEnable = Input(Bool())
    val texBase = Input(UInt(32.W))
    val texWidth = Input(UInt(14.W))
    val texHeight = Input(UInt(14.W))
    val texWrapClamp = Input(Bool())
    val texMaxLevel = Input(UInt(4.W))
    val depthTestEnable = Input(Bool())
    val depthFunc = Input(UInt(3.W))
    val depthWriteEnable = Input(Bool())
    val cullMode = Input(UInt(2.W))
    /** Sampler word port (separate client in the fabric arbitration). */
    val texMem = new Bundle {
      val req = Decoupled(new OmMemoryRequest)
      val resp = Flipped(Decoupled(new OmMemoryResponse))
    }
    val done = Output(Bool())
  })

  // The command payload type must follow the selected front end.  In vertex
  // core mode words 0..6 describe a vertex buffer and vertex shader rather
  // than containing three inline clip-space vertices.
  private val cb = Module(new CommandBufferStage(config, vertCore))
  private val rp = Module(new RenderPipeline(config, gpuConfig, fragCore, vertCore))
  // RenderHost snapshots the legacy registers and queue ownership on the
  // same edge that it presents start.  Delay the parser start one cycle so
  // the selected command configuration is visible at the parser boundary.
  private val startDelay = RegInit(false.B)
  startDelay := io.start

  cb.io.base := io.cmdBase
  cb.io.count := io.cmdCount
  cb.io.start := startDelay
  cb.io.mem.req <> io.cbMem.req
  cb.io.mem.resp <> io.cbMem.resp

  cb.io.draw <> rp.io.draw

  rp.io.colorBase := io.colorBase
  rp.io.depthBase := io.depthBase
  rp.io.stride := io.stride
  rp.io.depthTestEnable := io.depthTestEnable
  rp.io.depthFunc := io.depthFunc
  rp.io.depthWriteEnable := io.depthWriteEnable
  rp.io.cullMode := io.cullMode
  rp.io.texEnable := io.texEnable
  rp.io.texBase := io.texBase
  rp.io.texWidth := io.texWidth
  rp.io.texHeight := io.texHeight
  rp.io.texWrapClamp := io.texWrapClamp
  rp.io.texMaxLevel := io.texMaxLevel
  rp.io.mem.req <> io.fbMem.req
  rp.io.mem.resp <> io.fbMem.resp
  rp.io.texMem <> io.texMem

  io.kernelMemReq <> rp.io.kernelMemReq
  rp.io.kernelMemResp <> io.kernelMemResp
  io.kernelWordMemReq <> rp.io.kernelWordMemReq
  rp.io.kernelWordMemResp <> io.kernelWordMemResp
  rp.io.kernelL1Invalidate <> io.kernelL1Invalidate
  io.kernelL1InvalidateDone <> rp.io.kernelL1InvalidateDone
  io.kernelGlobalAtomicRequest <> rp.io.kernelGlobalAtomicRequest
  rp.io.kernelGlobalAtomicResponse <> io.kernelGlobalAtomicResponse

  // Done once every command has been consumed and the render pipeline is idle.
  io.done := cb.io.done && rp.io.done
}
