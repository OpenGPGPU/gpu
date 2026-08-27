package opengpu.core.memory

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig

/** Non-blocking write-through transaction table for one L2 slice.
  *
  * Lower IDs are the entry index plus lowerIdBase. The original requester ID
  * is restored only after the write acknowledgement, allowing lower responses
  * to return out of order. Requests to an already-active cache line are held
  * to preserve write ordering.
  */
class L2StoreTransactionTable(
  config: GpuConfig = GpuConfig(),
  entries: Int = 2,
  lineBytes: Int = 64,
  maxOutstanding: Int = 8,
  lowerIdBase: Int = 4
) extends Module {
  require(entries > 0 && isPow2(entries))
  require(lineBytes > 0 && isPow2(lineBytes))
  require(lowerIdBase >= 0)
  require(lowerIdBase + entries <= maxOutstanding)
  private val offsetWidth = log2Ceil(lineBytes)

  val io = IO(new Bundle {
    val allocate = Flipped(Decoupled(
      new ComputeMemoryRequest(config, lineBytes, maxOutstanding)))
    val lowerRequest = Decoupled(
      new ComputeMemoryRequest(config, lineBytes, maxOutstanding))
    val lowerResponse = Flipped(Decoupled(
      new ComputeMemoryResponse(lineBytes, maxOutstanding)))
    val response = Decoupled(
      new ComputeMemoryResponse(lineBytes, maxOutstanding))
    val active = Output(Bool())
  })

  private object EntryState {
    val free = 0.U(2.W)
    val issue = 1.U(2.W)
    val waitAck = 2.U(2.W)
    val respond = 3.U(2.W)
  }
  private val state = RegInit(VecInit(Seq.fill(entries)(EntryState.free)))
  private val requests = Reg(Vec(entries,
    new ComputeMemoryRequest(config, lineBytes, maxOutstanding)))
  private val faults = Reg(Vec(entries, Bool()))

  // Break the wide allocate-data path before the dynamically selected entry
  // write.  This stage can commit one request and capture the next request in
  // the same cycle, so it adds latency but does not reduce peak throughput.
  private val allocationPending = RegInit(false.B)
  private val pendingEntry = Reg(UInt(log2Ceil(entries).W))
  private val pendingRequest = Reg(
    new ComputeMemoryRequest(config, lineBytes, maxOutstanding))

  private val incomingLine = io.allocate.bits.address(
    config.xLen - 1, offsetWidth)
  private val lineConflict = VecInit((0 until entries).map { entry =>
    state(entry) =/= EntryState.free &&
      requests(entry).address(config.xLen - 1, offsetWidth) === incomingLine
  }).asUInt.orR || (allocationPending &&
    pendingRequest.address(config.xLen - 1, offsetWidth) === incomingLine)
  private val reservedEntries = UIntToOH(pendingEntry, entries) &
    Fill(entries, allocationPending)
  private val freeEntries = VecInit((0 until entries).map { entry =>
    state(entry) === EntryState.free && !reservedEntries(entry)
  })
  private val hasFree = freeEntries.asUInt.orR
  private val allocateEntry = PriorityEncoder(freeEntries)
  io.allocate.ready := hasFree && !lineConflict
  allocationPending := io.allocate.fire
  when(io.allocate.fire) {
    assert(io.allocate.bits.isWrite,
      "L2 store table accepts only write requests")
    pendingEntry := allocateEntry
    pendingRequest := io.allocate.bits
  }
  when(allocationPending) {
    requests(pendingEntry) := pendingRequest
    state(pendingEntry) := EntryState.issue
  }

  private val issueEntries = VecInit(state.map(_ === EntryState.issue))
  private val hasIssue = issueEntries.asUInt.orR
  private val issueEntry = PriorityEncoder(issueEntries)
  io.lowerRequest.valid := hasIssue
  io.lowerRequest.bits := requests(issueEntry)
  io.lowerRequest.bits.transactionId := issueEntry + lowerIdBase.U
  when(io.lowerRequest.fire) { state(issueEntry) := EntryState.waitAck }

  private val lowerId = io.lowerResponse.bits.transactionId
  private val lowerInRange = lowerId >= lowerIdBase.U &&
    lowerId < (lowerIdBase + entries).U
  private val ackEntry = (lowerId - lowerIdBase.U)(log2Ceil(entries) - 1, 0)
  private val ackExpected = lowerInRange &&
    state(ackEntry) === EntryState.waitAck
  io.lowerResponse.ready := ackExpected
  when(io.lowerResponse.valid) {
    assert(ackExpected,
      "L2 store acknowledgement must identify an issued store entry")
  }
  when(io.lowerResponse.fire) {
    faults(ackEntry) := io.lowerResponse.bits.fault
    state(ackEntry) := EntryState.respond
  }

  private val responseEntries = VecInit(state.map(_ === EntryState.respond))
  private val hasResponse = responseEntries.asUInt.orR
  private val responseEntry = PriorityEncoder(responseEntries)
  io.response.valid := hasResponse
  io.response.bits.transactionId := requests(responseEntry).transactionId
  io.response.bits.readData := 0.U
  io.response.bits.fault := faults(responseEntry)
  when(io.response.fire) { state(responseEntry) := EntryState.free }

  io.active := allocationPending ||
    state.map(_ =/= EntryState.free).reduce(_ || _)
}
