package opengpu.graphics

import chisel3._
import chisel3.util._

/** One texture-sample request: texture-space coordinates as unsigned Q16.16
  * over the unit square ([0,1) maps the texture extent; larger values reach
  * the wrap/clamp machinery).
  *
  * `mipLevel` selects the desired mip level (0 = base). `lodFrac` is the
  * Q0.8 weight of the following level for trilinear filtering. The unit walks the
  * chain at most min(mipLevel, texMaxLevel) levels deep, so a level-0 request
  * behaves exactly as before mip support existed.
  */
class TexSampleRequest extends Bundle {
  val u = UInt(32.W)
  val v = UInt(32.W)
  val mipLevel = UInt(4.W)
  val lodFrac = UInt(8.W)
}

/** Wrap behaviour applied independently to both axes.
  *
  *   0 = REPEAT: the texture tiles; requires power-of-two dimensions (asserted
  *       at request time -- the wrap is a mask, so a non-power-of-two size
  *       would alias rather than tile).
  *   1 = CLAMP : samples past the edge stick to the edge texels.
  */
object TexWrap {
  val repeat = 0.U(1.W)
  val clamp = 1.U(1.W)
}

/** Fixed-function texture sampling unit (M5b).
  *
  * Samples an RGBA8888 texture in shared memory with a bilinear filter.  This
  * is the fixed-function stage the roadmap places beside the SIMT lanes: the
  * shader program computes coordinates, the sampler decodes format/addressing/
  * filtering so the lanes never issue four raw loads plus blend maths per
  * sample.
  *
  * Configuration comes in as registers (driven by the host/driver, mirroring
  * the OutputMerger register model -- hardware only computes addresses and
  * issues reads, it never owns or sizes a buffer):
  *   - texBase    : byte address of texel (0,0);
  *   - texWidth/H : texture extent in texels;
  *   - wrapMode   : TexWrap.repeat or TexWrap.clamp, applied to both axes.
  *
  * Texel word layout matches the pipeline's packed-colour convention:
  * bits[31:24] red, [23:16] green, [15:8] blue, [7:0] alpha (little-endian
  * bytes A,B,G,R). Alpha currently passes through as opaque 0xff; alpha
  * blending stays with the OM hook.
  *
  * Sampling maths (all integer, no hidden rounding):
  *   biased = (u * width) - 0.5      -- texel-space coordinate with 8
  *                                      fractional bits, half-texel aligned so
  *                                      texel centres sit at u=(i+0.5)/N
  *   REPEAT: biased &= (width<<8)-1  -- masks index AND fraction in one op;
  *           wraparound handles the negative phase, tiling cleanly for
  *           power-of-two extents
  *   CLAMP : saturate biased into [0, (width<<8)-1]
  *   x0 = xs >> 8 ; fx = xs & 0xff
  *   REPEAT: x1 = (x0 + 1) & (width - 1)   (x0 is already in range)
  *   CLAMP : x0 = min(x0, width - 1); x1 = min(x0 + 1, width - 1)
  * and identically for v/y/height.  With zero fractions (sample centre) the
  * blend degenerates to the exact texel value, so NEAREST behaves correctly
  * through the same datapath.
  *
  * Mip chain: levels are stored as one contiguous chain starting at texBase;
  * level i has dims max(1,w>>i) x max(1,h>>i), RGBA8888 tightly packed, so
  * level i's byte offset is the sum of the preceding levels' w*h*4.  On
  * accept the unit walks the chain one level per cycle -- accumulate the
  * current level's byte size into the base, halve the dims (floored at 1) --
  * up to min(mipLevel, texMaxLevel) steps, then decodes and fetches from the
  * resolved level.  Zero steps leaves base/dims untouched, so level 0 is
  * bit-identical to the pre-mip unit when `lodFrac` is zero. The byte offsets stay natural
  * width: a 2^14 x 2^14 RGBA8888 level is 2^30 bytes and the whole chain
  * fits under 2^32, so no fixed-size slicing is needed. When `lodFrac` is
  * non-zero and a following level exists, the unit filters both selected
  * levels and mixes their RGB values with weights `256-lodFrac` and
  * `lodFrac`; a request at the final level remains a single-level fetch. The REPEAT
  * power-of-two assert stays on the level-0 dims (halving a power of two
  * keeps it a power of two until the floor at 1 takes over).
  *
  * Blend: each channel accumulates the four taps weighted by
  *   w00=(256-fx)(256-fy) w10=fx(256-fy) w01=(256-fx)fy w11=fx*fy
  * whose sum is exactly 65536, then shifts right by 16 (truncate).
  *
  * The four taps of one mip level are issued in consecutive cycles through
  * one `OmMemoryRequest` word port, with up to four reads outstanding.  The
  * response's echoed byte address routes out-of-order completions back to the
  * matching tap; duplicate clamp addresses consume the first still-pending
  * matching slot.  Trilinear sampling keeps the two four-tap level groups
  * sequential, bounding the required downstream transaction capacity at four.
  */
class TextureUnit(
  gfxConfig: GraphicsConfig = GraphicsConfig()
) extends Module {
  val io = IO(new Bundle {
    val texBase = Input(UInt(32.W))
    val texWidth = Input(UInt(14.W))
    val texHeight = Input(UInt(14.W))
    /** TexWrap.repeat or TexWrap.clamp (see above). */
    val wrapMode = Input(Bool())
    /** Deepest mip level the level-walk may reach (TEX_CONFIG[5:2]). */
    val texMaxLevel = Input(UInt(4.W))
    val sample = Flipped(Decoupled(new TexSampleRequest))
    val result = Decoupled(UInt(32.W))
    val mem = new Bundle {
      val req = Decoupled(new OmMemoryRequest)
      val resp = Flipped(Decoupled(new OmMemoryResponse))
    }
  })

  // sBlend exists so the final response's tapWords write commits before the
  // blend reads it (same-cycle write + blend-read would otherwise use stale
  // data).
  // sLevel walks the mip chain between accept and tap decode (see the class
  // doc); level 0 takes one decode cycle without changing base or dimensions.
  private val sIdle :: sLevel :: sFetch :: sBlend :: sDone :: Nil = Enum(5)
  private val state = RegInit(sIdle)

  // Level-walk state latched at accept: the request pins are only stable
  // through the handshake cycle, so the coordinates start the walk in
  // registers.  levelBase accumulates the byte offsets of the skipped
  // levels; levelW/H shrink per level, floored at 1.
  private val uReg = RegInit(0.U(32.W))
  private val vReg = RegInit(0.U(32.W))
  private val levelBase = RegInit(0.U(32.W))
  private val levelW = RegInit(1.U(14.W))
  private val levelH = RegInit(1.U(14.W))
  private val levelSteps = RegInit(0.U(4.W))
  private val baseMipLevel = RegInit(0.U(4.W))
  private val lodFracReg = RegInit(0.U(8.W))
  private val secondLevel = RegInit(false.B)

  // Tap geometry latched at accept time so the memory latency window touches
  // no live multiplies: clamped/wrapped corner indices and 8-bit fractions.
  private val fxReg = RegInit(0.U(8.W))
  private val fyReg = RegInit(0.U(8.W))

  // Four per-level reads. `tapIssued` makes request bits stable under memory
  // backpressure; `tapReceived` allows responses to return in any order.
  private val tapAddrs = Reg(Vec(4, UInt(32.W)))
  private val tapIssued = RegInit(0.U(4.W))
  private val tapReceived = RegInit(0.U(4.W))
  private val tapWords = Reg(Vec(4, UInt(32.W)))

  // Blended result held until result.fire.
  private val resultReg = RegInit(0.U(32.W))
  private val firstLevelResult = RegInit(0.U(32.W))

  io.sample.ready := state === sIdle

  // ---------------------------------------------------------------------
  // Coordinate decode: selected by wrap mode as documented above.
  // ---------------------------------------------------------------------
  /** Axis decode with the industry-standard half-texel alignment: the sample
    * coordinate maps to texel space minus one half texel, so u = (i+0.5)/N
    * hits texel i's centre and u = k/N falls exactly between texel k-1 and k.
    *
    * The bias is applied before wrapping, using two's-complement wraparound:
    *   biased = (u*extent) - 0.5            (fixed-point, wide enough never
    *                                          to lose information)
    *   REPEAT: biased & ((extent<<8)-1)     -- masks index and fraction; the
    *           wrapped-negative phase lands on the far side of the period,
    *           giving seamless tiling for power-of-two extents
    *   CLAMP : saturate biased into [0, (extent<<8)-1]
    */
  private def axisDecode(coord: UInt, extent: UInt): (UInt, UInt, UInt) = {
    // Coordinate ranges here keep every product below 2^46.  Shift into
    // texel-space x256 first, THEN apply the half-texel bias (the bias is
    // -128 in the post-shift domain; applying it pre-shift would be off by
    // a factor of 256).
    // Natural-width shift + borrow-capable subtraction; no fixed-size
    // slicing so no truncation surprises.
    val biased = ((coord * extent) >> 8) - 128.U
    val signedBiased = biased.asSInt

    val periodLast = (extent << 8) - 1.U
    val periodLastPad = periodLast.pad(64)

    val clampedSigned = WireDefault(signedBiased.pad(64))
    when(signedBiased < 0.S) { clampedSigned := 0.S }
      .elsewhen(signedBiased > periodLastPad.asSInt) {
        clampedSigned := periodLastPad.asSInt }

    val inRange = Mux(io.wrapMode === TexWrap.clamp,
      clampedSigned.asUInt,
      biased & periodLastPad)

    val idx0raw = inRange(21, 8)
    val frac = inRange(7, 0)
    val idx0 =
      Mux(io.wrapMode === TexWrap.clamp,
        Mux(idx0raw > extent - 1.U, extent - 1.U, idx0raw), idx0raw)
    val idx1 =
      Mux(io.wrapMode === TexWrap.clamp,
        Mux(idx0 + 1.U > extent - 1.U, extent - 1.U, idx0 + 1.U),
        (idx0 + 1.U) & (extent - 1.U))
    (idx0, idx1, frac)
  }

  // Decode off the latched coordinates against the walked-to level's dims
  // (at level 0 these registers hold io.texBase/Width/Height verbatim).
  private val decX = axisDecode(uReg, levelW)
  private val decY = axisDecode(vReg, levelH)

  // ---------------------------------------------------------------------
  // Tap addressing: linear RGBA8888, 4 bytes per texel, into the selected
  // mip level's base/extent.
  // ---------------------------------------------------------------------
  private def tapAddr(xIdx: UInt, yIdx: UInt): UInt =
    levelBase + ((yIdx * levelW + xIdx) << 2)

  private val unissued = ~tapIssued
  private val hasUnissued = unissued.orR
  private val issueIdx = PriorityEncoder(unissued)
  io.mem.req.valid := state === sFetch && hasUnissued
  io.mem.req.bits.write := false.B
  io.mem.req.bits.addr := tapAddrs(issueIdx)
  io.mem.req.bits.data := 0.U
  io.mem.resp.ready := state === sFetch

  // An address may intentionally appear more than once at a clamped edge.
  // Each response consumes exactly one issued, not-yet-received matching tap;
  // identical responses can therefore arrive in any order without ambiguity.
  private val responseMatches = VecInit((0 until 4).map { i =>
    tapIssued(i) && !tapReceived(i) &&
      tapAddrs(i) === io.mem.resp.bits.addr
  })
  private val responseMatchBits = responseMatches.asUInt
  private val responseIdx = PriorityEncoder(responseMatchBits)
  private val responseAccepted = io.mem.resp.fire && !io.mem.resp.bits.write
  private val receivedAfterResponse =
    tapReceived | UIntToOH(responseIdx, 4)

  // ---------------------------------------------------------------------
  // Blend: fixed-point weights summing to exactly 65536, truncated shift.
  // ---------------------------------------------------------------------
  private val w00 = (256.U - fxReg) * (256.U - fyReg)
  private val w10 = fxReg * (256.U - fyReg)
  private val w01 = (256.U - fxReg) * fyReg
  private val w11 = fxReg * fyReg

  private def blend(sel: UInt => UInt): UInt = {
    val acc = sel(tapWords(0)) * w00 + sel(tapWords(1)) * w10 +
      sel(tapWords(2)) * w01 + sel(tapWords(3)) * w11
    acc(23, 16)
  }

  // Output uses the pipeline's packed-colour convention (r<<24 | g<<16 |
  // b<<8 | opaque alpha).
  private val blended = Cat(
    blend(w => w(31, 24)), // red
    blend(w => w(23, 16)), // green
    blend(w => w(15, 8)), // blue
    0xff.U(8.W)
  )

  private def trilinearChannel(shift: Int): UInt = {
    val first = firstLevelResult(shift + 7, shift)
    val second = blended(shift + 7, shift)
    (((first * (256.U - lodFracReg)) + second * lodFracReg) >> 8)(7, 0)
  }
  private val trilinear = Cat(
    trilinearChannel(24), trilinearChannel(16), trilinearChannel(8), 0xff.U(8.W))

  io.result.valid := state === sDone
  io.result.bits := resultReg

  switch(state) {
    is(sIdle) {
      when(io.sample.fire) {
        // Power-of-two extents are required for the mask-based REPEAT wrap.
        when(io.wrapMode === TexWrap.repeat) {
          assert(
            (io.texWidth & (io.texWidth - 1.U)) === 0.U &&
              (io.texHeight & (io.texHeight - 1.U)) === 0.U,
            "REPEAT wrapping requires power-of-two texture dimensions")
        }
        assert(io.texWidth =/= 0.U && io.texHeight =/= 0.U,
          "texture dimensions must be non-zero")
        uReg := io.sample.bits.u
        vReg := io.sample.bits.v
        levelBase := io.texBase
        levelW := io.texWidth
        levelH := io.texHeight
        val selectedLevel = Mux(io.sample.bits.mipLevel > io.texMaxLevel,
          io.texMaxLevel, io.sample.bits.mipLevel)
        levelSteps := selectedLevel
        baseMipLevel := selectedLevel
        // There is no next level at the upper clamp, so suppress the second
        // four-tap fetch rather than blending a level with itself.
        lodFracReg := Mux(selectedLevel < io.texMaxLevel,
          io.sample.bits.lodFrac, 0.U)
        secondLevel := false.B
        state := sLevel
      }
    }
    is(sLevel) {
      when(levelSteps === 0.U) {
        fxReg := decX._3
        fyReg := decY._3
        tapAddrs(0) := tapAddr(decX._1, decY._1)
        tapAddrs(1) := tapAddr(decX._2, decY._1)
        tapAddrs(2) := tapAddr(decX._1, decY._2)
        tapAddrs(3) := tapAddr(decX._2, decY._2)
        tapIssued := 0.U
        tapReceived := 0.U
        state := sFetch
      }.otherwise {
        levelBase := levelBase + ((levelW * levelH) << 2)
        levelW := Mux(levelW === 1.U, 1.U, levelW >> 1)
        levelH := Mux(levelH === 1.U, 1.U, levelH >> 1)
        levelSteps := levelSteps - 1.U
      }
    }
    is(sFetch) {
      when(io.mem.req.fire) {
        tapIssued := tapIssued | UIntToOH(issueIdx, 4)
      }
      when(io.mem.resp.fire) {
        assert(!io.mem.resp.bits.write,
          "texture reads must not receive a write acknowledgement")
        assert(responseMatchBits.orR,
          "texture response must match an issued, pending tap address")
      }
      when(responseAccepted && responseMatchBits.orR) {
        tapWords(responseIdx) := io.mem.resp.bits.data
        tapReceived := receivedAfterResponse
        when(receivedAfterResponse.andR) { state := sBlend }
      }
    }
    is(sBlend) {
      when(!secondLevel && lodFracReg =/= 0.U) {
        // Fetch level N+1 from a freshly rewound packed-chain walk. Keeping
        // the two bilinear passes serial preserves the one-word memory-port
        // contract and all response backpressure behaviour.
        firstLevelResult := blended
        levelBase := io.texBase
        levelW := io.texWidth
        levelH := io.texHeight
        levelSteps := baseMipLevel + 1.U
        secondLevel := true.B
        state := sLevel
      }.otherwise {
        resultReg := Mux(secondLevel, trilinear, blended)
        state := sDone
      }
    }
    is(sDone) {
      when(io.result.fire) { state := sIdle }
    }
  }
}
