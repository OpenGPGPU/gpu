package gpu.graphics

import chisel3._
import chisel3.util._

/** Per-vertex attributes handed to the fragment stage.  A fragment shader that
  * later runs on the SIMT lanes consumes interpolated values of these as its
  * inputs; for now (M3a) we interpolate an 8-bit per-channel colour.
  */
class Varyings extends Bundle {
  val r = UInt(8.W)
  val g = UInt(8.W)
  val b = UInt(8.W)
}

/** Barycentric attribute interpolation.
  *
  * Each screen-space channel is interpolated with the barycentric weights
  * derived from the rasterizer's edge values:
  *
  *   attr = (e0*attr0 + e1*attr1 + e2*attr2) / area
  *
  * where area = e0 + e1 + e2 is twice the signed triangle area.  This is the
  * fixed-function interpolation stage between the rasterizer and the
  * programmable fragment shader.
  */
class FragmentInterpolator(config: GraphicsConfig) extends Module {
  val io = IO(new Bundle {
    val c0 = Input(new Varyings)
    val c1 = Input(new Varyings)
    val c2 = Input(new Varyings)
    val e0 = Input(SInt(config.edgeWidth.W))
    val e1 = Input(SInt(config.edgeWidth.W))
    val e2 = Input(SInt(config.edgeWidth.W))
    val area = Input(SInt(config.edgeWidth.W))
    val color = Output(new Varyings)
  })

  private def interp(u0: UInt, u1: UInt, u2: UInt): UInt = {
    val num = io.e0 * u0 + io.e1 * u1 + io.e2 * u2
    (num / io.area)(7, 0)
  }

  io.color.r := interp(io.c0.r, io.c1.r, io.c2.r)
  io.color.g := interp(io.c0.g, io.c1.g, io.c2.g)
  io.color.b := interp(io.c0.b, io.c1.b, io.c2.b)
}

/** A shaded fragment: screen position plus interpolated colour. */
class RasterFragment(config: GraphicsConfig) extends Bundle {
  val x = SInt(config.coordWidth.W)
  val y = SInt(config.coordWidth.W)
  val color = new Varyings
}

/** Shades a rasterized triangle into fixed-point screen fragments.
  *
  * This is a combinator->registered pipeline that, given one triangle, emits
  * every covered pixel's barycentrically-interpolated colour together with its
  * screen position.  It is the minimal reproducible front-end of the fragment
  * stage; a later milestone moves the per-pixel maths onto the SIMT shader
  * lanes.
  */
class RasterShader(config: GraphicsConfig) extends Module {
  val io = IO(new Bundle {
    val draw = Flipped(Decoupled(new TriangleVertices(config)))
    val colors = Input(Vec(3, new Varyings))
    val cullMode = Input(UInt(2.W))
    val done = Output(Bool())
    val pixel = Decoupled(new RasterFragment(config))
  })

  private val raster = Module(new TriangleRasterizer(config))
  private val interp = Module(new FragmentInterpolator(config))

  raster.io.draw <> io.draw
  raster.io.cullMode := io.cullMode
  interp.io.c0 := io.colors(0)
  interp.io.c1 := io.colors(1)
  interp.io.c2 := io.colors(2)
  interp.io.e0 := raster.io.pixel.bits.e0
  interp.io.e1 := raster.io.pixel.bits.e1
  interp.io.e2 := raster.io.pixel.bits.e2
  interp.io.area := raster.io.pixel.bits.area

  io.pixel.valid := raster.io.pixel.valid
  io.pixel.bits.x := raster.io.pixel.bits.x
  io.pixel.bits.y := raster.io.pixel.bits.y
  io.pixel.bits.color := interp.io.color
  raster.io.pixel.ready := io.pixel.ready
  io.done := raster.io.draw.ready
}
