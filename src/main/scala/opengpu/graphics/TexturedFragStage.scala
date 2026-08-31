package opengpu.graphics

import chisel3._
import chisel3.util._

/** Per-vertex texture coordinates, unsigned Q16.16 over the unit square.
  *
  * Coordinates larger than one unit reach the sampler's wrap/clamp machinery;
  * there is deliberately no sign -- negative UVs are not defined (authors wrap
  * instead), which keeps the interpolation and addressing unsigned throughout.
  */
class TexUV extends Bundle {
  val u = UInt(32.W)
  val v = UInt(32.W)
}

/** Perspective-correct UV interpolation across a covered pixel.
  *
  * Same plane-equation arithmetic as [[PerspectiveInterpolator]] applied to
  * the two texture channels:
  *   uv_px = (Σ eᵢ·(1/wᵢ)·uvᵢ) / (Σ eᵢ·(1/wᵢ))
  * Division matches that module's divider semantics (documented divisor >
  * 0 for front-facing triangles).
  */
class TexUVInterpolator(gfxConfig: GraphicsConfig) extends Module {
  val io = IO(new Bundle {
    val e0 = Input(SInt(gfxConfig.edgeWidth.W))
    val e1 = Input(SInt(gfxConfig.edgeWidth.W))
    val e2 = Input(SInt(gfxConfig.edgeWidth.W))
    val invW0 = Input(SInt(32.W))
    val invW1 = Input(SInt(32.W))
    val invW2 = Input(SInt(32.W))
    val uv0 = Input(new TexUV)
    val uv1 = Input(new TexUV)
    val uv2 = Input(new TexUV)
    val uvPx = Output(new TexUV)
  })

  private val denom =
    io.e0 * io.invW0 + io.e1 * io.invW1 + io.e2 * io.invW2

  private def interp(c0: UInt, c1: UInt, c2: UInt): UInt = {
    val num = io.e0 * io.invW0 * c0.asSInt +
      io.e1 * io.invW1 * c1.asSInt + io.e2 * io.invW2 * c2.asSInt
    (num / denom)(31, 0).asUInt
  }

  io.uvPx.u := interp(io.uv0.u, io.uv1.u, io.uv2.u)
  io.uvPx.v := interp(io.uv0.v, io.uv1.v, io.uv2.v)
}

/** Wires the fixed-function sampler ([[TextureUnit]]) into the fragment
  * stream: per-fragment perspective-correct UVs, one bilinear sample, colour
  * MODULATE (fragment x texel, truncated shift back to 8 bits) and straight
  * pass-through of position and depth.
  *
  * One fragment is in flight at a time -- the consumer sees standard ready/
  * valid flow control; the fetch chain inside the sampler is invisible except
  * as latency.  X/Y/depth and the sampled/modulated colour registers are
  * single-source updated (learned twice the hard way in M4b/M5b).
  */
class TexturedFragStage(
  gfxConfig: GraphicsConfig = GraphicsConfig()
) extends Module {
  val io = IO(new Bundle {
    val fragIn = Flipped(Decoupled(new RasterFragment(gfxConfig)))
    /** Rasterizer edge values for THIS fragment (from pixel.bits before the
      * handoff) so UVs interpolate perspective-correctly. */
    val e0 = Input(SInt(gfxConfig.edgeWidth.W))
    val e1 = Input(SInt(gfxConfig.edgeWidth.W))
    val e2 = Input(SInt(gfxConfig.edgeWidth.W))
    val invW0 = Input(SInt(32.W))
    val invW1 = Input(SInt(32.W))
    val invW2 = Input(SInt(32.W))
    /** Per-vertex UVs from the draw record. */
    val uv0 = Input(new TexUV)
    val uv1 = Input(new TexUV)
    val uv2 = Input(new TexUV)

    val texBase = Input(UInt(32.W))
    val texWidth = Input(UInt(14.W))
    val texHeight = Input(UInt(14.W))
    val wrapClamp = Input(Bool())

    /** Current fragment's perspective-correct coordinates. The core-backed
      * path reuses this interpolation while bypassing the fixed sampler. */
    val interpolatedUv = Output(new TexUV)

    val out = Decoupled(new RasterFragment(gfxConfig))
    val mem = new Bundle {
      val req = Decoupled(new OmMemoryRequest)
      val resp = Flipped(Decoupled(new OmMemoryResponse))
    }
  })

  private val uvInterp = Module(new TexUVInterpolator(gfxConfig))
  private val sampler = Module(new TextureUnit(gfxConfig))

  sampler.io.texBase := io.texBase
  sampler.io.texWidth := io.texWidth
  sampler.io.texHeight := io.texHeight
  sampler.io.wrapMode := io.wrapClamp

  // Mem passthrough: the sampler owns fetches exclusively (single-sample FSM).
  io.mem.req <> sampler.io.mem.req
  sampler.io.mem.resp <> io.mem.resp

  // Geometry travels combinationally while the fragment is accepted: the
  // sampler decodes its own request pins at that same edge.
  uvInterp.io.e0 := io.e0
  uvInterp.io.e1 := io.e1
  uvInterp.io.e2 := io.e2
  uvInterp.io.invW0 := io.invW0
  uvInterp.io.invW1 := io.invW1
  uvInterp.io.invW2 := io.invW2
  uvInterp.io.uv0 := io.uv0
  uvInterp.io.uv1 := io.uv1
  uvInterp.io.uv2 := io.uv2
  io.interpolatedUv := uvInterp.io.uvPx

  private val sWait :: sOut :: Nil = Enum(2)
  private val state = RegInit(sWait)

  // Sampler results are always consumed while waiting for a fragment.
  sampler.io.result.ready := state === sWait

  // Held fragment payload + fetched texel.
  private val heldX = RegInit(0.S(gfxConfig.coordWidth.W))
  private val heldY = RegInit(0.S(gfxConfig.coordWidth.W))
  private val heldDepth = RegInit(0.S(32.W))
  private val heldCovered = RegInit(false.B)
  private val heldColor = Reg(Vec(3, UInt(8.W))) // fragment colour
  private val texelWord = RegInit(0.U(32.W))
  private val heldE = Seq(RegInit(0.S(gfxConfig.edgeWidth.W)),
    RegInit(0.S(gfxConfig.edgeWidth.W)), RegInit(0.S(gfxConfig.edgeWidth.W)))

  private def modulate(fc: UInt, tc: UInt): UInt = (fc * tc)(15, 8)

  io.fragIn.ready := state === sWait && sampler.io.sample.ready
  sampler.io.sample.valid :=
    state === sWait && io.fragIn.valid && io.fragIn.ready
  sampler.io.sample.bits.u := uvInterp.io.uvPx.u
  sampler.io.sample.bits.v := uvInterp.io.uvPx.v

  io.out.valid := state === sOut
  io.out.bits.x := heldX
  io.out.bits.y := heldY
  io.out.bits.depth := heldDepth
  io.out.bits.e0 := heldE(0)
  io.out.bits.e1 := heldE(1)
  io.out.bits.e2 := heldE(2)
  io.out.bits.covered := heldCovered
  io.out.bits.color.r := modulate(heldColor(0), texelWord(31, 24))
  io.out.bits.color.g := modulate(heldColor(1), texelWord(23, 16))
  io.out.bits.color.b := modulate(heldColor(2), texelWord(15, 8))

  // Payload latch happens on the accept edge; the sampler completes several
  // cycles later, so its result is observed on its own when -- independent of
  // whether the upstream fragment stream is presenting another fragment.
  switch(state) {
    is(sWait) {
      when(io.fragIn.valid && io.fragIn.ready) {
        heldX := io.fragIn.bits.x
        heldY := io.fragIn.bits.y
        heldDepth := io.fragIn.bits.depth
        heldCovered := io.fragIn.bits.covered
        heldE(0) := io.fragIn.bits.e0
        heldE(1) := io.fragIn.bits.e1
        heldE(2) := io.fragIn.bits.e2
        Seq(io.fragIn.bits.color.r, io.fragIn.bits.color.g,
          io.fragIn.bits.color.b).zipWithIndex.foreach { case (c, i) =>
          heldColor(i) := c
        }
      }
    }
    is(sOut) {
      when(io.out.fire) { state := sWait }
    }
  }

  when(state === sWait && sampler.io.result.valid) {
    texelWord := sampler.io.result.bits
    state := sOut
  }
}
