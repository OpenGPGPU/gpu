package opengpu.graphics

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
  /** True for covered samples; false identifies a fragment-shader helper lane. */
  val covered = Bool()
}

/** Signed fixed-point helpers.
  *
  * `trim64` normalises any intermediate to the 64-bit interface width without
  * changing its value: narrow results are sign-extended (they are mathematically
  * exact for screen-bounded geometry), while results of 64+ bits are truncated
  * to their low 64 bits -- exactly the modular behaviour the engines have always
  * exposed on this interface.
  *
  * Crucially the ARITHMETIC itself stays at natural operand widths (no
  * pre-padding to 64 before every op), so synthesised multipliers stay
  * proportional to actual coordinate ranges instead of always 64x64 (M4b).
  */
private[graphics] object FixedPointMath {
  def trim64(x: SInt): SInt =
    if (x.getWidth >= 64) x(63, 0).asSInt else x.pad(64).asSInt

  def add(a: SInt, b: SInt): SInt = trim64(a + b)
  def sub(a: SInt, b: SInt): SInt = trim64(a - b)
  def mul(a: SInt, b: SInt): SInt = trim64(a * b)
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
  * Coverage uses the top-left fill rule so shared edges are drawn exactly
  * once, and is winding-independent: the edge values are flipped so the
  * triangle interior always lies on the positive side of each edge, then a
  * sample lands on an edge (E == 0) only if that edge is a top edge or a
  * left edge (standard A < 0 || (A == 0 && B < 0) test on the flipped
  * coefficient).
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

  // Twice-signed area decides orientation; positive => interior is E >= 0.
  val area = setup.io.edges.area
  val front = area >= 0.S

  private val e0 = eval(setup.io.edges.a0, setup.io.edges.b0, setup.io.edges.c0, io.pixel)
  private val e1 = eval(setup.io.edges.a1, setup.io.edges.b1, setup.io.edges.c1, io.pixel)
  private val e2 = eval(setup.io.edges.a2, setup.io.edges.b2, setup.io.edges.c2, io.pixel)
  io.e0 := e0
  io.e1 := e1
  io.e2 := e2
  io.area := area

  // Flip so interior is always on the positive side of each edge.
  private def fl(s: SInt): SInt = Mux(front, s, -s)

  private def edgeTopLeft(a: SInt, b: SInt): Bool =
    (fl(a) < 0.S) || (fl(a) === 0.S && fl(b) < 0.S)

  private def insideEdge(e: SInt, a: SInt, b: SInt): Bool = {
    val ef = fl(e)
    (ef > 0.S) || (ef === 0.S && edgeTopLeft(a, b))
  }

  io.inside :=
    insideEdge(e0, setup.io.edges.a0, setup.io.edges.b0) &&
    insideEdge(e1, setup.io.edges.a1, setup.io.edges.b1) &&
    insideEdge(e2, setup.io.edges.a2, setup.io.edges.b2)
}

/** Incremental 2x2-quad edge evaluation (M4b).
  *
  * Given each edge's value at the current quad origin plus the precomputed
  * one-pixel deltas (`dx = A << subPixelBits`, `dy = B << subPixelBits`),
  * evaluates all four lanes of the quad — top-left (base), top-right (+dx),
  * bottom-left (+dy), bottom-right (+dx+dy) — with pure additions instead of
  * re-evaluating `A*x + B*y + C` per pixel.  This kills the per-cycle 64-bit
  * multiply array of the M1/M3 rasterizer; modular addition distributes over
  * the plane equation, so the lane values are bit-exact against direct
  * evaluation even under 64-bit wraparound.
  *
  * The top-left fill-rule bias is a constant of the edge (it depends only on
  * the winding-flipped A/B coefficients), so it is hoisted out of the
  * per-cycle path entirely: setup computes three `topLeft(k)` booleans once
  * and coverage reduces to `sign-test(e) || (e == 0 && topLeft)`.
  */
class QuadCoverage(config: GraphicsConfig) extends Module {
  val io = IO(new Bundle {
    /** Edge values at the quad's top-left sample point. */
    val base = Vec(3, Input(SInt(config.edgeWidth.W)))
    /** Per-pixel deltas dx_k = A_k << subPixelBits, dy_k = B_k << subPixelBits. */
    val dx = Vec(3, Input(SInt(config.edgeWidth.W)))
    val dy = Vec(3, Input(SInt(config.edgeWidth.W)))
    /** Winding direction and hoisted top-left flags (per edge). */
    val front = Input(Bool())
    val topLeft = Vec(3, Input(Bool()))
    /** Lane k: 0=(x,y), 1=(x+1,y), 2=(x,y+1), 3=(x+1,y+1). */
    val inside = Output(Vec(4, Bool()))
    val e0 = Output(Vec(4, SInt(config.edgeWidth.W)))
    val e1 = Output(Vec(4, SInt(config.edgeWidth.W)))
    val e2 = Output(Vec(4, SInt(config.edgeWidth.W)))
  })

  import FixedPointMath._

  private def laneEdges(k: Int): Seq[SInt] = {
    val right = Seq.tabulate(3)(i => add(io.base(i), io.dx(i)))
    val down = Seq.tabulate(3)(i => add(io.base(i), io.dy(i)))
    k match {
      case 0 => io.base
      case 1 => right
      case 2 => down
      case _ => Seq.tabulate(3)(i => add(right(i), io.dy(i)))
    }
  }

  for (k <- 0 until 4) {
    val es = laneEdges(k)
    io.e0(k) := es(0)
    io.e1(k) := es(1)
    io.e2(k) := es(2)
    io.inside(k) :=
      (0 until 3).map { i =>
        // strictOk(e) <=> fl(e) > 0 where fl(e) = front ? e : -e: the sign
        // test on the winding-corrected edge value without materialising -e.
        val strictOk = Mux(io.front, es(i) > 0.S, es(i) < 0.S)
        strictOk || (es(i) === 0.S && io.topLeft(i))
      }.reduce(_ && _)
  }
}

/** Incremental-edge bounding-box rasterizer (M4b).
  *
  * Coverage-identical to the M3b engine -- same clamped bounding box,
  * same top-left fill rule, same strict x-then-y emission order and ready/
  * done contract -- but restructured into narrow pipeline stages so no cycle
  * chains a multiplier bank into a wide adder tree:
  *
  *   capture  : vertices + cull mode registered (compares only).
  *   stCoeffs : per-edge A/B vertex differences and C cross products at
  *              natural operand width; clamped bbox registered here so it
  *              reads the JUST-captured vertices.
  *   stSetup  : per-edge plane products against the box origin and the two
  *              area products registered (one narrow multiply each).
  *   stLoad   : origin edge values / signed area by pure adds off the
  *              registered products, hoisted top-left flags, cull and
  *              degenerate decision; engine registers load and scan starts.
  *   scanning : ONE add per edge per column step (one on row wrap), zero
  *              multiplies.
  *
  * Stage arithmetic never pads operands to 64 bits: coordinates are bounded
  * by screen<<subPixelBits, so every coefficient and plane value fits far
  * inside its stored width and truncation cannot lose information -- the
  * hardware stays bit-exact against the unbounded-integer software reference.
  * Modular addition distributes over the plane equation, so stepped values
  * equal direct evaluation even under wraparound.  Measured ASAP7 post-route:
  * this staged design closes 1 GHz (1011 MHz, +10.9 ps) where the M1
  * single-cycle 64x64 multiply bank reached only ~526 MHz.
  *
  * Four-lane 2x2 quad evaluation with pure additions is provided by
  * [[QuadCoverage]]. `quadMode` emits complete TL/TR/BL/BR groups including
  * uncovered helper lanes for fragment-core execution; the default mode keeps
  * the fixed-function covered-only scalar contract.
  */
class TriangleRasterizer(config: GraphicsConfig, quadMode: Boolean = false) extends Module {
  val io = IO(new Bundle {
    val draw = Flipped(Decoupled(new TriangleVertices(config)))
    val cullMode = Input(UInt(2.W))
    val pixel = Decoupled(new RasterPixel(config))
  })

  import FixedPointMath._

  // ---------------------------------------------------------------------
  // M4b incremental-edge rasterizer.
  //
  // Coverage-wise identical to the M3b engine (clamped bounding box, top-left
  // fill rule, strict x-then-y order, same ready/done contract), but split
  // into narrow pipeline stages instead of one fat per-cycle multiply cone:
  //
  //   capture  (on draw.fire): vertices + cull mode + clamped bbox
  //                            registered (compares/shifts only).
  //   stCoeffs               : per-edge A/B vertex differences and C cross
  //                            products at NATURAL operand width (six small
  //                            multiplies, nothing chained behind them).
  //   stSetup                : plane values at the box origin and the signed
  //                            area -- a handful of narrow multiplies against
  //                            REGISTERED coefficients -- plus hoisted
  //                            top-left flags and the cull/degenerate
  //                            decision; engine registers load here.
  //   scanning               : one add per edge per column step, one on row
  //                            wrap; ZERO multiplies.
  //
  // Stage arithmetic never pads operands to 64 bits: coordinates are bounded
  // by screen<<subPixelBits (<= 2^23 even off-screen, 32-bit input), so |A| <
  // 2^17 and every plane value fits far inside 64 bits -- truncating each
  // stored result to its declared width cannot lose information, keeping the
  // hardware bit-exact against the unbounded-integer software reference while
  // the multipliers stay small (the M1 baseline synthesised a single-cycle
  // bank of 13 64x64 arrays: 526 MHz, 233k cells).
  // ---------------------------------------------------------------------

  // Setup is three narrow stages: coefficients -> product terms -> load/scan
  // start, so no cycle chains a multiplier bank INTO a wide adder tree.
  private def stIdle = 0.U(2.W)
  private def stCoeffs = 1.U(2.W)
  private def stSetup = 2.U(2.W)
  private def stLoad = 3.U(2.W)
  private val setupState = RegInit(0.U(2.W))
  private val active = RegInit(false.B)

  io.draw.ready := setupState === stIdle && !active

  // Registered inputs shared by the setup stages.
  private val vHold = Reg(new TriangleVertices(config))
  private val cullReg = Reg(UInt(2.W))

  private class Coeffs extends Bundle {
    val a = Vec(3, SInt(34.W)) // y/x differences
    val b = Vec(3, SInt(34.W))
    val c = Vec(3, SInt(66.W)) // 32x32 cross-product pair differences
  }
  private val coeffReg = RegInit(0.U.asTypeOf(new Coeffs))

  // Stage-3 inputs: per-edge plane products against the box origin, plus the
  // two area products -- all natural-width results of ONE multiply each,
  // with no further arithmetic behind them (the wide summation happens one
  // stage later over register values).
  private class Products extends Bundle {
    val pax = Vec(3, SInt(59.W)) // a_k * xOrigin
    val pby = Vec(3, SInt(59.W)) // b_k * yOrigin
    val saA = SInt(67.W)         // a_0 * v0.x
    val saB = SInt(67.W)         // b_0 * v0.y
  }
  private val prodReg = RegInit(0.U.asTypeOf(new Products))

  // ---------------------------------------------------------------------
  // stCoeffs combinational (off registered vertices).
  // ---------------------------------------------------------------------
  private def lineN(a: RasterPoint, b: RasterPoint): (SInt, SInt, SInt) =
    ((a.y - b.y).asSInt, (b.x - a.x).asSInt,
      (a.x * b.y - b.x * a.y).asSInt)
  private val linesN =
    Seq(lineN(vHold.v1, vHold.v2), lineN(vHold.v2, vHold.v0),
      lineN(vHold.v0, vHold.v1))

  // ---------------------------------------------------------------------
  // Engine registers.
  // ---------------------------------------------------------------------
  private val minX = RegInit(0.U(16.W))
  private val minY = RegInit(0.U(16.W))
  private val maxX = RegInit(0.U(16.W))
  private val maxY = RegInit(0.U(16.W))
  private val curX = RegInit(0.U(16.W))
  private val curY = RegInit(0.U(16.W))

  private class Edges extends Bundle {
    val e = Vec(3, SInt(config.edgeWidth.W))
  }
  private val edgeReg = RegInit(0.U.asTypeOf(new Edges))
  private val dxReg = RegInit(0.U.asTypeOf(new Edges))
  private val dyReg = RegInit(0.U.asTypeOf(new Edges))
  private val rowStartReg = RegInit(0.U.asTypeOf(new Edges))
  private val areaReg = RegInit(0.S(config.edgeWidth.W))
  private val frontReg = RegInit(true.B)
  private val tlReg = Seq(RegInit(false.B), RegInit(false.B), RegInit(false.B))
  private val quadLane = RegInit(0.U(2.W))

  // Stepped candidates: single adds off the current registers.
  private val edgeNextCol = VecInit((0 until 3).map(i =>
    FixedPointMath.trim64(edgeReg.e(i) + dxReg.e(i))))
  private val edgeNextRow = VecInit((0 until 3).map(i =>
    FixedPointMath.trim64(rowStartReg.e(i) + dyReg.e(i))))

  // Coverage on the current sample using hoisted fill-rule flags:
  // strictOk <=> fl(e) > 0 without materialising -e.
  private def laneInside(es: Seq[SInt]): Bool =
    (0 until 3).map { i =>
      val strictOk = Mux(frontReg, es(i) > 0.S, es(i) < 0.S)
      strictOk || (es(i) === 0.S && tlReg(i))
    }.reduce(_ && _)
  private val curInside =
    laneInside(Seq(edgeReg.e(0), edgeReg.e(1), edgeReg.e(2)))

  private val quad = Module(new QuadCoverage(config))
  quad.io.base.zipWithIndex.foreach { case (e, i) => e := edgeReg.e(i) }
  quad.io.dx.zipWithIndex.foreach { case (e, i) => e := dxReg.e(i) }
  quad.io.dy.zipWithIndex.foreach { case (e, i) => e := dyReg.e(i) }
  quad.io.front := frontReg
  quad.io.topLeft.zipWithIndex.foreach { case (flag, i) => flag := tlReg(i) }

  private val quadX = curX + quadLane(0)
  private val quadY = curY + quadLane(1)
  private val quadInsideScreen =
    quadX < config.screenWidth.U && quadY < config.screenHeight.U
  private val selectedInside = quad.io.inside(quadLane) && quadInsideScreen
  private val selectedE = Seq(quad.io.e0(quadLane), quad.io.e1(quadLane),
    quad.io.e2(quadLane))

  io.pixel.valid := active && (if (quadMode) true.B else curInside)
  io.pixel.bits.x := (if (quadMode) quadX else curX).asSInt
  io.pixel.bits.y := (if (quadMode) quadY else curY).asSInt
  io.pixel.bits.e0 := (if (quadMode) selectedE(0) else edgeReg.e(0))
  io.pixel.bits.e1 := (if (quadMode) selectedE(1) else edgeReg.e(1))
  io.pixel.bits.e2 := (if (quadMode) selectedE(2) else edgeReg.e(2))
  io.pixel.bits.area := areaReg
  io.pixel.bits.covered := (if (quadMode) selectedInside else curInside)

  // ---------------------------------------------------------------------
  // Scan advance: column step or row wrap, both single-add per edge.
  // ---------------------------------------------------------------------
  if (quadMode) {
    when(active && io.pixel.fire) {
      when(quadLane =/= 3.U) {
        quadLane := quadLane + 1.U
      }.otherwise {
        quadLane := 0.U
        when(curX < maxX) {
          curX := curX + 2.U
          edgeReg.e.zipWithIndex.foreach { case (r, i) =>
            r := FixedPointMath.trim64(edgeReg.e(i) + (dxReg.e(i) << 1)) }
        }.otherwise {
          curX := minX
          when(curY < maxY) {
            curY := curY + 2.U
            edgeReg.e.zipWithIndex.foreach { case (r, i) =>
              r := FixedPointMath.trim64(rowStartReg.e(i) +
                (dyReg.e(i) << 1)) }
            rowStartReg.e.zipWithIndex.foreach { case (r, i) =>
              r := FixedPointMath.trim64(rowStartReg.e(i) +
                (dyReg.e(i) << 1)) }
          }.otherwise {
            active := false.B
          }
        }
      }
    }
  } else {
    when(active) {
      // Advance on a fire or a miss; never stall on an inside sample.
      when(!curInside || io.pixel.fire) {
        when(curX < maxX) {
          curX := curX + 1.U
          edgeReg.e.zipWithIndex.foreach { case (r, i) => r := edgeNextCol(i) }
        }.otherwise {
          curX := minX
          when(curY < maxY) {
            curY := curY + 1.U
            edgeReg.e.zipWithIndex.foreach { case (r, i) => r := edgeNextRow(i) }
            rowStartReg.e.zipWithIndex.foreach { case (r, i) =>
              r := edgeNextRow(i) }
          }.otherwise {
            active := false.B
          }
        }
      }
    }
  }

  // ---------------------------------------------------------------------
  // Setup pipeline control.
  // ---------------------------------------------------------------------
  private def clampPixN(vv: SInt, max: Int): UInt =
    Mux(vv <= 0.S, 0.U,
      Mux(vv >= (max << config.subPixelBits).S, (max - 1).U,
        vv.apply(config.subPixelBits + 15, config.subPixelBits).asUInt))

  private val bboxMinXW =
    Seq(vHold.v0.x, vHold.v1.x, vHold.v2.x).reduce(_ min _)
  private val bboxMinYW =
    Seq(vHold.v0.y, vHold.v1.y, vHold.v2.y).reduce(_ min _)
  private val bboxMaxXW =
    Seq(vHold.v0.x, vHold.v1.x, vHold.v2.x).reduce(_ max _)
  private val bboxMaxYW =
    Seq(vHold.v0.y, vHold.v1.y, vHold.v2.y).reduce(_ max _)

  when(io.draw.fire) {
    vHold := io.draw.bits
    cullReg := io.cullMode
    setupState := stCoeffs
  }

  when(setupState === stCoeffs) {
    setupState := stSetup
    // Clamp against the JUST-CAPTURED vertices (vHold registered last edge).
    minX := (if (quadMode) clampPixN(bboxMinXW, config.screenWidth) & "hfffe".U
      else clampPixN(bboxMinXW, config.screenWidth))
    minY := (if (quadMode) clampPixN(bboxMinYW, config.screenHeight) & "hfffe".U
      else clampPixN(bboxMinYW, config.screenHeight))
    maxX := (if (quadMode) clampPixN(bboxMaxXW, config.screenWidth) & "hfffe".U
      else clampPixN(bboxMaxXW, config.screenWidth))
    maxY := (if (quadMode) clampPixN(bboxMaxYW, config.screenHeight) & "hfffe".U
      else clampPixN(bboxMaxYW, config.screenHeight))
    coeffReg.a.zipWithIndex.foreach { case (r, i) => r := linesN(i)._1 }
    coeffReg.b.zipWithIndex.foreach { case (r, i) => r := linesN(i)._2 }
    coeffReg.c.zipWithIndex.foreach { case (r, i) => r := linesN(i)._3 }
  }

  when(setupState === stSetup) {
    setupState := stLoad

    val pxn = (minX << config.subPixelBits)
      .apply(config.subPixelBits + 15, 0).asSInt
    val pyn = (minY << config.subPixelBits)
      .apply(config.subPixelBits + 15, 0).asSInt

    prodReg.pax.zipWithIndex.foreach { case (r, i) =>
      r := coeffReg.a(i) * pxn }
    prodReg.pby.zipWithIndex.foreach { case (r, i) =>
      r := coeffReg.b(i) * pyn }
    prodReg.saA := coeffReg.a(0) * vHold.v0.x
    prodReg.saB := coeffReg.b(0) * vHold.v0.y
  }

  when(setupState === stLoad) {
    setupState := stIdle

    // Pure additions off registered products.
    val originE = (0 until 3).map { k =>
      FixedPointMath.trim64(
        prodReg.pax(k) + prodReg.pby(k) + coeffReg.c(k)) }
    val signedAreaN =
      FixedPointMath.trim64(prodReg.saA + prodReg.saB + coeffReg.c(0))

    val frontN = signedAreaN >= 0.S
    val degenerate = signedAreaN === 0.S
    val culledFinal =
      Mux(cullReg === 0.U, false.B,
        Mux(cullReg === 1.U, signedAreaN < 0.S, signedAreaN >= 0.S))

    when(culledFinal || degenerate) {
      active := false.B
    }.otherwise {
      active := true.B
      quadLane := 0.U
      curX := minX
      curY := minY
      areaReg := signedAreaN
      dxReg.e.zipWithIndex.foreach { case (r, i) =>
        r := FixedPointMath.trim64(coeffReg.a(i) << config.subPixelBits) }
      dyReg.e.zipWithIndex.foreach { case (r, i) =>
        r := FixedPointMath.trim64(coeffReg.b(i) << config.subPixelBits) }
      edgeReg.e.zipWithIndex.foreach { case (r, i) => r := originE(i) }
      rowStartReg.e.zipWithIndex.foreach { case (r, i) => r := originE(i) }
      frontReg := frontN
      tlReg.zipWithIndex.foreach { case (r, i) =>
        val fa = Mux(frontN, coeffReg.a(i), -coeffReg.a(i))
        val fb = Mux(frontN, coeffReg.b(i), -coeffReg.b(i))
        r := (fa < 0.S) || (fa === 0.S && fb < 0.S)
      }
    }
  }
}
