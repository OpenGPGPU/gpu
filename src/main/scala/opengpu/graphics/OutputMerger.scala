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
  * contract); write acknowledgements are fire-and-forget and popped wherever
  * they arrive.
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
    val fragIn = Flipped(Decoupled(new OmFragment))
    val mem = new Bundle {
      val req = Decoupled(new OmMemoryRequest)
      val resp = Flipped(Decoupled(new OmMemoryResponse))
    }
    val colorBase = Input(UInt(32.W))
    val depthBase = Input(UInt(32.W))
    val stride = Input(UInt(32.W)) // bytes per row
    val depthTestEnable = Input(Bool())
    val depthFunc = Input(UInt(3.W))
    val depthWriteEnable = Input(Bool())
    val blendEnable = Input(Bool())
    val accepted = Output(Bool())
    val wroteDepth = Output(Bool())
    val wroteColor = Output(Bool())
    /** No in-flight entry: every accepted fragment's writes have been issued
      * (and fire-and-forget acknowledgements are all that may remain in the
      * memory system).  A completion signal must wait for this, since
      * `fragIn.ready` only reports slot availability.
      */
    val drained = Output(Bool())
  })

  private def sReadDepth = 0.U(3.W)
  private def sWaitDepth = 1.U(3.W)
  private def sReadColor = 2.U(3.W)
  private def sWaitColor = 3.U(3.W)
  private def sWriteColor = 4.U(3.W)
  private def sWriteDepth = 5.U(3.W)

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
    val blendedColor = UInt(32.W)
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

  // ---------------------------------------------------------------------
  // Acceptance: a free slot and no address overlap with an in-flight entry.
  // Addresses are computed from the current configuration and latched, so
  // the configuration may change while entries are in flight.
  // ---------------------------------------------------------------------
  private val freeVec = VecInit(entries.map(e => !e.valid))
  private val anyFree = freeVec.asUInt.orR
  private val freeIdx = PriorityEncoder(freeVec)
  private val newColorAddr = (io.colorBase +
    (io.fragIn.bits.y * io.stride) + io.fragIn.bits.x * bppColor.U)(31, 0)
  private val newDepthAddr = (io.depthBase +
    (io.fragIn.bits.y * io.stride) + io.fragIn.bits.x * bppDepth.U)(31, 0)
  private val addrConflict = VecInit(entries.map(e => e.valid &&
    (e.colorAddr === newColorAddr || e.depthAddr === newDepthAddr))).asUInt.orR

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
    portArbiter.io.in(i).bits.data := Mux(e.state === sWriteDepth, e.depth,
      Mux(e.blendEnable, e.blendedColor, e.color))
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
  // the colour/depth buffers are disjoint by the driver contract).  Write
  // acknowledgements are popped wherever they arrive.
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
        e.state := sReadDepth
        e.colorAddr := newColorAddr
        e.depthAddr := newDepthAddr
        e.color := io.fragIn.bits.color
        e.depth := io.fragIn.bits.depth
        e.depthTestEnable := io.depthTestEnable
        e.depthFunc := io.depthFunc
        e.writeDepth := io.depthWriteEnable
        e.blendEnable := io.blendEnable
        e.blendedColor := 0.U
      }
    }.otherwise {
      when(granted(i)) {
        when(e.state === sReadDepth) { e.state := sWaitDepth }
        when(e.state === sReadColor) { e.state := sWaitColor }
        when(e.state === sWriteColor) {
          e.valid := Mux(e.writeDepth, true.B, false.B)
          when(e.writeDepth) { e.state := sWriteDepth }
        }
        when(e.state === sWriteDepth) { e.valid := false.B }
      }
      when(respRead && depthMatchers(i)) {
        val pass = Mux(!e.depthTestEnable, true.B,
          depthPass(e.depth, io.mem.resp.bits.data, e.depthFunc))
        when(pass) {
          e.state := Mux(e.blendEnable, sReadColor, sWriteColor)
        }.otherwise {
          e.valid := false.B
        }
      }
      when(respRead && colorMatchers(i)) {
        e.blendedColor := blendSourceOver(e.color, io.mem.resp.bits.data)
        e.state := sWriteColor
      }
    }
  }
}
