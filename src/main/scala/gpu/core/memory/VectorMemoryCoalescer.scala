package gpu.core.memory

import chisel3._
import chisel3.util._
import gpu.config.GpuConfig

class VectorCacheLineRequest(
  config: GpuConfig,
  val lineBytes: Int = 64
) extends Bundle {
  val warpId = UInt(config.warpIdWidth.W)
  val lineAddress = UInt(config.xLen.W)
  val writeData = UInt((lineBytes * 8).W)
  val byteMask = UInt(lineBytes.W)
  val isStore = Bool()
}

class VectorCacheLineResponse(val lineBytes: Int = 64) extends Bundle {
  val readData = UInt((lineBytes * 8).W)
  val fault = Bool()
  val pageFault = Bool()
}

/** Coalesces one contiguous unit-stride vector transaction into cache lines.
  *
  * The current architectural profile has one element per lane and at most one
  * cache-line worth of vector data, so an unaligned vector spans at most two
  * lines. Requests are issued one at a time and load bytes are reassembled
  * only after every required line has responded.
  */
class VectorMemoryCoalescer(
  config: GpuConfig = GpuConfig(),
  lineBytes: Int = 64
) extends Module {
  require(isPow2(lineBytes), "cache line size must be a power of two")
  require(
    config.lanes * (config.xLen / 8) <= lineBytes,
    "one vector transaction must contain at most one cache line of data"
  )

  private val lineOffsetWidth = log2Ceil(lineBytes)
  private val lineWidth = lineBytes * 8
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new VectorMemoryTransaction(config)))
    val cacheRequest =
      Decoupled(new VectorCacheLineRequest(config, lineBytes))
    val cacheResponse =
      Flipped(Decoupled(new VectorCacheLineResponse(lineBytes)))
    val out = Decoupled(new VectorMemoryResponse(config))
  })

  private val transactionValid = RegInit(false.B)
  private val transaction = Reg(new VectorMemoryTransaction(config))
  private val requestIndex = RegInit(0.U(1.W))
  private val waitingResponse = RegInit(false.B)
  private val firstLineData = Reg(UInt(lineWidth.W))
  private val secondLineData = Reg(UInt(lineWidth.W))
  private val firstLineFault = RegInit(false.B)
  private val secondLineFault = RegInit(false.B)
  private val firstLinePageFault = RegInit(false.B)
  private val secondLinePageFault = RegInit(false.B)
  private val outputValid = RegInit(false.B)
  private val outputBits = Reg(new VectorMemoryResponse(config))

  private val firstLineAddress =
    Cat(
      transaction.addresses(0)(config.xLen - 1, lineOffsetWidth),
      0.U(lineOffsetWidth.W)
    )
  private val lastLane = config.lanes - 1
  private val elementBytes = 1.U << transaction.elementSize
  private val lastByteAddress =
    transaction.addresses(lastLane) + elementBytes - 1.U
  private val needsSecondLine =
    lastByteAddress(config.xLen - 1, lineOffsetWidth) =/=
      firstLineAddress(config.xLen - 1, lineOffsetWidth)
  private val selectedLineAddress =
    firstLineAddress + (requestIndex << lineOffsetWidth)

  private val lineDataBytes = Wire(Vec(lineBytes, UInt(8.W)))
  private val lineByteMask = Wire(Vec(lineBytes, Bool()))
  lineDataBytes.foreach(_ := 0.U)
  lineByteMask.foreach(_ := false.B)
  for {
    lane <- 0 until config.lanes
    byte <- 0 until 4
  } {
    val byteEnabled =
      transaction.laneMask(lane) && byte.U < elementBytes
    val byteAddress = transaction.addresses(lane) + byte.U
    val belongsToSelectedLine =
      byteAddress(config.xLen - 1, lineOffsetWidth) ===
        selectedLineAddress(config.xLen - 1, lineOffsetWidth)
    val offset = byteAddress(lineOffsetWidth - 1, 0)
    when(byteEnabled && belongsToSelectedLine) {
      lineDataBytes(offset) :=
        transaction.writeData(lane)(byte * 8 + 7, byte * 8)
      lineByteMask(offset) := true.B
    }
  }

  io.in.ready := !transactionValid && !outputValid
  when(io.in.fire) {
    when(io.in.bits.laneMask === 0.U) {
      outputValid := true.B
      outputBits.readData := 0.U.asTypeOf(outputBits.readData)
      outputBits.faultMask := 0.U
      outputBits.pageFault := false.B
    }.otherwise {
      transactionValid := true.B
      transaction := io.in.bits
      requestIndex := 0.U
      waitingResponse := false.B
      firstLineFault := false.B
      secondLineFault := false.B
      firstLinePageFault := false.B
      secondLinePageFault := false.B
      for (lane <- 1 until config.lanes) {
        assert(
          io.in.bits.addresses(lane) ===
            io.in.bits.addresses(lane - 1) +
              (1.U << io.in.bits.elementSize),
          "VectorMemoryCoalescer accepts contiguous unit-stride transactions"
        )
      }
    }
  }

  io.cacheRequest.valid := transactionValid && !waitingResponse
  io.cacheRequest.bits.warpId := transaction.warpId
  io.cacheRequest.bits.lineAddress := selectedLineAddress
  io.cacheRequest.bits.writeData := lineDataBytes.asUInt
  io.cacheRequest.bits.byteMask := lineByteMask.asUInt
  io.cacheRequest.bits.isStore := transaction.isStore
  when(io.cacheRequest.fire) {
    waitingResponse := true.B
  }

  io.cacheResponse.ready :=
    transactionValid && waitingResponse && !outputValid
  when(io.cacheResponse.fire) {
    waitingResponse := false.B
    when(requestIndex === 0.U) {
      firstLineData := io.cacheResponse.bits.readData
      firstLineFault := io.cacheResponse.bits.fault
      firstLinePageFault := io.cacheResponse.bits.pageFault
    }.otherwise {
      secondLineData := io.cacheResponse.bits.readData
      secondLineFault := io.cacheResponse.bits.fault
      secondLinePageFault := io.cacheResponse.bits.pageFault
    }

    when(requestIndex === 0.U && needsSecondLine) {
      requestIndex := 1.U
    }.otherwise {
      transactionValid := false.B
      outputValid := true.B
      val responseFirstData =
        Mux(requestIndex === 0.U, io.cacheResponse.bits.readData, firstLineData)
      val responseSecondData =
        Mux(requestIndex === 1.U, io.cacheResponse.bits.readData, secondLineData)
      val responseFirstFault =
        Mux(requestIndex === 0.U, io.cacheResponse.bits.fault, firstLineFault)
      val responseSecondFault =
        Mux(requestIndex === 1.U, io.cacheResponse.bits.fault, secondLineFault)
      val responseFirstPageFault = Mux(
        requestIndex === 0.U,
        io.cacheResponse.bits.pageFault,
        firstLinePageFault
      )
      val responseSecondPageFault = Mux(
        requestIndex === 1.U,
        io.cacheResponse.bits.pageFault,
        secondLinePageFault
      )
      val laneFaults = Wire(Vec(config.lanes, Bool()))
      for (lane <- 0 until config.lanes) {
        val laneBytes = Wire(Vec(4, UInt(8.W)))
        for (byte <- 0 until 4) {
          val byteAddress = transaction.addresses(lane) + byte.U
          val fromSecond =
            byteAddress(config.xLen - 1, lineOffsetWidth) =/=
              firstLineAddress(config.xLen - 1, lineOffsetWidth)
          val offset = byteAddress(lineOffsetWidth - 1, 0)
          val selectedLine =
            Mux(fromSecond, responseSecondData, responseFirstData)
          laneBytes(byte) := selectedLine.asTypeOf(
            Vec(lineBytes, UInt(8.W))
          )(offset)
        }
        outputBits.readData(lane) := laneBytes.asUInt
        val touchesSecond =
          (transaction.addresses(lane) + elementBytes - 1.U)(
            config.xLen - 1,
            lineOffsetWidth
          ) =/= firstLineAddress(
            config.xLen - 1,
            lineOffsetWidth
          )
        val touchesFirst =
          transaction.addresses(lane)(
            config.xLen - 1,
            lineOffsetWidth
          ) === firstLineAddress(
            config.xLen - 1,
            lineOffsetWidth
          )
        laneFaults(lane) :=
          transaction.laneMask(lane) &&
            ((touchesFirst && responseFirstFault) ||
              (touchesSecond && responseSecondFault))
      }
      outputBits.faultMask := laneFaults.asUInt
      outputBits.pageFault :=
        (responseFirstFault && responseFirstPageFault) ||
          (responseSecondFault && responseSecondPageFault)
    }
  }

  io.out.valid := outputValid
  io.out.bits := outputBits
  when(io.out.fire) {
    outputValid := false.B
  }
}
