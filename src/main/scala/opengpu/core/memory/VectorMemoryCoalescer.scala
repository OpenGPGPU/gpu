package opengpu.core.memory

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig

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

/** Coalesces one vector transaction into the unique cache lines it touches.
  *
  * Unit-stride lanes normally collapse into one or two requests, while strided
  * lanes may occupy independent lines. Requests are issued one at a time and
  * load bytes are reassembled only after every required line has responded.
  */
class VectorMemoryCoalescer(
  config: GpuConfig = GpuConfig(),
  lineBytes: Int = 64
) extends Module {
  require(isPow2(lineBytes), "cache line size must be a power of two")
  require(lineBytes >= config.xLen / 8, "one element must span at most two lines")

  private val lineOffsetWidth = log2Ceil(lineBytes)
  private val lineWidth = lineBytes * 8
  private val maxLines = config.lanes * 2
  private val requestIndexWidth = math.max(1, log2Ceil(maxLines))
  private val lineCountWidth = math.max(1, log2Ceil(maxLines + 1))
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
  private val requestIndex = RegInit(0.U(requestIndexWidth.W))
  private val lineCount = Reg(UInt(lineCountWidth.W))
  private val lineAddresses = Reg(Vec(maxLines, UInt(config.xLen.W)))
  private val waitingResponse = RegInit(false.B)
  private val lineData = Reg(Vec(maxLines, UInt(lineWidth.W)))
  private val lineFaults = RegInit(VecInit(Seq.fill(maxLines)(false.B)))
  private val linePageFaults = RegInit(VecInit(Seq.fill(maxLines)(false.B)))
  private val outputValid = RegInit(false.B)
  private val outputBits = Reg(new VectorMemoryResponse(config))

  private val elementBytes = 1.U << transaction.elementSize
  private val selectedLineAddress = lineAddresses(requestIndex)

  private def alignLine(address: UInt): UInt =
    Cat(
      address(config.xLen - 1, lineOffsetWidth),
      0.U(lineOffsetWidth.W)
    )

  private val inputElementBytes = 1.U << io.in.bits.elementSize
  private val candidateValid = Wire(Vec(maxLines, Bool()))
  private val candidateAddress = Wire(Vec(maxLines, UInt(config.xLen.W)))
  for (lane <- 0 until config.lanes) {
    val first = alignLine(io.in.bits.addresses(lane))
    val last = alignLine(
      io.in.bits.addresses(lane) + inputElementBytes - 1.U
    )
    candidateValid(lane * 2) := io.in.bits.laneMask(lane)
    candidateAddress(lane * 2) := first
    candidateValid(lane * 2 + 1) :=
      io.in.bits.laneMask(lane) && last =/= first
    candidateAddress(lane * 2 + 1) := last
  }

  private val uniqueCandidate = Wire(Vec(maxLines, Bool()))
  private val inputLineAddresses = Wire(Vec(maxLines, UInt(config.xLen.W)))
  inputLineAddresses.foreach(_ := 0.U)
  for (candidate <- 0 until maxLines) {
    val duplicate = if (candidate == 0) {
      false.B
    } else {
      VecInit((0 until candidate).map { previous =>
        candidateValid(previous) &&
          candidateAddress(previous) === candidateAddress(candidate)
      }).asUInt.orR
    }
    uniqueCandidate(candidate) := candidateValid(candidate) && !duplicate
    val compactIndexRaw = if (candidate == 0) {
      0.U
    } else {
      PopCount(VecInit((0 until candidate).map(uniqueCandidate(_))))
    }
    val compactIndex = Wire(UInt(requestIndexWidth.W))
    compactIndex := compactIndexRaw
    when(uniqueCandidate(candidate)) {
      inputLineAddresses(compactIndex) := candidateAddress(candidate)
    }
  }
  private val inputLineCount = PopCount(uniqueCandidate)

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
      lineCount := inputLineCount
      waitingResponse := false.B
      for (line <- 0 until maxLines) {
        lineAddresses(line) := inputLineAddresses(line)
        lineFaults(line) := false.B
        linePageFaults(line) := false.B
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
    lineData(requestIndex) := io.cacheResponse.bits.readData
    lineFaults(requestIndex) := io.cacheResponse.bits.fault
    linePageFaults(requestIndex) := io.cacheResponse.bits.pageFault

    when(requestIndex =/= lineCount - 1.U) {
      requestIndex := requestIndex + 1.U
    }.otherwise {
      transactionValid := false.B
      outputValid := true.B
      val resolvedLineData = Wire(Vec(maxLines, UInt(lineWidth.W)))
      val resolvedLineFaults = Wire(Vec(maxLines, Bool()))
      val resolvedLinePageFaults = Wire(Vec(maxLines, Bool()))
      for (line <- 0 until maxLines) {
        val isCurrent = requestIndex === line.U
        resolvedLineData(line) :=
          Mux(isCurrent, io.cacheResponse.bits.readData, lineData(line))
        resolvedLineFaults(line) :=
          Mux(isCurrent, io.cacheResponse.bits.fault, lineFaults(line))
        resolvedLinePageFaults(line) :=
          Mux(
            isCurrent,
            io.cacheResponse.bits.pageFault,
            linePageFaults(line)
          )
      }
      val laneFaults = Wire(Vec(config.lanes, Bool()))
      for (lane <- 0 until config.lanes) {
        val laneBytes = Wire(Vec(4, UInt(8.W)))
        for (byte <- 0 until 4) {
          val byteAddress = transaction.addresses(lane) + byte.U
          val offset = byteAddress(lineOffsetWidth - 1, 0)
          val selectedByte = WireDefault(0.U(8.W))
          for (line <- 0 until maxLines) {
            when(
              line.U < lineCount &&
                alignLine(byteAddress) === lineAddresses(line)
            ) {
              selectedByte := resolvedLineData(line).asTypeOf(
                Vec(lineBytes, UInt(8.W))
              )(offset)
            }
          }
          laneBytes(byte) := selectedByte
        }
        outputBits.readData(lane) := laneBytes.asUInt
        val first = alignLine(transaction.addresses(lane))
        val last = alignLine(
          transaction.addresses(lane) + elementBytes - 1.U
        )
        val matchingFaults = (0 until maxLines).map { line =>
          line.U < lineCount &&
            (first === lineAddresses(line) || last === lineAddresses(line)) &&
            resolvedLineFaults(line)
        }
        laneFaults(lane) := transaction.laneMask(lane) &&
          VecInit(matchingFaults).asUInt.orR
      }
      outputBits.faultMask := laneFaults.asUInt
      outputBits.pageFault := VecInit((0 until maxLines).map { line =>
        line.U < lineCount && resolvedLineFaults(line) &&
          resolvedLinePageFaults(line)
      }).asUInt.orR
    }
  }

  io.out.valid := outputValid
  io.out.bits := outputBits
  when(io.out.fire) {
    outputValid := false.B
  }
}
