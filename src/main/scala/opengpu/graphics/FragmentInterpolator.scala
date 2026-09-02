package opengpu.graphics

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
    val d0 = Input(SInt(32.W))
    val d1 = Input(SInt(32.W))
    val d2 = Input(SInt(32.W))
    val e0 = Input(SInt(config.edgeWidth.W))
    val e1 = Input(SInt(config.edgeWidth.W))
    val e2 = Input(SInt(config.edgeWidth.W))
    val area = Input(SInt(config.edgeWidth.W))
    val color = Output(new Varyings)
    val depth = Output(SInt(32.W))
  })

  private def interp(u0: UInt, u1: UInt, u2: UInt): UInt = {
    val num = io.e0 * u0 + io.e1 * u1 + io.e2 * u2
    (num / io.area)(7, 0)
  }

  private def interpS(s0: SInt, s1: SInt, s2: SInt): SInt = {
    val num = io.e0 * s0 + io.e1 * s1 + io.e2 * s2
    (num / io.area)(31, 0).asSInt
  }

  io.color.r := interp(io.c0.r, io.c1.r, io.c2.r)
  io.color.g := interp(io.c0.g, io.c1.g, io.c2.g)
  io.color.b := interp(io.c0.b, io.c1.b, io.c2.b)
  io.depth := interpS(io.d0, io.d1, io.d2)
}

/** A shaded fragment: screen position, interpolated colour, depth, and the
  * raw barycentric edge values of its source pixel (consumers that need a
  * second interpolation pass -- e.g. texture coordinates -- reuse them
  * instead of re-deriving; texture-disabled consumers ignore them). */
class RasterFragment(config: GraphicsConfig) extends Bundle {
  val x = SInt(config.coordWidth.W)
  val y = SInt(config.coordWidth.W)
  val color = new Varyings
  /** Alpha is kept separately because legacy vertex varyings are RGB-only. */
  val alpha = UInt(8.W)
  val depth = SInt(32.W)
  val e0 = SInt(config.edgeWidth.W)
  val e1 = SInt(config.edgeWidth.W)
  val e2 = SInt(config.edgeWidth.W)
  val covered = Bool()
}

/** A shaded 2x2 quad emitted in one beat: lane 0=TL, 1=TR, 2=BL, 3=BR.  The
  * per-lane `covered` flags are the helper-lane mask consumed by the
  * fragment dispatcher. */
class FragmentQuad(config: GraphicsConfig) extends Bundle {
  val lanes = Vec(4, new RasterFragment(config))
}

/** Shades a rasterized triangle into fixed-point screen fragments.
  *
  * This is a combinator->registered pipeline that, given one triangle, emits
  * every covered pixel's barycentrically-interpolated colour and depth together
  * with its screen position on `pixel`.
  *
  * With `quadMode` the rasterizer's whole-quad stream is shaded instead: four
  * interpolators (one per lane) produce a complete TL/TR/BL/BR
  * [[FragmentQuad]] per cycle on `quad`, helper lanes included, for the
  * core-backed fragment dispatcher.  The unused port of each configuration is
  * tied off.
  */
class RasterShader(config: GraphicsConfig, quadMode: Boolean = false) extends Module {
  val io = IO(new Bundle {
    val draw = Flipped(Decoupled(new TriangleVertices(config)))
    val colors = Input(Vec(3, new Varyings))
    val depths = Input(Vec(3, SInt(32.W)))
    val cullMode = Input(UInt(2.W))
    val done = Output(Bool())
    val pixel = Decoupled(new RasterFragment(config))
    val quad = Decoupled(new FragmentQuad(config))
  })

  private val raster = Module(new TriangleRasterizer(config, quadMode))

  raster.io.draw <> io.draw
  raster.io.cullMode := io.cullMode
  io.done := raster.io.draw.ready

  if (quadMode) {
    val laneInterps = Seq.fill(4)(Module(new FragmentInterpolator(config)))
    for (k <- 0 until 4) {
      laneInterps(k).io.c0 := io.colors(0)
      laneInterps(k).io.c1 := io.colors(1)
      laneInterps(k).io.c2 := io.colors(2)
      laneInterps(k).io.d0 := io.depths(0)
      laneInterps(k).io.d1 := io.depths(1)
      laneInterps(k).io.d2 := io.depths(2)
      laneInterps(k).io.e0 := raster.io.quad.bits.lanes(k).e0
      laneInterps(k).io.e1 := raster.io.quad.bits.lanes(k).e1
      laneInterps(k).io.e2 := raster.io.quad.bits.lanes(k).e2
      laneInterps(k).io.area := raster.io.quad.bits.lanes(k).area

      io.quad.bits.lanes(k).x := raster.io.quad.bits.lanes(k).x
      io.quad.bits.lanes(k).y := raster.io.quad.bits.lanes(k).y
      io.quad.bits.lanes(k).color := laneInterps(k).io.color
      io.quad.bits.lanes(k).alpha := 0xff.U
      io.quad.bits.lanes(k).depth := laneInterps(k).io.depth
      io.quad.bits.lanes(k).e0 := raster.io.quad.bits.lanes(k).e0
      io.quad.bits.lanes(k).e1 := raster.io.quad.bits.lanes(k).e1
      io.quad.bits.lanes(k).e2 := raster.io.quad.bits.lanes(k).e2
      io.quad.bits.lanes(k).covered := raster.io.quad.bits.lanes(k).covered
    }
    io.quad.valid := raster.io.quad.valid
    raster.io.quad.ready := io.quad.ready

    io.pixel.valid := false.B
    io.pixel.bits := 0.U.asTypeOf(new RasterFragment(config))
    raster.io.pixel.ready := false.B
  } else {
    val interp = Module(new FragmentInterpolator(config))
    interp.io.c0 := io.colors(0)
    interp.io.c1 := io.colors(1)
    interp.io.c2 := io.colors(2)
    interp.io.d0 := io.depths(0)
    interp.io.d1 := io.depths(1)
    interp.io.d2 := io.depths(2)
    interp.io.e0 := raster.io.pixel.bits.e0
    interp.io.e1 := raster.io.pixel.bits.e1
    interp.io.e2 := raster.io.pixel.bits.e2
    interp.io.area := raster.io.pixel.bits.area

    io.pixel.valid := raster.io.pixel.valid
    io.pixel.bits.x := raster.io.pixel.bits.x
    io.pixel.bits.y := raster.io.pixel.bits.y
    io.pixel.bits.color := interp.io.color
    io.pixel.bits.alpha := 0xff.U
    io.pixel.bits.depth := interp.io.depth
    io.pixel.bits.e0 := raster.io.pixel.bits.e0
    io.pixel.bits.e1 := raster.io.pixel.bits.e1
    io.pixel.bits.e2 := raster.io.pixel.bits.e2
    io.pixel.bits.covered := raster.io.pixel.bits.covered
    raster.io.pixel.ready := io.pixel.ready

    io.quad.valid := false.B
    io.quad.bits := 0.U.asTypeOf(new FragmentQuad(config))
    raster.io.quad.ready := false.B
  }
}
