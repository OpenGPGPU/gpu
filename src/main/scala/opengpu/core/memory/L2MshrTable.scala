package opengpu.core.memory

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig

class L2MshrRequest(
  config: GpuConfig,
  val maxOutstanding: Int,
  val numComputeUnits: Int,
  val sets: Int,
  val ways: Int
) extends Bundle {
  val lineAddress = UInt(config.xLen.W)
  val transactionId = UInt(math.max(1, log2Ceil(maxOutstanding)).W)
  val requester = UInt(math.max(1, log2Ceil(numComputeUnits)).W)
  val trackSharer = Bool()
  val victimSet = UInt(math.max(1, log2Ceil(sets)).W)
  val victimWay = UInt(math.max(1, log2Ceil(ways)).W)
}

class L2MshrAllocation(val entries: Int) extends Bundle {
  val entryId = UInt(math.max(1, log2Ceil(entries)).W)
  val merged = Bool()
}

class L2MshrRefill(
  val entries: Int,
  val lineBytes: Int
) extends Bundle {
  val entryId = UInt(math.max(1, log2Ceil(entries)).W)
  val readData = UInt((lineBytes * 8).W)
  val fault = Bool()
}

class L2MshrResponse(
  config: GpuConfig,
  val maxOutstanding: Int,
  val lineBytes: Int
) extends Bundle {
  val lineAddress = UInt(config.xLen.W)
  val transactionId = UInt(math.max(1, log2Ceil(maxOutstanding)).W)
  val readData = UInt((lineBytes * 8).W)
  val fault = Bool()
}

class L2MshrFill(
  config: GpuConfig,
  val entries: Int,
  val numComputeUnits: Int,
  val lineBytes: Int,
  val sets: Int,
  val ways: Int
) extends Bundle {
  val entryId = UInt(math.max(1, log2Ceil(entries)).W)
  val lineAddress = UInt(config.xLen.W)
  val sharers = UInt(numComputeUnits.W)
  val readData = UInt((lineBytes * 8).W)
  val fault = Bool()
  val victimSet = UInt(math.max(1, log2Ceil(sets)).W)
  val victimWay = UInt(math.max(1, log2Ceil(ways)).W)
}

/** Multi-entry load-miss status table.
  *
  * Different lines allocate independent entries. Requests to an existing line
  * merge their transaction and CU ownership. Refills may arrive out of order;
  * each refill is first exposed to the cache fill pipeline, then all merged
  * requesters receive the same data/fault response.
  */
class L2MshrTable(
  config: GpuConfig = GpuConfig(),
  entries: Int = 4,
  maxOutstanding: Int = 8,
  numComputeUnits: Int = 2,
  lineBytes: Int = 64,
  sets: Int = 64,
  ways: Int = 4
) extends Module {
  require(entries > 0 && isPow2(entries))
  require(maxOutstanding > 0)
  require(numComputeUnits > 0)
  require(sets > 1 && isPow2(sets))
  require(ways > 0 && isPow2(ways))
  private val entryWidth = math.max(1, log2Ceil(entries))

  val io = IO(new Bundle {
    val request = Flipped(Decoupled(new L2MshrRequest(
      config, maxOutstanding, numComputeUnits, sets, ways)))
    val allocation = Decoupled(new L2MshrAllocation(entries))
    val refill = Flipped(Decoupled(new L2MshrRefill(entries, lineBytes)))
    val fill = Decoupled(new L2MshrFill(
      config, entries, numComputeUnits, lineBytes, sets, ways))
    val response = Decoupled(new L2MshrResponse(
      config, maxOutstanding, lineBytes))
    val validEntries = Output(UInt(entries.W))
  })

  private val valid = RegInit(VecInit(Seq.fill(entries)(false.B)))
  private val refilled = RegInit(VecInit(Seq.fill(entries)(false.B)))
  private val fillSent = RegInit(VecInit(Seq.fill(entries)(false.B)))
  private val lineAddress = Reg(Vec(entries, UInt(config.xLen.W)))
  private val waiters = RegInit(VecInit(Seq.fill(entries)(0.U(maxOutstanding.W))))
  private val sharers = RegInit(VecInit(Seq.fill(entries)(0.U(numComputeUnits.W))))
  private val refillData = Reg(Vec(entries, UInt((lineBytes * 8).W)))
  private val refillFault = Reg(Vec(entries, Bool()))
  private val victimSet = Reg(Vec(entries,
    UInt(math.max(1, log2Ceil(sets)).W)))
  private val victimWay = Reg(Vec(entries,
    UInt(math.max(1, log2Ceil(ways)).W)))

  private val lineMatch = VecInit((0 until entries).map { entry =>
    valid(entry) && lineAddress(entry) === io.request.bits.lineAddress
  })
  private val hasMatch = lineMatch.asUInt.orR
  private val matchedEntry = PriorityEncoder(lineMatch)
  private val free = ~valid.asUInt
  private val hasFree = free.orR
  private val freeEntry = PriorityEncoder(free)
  private val selectedEntry = Mux(hasMatch, matchedEntry, freeEntry)
  private val duplicateWaiter = hasMatch &&
    waiters(matchedEntry)(io.request.bits.transactionId)
  private val victimConflict = VecInit((0 until entries).map { entry =>
    valid(entry) && victimSet(entry) === io.request.bits.victimSet &&
      victimWay(entry) === io.request.bits.victimWay
  }).asUInt.orR

  // The lookup result is registered before any entry array is updated.  The
  // original single-cycle path ran valid/line matching and priority selection
  // directly into the dynamically selected waiter register.  Allocation was
  // already a two-cycle handshake, so this boundary preserves its maximum
  // acceptance rate while removing that long feedback path.
  private val allocationValid = RegInit(false.B)
  private val allocationRequest = Reg(new L2MshrRequest(
    config, maxOutstanding, numComputeUnits, sets, ways))
  private val allocationEntry = Reg(UInt(entryWidth.W))
  private val allocationMerged = Reg(Bool())
  io.request.ready := !allocationValid &&
    (hasMatch || (hasFree && !victimConflict)) && !duplicateWaiter
  io.allocation.valid := allocationValid
  io.allocation.bits.entryId := allocationEntry
  io.allocation.bits.merged := allocationMerged
  when(io.request.fire) {
    allocationRequest := io.request.bits
    allocationEntry := selectedEntry
    allocationMerged := hasMatch
    allocationValid := true.B
  }
  when(io.allocation.fire) {
    when(!allocationMerged) {
      valid(allocationEntry) := true.B
      refilled(allocationEntry) := false.B
      fillSent(allocationEntry) := false.B
      lineAddress(allocationEntry) := allocationRequest.lineAddress
      waiters(allocationEntry) := UIntToOH(
        allocationRequest.transactionId, maxOutstanding)
      sharers(allocationEntry) := Mux(allocationRequest.trackSharer,
        UIntToOH(allocationRequest.requester, numComputeUnits), 0.U)
      victimSet(allocationEntry) := allocationRequest.victimSet
      victimWay(allocationEntry) := allocationRequest.victimWay
    }.otherwise {
      waiters(allocationEntry) := waiters(allocationEntry) | UIntToOH(
        allocationRequest.transactionId, maxOutstanding)
      when(allocationRequest.trackSharer) {
        sharers(allocationEntry) := sharers(allocationEntry) | UIntToOH(
          allocationRequest.requester, numComputeUnits)
      }
    }
    allocationValid := false.B
  }

  private val refillEntryValid = valid(io.refill.bits.entryId) &&
    !refilled(io.refill.bits.entryId)
  io.refill.ready := refillEntryValid
  when(io.refill.fire) {
    refilled(io.refill.bits.entryId) := true.B
    refillData(io.refill.bits.entryId) := io.refill.bits.readData
    refillFault(io.refill.bits.entryId) := io.refill.bits.fault
  }

  private val fillCandidates = VecInit((0 until entries).map { entry =>
    valid(entry) && refilled(entry) && !fillSent(entry)
  })
  private val fillEntry = PriorityEncoder(fillCandidates)
  io.fill.valid := fillCandidates.asUInt.orR
  io.fill.bits.entryId := fillEntry
  io.fill.bits.lineAddress := lineAddress(fillEntry)
  io.fill.bits.sharers := sharers(fillEntry)
  io.fill.bits.readData := refillData(fillEntry)
  io.fill.bits.fault := refillFault(fillEntry)
  io.fill.bits.victimSet := victimSet(fillEntry)
  io.fill.bits.victimWay := victimWay(fillEntry)
  when(io.fill.fire) { fillSent(fillEntry) := true.B }

  private val responseCandidates = VecInit((0 until entries).map { entry =>
    valid(entry) && refilled(entry) && fillSent(entry) && waiters(entry).orR
  })
  private val responseEntry = PriorityEncoder(responseCandidates)
  private val responseTransaction = PriorityEncoder(waiters(responseEntry))
  // Do not retire the final waiter while a merge targeting the same entry is
  // being captured or committed.  Besides making the registered lookup safe,
  // this closes a pre-existing same-cycle multiple-write corner case.
  private val capturedMergeTargetsResponse = io.request.fire && hasMatch &&
    matchedEntry === responseEntry
  private val pendingMergeTargetsResponse = allocationValid &&
    allocationMerged && allocationEntry === responseEntry
  private val responseBlockedForMerge = capturedMergeTargetsResponse ||
    pendingMergeTargetsResponse
  io.response.valid := responseCandidates.asUInt.orR && !responseBlockedForMerge
  io.response.bits.lineAddress := lineAddress(responseEntry)
  io.response.bits.transactionId := responseTransaction
  io.response.bits.readData := refillData(responseEntry)
  io.response.bits.fault := refillFault(responseEntry)
  when(io.response.fire) {
    val remaining = waiters(responseEntry) &
      ~UIntToOH(responseTransaction, maxOutstanding)
    waiters(responseEntry) := remaining
    when(!remaining.orR) {
      valid(responseEntry) := false.B
      refilled(responseEntry) := false.B
      fillSent(responseEntry) := false.B
      sharers(responseEntry) := 0.U
    }
  }

  io.validEntries := valid.asUInt

  when(io.refill.valid) {
    assert(refillEntryValid,
      "L2 MSHR refill must identify a valid, unfilled entry")
  }
}

/** Refill-side execution engine around [[L2MshrTable]]. It allocates one
  * lower request for each newly allocated entry, suppresses lower traffic for
  * merged requests, and routes out-of-order lower responses by MSHR entry ID.
  */
class L2MissEngine(
  config: GpuConfig = GpuConfig(),
  entries: Int = 4,
  maxOutstanding: Int = 8,
  numComputeUnits: Int = 2,
  lineBytes: Int = 64,
  sets: Int = 64,
  ways: Int = 4
) extends Module {
  require(entries <= maxOutstanding,
    "MSHR entry IDs must fit in the lower transaction-ID namespace")
  private val entryWidth = math.max(1, log2Ceil(entries))
  val io = IO(new Bundle {
    val miss = Flipped(Decoupled(new L2MshrRequest(
      config, maxOutstanding, numComputeUnits, sets, ways)))
    val lowerRequest = Decoupled(new ComputeMemoryRequest(
      config, lineBytes, maxOutstanding))
    val allocation = Valid(new L2MshrAllocation(entries))
    val authorize = Flipped(Valid(UInt(entryWidth.W)))
    val lowerResponse = Flipped(Decoupled(new ComputeMemoryResponse(
      lineBytes, maxOutstanding)))
    val fill = Decoupled(new L2MshrFill(
      config, entries, numComputeUnits, lineBytes, sets, ways))
    val response = Decoupled(new L2MshrResponse(
      config, maxOutstanding, lineBytes))
    val validEntries = Output(UInt(entries.W))
  })

  private val table = Module(new L2MshrTable(
    config, entries, maxOutstanding, numComputeUnits, lineBytes, sets, ways))
  private val heldLineAddress = Reg(UInt(config.xLen.W))
  private val lowerPending = RegInit(VecInit(Seq.fill(entries)(false.B)))
  private val lowerLineAddress = Reg(Vec(entries, UInt(config.xLen.W)))

  table.io.request.valid := io.miss.valid
  table.io.request.bits := io.miss.bits
  io.miss.ready := table.io.request.ready
  when(io.miss.fire) {
    heldLineAddress := io.miss.bits.lineAddress
  }

  table.io.allocation.ready := true.B
  io.allocation.valid := table.io.allocation.valid
  io.allocation.bits := table.io.allocation.bits
  when(table.io.allocation.fire) {
    when(!table.io.allocation.bits.merged) {
      lowerLineAddress(table.io.allocation.bits.entryId) := heldLineAddress
    }
  }
  when(io.authorize.valid) {
    val allocationCommitsThisEntry = table.io.allocation.fire &&
      !table.io.allocation.bits.merged &&
      table.io.allocation.bits.entryId === io.authorize.bits
    assert(table.io.validEntries(io.authorize.bits) || allocationCommitsThisEntry,
      "only an allocated L2 MSHR may be authorized")
    lowerPending(io.authorize.bits) := true.B
  }

  private val pendingBits = lowerPending.asUInt
  private val selectedEntry = PriorityEncoder(pendingBits)
  io.lowerRequest.valid := pendingBits.orR
  io.lowerRequest.bits.address := lowerLineAddress(selectedEntry)
  io.lowerRequest.bits.writeData := 0.U
  io.lowerRequest.bits.byteMask := 0.U
  io.lowerRequest.bits.isWrite := false.B
  io.lowerRequest.bits.sizeLog2 := log2Ceil(lineBytes).U
  io.lowerRequest.bits.transactionId := selectedEntry
  io.lowerRequest.bits.cacheClient := false.B
  io.lowerRequest.bits.cacheResident := false.B
  when(io.lowerRequest.fire) { lowerPending(selectedEntry) := false.B }

  private val responseEntry =
    io.lowerResponse.bits.transactionId(entryWidth - 1, 0)
  private val responseInRange =
    io.lowerResponse.bits.transactionId < entries.U
  table.io.refill.valid := io.lowerResponse.valid && responseInRange
  table.io.refill.bits.entryId := responseEntry
  table.io.refill.bits.readData := io.lowerResponse.bits.readData
  table.io.refill.bits.fault := io.lowerResponse.bits.fault
  io.lowerResponse.ready := responseInRange && table.io.refill.ready
  when(io.lowerResponse.valid) {
    assert(responseInRange,
      "L2 miss-engine response must identify an MSHR entry")
  }

  io.fill <> table.io.fill
  io.response <> table.io.response
  io.validEntries := table.io.validEntries
}
