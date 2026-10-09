package opengpu.graphics

import chisel3._
import chisel3.util._

/** Bounded, ordered draw replay in 16x16 tile order.
  *
  * A chunk holds up to eight command records. Each tile sees the chunk's
  * draws in submission order, with their original scissor intersected with
  * the tile. The command reader stalls while a chunk is replayed, then fills
  * the next chunk. This bounds on-chip command storage without limiting the
  * command-buffer length. Tile visibility between chunks is maintained by
  * the attachment store's acknowledged flush at `passEnd`.
  */
class TileDrawBinner(config: GraphicsConfig) extends Module {
  require(config.tileBinning && config.tileSize == 16)
  private val capacity = 8
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

  private val Seq(idle, collect, send, waitDraw, nextTile,
    finishChunk, complete) = Enum(7)
  private val state = RegInit(idle)
  private val draws = Reg(Vec(capacity, new SceneTriangle(config)))
  private val count = RegInit(0.U(log2Ceil(capacity + 1).W))
  private val drawIndex = RegInit(0.U(log2Ceil(capacity).W))
  private val tileX = RegInit(0.U(16.W))
  private val tileY = RegInit(0.U(16.W))

  private val selected = draws(drawIndex)
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
  private val intersects = minX < maxX && minY < maxY

  io.drawIn.ready := state === collect && count < capacity.U && !io.start
  io.drawOut.valid := state === send && intersects && !io.start
  io.drawOut.bits := selected
  io.drawOut.bits.scissorEnable := true.B
  io.drawOut.bits.scissorMinX := minX
  io.drawOut.bits.scissorMinY := minY
  io.drawOut.bits.scissorMaxX := maxX
  io.drawOut.bits.scissorMaxY := maxY
  io.passEnd := state === finishChunk
  io.done := state === idle || state === complete

  private def advanceDraw(): Unit = {
    when(drawIndex +& 1.U < count) {
      drawIndex := drawIndex + 1.U
      state := send
    }.otherwise {
      state := nextTile
    }
  }

  when(io.start) {
    state := collect
    count := 0.U
    drawIndex := 0.U
    tileX := 0.U
    tileY := 0.U
  }.otherwise {
    switch(state) {
      is(collect) {
        when(io.drawIn.fire) {
          draws(count(log2Ceil(capacity) - 1, 0)) := io.drawIn.bits
          count := count + 1.U
          when(count === (capacity - 1).U) {
            drawIndex := 0.U
            tileX := 0.U
            tileY := 0.U
            state := send
          }
        }.elsewhen(io.inputDone) {
          when(count === 0.U) {
            state := complete
          }.otherwise {
            drawIndex := 0.U
            tileX := 0.U
            tileY := 0.U
            state := send
          }
        }
      }
      is(send) {
        when(!intersects) { advanceDraw() }
          .elsewhen(io.drawOut.fire) { state := waitDraw }
      }
      is(waitDraw) {
        when(io.renderDrained) { advanceDraw() }
      }
      is(nextTile) {
        when(nextTileX < config.screenWidth.U) {
          tileX := nextTileX(15, 0)
          drawIndex := 0.U
          state := send
        }.elsewhen(nextTileY < config.screenHeight.U) {
          tileX := 0.U
          tileY := nextTileY(15, 0)
          drawIndex := 0.U
          state := send
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
