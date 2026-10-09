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

  private val Seq(idle, collect, boundsStart, boundsX, boundsY,
    binStart, binScan, loadTile, captureTile, send, waitDraw,
    nextTile, finishChunk, complete) = Enum(14)
  private val state = RegInit(idle)
  private val draws = Reg(Vec(capacity, new SceneTriangle(config)))
  private val tileMasks = SyncReadMem(tileCount, UInt(capacity.W))
  private val count = RegInit(0.U(log2Ceil(capacity + 1).W))
  private val boundsIndex = RegInit(0.U(log2Ceil(capacity).W))
  private val boundMinX = Reg(Vec(capacity, UInt(16.W)))
  private val boundMaxX = Reg(Vec(capacity, UInt(16.W)))
  private val boundMinY = Reg(Vec(capacity, UInt(16.W)))
  private val boundMaxY = Reg(Vec(capacity, UInt(16.W)))
  private val scanMinX = RegInit("hffff".U(16.W))
  private val scanMaxX = RegInit(0.U(16.W))
  private val scanMinY = RegInit("hffff".U(16.W))
  private val scanMaxY = RegInit(0.U(16.W))
  private val scanMask = RegInit(0.U(capacity.W))
  private val scanIndex = PriorityEncoder(scanMask)
  private val tileIndex = RegInit(0.U(tileIndexWidth.W))
  private val buildMask = RegInit(0.U(capacity.W))
  private val emitMask = RegInit(0.U(capacity.W))
  private val emitIndex = PriorityEncoder(emitMask)
  private val tileX = RegInit(0.U(16.W))
  private val tileY = RegInit(0.U(16.W))

  private val findingBounds = state === boundsStart ||
    state === boundsX || state === boundsY
  private val selected = draws(Mux(findingBounds, boundsIndex,
    Mux(state === binScan, scanIndex, emitIndex)))
  private val readMask = tileMasks.read(tileIndex, state === loadTile)
  private val nextTileX = tileX +& 16.U
  private val nextTileY = tileY +& 16.U
  private val tileMaxX = Mux(nextTileX >= config.screenWidth.U,
    config.screenWidth.U(16.W), nextTileX(15, 0))
  private val tileMaxY = Mux(nextTileY >= config.screenHeight.U,
    config.screenHeight.U(16.W), nextTileY(15, 0))
  // Cheap scissor checks run in parallel. Most tiles in a small-scissor
  // chunk can write an empty mask without invoking any geometry comparison.
  private val candidates = VecInit((0 until capacity).map { i =>
    val d = draws(i)
    val x0 = Mux(d.scissorEnable, d.scissorMinX, 0.U(16.W))
    val y0 = Mux(d.scissorEnable, d.scissorMinY, 0.U(16.W))
    val x1 = Mux(d.scissorEnable, d.scissorMaxX, config.screenWidth.U(16.W))
    val y1 = Mux(d.scissorEnable, d.scissorMaxY, config.screenHeight.U(16.W))
    i.U < count && x0 < tileMaxX && y0 < tileMaxY &&
      x1 > tileX && y1 > tileY && x0 < x1 && y0 < y1 &&
      tileX >= boundMinX(i) && tileX <= boundMaxX(i) &&
      tileY >= boundMinY(i) && tileY <= boundMaxY(i)
  }).asUInt
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
  private val outsideX = outsideAxis(true, loX, hiX)
  private val outsideY = outsideAxis(false, loY, hiY)
  private val outsideGeometry = positiveW && (outsideX || outsideY)
  private val axisHitX = !outsideX
  private val axisHitY = !outsideY
  private val nextMinX = Mux(axisHitX && scanMinX === "hffff".U,
    tileX, scanMinX)
  private val nextMaxX = Mux(axisHitX, tileX, scanMaxX)
  private val nextMinY = Mux(axisHitY && scanMinY === "hffff".U,
    tileY, scanMinY)
  private val nextMaxY = Mux(axisHitY, tileY, scanMaxY)
  private val intersects = scissorIntersects && !outsideGeometry
  private val nextMask = buildMask |
    Mux(intersects, UIntToOH(scanIndex, capacity), 0.U(capacity.W))
  private val remaining = scanMask & ~UIntToOH(scanIndex, capacity)
  private val binWrite = WireDefault(false.B)
  private val binWriteMask = WireDefault(0.U(capacity.W))
  when(binWrite) { tileMasks.write(tileIndex, binWriteMask) }

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

  private def advanceBoundsDraw(): Unit = {
    tileX := 0.U
    tileY := 0.U
    when(boundsIndex +& 1.U < count) {
      boundsIndex := boundsIndex + 1.U
      state := boundsStart
    }.otherwise {
      tileIndex := 0.U
      state := binStart
    }
  }

  private def advanceBinTile(): Unit = {
    buildMask := 0.U
    scanMask := 0.U
    when(nextTileX < config.screenWidth.U) {
      tileX := nextTileX(15, 0)
      tileIndex := tileIndex + 1.U
      state := binStart
    }.elsewhen(nextTileY < config.screenHeight.U) {
      tileX := 0.U
      tileY := nextTileY(15, 0)
      tileIndex := tileIndex + 1.U
      state := binStart
    }.otherwise {
      tileX := 0.U
      tileY := 0.U
      tileIndex := 0.U
      state := loadTile
    }
  }

  when(io.start) {
    state := collect
    count := 0.U
    boundsIndex := 0.U
    scanMask := 0.U
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
            boundsIndex := 0.U
            scanMask := 0.U
            tileIndex := 0.U
            buildMask := 0.U
            tileX := 0.U
            tileY := 0.U
            state := boundsStart
          }
        }.elsewhen(io.inputDone) {
          when(count === 0.U) {
            state := complete
          }.otherwise {
            boundsIndex := 0.U
            scanMask := 0.U
            tileIndex := 0.U
            buildMask := 0.U
            tileX := 0.U
            tileY := 0.U
            state := boundsStart
          }
        }
      }
      is(boundsStart) {
        when(selected.scissorEnable || !positiveW) {
          // Scissor already provides a cheap range, and non-positive w may
          // produce clipped vertices outside the original projected bounds.
          boundMinX(boundsIndex) := 0.U
          boundMaxX(boundsIndex) := (((config.screenWidth - 1) / 16) * 16).U
          boundMinY(boundsIndex) := 0.U
          boundMaxY(boundsIndex) := (((config.screenHeight - 1) / 16) * 16).U
          advanceBoundsDraw()
        }.otherwise {
          scanMinX := "hffff".U
          scanMaxX := 0.U
          scanMinY := "hffff".U
          scanMaxY := 0.U
          tileX := 0.U
          tileY := 0.U
          state := boundsX
        }
      }
      is(boundsX) {
        scanMinX := nextMinX
        scanMaxX := nextMaxX
        when(nextTileX < config.screenWidth.U) {
          tileX := nextTileX(15, 0)
        }.otherwise {
          boundMinX(boundsIndex) := nextMinX
          boundMaxX(boundsIndex) := nextMaxX
          tileX := 0.U
          when(nextMinX === "hffff".U) {
            boundMinY(boundsIndex) := "hffff".U
            boundMaxY(boundsIndex) := 0.U
            advanceBoundsDraw()
          }.otherwise {
            state := boundsY
          }
        }
      }
      is(boundsY) {
        scanMinY := nextMinY
        scanMaxY := nextMaxY
        when(nextTileY < config.screenHeight.U) {
          tileY := nextTileY(15, 0)
        }.otherwise {
          boundMinY(boundsIndex) := nextMinY
          boundMaxY(boundsIndex) := nextMaxY
          advanceBoundsDraw()
        }
      }
      is(binStart) {
        when(candidates.orR) {
          scanMask := candidates
          state := binScan
        }.otherwise {
          binWrite := true.B
          binWriteMask := 0.U
          advanceBinTile()
        }
      }
      is(binScan) {
        when(remaining.orR) {
          buildMask := nextMask
          scanMask := remaining
        }.otherwise {
          binWrite := true.B
          binWriteMask := nextMask
          advanceBinTile()
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
