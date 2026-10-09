package opengpu.graphics

import chisel3._
import chisel3.util._

/** Bounded, ordered draw replay in 16x16 tile order.
  *
  * A chunk holds up to eight command records. One byte per tile records which
  * draws can reach it; replay uses the lowest set bit to preserve submission
  * order. The command reader stalls while a chunk is binned and replayed,
  * then fills the next chunk. Tile visibility between chunks is maintained
  * by the attachment store's acknowledged flush at `passEnd`.
  */
class TileDrawBinner(config: GraphicsConfig) extends Module {
  require(config.tileBinning && config.tileSize == 16)
  private val capacity = 8
  private val tileColumns = (config.screenWidth + 15) / 16
  private val tileRows = (config.screenHeight + 15) / 16
  private val tileCount = tileColumns * tileRows
  private val tileIndexWidth = log2Ceil(tileCount).max(1)
  val io = IO(new Bundle {
    val start = Input(Bool())
    val drawIn = Flipped(Decoupled(new SceneTriangle(config)))
    val inputDone = Input(Bool())
    val drawOut = Decoupled(new SceneTriangle(config))
    /** Shader, expander, and OM have finished the previous draw. */
    val renderDrained = Input(Bool())
    /** The final tile's dirty words have been acknowledged. */
    val passDone = Input(Bool())
    val passEnd = Output(Bool())
    val done = Output(Bool())
  })

  private val Seq(idle, collect, bin, loadTile, captureTile, send,
    waitDraw, nextTile, finishChunk, complete) = Enum(10)
  private val state = RegInit(idle)
  private val draws = Reg(Vec(capacity, new SceneTriangle(config)))
  private val tileMasks = SyncReadMem(tileCount, UInt(capacity.W))
  private val count = RegInit(0.U(log2Ceil(capacity + 1).W))
  private val scanIndex = RegInit(0.U(log2Ceil(capacity).W))
  private val tileIndex = RegInit(0.U(tileIndexWidth.W))
  private val buildMask = RegInit(0.U(capacity.W))
  private val emitMask = RegInit(0.U(capacity.W))
  private val emitIndex = PriorityEncoder(emitMask)
  private val tileX = RegInit(0.U(16.W))
  private val tileY = RegInit(0.U(16.W))

  private val selected = draws(Mux(state === bin, scanIndex, emitIndex))
  private val readMask = tileMasks.read(tileIndex, state === loadTile)
  private val nextTileX = tileX +& 16.U
  private val nextTileY = tileY +& 16.U
  private val tileMaxX = Mux(nextTileX >= config.screenWidth.U,
    config.screenWidth.U(16.W), nextTileX(15, 0))
  private val tileMaxY = Mux(nextTileY >= config.screenHeight.U,
    config.screenHeight.U(16.W), nextTileY(15, 0))
  private val originalMinX = Mux(selected.scissorEnable,
    selected.scissorMinX, 0.U(16.W))
  private val originalMinY = Mux(selected.scissorEnable,
    selected.scissorMinY, 0.U(16.W))
  private val originalMaxX = Mux(selected.scissorEnable,
    selected.scissorMaxX, config.screenWidth.U(16.W))
  private val originalMaxY = Mux(selected.scissorEnable,
    selected.scissorMaxY, config.screenHeight.U(16.W))
  private val minX = Mux(originalMinX > tileX, originalMinX, tileX)
  private val minY = Mux(originalMinY > tileY, originalMinY, tileY)
  private val maxX = Mux(originalMaxX < tileMaxX, originalMaxX, tileMaxX)
  private val maxY = Mux(originalMaxY < tileMaxY, originalMaxY, tileMaxY)
  private val scissorIntersects = minX < maxX && minY < maxY

  // Reject a tile only when all three clip-space vertices are strictly on
  // one side of an expanded tile boundary. Keep a one-pixel margin for MSAA
  // sample offsets and rasterizer rounding. A non-positive w falls back to
  // replay, since clipping may create vertices on the visible side.
  private val loX = Mux(tileX === 0.U, 0.U, tileX - 1.U)
  private val loY = Mux(tileY === 0.U, 0.U, tileY - 1.U)
  private val hiX = tileMaxX +& 1.U
  private val hiY = tileMaxY +& 1.U
  private def outsideAxis(xAxis: Boolean, lo: UInt, hi: UInt): Bool = {
    val comparisons = selected.clip.map { v =>
      val position = if (xAxis) v.x else v.y
      val dimension = if (xAxis) config.screenWidth else config.screenHeight
      val numerator = (position +& v.w) * dimension.S
      (numerator < ((v.w * lo.zext) << 1),
        numerator > ((v.w * hi.zext) << 1))
    }
    comparisons.map(_._1).reduce(_ && _) ||
      comparisons.map(_._2).reduce(_ && _)
  }
  private val positiveW = selected.clip.map(_.w > 0.S).reduce(_ && _)
  private val outsideGeometry = positiveW &&
    (outsideAxis(true, loX, hiX) || outsideAxis(false, loY, hiY))
  private val intersects = scissorIntersects && !outsideGeometry
  private val nextMask = buildMask |
    Mux(intersects, UIntToOH(scanIndex, capacity), 0.U(capacity.W))

  io.drawIn.ready := state === collect && count < capacity.U && !io.start
  io.drawOut.valid := state === send && emitMask.orR && !io.start
  io.drawOut.bits := selected
  io.drawOut.bits.scissorEnable := true.B
  io.drawOut.bits.scissorMinX := minX
  io.drawOut.bits.scissorMinY := minY
  io.drawOut.bits.scissorMaxX := maxX
  io.drawOut.bits.scissorMaxY := maxY
  io.passEnd := state === finishChunk
  io.done := state === idle || state === complete

  when(io.start) {
    state := collect
    count := 0.U
    scanIndex := 0.U
    tileIndex := 0.U
    buildMask := 0.U
    emitMask := 0.U
    tileX := 0.U
    tileY := 0.U
  }.otherwise {
    switch(state) {
      is(collect) {
        when(io.drawIn.fire) {
          draws(count(log2Ceil(capacity) - 1, 0)) := io.drawIn.bits
          count := count + 1.U
          when(count === (capacity - 1).U) {
            scanIndex := 0.U
            tileIndex := 0.U
            buildMask := 0.U
            tileX := 0.U
            tileY := 0.U
            state := bin
          }
        }.elsewhen(io.inputDone) {
          when(count === 0.U) {
            state := complete
          }.otherwise {
            scanIndex := 0.U
            tileIndex := 0.U
            buildMask := 0.U
            tileX := 0.U
            tileY := 0.U
            state := bin
          }
        }
      }
      is(bin) {
        when(scanIndex +& 1.U < count) {
          buildMask := nextMask
          scanIndex := scanIndex + 1.U
        }.otherwise {
          tileMasks.write(tileIndex, nextMask)
          buildMask := 0.U
          scanIndex := 0.U
          when(nextTileX < config.screenWidth.U) {
            tileX := nextTileX(15, 0)
            tileIndex := tileIndex + 1.U
          }.elsewhen(nextTileY < config.screenHeight.U) {
            tileX := 0.U
            tileY := nextTileY(15, 0)
            tileIndex := tileIndex + 1.U
          }.otherwise {
            tileX := 0.U
            tileY := 0.U
            tileIndex := 0.U
            state := loadTile
          }
        }
      }
      is(loadTile) { state := captureTile }
      is(captureTile) {
        emitMask := readMask
        state := send
      }
      is(send) {
        when(!emitMask.orR) { state := nextTile }
          .elsewhen(io.drawOut.fire) { state := waitDraw }
      }
      is(waitDraw) {
        when(io.renderDrained) {
          emitMask := emitMask & ~UIntToOH(emitIndex, capacity)
          state := send
        }
      }
      is(nextTile) {
        when(nextTileX < config.screenWidth.U) {
          tileX := nextTileX(15, 0)
          tileIndex := tileIndex + 1.U
          state := loadTile
        }.elsewhen(nextTileY < config.screenHeight.U) {
          tileX := 0.U
          tileY := nextTileY(15, 0)
          tileIndex := tileIndex + 1.U
          state := loadTile
        }.otherwise {
          state := finishChunk
        }
      }
      is(finishChunk) {
        when(io.passDone) {
          count := 0.U
          state := collect
        }
      }
    }
  }
}
