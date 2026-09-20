package opengpu.graphics

import chisel3._
import chisel3.util._

/** One-word memory request/response used by the Output Merger.
  *
  * The OM issues a single size-4 byte read (depth) or write (color + depth)
  * per RMW.  In the integrated SoC this sits on the shared memory hierarchy
  * (SharedL2Slice); the standalone test drives a memory model that holds the
  * software-allocated color/depth buffers.
  */
class OmMemoryRequest extends Bundle {
  val write = Bool()
  val addr = UInt(32.W)
  val data = UInt(32.W)
}

class OmMemoryResponse extends Bundle {
  val data = UInt(32.W)
  // Echoes the request's write bit.  Responses may arrive out of order (a
  // shared L2 arbitrates several clients/banks), so a write acknowledgement
  // can overtake a later read; recipients waiting on read data must check
  // this tag instead of assuming the next response is their read.
  val write = Bool()
  // Echoes the request's byte address, so a client with several reads in
  // flight (the output merger's in-flight pixel table, a parallel texture
  // unit) can attribute an out-of-order response to the request that caused
  // it.  Producers: OmWordToLinePort (from its per-transaction slot) and the
  // memory models.
  val addr = UInt(32.W)
}

/** A fragment presented to the Output Merger (integer pixel position). */
class OmFragment extends Bundle {
  val x = UInt(16.W)
  val y = UInt(16.W)
  val color = UInt(32.W) // RGBA8888
  val depth = UInt(30.W) // D24 in the low 24 bits of a 32-bit word
  val sampleIndex = UInt(2.W)
}

/** Depth-test / output-merge read-modify-write with an in-flight pixel table.
  *
  * Up to `inflight` fragments process concurrently: each in-flight entry runs
  * its own read depth word -> compare per `depthFunc` -> (optionally read the
  * destination colour for source-over blending) -> write colour/depth
  * pipeline, and a round-robin arbiter multiplexes the entries onto the single
  * word memory port, so the port issues a transaction every cycle instead of
  * idling through each fragment's read latency.
  *
  * Per-pixel submission order is preserved by the table itself: a fragment
  * whose colour or depth word address matches an in-flight entry is not
  * accepted until that entry completes, so two fragments of one pixel are
  * never merged concurrently.  Read responses are attributed by the echoed
  * `OmMemoryResponse.addr` (unique per in-flight entry: same-pixel fragments
  * are excluded, and the colour/depth buffers are disjoint by the driver
  * contract). Write acknowledgements retire their address-matched entry;
  * reservations remain live until both writes are acknowledged.
  *
  * Every entry latches the fragment payload, the pixel addresses, and the
  * test/blend configuration at acceptance, so the programmer-visible
  * configuration may change (a completed draw's context retiring) while
  * earlier entries are still in flight.
  *
  * Registers (driver-writable; inputs here): color/depth base addresses, row
  * stride in bytes, depth-test enable, depth func (0=less, 1=less-eq,
  * 2=greater, 3=greater-eq, 4=eq, 5=ne, 6=always, 7=never), depth-write
  * enable, and source-over alpha blending enable.  When blending is enabled
  * the ROP reads the destination colour after a passing depth test, then
  * writes `src * srcA + dst * (255-srcA)` divided by 255.  The extra read is
  * an in-flight entry's own colour word, serialized behind its depth test.
  *
  * `blendCfgEnable` selects the GL-style factor/equation blend instead
  * (src/dst factors in GL order plus ADD/SUB/REV_SUB/MIN/MAX), overriding the
  * legacy source-over whenever it is present on a fragment.
  *
  * `stencilTestEnable` runs a single-sided GL stencil test against the stored
  * D24S8 stencil byte (bits [31:24]) before the depth compare: a stencil fail
  * applies the fail op to the stencil byte (masked by the write mask) and
  * never reaches colour; a depth fail applies the z-fail op; a depth pass
  * applies the z-pass op even when depth write is off.  Ops share the GL
  * 3-bit encoding (KEEP/ZERO/REPLACE/INCR/DECR/INVERT/INCRWRAP/DECRWRAP) and
  * the func shares the depth-func encoding.
  */
class OutputMerger(
  config: GraphicsConfig,
  colorBytesPerPixel: Int = 4,
  depthBytesPerPixel: Int = 4,
  inflight: Int = 4
) extends Module {
  require(inflight >= 1, "the output merger needs at least one in-flight slot")
  private val bppColor = colorBytesPerPixel
  private val bppDepth = depthBytesPerPixel

  val io = IO(new Bundle {
    val addressConflict = Output(Bool())
    val fragIn = Flipped(Decoupled(new OmFragment))
    val mem = new Bundle {
      val req = Decoupled(new OmMemoryRequest)
      val resp = Flipped(Decoupled(new OmMemoryResponse))
    }
    val colorBase = Input(UInt(32.W))
    val depthBase = Input(UInt(32.W))
    val stride = Input(UInt(32.W)) // bytes per row
    val sampleMode = Input(UInt(2.W))
    val depthTestEnable = Input(Bool())
    val depthFunc = Input(UInt(3.W))
    val depthWriteEnable = Input(Bool())
    val blendEnable = Input(Bool())
    /** GL-style blend-factor/equation override; present overrides the legacy
      * source-over `blendEnable`. */
    val blendCfgEnable = Input(Bool())
    val blendSrcFactor = Input(UInt(4.W))
    val blendDstFactor = Input(UInt(4.W))
    val blendEquation = Input(UInt(3.W))
    /** Single-sided GL stencil state (func shares the depth-func encoding). */
    val stencilTestEnable = Input(Bool())
    val stencilFunc = Input(UInt(3.W))
    val stencilRef = Input(UInt(8.W))
    val stencilReadMask = Input(UInt(8.W))
    val stencilWriteMask = Input(UInt(8.W))
    val stencilFailOp = Input(UInt(3.W))
    val stencilZFailOp = Input(UInt(3.W))
    val stencilZPassOp = Input(UInt(3.W))
    val accepted = Output(Bool())
    val wroteDepth = Output(Bool())
    val wroteColor = Output(Bool())
    /** Every accepted fragment has retired, including all write responses.
      * The memory adapter must acknowledge writes only after visibility at
      * the shared memory completion point; ready only reports capacity.
      */
    val drained = Output(Bool())
  })

  private def sReadDepth = 0.U(3.W)
  private def sWaitDepth = 1.U(3.W)
  private def sReadColor = 2.U(3.W)
  private def sWaitColor = 3.U(3.W)
  private def sWriteColor = 4.U(3.W)
  private def sWriteDepth = 5.U(3.W)
  private def sWaitColorWrite = 6.U(3.W)
  private def sWaitDepthWrite = 7.U(3.W)

  private class Entry extends Bundle {
    val valid = Bool()
    val state = UInt(3.W)
    val colorAddr = UInt(32.W)
    val depthAddr = UInt(32.W)
    val color = UInt(32.W)
    val depth = UInt(32.W)
    val depthTestEnable = Bool()
    val depthFunc = UInt(3.W)
    val writeDepth = Bool()
    val blendEnable = Bool()
    val blendCfgEnable = Bool()
    val blendSrcFactor = UInt(4.W)
    val blendDstFactor = UInt(4.W)
    val blendEquation = UInt(3.W)
    val stencilTestEnable = Bool()
    val stencilFunc = UInt(3.W)
    val stencilRef = UInt(8.W)
    val stencilReadMask = UInt(8.W)
    val stencilWriteMask = UInt(8.W)
    val stencilFailOp = UInt(3.W)
    val stencilZFailOp = UInt(3.W)
    val stencilZPassOp = UInt(3.W)
    val blendedColor = UInt(32.W)
    /** Full depth/stencil word presented to sWriteDepth. */
    val depthWriteData = UInt(32.W)
  }

  private val entries = RegInit(VecInit(Seq.fill(inflight)(0.U.asTypeOf(new Entry))))

  private def depthPass(newDepth: UInt, stored: UInt, func: UInt): Bool =
    MuxLookup(func, true.B)(
      Seq(
        0.U -> (newDepth < stored),
        1.U -> (newDepth <= stored),
        2.U -> (newDepth > stored),
        3.U -> (newDepth >= stored),
        4.U -> (newDepth === stored),
        5.U -> (newDepth =/= stored),
        6.U -> true.B,
        7.U -> false.B
      )
    )

  /** GL stencil op (GL order): 0=KEEP, 1=ZERO, 2=REPLACE, 3=INCR (saturate),
    * 4=DECR (saturate), 5=INVERT, 6=INCRWRAP, 7=DECRWRAP.  Wrap ops ignore
    * ref and wrap naturally at 8 bits.
    */
  private def stencilOpResult(op: UInt, stored: UInt, ref: UInt): UInt = {
    val incrSat = Mux(stored === 255.U(8.W), 255.U(8.W), (stored + 1.U)(7, 0))
    val decrSat = Mux(stored === 0.U(8.W), 0.U(8.W), (stored - 1.U)(7, 0))
    MuxLookup(op, stored)(Seq(
      0.U -> stored,
      1.U -> 0.U(8.W),
      2.U -> ref,
      3.U -> incrSat,
      4.U -> decrSat,
      5.U -> (stored ^ 255.U(8.W)),
      6.U -> (stored + 1.U)(7, 0),
      7.U -> (stored - 1.U)(7, 0)
    ))
  }

  /** Exact rounded division by 255 for the 8-bit source-over equation. */
  private def over(src: UInt, dst: UInt, srcA: UInt): UInt =
    ((src * srcA + dst * (255.U - srcA) + 127.U) / 255.U)(7, 0)

  private def blendSourceOver(src: UInt, dst: UInt): UInt = {
    val a = src(7, 0)
    val outA = (a + ((dst(7, 0) * (255.U - a) + 127.U) / 255.U))(7, 0)
    Cat(over(src(31, 24), dst(31, 24), a),
      over(src(23, 16), dst(23, 16), a),
      over(src(15, 8), dst(15, 8), a), outA)
  }

  /** Rounded `c * m / 255` for the general blend factors' scaled operand. */
  private def mul255(c: UInt, m: UInt): UInt =
    ((c * m + 127.U) / 255.U)(7, 0)

  /** 8-bit clamp of a 9-bit ADD/SUB result. */
  private def sat8(v: UInt): UInt = Mux(v > 255.U(9.W), 255.U(8.W), v(7, 0))

  /** 255 - x kept at 8 bits. */
  private def inv8(x: UInt): UInt = (255.U(9.W) - x)(7, 0)

  /** Multiplier an 8-bit blend factor selects for one channel, given that
    * channel's source/destination components and the two alphas.
    * Encoding (GL order): 0=ZERO, 1=ONE, 2=SRC_COLOR, 3=ONE_MINUS_SRC_COLOR,
    * 4=SRC_ALPHA, 5=ONE_MINUS_SRC_ALPHA, 6=DST_COLOR, 7=ONE_MINUS_DST_COLOR,
    * 8=DST_ALPHA, 9=ONE_MINUS_DST_ALPHA, 10=SRC_ALPHA_SATURATE, 11-15 = 0
    * (reserved; the driver rejects them).
    */
  private def factorMul(factor: UInt, sc: UInt, sa: UInt, dc: UInt, da: UInt): UInt = {
    val sat = Mux(sa <= inv8(da), sa, inv8(da))
    MuxLookup(factor, 0.U(8.W))(Seq(
      0.U -> 0.U(8.W),
      1.U -> 255.U(8.W),
      2.U -> sc,
      3.U -> inv8(sc),
      4.U -> sa,
      5.U -> inv8(sa),
      6.U -> dc,
      7.U -> inv8(dc),
      8.U -> da,
      9.U -> inv8(da),
      10.U -> sat
    ))
  }

  /** GL-style factor/equation blend of one RGBA8888 word pair.
    * Equations (GL order): 0=ADD, 1=SUB, 2=REV_SUB, 3=MIN, 4=MAX; 5-7 fall
    * back to ADD.  MIN/MAX ignore the factors per GL semantics.  All four
    * channels use the same factors/equation; *_COLOR factors are per-channel
    * and alpha factors uniform, which the per-channel loop below expresses
    * naturally (for the alpha position sc == sa).
    */
  private def blendGeneral(
    src: UInt, dst: UInt, srcFactor: UInt, dstFactor: UInt, eq: UInt
  ): UInt = {
    val sa = src(7, 0)
    val da = dst(7, 0)
    def blendChan(hi: Int): UInt = {
      val sc = src(hi + 7, hi)
      val dc = dst(hi + 7, hi)
      val s = mul255(sc, factorMul(srcFactor, sc, sa, dc, da))
      val d = mul255(dc, factorMul(dstFactor, sc, sa, dc, da))
      MuxLookup(eq, sat8(s +& d))(Seq(
        0.U -> sat8(s +& d),
        1.U -> Mux(s < d, 0.U(8.W), s - d),
        2.U -> Mux(d < s, 0.U(8.W), d - s),
        3.U -> Mux(sc < dc, sc, dc),
        4.U -> Mux(sc > dc, sc, dc)
      ))
    }
    Cat(blendChan(24), blendChan(16), blendChan(8), blendChan(0))
  }

  // ---------------------------------------------------------------------
  // Acceptance: a free slot and no address overlap with an in-flight entry.
  // Addresses are computed from the current configuration and latched, so
  // the configuration may change while entries are in flight.
  // ---------------------------------------------------------------------
  private val freeVec = VecInit(entries.map(e => !e.valid))
  private val anyFree = freeVec.asUInt.orR
  private val freeIdx = PriorityEncoder(freeVec)
  private val sampleOffset = (io.fragIn.bits.x << io.sampleMode) +
    Mux(io.sampleMode === 0.U, 0.U, io.fragIn.bits.sampleIndex)
  private val newColorAddr = (io.colorBase +
    (io.fragIn.bits.y * io.stride) + sampleOffset * bppColor.U)(31, 0)
  private val newDepthAddr = (io.depthBase +
    (io.fragIn.bits.y * io.stride) + sampleOffset * bppDepth.U)(31, 0)
  private val addrConflict = VecInit(entries.map(e => e.valid &&
    (e.colorAddr === newColorAddr || e.depthAddr === newDepthAddr ||
      e.colorAddr === newDepthAddr || e.depthAddr === newColorAddr))).asUInt.orR

  io.addressConflict := io.fragIn.valid && addrConflict
  io.fragIn.ready := anyFree && !addrConflict
  io.accepted := io.fragIn.fire
  io.drained := !VecInit(entries.map(_.valid)).asUInt.orR

  // ---------------------------------------------------------------------
  // Memory port: round-robin arbitration among the requesting entries.
  // ---------------------------------------------------------------------
  private val portArbiter = Module(new RRArbiter(new OmMemoryRequest, inflight))
  private val granted = Wire(Vec(inflight, Bool()))
  for (i <- 0 until inflight) {
    val e = entries(i)
    val reading = e.state === sReadDepth || e.state === sReadColor
    val writing = e.state === sWriteColor || e.state === sWriteDepth
    portArbiter.io.in(i).valid := e.valid && (reading || writing)
    portArbiter.io.in(i).bits.addr :=
      Mux(e.state === sReadDepth || e.state === sWriteDepth, e.depthAddr, e.colorAddr)
    portArbiter.io.in(i).bits.write := writing
    portArbiter.io.in(i).bits.data := Mux(e.state === sWriteDepth, e.depthWriteData,
      Mux(e.blendEnable || e.blendCfgEnable, e.blendedColor, e.color))
    granted(i) := portArbiter.io.in(i).fire
  }
  io.mem.req <> portArbiter.io.out
  io.mem.resp.ready := true.B

  io.wroteColor := VecInit(granted.zip(entries).map { case (g, e) =>
    g && e.state === sWriteColor }).asUInt.orR
  io.wroteDepth := VecInit(granted.zip(entries).map { case (g, e) =>
    g && e.state === sWriteDepth }).asUInt.orR

  // ---------------------------------------------------------------------
  // Response attribution: a read response belongs to the one entry waiting
  // for that address (same-pixel fragments are excluded at acceptance, and
  // the colour/depth buffers are disjoint by the driver contract). Write
  // acknowledgements are matched independently below.
  // ---------------------------------------------------------------------
  private val respRead = io.mem.resp.fire && !io.mem.resp.bits.write
  private val depthMatchers = VecInit(entries.map(e =>
    e.valid && e.state === sWaitDepth && e.depthAddr === io.mem.resp.bits.addr))
  private val colorMatchers = VecInit(entries.map(e =>
    e.valid && e.state === sWaitColor && e.colorAddr === io.mem.resp.bits.addr))
  when(respRead) {
    assert(PopCount(depthMatchers) + PopCount(colorMatchers) === 1.U,
      "a read response must match exactly one in-flight OM entry")
  }

  for (i <- 0 until inflight) {
    val e = entries(i)
    when(!e.valid) {
      when(io.fragIn.fire && freeIdx === i.U) {
        e.valid := true.B
        // The stencil test rides the depth-word read: with the depth test
        // disabled GL still runs the stencil test (depth passes implicitly),
        // so a stencil-enabled draw always reads the D24S8 word first.
        e.state := Mux(io.depthTestEnable || io.stencilTestEnable, sReadDepth,
          Mux(io.blendEnable || io.blendCfgEnable, sReadColor, sWriteColor))
        e.colorAddr := newColorAddr
        e.depthAddr := newDepthAddr
        e.color := io.fragIn.bits.color
        e.depth := io.fragIn.bits.depth
        e.depthTestEnable := io.depthTestEnable
        e.depthFunc := io.depthFunc
        e.writeDepth := io.depthWriteEnable
        e.blendEnable := io.blendEnable
        e.blendCfgEnable := io.blendCfgEnable
        e.blendSrcFactor := io.blendSrcFactor
        e.blendDstFactor := io.blendDstFactor
        e.blendEquation := io.blendEquation
        e.stencilTestEnable := io.stencilTestEnable
        e.stencilFunc := io.stencilFunc
        e.stencilRef := io.stencilRef
        e.stencilReadMask := io.stencilReadMask
        e.stencilWriteMask := io.stencilWriteMask
        e.stencilFailOp := io.stencilFailOp
        e.stencilZFailOp := io.stencilZFailOp
        e.stencilZPassOp := io.stencilZPassOp
        e.blendedColor := 0.U
        // Without a depth read there is no stored stencil byte to preserve,
        // so the no-depth-test path writes stencil 0 under the D24 depth.
        e.depthWriteData := Cat(0.U(8.W), io.fragIn.bits.depth(23, 0))
      }
    }.otherwise {
      when(granted(i)) {
        when(e.state === sReadDepth) { e.state := sWaitDepth }
        when(e.state === sReadColor) { e.state := sWaitColor }
        when(e.state === sWriteColor) {
          e.state := sWaitColorWrite
        }
        when(e.state === sWriteDepth) { e.state := sWaitDepthWrite }
      }
      when(io.mem.resp.fire && io.mem.resp.bits.write) {
        when(e.state === sWaitColorWrite && e.colorAddr === io.mem.resp.bits.addr) {
          when(e.writeDepth) { e.state := sWriteDepth }
            .otherwise { e.valid := false.B }
        }
        when(e.state === sWaitDepthWrite && e.depthAddr === io.mem.resp.bits.addr) {
          e.valid := false.B
        }
      }
      when(respRead && depthMatchers(i)) {
        // D24S8: depth occupies bits [23:0] of the stored word and the
        // stencil byte rides in bits [31:24], so the compare narrows to the
        // low 24 bits.
        val stored = io.mem.resp.bits.data
        val storedStencil = stored(31, 24)
        val storedDepth = stored(23, 0)
        // Masked application of a stencil op to the stored byte: the write
        // mask selects the updated bits, the rest keep their stored value.
        def applyOp(op: UInt): UInt = {
          val updated = stencilOpResult(op, storedStencil, e.stencilRef)
          (updated & e.stencilWriteMask) |
            (storedStencil & ~e.stencilWriteMask)
        }
        // GL runs the stencil test before the depth test; the func shares the
        // depth-func encoding.
        val sPass = !e.stencilTestEnable ||
          depthPass(e.stencilRef & e.stencilReadMask,
            storedStencil & e.stencilReadMask, e.stencilFunc)
        when(!sPass) {
          // Stencil fail: update the stencil byte only and skip colour.
          val newWord = Cat(applyOp(e.stencilFailOp), storedDepth)
          when(newWord =/= stored) {
            e.depthWriteData := newWord
            e.state := sWriteDepth
          }.otherwise { e.valid := false.B }
        }.elsewhen(e.depthTestEnable &&
            !depthPass(e.depth(23, 0), storedDepth, e.depthFunc)) {
          // Depth fail: apply the z-fail op to the stencil byte only.
          val newWord = Cat(Mux(e.stencilTestEnable,
            applyOp(e.stencilZFailOp), storedStencil), storedDepth)
          when(newWord =/= stored) {
            e.depthWriteData := newWord
            e.state := sWriteDepth
          }.otherwise { e.valid := false.B }
        }.otherwise {
          // Depth pass: the z-pass op applies even when depth write is off.
          val zpassStencil = Mux(e.stencilTestEnable,
            applyOp(e.stencilZPassOp), storedStencil)
          val stencilChanged = e.stencilTestEnable && zpassStencil =/= storedStencil
          e.depthWriteData := Cat(zpassStencil,
            Mux(e.writeDepth, e.depth(23, 0), storedDepth))
          // A changed stencil byte forces the depth-word write even when the
          // depth plane itself is write-protected.
          e.writeDepth := e.writeDepth || stencilChanged
          e.state := Mux(e.blendEnable || e.blendCfgEnable, sReadColor, sWriteColor)
        }
      }
      when(respRead && colorMatchers(i)) {
        e.blendedColor := Mux(e.blendCfgEnable,
          blendGeneral(e.color, io.mem.resp.bits.data, e.blendSrcFactor,
            e.blendDstFactor, e.blendEquation),
          blendSourceOver(e.color, io.mem.resp.bits.data))
        e.state := sWriteColor
      }
    }
  }
}
