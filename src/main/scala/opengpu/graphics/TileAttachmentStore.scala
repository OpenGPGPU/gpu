package opengpu.graphics

import chisel3._
import chisel3.util._

/** One 16x16, per-sample RGBA8 + D24S8 tile behind the OutputMerger.
  *
  * A full-word write allocates without fetching old data. Reads fetch a word
  * only on a miss, and subsequent depth/blend operations use the local copy.
  * Dirty words are written back when a fragment changes tile or the draw
  * finishes. The OM must drain before the tile is evicted, because its entries
  * may still refer to the old tile after the next fragment becomes visible.
  */
class TileAttachmentStore(maxSampleCount: Int = 4) extends Module {
  require(Set(1, 2, 4)(maxSampleCount))
  private val wordsPerPlane = 256 * maxSampleCount
  private val wordIndexBits = log2Ceil(wordsPerPlane)
  private val dirtyWords = 2 * wordsPerPlane
  private val dirtyAddrBits = log2Ceil(dirtyWords)
  private val dirtyCountBits = log2Ceil(dirtyWords + 1)
  val io = IO(new Bundle {
    val fragmentValid = Input(Bool())
    val fragmentFire = Input(Bool())
    val fragmentX = Input(UInt(16.W))
    val fragmentY = Input(UInt(16.W))
    val colorBase = Input(UInt(32.W))
    val depthBase = Input(UInt(32.W))
    val stride = Input(UInt(32.W))
    val sampleMode = Input(UInt(2.W))
    val omDrained = Input(Bool())
    /** All upstream stages have drained; the last tile can be stored. */
    val finish = Input(Bool())
    val permit = Output(Bool())
    /** All dirty words have been acknowledged by external memory. */
    val drained = Output(Bool())
    val wordIndex = Input(UInt(10.W))
    val depthPlane = Input(Bool())
    val om = new Bundle {
      val req = Flipped(Decoupled(new OmMemoryRequest))
      val resp = Decoupled(new OmMemoryResponse)
    }
    val mem = new Bundle {
      val req = Decoupled(new OmMemoryRequest)
      val resp = Flipped(Decoupled(new OmMemoryResponse))
    }
  })

  private val Seq(idle, active, drain, missRequest, missResponse,
    flushRequest, flushResponse) = Enum(7)
  private val state = RegInit(idle)
  private val tileX = Reg(UInt(12.W))
  private val tileY = Reg(UInt(12.W))
  private val colorBaseReg = Reg(UInt(32.W))
  private val depthBaseReg = Reg(UInt(32.W))
  private val strideReg = Reg(UInt(32.W))
  private val sampleModeReg = Reg(UInt(2.W))
  private val colorTileBase = Reg(UInt(32.W))
  private val depthTileBase = Reg(UInt(32.W))

  private val colorWords = Mem(wordsPerPlane, UInt(32.W))
  private val depthWords = Mem(wordsPerPlane, UInt(32.W))
  private val colorValid = RegInit(VecInit(Seq.fill(wordsPerPlane)(false.B)))
  private val depthValid = RegInit(VecInit(Seq.fill(wordsPerPlane)(false.B)))
  // Each word enters the list only on its first write. Flush therefore
  // examines changed words rather than scanning all 512 possible words or
  // building a wide priority encoder on the dirty bitmap.
  private val dirty = RegInit(VecInit(Seq.fill(dirtyWords)(false.B)))
  private val dirtyList = Mem(dirtyWords, UInt(11.W))
  private val dirtyCount = RegInit(0.U(dirtyCountBits.W))
  private val flushCursor = RegInit(0.U(dirtyCountBits.W))

  private val responseValid = RegInit(false.B)
  private val responseWrite = Reg(Bool())
  private val responseAddr = Reg(UInt(32.W))
  private val responseData = Reg(UInt(32.W))
  io.om.resp.valid := responseValid
  io.om.resp.bits.write := responseWrite
  io.om.resp.bits.addr := responseAddr
  io.om.resp.bits.data := responseData
  when(io.om.resp.fire) { responseValid := false.B }

  private val sameTile = tileX === io.fragmentX(15, 4) &&
    tileY === io.fragmentY(15, 4) &&
    colorBaseReg === io.colorBase && depthBaseReg === io.depthBase &&
    strideReg === io.stride && sampleModeReg === io.sampleMode
  io.permit := state === idle || (state === active && sameTile)
  io.drained := state === idle && !responseValid
  when(io.fragmentFire) {
    assert(io.permit, "a fragment cannot enter the OM before its tile is ready")
    assert(io.sampleMode <= log2Ceil(maxSampleCount).U,
      "tile sample mode exceeds the configured sample count")
  }

  private val missAddr = Reg(UInt(32.W))
  private val missIndex = Reg(UInt(wordIndexBits.W))
  private val missDepth = Reg(Bool())
  private val missReturnsToDrain = Reg(Bool())
  private val flushWord = Reg(UInt((wordIndexBits + 1).W))
  private val flushAddr = Reg(UInt(32.W))
  private val dirtyAny = flushCursor =/= dirtyCount
  private val nextDirty = dirtyList.read(flushCursor(dirtyAddrBits - 1, 0))
  private val nextIndex = Wire(UInt(10.W))
  nextIndex := nextDirty(wordIndexBits - 1, 0)
  private val nextDepth = nextDirty(wordIndexBits)
  private val nextBase = Mux(nextDepth, depthTileBase, colorTileBase)
  private val nextRow = MuxLookup(sampleModeReg, nextIndex(7, 4))(
    Seq(0.U -> nextIndex(7, 4), 1.U -> nextIndex(8, 5),
      2.U -> nextIndex(9, 6)))
  private val nextColumnSample = MuxLookup(sampleModeReg,
    Cat(0.U(2.W), nextIndex(3, 0)))(
    Seq(0.U -> Cat(0.U(2.W), nextIndex(3, 0)),
      1.U -> Cat(0.U(1.W), nextIndex(4, 0)),
      2.U -> nextIndex(5, 0)))
  private val nextAddr = (nextBase + nextRow * strideReg +
    (nextColumnSample << 2))(31, 0)

  io.om.req.ready := (state === active || state === drain) && !responseValid
  io.mem.req.valid := state === missRequest || (state === flushRequest && dirtyAny)
  io.mem.req.bits.write := state === flushRequest
  io.mem.req.bits.addr := Mux(state === missRequest, missAddr, nextAddr)
  io.mem.req.bits.data := Mux(nextDepth,
    depthWords.read(nextIndex), colorWords.read(nextIndex))
  io.mem.resp.ready := state === missResponse || state === flushResponse

  when(state === idle && io.fragmentFire) {
    tileX := io.fragmentX(15, 4)
    tileY := io.fragmentY(15, 4)
    colorBaseReg := io.colorBase
    depthBaseReg := io.depthBase
    strideReg := io.stride
    sampleModeReg := io.sampleMode
    val rowOffset = (io.fragmentY & "hfff0".U) * io.stride
    val colOffset = ((io.fragmentX & "hfff0".U) << io.sampleMode) << 2
    colorTileBase := (io.colorBase + rowOffset + colOffset)(31, 0)
    depthTileBase := (io.depthBase + rowOffset + colOffset)(31, 0)
    colorValid := VecInit(Seq.fill(wordsPerPlane)(false.B))
    depthValid := VecInit(Seq.fill(wordsPerPlane)(false.B))
    state := active
  }

  when(state === active || state === drain) {
    when(io.fragmentValid && !sameTile || io.finish && io.omDrained) {
      when(state === active) { state := drain }
    }
    when(io.om.req.fire) {
      val wordIndex = io.wordIndex(wordIndexBits - 1, 0)
      responseAddr := io.om.req.bits.addr
      responseWrite := io.om.req.bits.write
      responseData := 0.U
      when(io.om.req.bits.write) {
        val word = Cat(io.depthPlane, wordIndex)
        when(!dirty(word)) {
          assert(dirtyCount < dirtyWords.U, "tile dirty list cannot overflow")
          dirtyList.write(dirtyCount(dirtyAddrBits - 1, 0), word)
          dirtyCount := dirtyCount + 1.U
          dirty(word) := true.B
        }
        when(io.depthPlane) {
          depthWords.write(wordIndex, io.om.req.bits.data)
          depthValid(wordIndex) := true.B
        }.otherwise {
          colorWords.write(wordIndex, io.om.req.bits.data)
          colorValid(wordIndex) := true.B
        }
        responseValid := true.B
      }.otherwise {
        val hit = Mux(io.depthPlane, depthValid(wordIndex),
          colorValid(wordIndex))
        when(hit) {
          responseData := Mux(io.depthPlane,
            depthWords.read(wordIndex), colorWords.read(wordIndex))
          responseValid := true.B
        }.otherwise {
          missAddr := io.om.req.bits.addr
          missIndex := wordIndex
          missDepth := io.depthPlane
          missReturnsToDrain := state === drain || io.fragmentValid && !sameTile
          state := missRequest
        }
      }
    }
  }

  when(state === missRequest && io.mem.req.fire) { state := missResponse }
  when(state === missResponse && io.mem.resp.fire) {
    assert(!io.mem.resp.bits.write && io.mem.resp.bits.addr === missAddr,
      "tile read response must match its outstanding miss")
    when(missDepth) {
      depthWords.write(missIndex, io.mem.resp.bits.data)
      depthValid(missIndex) := true.B
    }.otherwise {
      colorWords.write(missIndex, io.mem.resp.bits.data)
      colorValid(missIndex) := true.B
    }
    responseData := io.mem.resp.bits.data
    responseValid := true.B
    state := Mux(missReturnsToDrain, drain, active)
  }

  when(state === drain && io.omDrained && !responseValid) {
    state := flushRequest
  }
  when(state === flushRequest) {
    when(!dirtyAny) {
      dirtyCount := 0.U
      flushCursor := 0.U
      state := idle
    }.elsewhen(io.mem.req.fire) {
      flushWord := nextDirty
      flushAddr := nextAddr
      state := flushResponse
    }
  }
  when(state === flushResponse && io.mem.resp.fire) {
    assert(io.mem.resp.bits.write && io.mem.resp.bits.addr === flushAddr,
      "tile store response must acknowledge the selected word")
    dirty(flushWord) := false.B
    flushCursor := flushCursor + 1.U
    state := flushRequest
  }
}
