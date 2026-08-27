package opengpu.core.memory

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig

class ComputeMemoryRequest(
  config: GpuConfig,
  val lineBytes: Int = 64,
  val maxOutstanding: Int = 4
)
    extends Bundle {
  val address = UInt(config.xLen.W)
  val writeData = UInt((lineBytes * 8).W)
  val byteMask = UInt(lineBytes.W)
  val isWrite = Bool()
  /** log2(bytes): 2 for a PTE word, 6 for a 64-byte cache line. */
  val sizeLog2 = UInt(3.W)
  /** True only for traffic backed by a private data-cache line. */
  val cacheClient = Bool()
  /** For a store, the requesting private cache currently holds the line. */
  val cacheResident = Bool()
  val transactionId = UInt(math.max(1, log2Ceil(maxOutstanding)).W)
}

class ComputeMemoryResponse(
  val lineBytes: Int = 64,
  val maxOutstanding: Int = 4
) extends Bundle {
  val readData = UInt((lineBytes * 8).W)
  val fault = Bool()
  val transactionId = UInt(math.max(1, log2Ceil(maxOutstanding)).W)
}

/** Serializes instruction refill, data-cache traffic, and both PTWs onto one
  * physical memory port. The selected source is retained until its response
  * is accepted, so arbitrary response latency cannot misroute data.
  *
  * Response-timing contract (a testbench backing `memoryRequest/memoryResponse`
  * must obey these):
  *  - A transaction slot (`transactionValid`) is allocated on `memoryRequest.fire`
  *    and is released only when the matching `memoryResponse` is accepted. Writes
  *    are *not* fire-and-forget: they hold a slot until acknowledged (write-through
  *    cache dependency), so a harness that never answers a write exhausts the
  *    `maxOutstanding` slots and stalls the interconnect.
  *  - Response routing reads `transactionValid/transactionSource`, which are
  *    registered on the request-fire edge. A response may therefore reference only
  *    a transaction committed in a *prior* cycle (minimum one-cycle request-to-
  *    response spacing); a same-cycle response would reference a not-yet-committed
  *    slot and is a protocol violation rejected by the assert below.
  *  - `memoryResponse.ready` is the ready of the downstream sink chosen by the
  *    stored source, so response-side backpressure propagates naturally.
  */
class ComputeUnitMemoryInterconnect(
  config: GpuConfig = GpuConfig(),
  lineBytes: Int = 64,
  maxOutstanding: Int = 4
) extends Module {
  require(lineBytes == 64, "current cache hierarchy uses 64-byte lines")
  require(maxOutstanding >= 1)
  private val transactionIdWidth = math.max(1, log2Ceil(maxOutstanding))
  val io = IO(new Bundle {
    val instructionRequest =
      Flipped(Decoupled(new InstructionLineRequest(config, lineBytes)))
    val instructionResponse =
      Decoupled(new InstructionLineResponse(lineBytes))
    val dataRequest =
      Flipped(Decoupled(new VectorLowerMemoryRequest(config, lineBytes)))
    val dataResponse = Decoupled(new VectorLowerMemoryResponse(lineBytes))
    val instructionPageTableRequest =
      Flipped(Decoupled(new PageTableMemoryRequest(config)))
    val instructionPageTableResponse = Decoupled(new PageTableMemoryResponse)
    val dataPageTableRequest =
      Flipped(Decoupled(new PageTableMemoryRequest(config)))
    val dataPageTableResponse = Decoupled(new PageTableMemoryResponse)
    val memoryRequest = Decoupled(
      new ComputeMemoryRequest(config, lineBytes, maxOutstanding))
    val memoryResponse = Flipped(Decoupled(
      new ComputeMemoryResponse(lineBytes, maxOutstanding)))
  })

  private val arbiter = Module(new RRArbiter(
    new ComputeMemoryRequest(config, lineBytes, maxOutstanding), 4))
  private val transactionValid =
    RegInit(VecInit(Seq.fill(maxOutstanding)(false.B)))
  private val transactionSource = Reg(Vec(maxOutstanding, UInt(2.W)))
  private val transactionInstructionId = Reg(Vec(maxOutstanding, UInt(2.W)))
  private val freeTransactions = ~transactionValid.asUInt
  private val hasFreeTransaction = freeTransactions.orR
  private val allocatedId = PriorityEncoder(freeTransactions)
  private val sourceBusy = Wire(Vec(4, Bool()))
  for (source <- 0 until 4) {
    if (source == 0) sourceBusy(source) := false.B
    else sourceBusy(source) := (0 until maxOutstanding).map { id =>
        transactionValid(id) && transactionSource(id) === source.U
      }.reduce(_ || _)
  }

  arbiter.io.in(0).valid := io.instructionRequest.valid && !sourceBusy(0)
  arbiter.io.in(0).bits.address := io.instructionRequest.bits.lineAddress
  arbiter.io.in(0).bits.writeData := 0.U
  arbiter.io.in(0).bits.byteMask := 0.U
  arbiter.io.in(0).bits.isWrite := false.B
  arbiter.io.in(0).bits.sizeLog2 := 6.U
  arbiter.io.in(0).bits.cacheClient := false.B
  arbiter.io.in(0).bits.cacheResident := false.B
  arbiter.io.in(0).bits.transactionId := allocatedId
  io.instructionRequest.ready := arbiter.io.in(0).ready && !sourceBusy(0)

  arbiter.io.in(1).valid := io.dataRequest.valid && !sourceBusy(1)
  arbiter.io.in(1).bits.address := io.dataRequest.bits.lineAddress
  arbiter.io.in(1).bits.writeData := io.dataRequest.bits.writeData
  arbiter.io.in(1).bits.byteMask := io.dataRequest.bits.byteMask
  arbiter.io.in(1).bits.isWrite := io.dataRequest.bits.isWrite
  arbiter.io.in(1).bits.sizeLog2 := 6.U
  arbiter.io.in(1).bits.cacheClient := true.B
  arbiter.io.in(1).bits.cacheResident := io.dataRequest.bits.cacheResident
  arbiter.io.in(1).bits.transactionId := allocatedId
  io.dataRequest.ready := arbiter.io.in(1).ready && !sourceBusy(1)

  private def connectPageTableRequest(index: Int, request: DecoupledIO[PageTableMemoryRequest]): Unit = {
    arbiter.io.in(index).valid := request.valid && !sourceBusy(index)
    arbiter.io.in(index).bits.address := request.bits.address
    arbiter.io.in(index).bits.writeData := 0.U
    arbiter.io.in(index).bits.byteMask := 0.U
    arbiter.io.in(index).bits.isWrite := false.B
    arbiter.io.in(index).bits.sizeLog2 := 2.U
    arbiter.io.in(index).bits.cacheClient := false.B
    arbiter.io.in(index).bits.cacheResident := false.B
    arbiter.io.in(index).bits.transactionId := allocatedId
    request.ready := arbiter.io.in(index).ready && !sourceBusy(index)
  }
  connectPageTableRequest(2, io.instructionPageTableRequest)
  connectPageTableRequest(3, io.dataPageTableRequest)

  io.memoryRequest.valid := hasFreeTransaction && arbiter.io.out.valid
  io.memoryRequest.bits := arbiter.io.out.bits
  io.memoryRequest.bits.transactionId := allocatedId
  arbiter.io.out.ready := hasFreeTransaction && io.memoryRequest.ready
  when(io.memoryRequest.fire) {
    transactionValid(allocatedId) := true.B
    transactionSource(allocatedId) := arbiter.io.chosen
    transactionInstructionId(allocatedId) := io.instructionRequest.bits.requestId
  }

  private val responseId = io.memoryResponse.bits.transactionId
  private val responseIdInRange = responseId < maxOutstanding.U
  private val responseIdValid = responseIdInRange && transactionValid(responseId)
  private val responseSource = Mux(
    responseIdValid, transactionSource(responseId), 0.U)
  io.instructionResponse.valid := responseIdValid && responseSource === 0.U && io.memoryResponse.valid
  io.instructionResponse.bits.readData := io.memoryResponse.bits.readData
  io.instructionResponse.bits.fault := io.memoryResponse.bits.fault
  io.instructionResponse.bits.requestId := transactionInstructionId(responseId)
  io.dataResponse.valid := responseIdValid && responseSource === 1.U && io.memoryResponse.valid
  io.dataResponse.bits.readData := io.memoryResponse.bits.readData
  io.dataResponse.bits.fault := io.memoryResponse.bits.fault
  io.instructionPageTableResponse.valid :=
    responseIdValid && responseSource === 2.U && io.memoryResponse.valid
  io.instructionPageTableResponse.bits.pte := io.memoryResponse.bits.readData(31, 0)
  io.instructionPageTableResponse.bits.fault := io.memoryResponse.bits.fault
  io.dataPageTableResponse.valid :=
    responseIdValid && responseSource === 3.U && io.memoryResponse.valid
  io.dataPageTableResponse.bits.pte := io.memoryResponse.bits.readData(31, 0)
  io.dataPageTableResponse.bits.fault := io.memoryResponse.bits.fault

  private val selectedReady = MuxLookup(responseSource, false.B)(Seq(
    0.U -> io.instructionResponse.ready,
    1.U -> io.dataResponse.ready,
    2.U -> io.instructionPageTableResponse.ready,
    3.U -> io.dataPageTableResponse.ready
  ))
  io.memoryResponse.ready := responseIdValid && selectedReady
  when(io.memoryResponse.fire) { transactionValid(responseId) := false.B }
  when(io.memoryResponse.valid) {
    assert(responseIdValid, "memory response must reference an outstanding transaction")
  }
}
