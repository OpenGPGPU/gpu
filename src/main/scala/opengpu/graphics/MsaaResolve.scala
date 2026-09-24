package opengpu.graphics

import chisel3._
import chisel3.util._

/** Streaming MSAA resolve backend.
  *
  * Averages the `1 << sampleMode` interleaved RGBA8888 colour samples of every
  * logical pixel into one packed word in a separate single-sample destination
  * buffer.  This is the hardware form of the validated resolve described in
  * [[docs/MSAA_DESIGN.md]]: both programmed strides are honoured, so the source
  * and destination may carry physical row padding, and each channel is rounded
  * half-up as `(sum + samples/2) / samples`.
  *
  * The engine issues exactly one word request at a time, so it needs no
  * transaction tagging: read responses are consumed while accumulating and the
  * write is acknowledged before the next pixel.  Range admission is *not* this
  * block's responsibility; a typed driver operation must validate the
  * source/destination buffers before starting it.
  *
  * `srcStride`/`dstStride` are physical row strides in bytes and are used
  * verbatim.  `width`/`height` are the logical extent; a zero extent completes
  * without touching memory.
  */
class MsaaResolveEngine(maxSampleCount: Int = 4) extends Module {
  require(Set(1, 2, 4)(maxSampleCount))
  override def desiredName: String = "MsaaResolveEngine"

  val io = IO(new Bundle {
    /** One-cycle pulse; ignored unless the engine is idle. */
    val start = Input(Bool())
    val srcBase = Input(UInt(32.W))
    val dstBase = Input(UInt(32.W))
    val srcStride = Input(UInt(32.W))
    val dstStride = Input(UInt(32.W))
    val imgWidth = Input(UInt(16.W))
    val imgHeight = Input(UInt(16.W))
    val sampleMode = Input(UInt(2.W))
    val busy = Output(Bool())
    val done = Output(Bool())
    val mem = new Bundle {
      val req = Decoupled(new OmMemoryRequest)
      val resp = Flipped(Decoupled(new OmMemoryResponse))
    }
  })

  private val sIdle :: sPrep :: sReadReq :: sReadResp :: sWriteReq :: sWriteResp :: sWriteUse :: sDone :: Nil =
    Enum(8)
  private val state = RegInit(sIdle)

  // Configuration latched at start so the host may reprogram immediately.
  private val srcStride = Reg(UInt(32.W))
  private val dstStride = Reg(UInt(32.W))
  private val imgWidth = Reg(UInt(16.W))
  private val imgHeight = Reg(UInt(16.W))
  private val mode = Reg(UInt(2.W))
  private val samples = Reg(UInt(3.W))

  private val x = RegInit(0.U(16.W))
  private val y = RegInit(0.U(16.W))
  private val s = RegInit(0.U(2.W))
  private val acc = RegInit(VecInit(Seq.fill(4)(0.U(32.W))))
  private val result = Reg(UInt(32.W))
  // Scanline bases avoid a y*stride multiply on the request-address path.
  private val srcRowBase = Reg(UInt(32.W))
  private val dstRowBase = Reg(UInt(32.W))
  // Address is registered in sPrep / response handlers so mode/x/s arithmetic
  // cannot sit on the mem-request → translator-pending path.
  private val reqAddr = Reg(UInt(32.W))

  private val samplesByMode =
    VecInit(1.U(3.W), 2.U(3.W), 4.U(3.W), 4.U(3.W))
  // Half-up rounding term; 1x needs none, 2x adds 1, 4x adds 2.
  private val roundByMode = VecInit(0.U(3.W), 1.U(3.W), 2.U(3.W), 0.U(3.W))

  // samples is always 1/2/4 (1 << mode for mode 0..2; mode 3 aliases 4x).
  private def sampleAddr(sx: UInt, ss: UInt): UInt = {
    val sampleIndex = MuxLookup(mode, (sx << 2) + ss)(Seq(
      0.U -> (sx + ss),
      1.U -> ((sx << 1) + ss),
      2.U -> ((sx << 2) + ss),
      3.U -> ((sx << 2) + ss)
    ))
    srcRowBase + (sampleIndex << 2)
  }
  private def destAddr(sx: UInt): UInt =
    dstRowBase + (sx << 2)

  private def nextAcc(c: Int, data: UInt): UInt =
    (acc(c) + data(8 * c + 7, 8 * c))(31, 0)

  private def average(data: UInt): UInt = {
    val round = roundByMode(mode)
    val channels = (0 until 4).map { c =>
      val summed = nextAcc(c, data) +& round
      val averaged = MuxLookup(mode, summed >> 2)(Seq(
        0.U -> summed,
        1.U -> (summed >> 1),
        2.U -> (summed >> 2),
        3.U -> (summed >> 2)
      ))
      averaged(7, 0)
    }
    Cat(channels(3), channels(2), channels(1), channels(0))
  }

  io.busy := state =/= sIdle
  io.done := state === sDone
  io.mem.req.valid := false.B
  io.mem.req.bits.write := false.B
  io.mem.req.bits.addr := 0.U
  io.mem.req.bits.data := 0.U
  io.mem.resp.ready := false.B

  switch(state) {
    is(sIdle) {
      when(io.start) {
        srcStride := io.srcStride
        dstStride := io.dstStride
        imgWidth := io.imgWidth
        imgHeight := io.imgHeight
        mode := io.sampleMode
        samples := samplesByMode(io.sampleMode)
        x := 0.U
        y := 0.U
        s := 0.U
        acc := VecInit(Seq.fill(4)(0.U(32.W)))
        srcRowBase := io.srcBase
        dstRowBase := io.dstBase
        state := Mux(io.imgWidth === 0.U || io.imgHeight === 0.U,
          sDone, sPrep)
      }
    }

    is(sPrep) {
      reqAddr := sampleAddr(x, s)
      state := sReadReq
    }

    is(sReadReq) {
      io.mem.req.valid := true.B
      io.mem.req.bits.addr := reqAddr
      when(io.mem.req.fire) { state := sReadResp }
    }

    is(sReadResp) {
      io.mem.resp.ready := true.B
      when(io.mem.resp.fire) {
        val data = io.mem.resp.bits.data
        acc := VecInit((0 until 4).map(c => nextAcc(c, data)))
        when(s === samples - 1.U) {
          result := average(data)
          reqAddr := destAddr(x)
          state := sWriteReq
        }.otherwise {
          s := s + 1.U
          reqAddr := sampleAddr(x, s + 1.U)
          state := sReadReq
        }
      }
    }

    is(sWriteReq) {
      io.mem.req.valid := true.B
      io.mem.req.bits.write := true.B
      io.mem.req.bits.addr := reqAddr
      io.mem.req.bits.data := result
      when(io.mem.req.fire) { state := sWriteResp }
    }

    is(sWriteResp) {
      io.mem.resp.ready := true.B
      when(io.mem.resp.fire) {
        // Capture the ack only; advance x/y next cycle so L2 response fabric
        // cannot reach resolveEngine.y in one edge (copyack limiter).
        state := sWriteUse
      }
    }

    is(sWriteUse) {
      acc := VecInit(Seq.fill(4)(0.U(32.W)))
      s := 0.U
      when(x === imgWidth - 1.U) {
        x := 0.U
        when(y === imgHeight - 1.U) {
          state := sDone
        }.otherwise {
          y := y + 1.U
          srcRowBase := srcRowBase + srcStride
          dstRowBase := dstRowBase + dstStride
          state := sPrep
        }
      }.otherwise {
        x := x + 1.U
        state := sPrep
      }
    }

    is(sDone) {
      state := sIdle
    }
  }
}
