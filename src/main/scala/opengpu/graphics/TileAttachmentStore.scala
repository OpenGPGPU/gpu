package opengpu.graphics

import chisel3._
import chisel3.util._

/** One 16x16, single-sample RGBA8 + D24S8 tile behind the OutputMerger.
  *
  * A full-word write allocates without fetching old data. Reads fetch a word
  * only on a miss, and subsequent depth/blend operations use the local copy.
  * Dirty words are written back when a fragment changes tile or the draw
  * finishes. The OM must drain before the tile is evicted, because its entries
  * may still refer to the old tile after the next fragment becomes visible.
  */
class TileAttachmentStore extends Module {
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
    val wordIndex = Input(UInt(8.W))
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
  private val colorTileBase = Reg(UInt(32.W))
  private val depthTileBase = Reg(UInt(32.W))

  private val colorWords = Mem(256, UInt(32.W))
  private val depthWords = Mem(256, UInt(32.W))
  private val colorValid = RegInit(VecInit(Seq.fill(256)(false.B)))
  private val depthValid = RegInit(VecInit(Seq.fill(256)(false.B)))
  // Each word enters the list only on its first write. Flush therefore
  // examines changed words rather than scanning all 512 possible words or
  // building a wide priority encoder on the dirty bitmap.
  private val dirty = RegInit(VecInit(Seq.fill(512)(false.B)))
  private val dirtyList = Mem(512, UInt(9.W))
  private val dirtyCount = RegInit(0.U(10.W))
  private val flushCursor = RegInit(0.U(10.W))

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
    strideReg === io.stride
  io.permit := state === idle || (state === active && sameTile)
  io.drained := state === idle && !responseValid
  when(io.fragmentFire) {
    assert(io.permit, "a fragment cannot enter the OM before its tile is ready")
    assert(io.sampleMode === 0.U, "tile attachments currently support 1x only")
  }

  private val missAddr = Reg(UInt(32.W))
  private val missIndex = Reg(UInt(8.W))
  private val missDepth = Reg(Bool())
  private val missReturnsToDrain = Reg(Bool())
  private val flushWord = Reg(UInt(9.W))
  private val flushAddr = Reg(UInt(32.W))
  private val dirtyAny = flushCursor =/= dirtyCount
  private val nextDirty = dirtyList.read(flushCursor(8, 0))
  private val nextIndex = nextDirty(7, 0)
  private val nextDepth = nextDirty(8)
  private val nextBase = Mux(nextDepth, depthTileBase, colorTileBase)
  private val nextAddr = (nextBase + nextIndex(7, 4) * strideReg +
    (nextIndex(3, 0) << 2))(31, 0)

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
    val rowOffset = (io.fragmentY & "hfff0".U) * io.stride
    val colOffset = (io.fragmentX & "hfff0".U) << 2
    colorTileBase := (io.colorBase + rowOffset + colOffset)(31, 0)
    depthTileBase := (io.depthBase + rowOffset + colOffset)(31, 0)
    colorValid := VecInit(Seq.fill(256)(false.B))
    depthValid := VecInit(Seq.fill(256)(false.B))
    state := active
  }

  when(state === active || state === drain) {
    when(io.fragmentValid && !sameTile || io.finish && io.omDrained) {
      when(state === active) { state := drain }
    }
    when(io.om.req.fire) {
      responseAddr := io.om.req.bits.addr
      responseWrite := io.om.req.bits.write
      responseData := 0.U
      when(io.om.req.bits.write) {
        val word = Cat(io.depthPlane, io.wordIndex)
        when(!dirty(word)) {
          assert(dirtyCount < 512.U, "tile dirty list cannot overflow")
          dirtyList.write(dirtyCount(8, 0), word)
          dirtyCount := dirtyCount + 1.U
          dirty(word) := true.B
        }
        when(io.depthPlane) {
          depthWords.write(io.wordIndex, io.om.req.bits.data)
          depthValid(io.wordIndex) := true.B
        }.otherwise {
          colorWords.write(io.wordIndex, io.om.req.bits.data)
          colorValid(io.wordIndex) := true.B
        }
        responseValid := true.B
      }.otherwise {
        val hit = Mux(io.depthPlane, depthValid(io.wordIndex),
          colorValid(io.wordIndex))
        when(hit) {
          responseData := Mux(io.depthPlane,
            depthWords.read(io.wordIndex), colorWords.read(io.wordIndex))
          responseValid := true.B
        }.otherwise {
          missAddr := io.om.req.bits.addr
          missIndex := io.wordIndex
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
