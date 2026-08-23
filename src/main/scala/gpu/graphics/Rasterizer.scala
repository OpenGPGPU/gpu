package gpu.graphics

import chisel3._
import chisel3.util._

/** Fixed-point rasterization parameters.
  *
  * Vertex and pixel coordinates are carried as signed fixed-point values
  * scaled by 2^subPixelBits.  The fractional bits are what let the edge
  * functions disambiguate pixels that straddle a triangle edge, matching the
  * sub-pixel precision used by typical hardware rasterizers.
  */
case class GraphicsConfig(
  screenWidth: Int = 128,
  screenHeight: Int = 128,
  subPixelBits: Int = 8
) {
  def coordWidth: Int = 32
  def edgeWidth: Int = 64

  /** Convert an integer screen coordinate into fixed-point representation. */
  def toFixed(x: Int): Int = x << subPixelBits
}

/** A 2D point in signed fixed-point screen space. */
class RasterPoint(config: GraphicsConfig) extends Bundle {
  val x = SInt(config.coordWidth.W)
  val y = SInt(config.coordWidth.W)
}

/** Three triangle vertices in fixed-point screen space. */
class TriangleVertices(config: GraphicsConfig) extends Bundle {
  val v0 = new RasterPoint(config)
  val v1 = new RasterPoint(config)
  val v2 = new RasterPoint(config)
}

/** Twice-the-signed-area / barycentric plane coefficients for one triangle.
  *
  * Each edge is stored as an implicit line A*x + B*y + C so the edge value at
  * any sample point is a single fused multiply-add.  This is the same
  * representation used by VX_raster_edge and is what lets a rasterizer advance
  * edge values incrementally across a bounding box.
  */
class TriangleEdgeSet(config: GraphicsConfig) extends Bundle {
  val a0 = SInt(config.edgeWidth.W)
  val b0 = SInt(config.edgeWidth.W)
  val c0 = SInt(config.edgeWidth.W)
  val a1 = SInt(config.edgeWidth.W)
  val b1 = SInt(config.edgeWidth.W)
  val c1 = SInt(config.edgeWidth.W)
  val a2 = SInt(config.edgeWidth.W)
  val b2 = SInt(config.edgeWidth.W)
  val c2 = SInt(config.edgeWidth.W)
  val area = SInt(config.edgeWidth.W)
}

/** A rasterized pixel: position plus the three evaluated edge values and the
  * signed twice-the-triangle-area.  Normalized barycentric coordinates are
  * derived downstream as edge_k / area.
  */
class RasterPixel(config: GraphicsConfig) extends Bundle {
  val x = SInt(config.coordWidth.W)
  val y = SInt(config.coordWidth.W)
  val e0 = SInt(config.edgeWidth.W)
  val e1 = SInt(config.edgeWidth.W)
  val e2 = SInt(config.edgeWidth.W)
  val area = SInt(config.edgeWidth.W)
}

/** Signed 64-bit helpers.  All arithmetic is carried in 64-bit signed
  * domain so the fixed-point vertex differences and plane-equation products
  * cannot wrap for the supported screen sizes.
  */
private[graphics] object FixedPointMath {
  def add(a: SInt, b: SInt): SInt = (a.pad(64) + b.pad(64))(63, 0).asSInt
  def sub(a: SInt, b: SInt): SInt = (a.pad(64) - b.pad(64))(63, 0).asSInt
  def mul(a: SInt, b: SInt): SInt = (a.pad(64) * b.pad(64))(63, 0).asSInt
}

/** Turn three vertices into the per-triangle edge plane coefficients. */
class TriangleEdgeSetup(config: GraphicsConfig) extends Module {
  import FixedPointMath._

  val io = IO(new Bundle {
    val vertices = Input(new TriangleVertices(config))
    val edges = Output(new TriangleEdgeSet(config))
  })

  private def line(a: RasterPoint, b: RasterPoint): (SInt, SInt, SInt) = {
    val aa = sub(a.y, b.y)
    val bb = sub(b.x, a.x)
    val cc = sub(mul(a.x, b.y), mul(b.x, a.y))
    (aa, bb, cc)
  }

  // Edge 0 is opposite v0 (through v1,v2); edge 1 opposite v1; edge 2 opposite v2.
  private val e0 = line(io.vertices.v1, io.vertices.v2)
  private val e1 = line(io.vertices.v2, io.vertices.v0)
  private val e2 = line(io.vertices.v0, io.vertices.v1)

  io.edges.a0 := e0._1
  io.edges.b0 := e0._2
  io.edges.c0 := e0._3
  io.edges.a1 := e1._1
  io.edges.b1 := e1._2
  io.edges.c1 := e1._3
  io.edges.a2 := e2._1
  io.edges.b2 := e2._2
  io.edges.c2 := e2._3

  // Twice the signed triangle area (evaluate the three lines at a vertex).
  private val area =
    add(add(mul(e0._1, io.vertices.v0.x), mul(e0._2, io.vertices.v0.y)), e0._3)
  io.edges.area := area
}

/** Combinational inside/edge-value evaluation for a single sample point.
  *
  * A point is covered when all three edge values agree in sign.  The winding
  * convention is not fixed: either a fully non-negative or fully non-positive
  * evaluation counts as inside, so culling is decided downstream.
  */
class TriangleCoverage(config: GraphicsConfig) extends Module {
  import FixedPointMath._

  val io = IO(new Bundle {
    val vertices = Input(new TriangleVertices(config))
    val pixel = Input(new RasterPoint(config))
    val inside = Output(Bool())
    val e0 = Output(SInt(config.edgeWidth.W))
    val e1 = Output(SInt(config.edgeWidth.W))
    val e2 = Output(SInt(config.edgeWidth.W))
    val area = Output(SInt(config.edgeWidth.W))
  })

  private val setup = Module(new TriangleEdgeSetup(config))
  setup.io.vertices := io.vertices

  private def eval(a: SInt, b: SInt, c: SInt, p: RasterPoint): SInt =
    add(add(mul(a, p.x), mul(b, p.y)), c)

  io.e0 := eval(setup.io.edges.a0, setup.io.edges.b0, setup.io.edges.c0, io.pixel)
  io.e1 := eval(setup.io.edges.a1, setup.io.edges.b1, setup.io.edges.c1, io.pixel)
  io.e2 := eval(setup.io.edges.a2, setup.io.edges.b2, setup.io.edges.c2, io.pixel)
  io.area := setup.io.edges.area

  private val allPos = io.e0 >= 0.S && io.e1 >= 0.S && io.e2 >= 0.S
  private val allNeg = io.e0 <= 0.S && io.e1 <= 0.S && io.e2 <= 0.S
  io.inside := allPos || allNeg
}

/** Bounding-box scanline rasterizer.
  *
  * On a valid draw it tightens the bounding box to the triangle and scans it
  * in x-then-y order, presenting one covered pixel per cycle on the decoupled
  * output.  Fully off-screen triangles emit nothing.
  */
class TriangleRasterizer(config: GraphicsConfig) extends Module {
  val io = IO(new Bundle {
    val draw = Flipped(Decoupled(new TriangleVertices(config)))
    val pixel = Decoupled(new RasterPixel(config))
  })

  private val coverage = Module(new TriangleCoverage(config))
  coverage.io.vertices := io.draw.bits

  private val active = RegInit(false.B)
  private val minX = Reg(UInt(16.W))
  private val minY = Reg(UInt(16.W))
  private val maxX = Reg(UInt(16.W))
  private val maxY = Reg(UInt(16.W))
  private val curX = Reg(UInt(16.W))
  private val curY = Reg(UInt(16.W))

  io.draw.ready := !active
  coverage.io.pixel.x := (curX << config.subPixelBits).asSInt
  coverage.io.pixel.y := (curY << config.subPixelBits).asSInt
  io.pixel.valid := active && coverage.io.inside
  io.pixel.bits.x := (curX << config.subPixelBits).asSInt
  io.pixel.bits.y := (curY << config.subPixelBits).asSInt
  io.pixel.bits.e0 := coverage.io.e0
  io.pixel.bits.e1 := coverage.io.e1
  io.pixel.bits.e2 := coverage.io.e2
  io.pixel.bits.area := coverage.io.area

  private val verts = io.draw.bits
  private def min3(a: SInt, b: SInt, c: SInt): SInt = Seq(a, b, c).reduce(_ min _)
  private def max3(a: SInt, b: SInt, c: SInt): SInt = Seq(a, b, c).reduce(_ max _)
  private val sMinX = min3(verts.v0.x, verts.v1.x, verts.v2.x)
  private val sMinY = min3(verts.v0.y, verts.v1.y, verts.v2.y)
  private val sMaxX = max3(verts.v0.x, verts.v1.x, verts.v2.x)
  private val sMaxY = max3(verts.v0.y, verts.v1.y, verts.v2.y)
  private val subMul: Int = 1 << config.subPixelBits
  private val sScreenX = (config.screenWidth * subMul).S
  private val sScreenY = (config.screenHeight * subMul).S

  private def clampPix(v: SInt, max: Int): UInt =
    Mux(v <= 0.S, 0.U, Mux(v >= (max * subMul).S, (max - 1).U, (v >> config.subPixelBits).asUInt))

  when(io.draw.fire) {
    active := true.B
    minX := clampPix(sMinX, config.screenWidth)
    minY := clampPix(sMinY, config.screenHeight)
    maxX := clampPix(sMaxX, config.screenWidth)
    maxY := clampPix(sMaxY, config.screenHeight)
    curX := clampPix(sMinX, config.screenWidth)
    curY := clampPix(sMinY, config.screenHeight)
  }.elsewhen(active) {
    when(!coverage.io.inside || io.pixel.fire) {
      when(curX < maxX) {
        curX := curX + 1.U
      }.otherwise {
        curX := minX
        when(curY < maxY) {
          curY := curY + 1.U
        }.otherwise {
          active := false.B
        }
      }
    }
  }
}
