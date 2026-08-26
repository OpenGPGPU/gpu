package gpu.graphics

import chisel3._
import chisel3.util._
import gpu.config.GpuConfig
import gpu.core.memory.{ComputeMemoryRequest, ComputeMemoryResponse}

/** Adapts the graphics word-level memory port to the core's line-level memory.
  *
  * The graphics fixed-function stages (`CommandBufferStage`, `OutputMerger`,
  * kernarg staging) speak word requests via `OmMemoryRequest` (a single 32-bit
  * read or write at a byte address).  The compute core and shared L2 speak
  * line requests via `ComputeMemoryRequest` (a 64-byte line with a per-byte
  * write mask).  This port translates between the two so the graphics stages
  * and the SIMT-kernel shader can share one line-based physical memory.
  *
  * Behaviour (matching the core memory interconnect's response-timing rules):
  *  - A word request is accepted only when a transaction slot is free; on the
  *    same cycle it is issued as a line request carrying that slot's id.
  *  - The matching line response, keyed by the echoed transaction id, is
  *    trimmed to the requested word and returned on the word port.
  *  - Reads and writes both hold their slot until the line response is
  *    accepted, so a write is acknowledged (not fire-and-forget).
  */
class OmWordToLinePort(
  config: GpuConfig = GpuConfig(),
  lineBytes: Int = 64,
  maxOutstanding: Int = 4
) extends Module {
  require(lineBytes == 64, "current cache hierarchy uses 64-byte lines")
  require(maxOutstanding >= 1)
  private val offsetWidth = log2Ceil(lineBytes)
  private val wordsPerLine = lineBytes / 4

  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new OmMemoryRequest))
    val out = Decoupled(new OmMemoryResponse)
    val memoryRequest =
      Decoupled(new ComputeMemoryRequest(config, lineBytes, maxOutstanding))
    val memoryResponse = Flipped(
      Decoupled(new ComputeMemoryResponse(lineBytes, maxOutstanding)))
  })

  private val wordIdx = io.in.bits.addr(offsetWidth - 1, 2)
  private val lineAddress = Cat(
    io.in.bits.addr(config.xLen - 1, offsetWidth), 0.U(offsetWidth.W))

  // Place the word at its byte offset within the line (word0 at bits[31:0]).
  private val wordSelect = VecInit((0 until wordsPerLine).map { i =>
    Mux(wordIdx === i.U, io.in.bits.data, 0.U(32.W))
  })
  private val writeData = wordSelect.asUInt
  private val byteMask = 0xf.U(lineBytes.W) << (wordIdx * 4.U)

  private val transactionValid =
    RegInit(VecInit(Seq.fill(maxOutstanding)(false.B)))
  private val transactionWord = Reg(Vec(maxOutstanding, UInt(4.W)))
  private val transactionWrite = Reg(Vec(maxOutstanding, Bool()))
  private val freeTransactions = ~transactionValid.asUInt
  private val hasFreeTransaction = freeTransactions.orR
  private val allocatedId = PriorityEncoder(freeTransactions)

  io.memoryRequest.valid := io.in.valid && hasFreeTransaction
  io.memoryRequest.bits.address := lineAddress
  io.memoryRequest.bits.writeData := writeData
  io.memoryRequest.bits.byteMask := byteMask
  io.memoryRequest.bits.isWrite := io.in.bits.write
  io.memoryRequest.bits.sizeLog2 := 6.U
  io.memoryRequest.bits.cacheClient := false.B
  io.memoryRequest.bits.cacheResident := false.B
  io.memoryRequest.bits.transactionId := allocatedId
  io.in.ready := hasFreeTransaction && io.memoryRequest.ready

  when(io.memoryRequest.fire) {
    transactionValid(allocatedId) := true.B
    transactionWord(allocatedId) := wordIdx
    transactionWrite(allocatedId) := io.in.bits.write
  }

  private val responseId = io.memoryResponse.bits.transactionId
  private val responseIdInRange = responseId < maxOutstanding.U
  private val responseIdValid = responseIdInRange && transactionValid(responseId)
  io.out.valid := responseIdValid && io.memoryResponse.valid
  io.out.bits.data :=
    (io.memoryResponse.bits.readData >> (transactionWord(responseId) * 32.U))(31, 0)
  io.out.bits.write := transactionWrite(responseId)
  io.memoryResponse.ready := responseIdValid && io.out.ready
  when(io.memoryResponse.fire) { transactionValid(responseId) := false.B }
  when(io.memoryResponse.valid) {
    assert(responseIdValid,
      "word-line response must reference an outstanding transaction")
  }
}
