package opengpu.graphics

import chisel3._
import chisel3.util._

/** Rasterize -> SIMT-shade -> output-merge for one screen-space triangle.
  *
  * This is RenderPipeline with the ShaderFragStage inserted between the
  * raster/interpolate stage and the output merger, so the fragment colour is
  * computed by a runtime-supplied SIMT shader program instead of the fixed
  * function path.  The shader program and uniform bank are inputs the driver
  * provides (in M6 these come from the command buffer / MMIO).
  */
class ShadedPipeline(config: GraphicsConfig, progSize: Int = 8) extends Module {
  val io = IO(new Bundle {
    val draw = Flipped(Decoupled(new TriangleVertices(config)))
    val colors = Input(Vec(3, new Varyings))
    val depths = Input(Vec(3, SInt(32.W)))
    val cullMode = Input(UInt(2.W))
    val prog = Input(Vec(progSize, new ShaderOp))
    val programBase = Input(UInt(8.W))
    val uniform = Input(Vec(16, SInt(32.W)))
    val mem = new Bundle {
      val req = Decoupled(new OmMemoryRequest)
      val resp = Flipped(Decoupled(new OmMemoryResponse))
    }
    val colorBase = Input(UInt(32.W))
    val depthBase = Input(UInt(32.W))
    val stride = Input(UInt(32.W))
    val depthTestEnable = Input(Bool())
    val depthFunc = Input(UInt(3.W))
    val depthWriteEnable = Input(Bool())
    val done = Output(Bool())
  })

  private val shader = Module(new RasterShader(config))
  private val fragShader = Module(new ShaderFragStage(config, lanes = 1, progSize))
  private val om = Module(new OutputMerger(config))

  shader.io.draw <> io.draw
  shader.io.colors := io.colors
  shader.io.depths := io.depths
  shader.io.cullMode := io.cullMode

  fragShader.io.fragIn.valid := shader.io.pixel.valid
  fragShader.io.fragIn.bits := shader.io.pixel.bits
  shader.io.pixel.ready := fragShader.io.fragIn.ready
  fragShader.io.prog := io.prog
  fragShader.io.programBase := io.programBase
  fragShader.io.uniform := io.uniform

  om.io.fragIn.valid := fragShader.io.out.valid
  om.io.fragIn.bits.x := fragShader.io.out.bits.x(15, 0).asUInt
  om.io.fragIn.bits.y := fragShader.io.out.bits.y(15, 0).asUInt
  om.io.fragIn.bits.color := Cat(
    fragShader.io.out.bits.color.r,
    fragShader.io.out.bits.color.g,
    fragShader.io.out.bits.color.b,
    0xff.U(8.W)
  )
  om.io.fragIn.bits.depth := fragShader.io.out.bits.depth(29, 0).asUInt
  fragShader.io.out.ready := om.io.fragIn.ready

  om.io.colorBase := io.colorBase
  om.io.depthBase := io.depthBase
  om.io.stride := io.stride
  om.io.depthTestEnable := io.depthTestEnable
  om.io.depthFunc := io.depthFunc
  om.io.depthWriteEnable := io.depthWriteEnable
  om.io.blendEnable := false.B
  om.io.mem.req <> io.mem.req
  om.io.mem.resp <> io.mem.resp

  io.done := shader.io.done && fragShader.io.fragIn.ready && om.io.fragIn.ready
}
