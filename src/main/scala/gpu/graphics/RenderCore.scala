package gpu.graphics

import chisel3._
import chisel3.util._

/** Top-level command-driven renderer.
  *
  * Wires CommandBufferStage (reads draw-call records from host memory) into
  * RenderPipeline (geometry viewport -> rasterize -> shade -> depth test ->
  * write colour/depth buffers).  The command buffer and the framebuffer use
  * two separate memory ports, mirroring a real integrated SoC where the
  * command ring and the render targets are distinct traffic.
  *
  * A `start` pulse makes the command stage read `cmdCount` records starting at
  * `cmdBase` and, for each, render one triangle; `done` goes high once every
  * record has been consumed and the last read-modify-write has retired.
  */
class RenderCore(config: GraphicsConfig) extends Module {
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
    val colorBase = Input(UInt(32.W))
    val depthBase = Input(UInt(32.W))
    val stride = Input(UInt(32.W))
    val depthTestEnable = Input(Bool())
    val depthFunc = Input(UInt(3.W))
    val depthWriteEnable = Input(Bool())
    val cullMode = Input(UInt(2.W))
    val done = Output(Bool())
  })

  private val cb = Module(new CommandBufferStage(config))
  private val rp = Module(new RenderPipeline(config))

  cb.io.base := io.cmdBase
  cb.io.count := io.cmdCount
  cb.io.start := io.start
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
  rp.io.mem.req <> io.fbMem.req
  rp.io.mem.resp <> io.fbMem.resp

  // Done once every command has been consumed and the render pipeline is idle.
  io.done := cb.io.done && rp.io.done
}
