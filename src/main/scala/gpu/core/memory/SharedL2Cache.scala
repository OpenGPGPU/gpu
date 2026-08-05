package gpu.core.memory

import chisel3._
import chisel3.util._
import gpu.config.GpuConfig

/** Shared L2 cache at the point where all compute-unit traffic has
  * already been assigned a globally unique transaction ID.
  *
  * Cacheable load misses use a four-entry non-blocking MSHR engine. Same-line
  * requests merge, independent lines may refill out of order, and each MSHR
  * reserves its physical victim slot. Stores remain write-through and AMOs
  * remain serialized; both wait for load MSHRs to retire so a stale refill
  * cannot overwrite a newer write. Tag and data arrays use synchronous reads
  * so emitted RTL can map to SRAM macros.
  */
class SharedL2Slice(
  config: GpuConfig = GpuConfig(),
  sets: Int = 128,
  ways: Int = 4,
  lineBytes: Int = 64,
  maxOutstanding: Int = 8,
  numComputeUnits: Int = 2,
  transactionsPerCu: Int = 4
) extends Module {
  require(isPow2(sets) && sets > 1)
  require(isPow2(ways) && ways > 0)
  require(isPow2(lineBytes) && lineBytes == 64)
  require(maxOutstanding > 0)
  require(numComputeUnits > 0)
  require(maxOutstanding >= numComputeUnits * transactionsPerCu)
  require(maxOutstanding > 4,
    "four load MSHRs plus one blocking store/AMO ID are required")

  private val offsetWidth = log2Ceil(lineBytes)
  private val indexWidth = log2Ceil(sets)
  private val tagWidth = config.xLen - offsetWidth - indexWidth
  private val wayWidth = math.max(1, log2Ceil(ways))
  private val lineWidth = lineBytes * 8

  val io = IO(new Bundle {
    val request = Flipped(Decoupled(
      new ComputeMemoryRequest(config, lineBytes, maxOutstanding)))
    val response = Decoupled(
      new ComputeMemoryResponse(lineBytes, maxOutstanding))
    val memoryRequest = Decoupled(
      new ComputeMemoryRequest(config, lineBytes, maxOutstanding))
    val memoryResponse = Flipped(Decoupled(
      new ComputeMemoryResponse(lineBytes, maxOutstanding)))
    val invalidate = Vec(numComputeUnits,
      Decoupled(new CacheLineInvalidate(config)))
    val invalidateDone = Vec(numComputeUnits,
      Flipped(Decoupled(new CacheLineInvalidate(config))))
    val atomicRequest = Vec(numComputeUnits,
      Flipped(Decoupled(new SharedAtomicRequest(config))))
    val atomicResponse = Vec(numComputeUnits,
      Decoupled(new SharedAtomicResponse(config)))
  })

  private object State extends ChiselEnum {
    val idle, lookup, missAllocate, invalidate, lower, respond, atomicRead,
      atomicExecute, atomicWrite, atomicRespond = Value
  }
  private val state = RegInit(State.idle)
  private val request = Reg(new ComputeMemoryRequest(
    config, lineBytes, maxOutstanding))
  private val response = Reg(new ComputeMemoryResponse(
    lineBytes, maxOutstanding))
  private val responseWaiters = RegInit(0.U(maxOutstanding.W))
  private val waiterSharers = RegInit(0.U(numComputeUnits.W))
  private val lookupHit = RegInit(false.B)
  private val lookupHitWay = Reg(UInt(wayWidth.W))
  private val lookupHitData = Reg(UInt(lineWidth.W))
  private val lookupVictimWay = Reg(UInt(wayWidth.W))
  private val invalidateTargets = RegInit(0.U(numComputeUnits.W))
  private val invalidateSent = RegInit(0.U(numComputeUnits.W))
  private val invalidateAcked = RegInit(0.U(numComputeUnits.W))
  private val invalidateAddress = Reg(UInt(config.xLen.W))
  private val invalidateForReplacement = RegInit(false.B)
  private val atomicMode = RegInit(false.B)
  private val atomicOwner = Reg(UInt(math.max(1, log2Ceil(numComputeUnits)).W))
  private val atomicRequest = Reg(new SharedAtomicRequest(config))
  private val atomicLineData = Reg(UInt(lineWidth.W))
  private val atomicOldValue = Reg(UInt(32.W))
  private val atomicNewValue = Reg(UInt(32.W))
  private val atomicFault = RegInit(false.B)
  private val atomicTargetWay = Reg(UInt(wayWidth.W))
  private val lowerIssued = RegInit(false.B)
  private val invalidateForMiss = RegInit(false.B)
  private val missEntry = Reg(UInt(2.W))
  private val missVictimWay = Reg(UInt(wayWidth.W))
  private val missVictimRotated = RegInit(false.B)
  private val allocatedVictimValid = Reg(Bool())
  private val allocatedVictimSharers = Reg(UInt(numComputeUnits.W))
  private val allocatedVictimAddress = Reg(UInt(config.xLen.W))

  private val tags = Seq.fill(ways)(SyncReadMem(sets, UInt(tagWidth.W)))
  private val data = Seq.fill(ways)(SyncReadMem(sets, UInt(lineWidth.W)))
  private val valid = Seq.fill(ways)(
    RegInit(VecInit(Seq.fill(sets)(false.B))))
  private val nextVictim =
    RegInit(VecInit(Seq.fill(sets)(0.U(wayWidth.W))))
  private val sharers = Seq.fill(ways)(
    RegInit(VecInit(Seq.fill(sets)(0.U(numComputeUnits.W)))))
  private val missEngine = Module(new L2MissEngine(
    config, entries = 4, maxOutstanding = maxOutstanding,
    numComputeUnits = numComputeUnits, lineBytes = lineBytes,
    sets = sets, ways = ways))

  private val atomicArbiter = Module(new RRArbiter(
    new SharedAtomicRequest(config), numComputeUnits))
  for (cu <- 0 until numComputeUnits) {
    atomicArbiter.io.in(cu) <> io.atomicRequest(cu)
  }
  private val loadMissesActive = missEngine.io.validEntries.orR
  private val acceptAtomic = state === State.idle && !loadMissesActive &&
    atomicArbiter.io.out.valid
  atomicArbiter.io.out.ready := state === State.idle && !loadMissesActive
  private val lookupAddress = Mux(acceptAtomic,
    atomicArbiter.io.out.bits.address, io.request.bits.address)
  private val lookupFire = atomicArbiter.io.out.fire || io.request.fire
  private val incomingIndex =
    lookupAddress(offsetWidth + indexWidth - 1, offsetWidth)
  private val requestIndex =
    request.address(offsetWidth + indexWidth - 1, offsetWidth)
  private val lookupReadIndex = Mux(lookupFire, incomingIndex, requestIndex)
  private val lookupReadEnable = lookupFire || state === State.lookup
  private val tagRead = VecInit(tags.map(
    _.read(lookupReadIndex, lookupReadEnable)))
  private val dataRead = VecInit(data.map(
    _.read(lookupReadIndex, lookupReadEnable)))

  private val requestTag =
    request.address(config.xLen - 1, offsetWidth + indexWidth)
  private val cacheable = request.sizeLog2 === offsetWidth.U &&
    request.address(offsetWidth - 1, 0) === 0.U
  private val hitByWay = VecInit((0 until ways).map { way =>
    valid(way)(requestIndex) && tagRead(way) === requestTag
  })
  private val hit = hitByWay.asUInt.orR
  private val hitWay = PriorityEncoder(hitByWay)
  private val hitData = Mux1H(hitByWay, dataRead)
  private val invalidByWay = VecInit((0 until ways).map { way =>
    !valid(way)(requestIndex)
  })
  private val hasInvalid = invalidByWay.asUInt.orR
  private val victimWay = Mux(
    hasInvalid, PriorityEncoder(invalidByWay), nextVictim(requestIndex))
  private def followingWay(way: UInt): UInt =
    if (ways == 1) 0.U else (way + 1.U)(wayWidth - 1, 0)

  private val requesterCu =
    request.transactionId / transactionsPerCu.U
  private val requesterOH = UIntToOH(requesterCu, numComputeUnits)
  private val hitSharers = Mux1H(hitByWay,
    VecInit(sharers.map(_(requestIndex))))
  private val otherSharers = hitSharers & ~requesterOH
  private val victimSharers = Mux1H(UIntToOH(victimWay, ways),
    VecInit(sharers.map(_(requestIndex))))
  private val victimTag = Mux1H(UIntToOH(victimWay, ways), tagRead)
  private val victimValid = Mux1H(UIntToOH(victimWay, ways),
    VecInit(valid.map(_(requestIndex))))
  private val victimAddress = Cat(
    victimTag, requestIndex, 0.U(offsetWidth.W))
  private val selectedMissWay = Mux(missVictimRotated,
    missVictimWay, victimWay)
  private val selectedMissSharers = Mux1H(UIntToOH(selectedMissWay, ways),
    VecInit(sharers.map(_(requestIndex))))
  private val selectedMissTag = Mux1H(UIntToOH(selectedMissWay, ways), tagRead)
  private val selectedMissValid = Mux1H(UIntToOH(selectedMissWay, ways),
    VecInit(valid.map(_(requestIndex))))
  private val selectedMissAddress = Cat(
    selectedMissTag, requestIndex, 0.U(offsetWidth.W))
  private val activeLine = Cat(
    request.address(config.xLen - 1, offsetWidth), 0.U(offsetWidth.W))

  private val atomicLineAddress = Cat(
    atomicRequest.address(config.xLen - 1, offsetWidth), 0.U(offsetWidth.W))
  private val atomicWordShift = Cat(atomicRequest.address(offsetWidth - 1, 2), 0.U(5.W))
  private val atomicReadWord = (atomicLineData >> atomicWordShift)(31, 0)
  private val atomicResult = MuxLookup(
    atomicRequest.operation,
    atomicRequest.operand
  )(Seq(
    AtomicMemoryOp.swap -> atomicRequest.operand,
    AtomicMemoryOp.add -> (atomicReadWord + atomicRequest.operand),
    AtomicMemoryOp.xor -> (atomicReadWord ^ atomicRequest.operand),
    AtomicMemoryOp.or -> (atomicReadWord | atomicRequest.operand),
    AtomicMemoryOp.and -> (atomicReadWord & atomicRequest.operand),
    AtomicMemoryOp.min -> Mux(atomicReadWord.asSInt < atomicRequest.operand.asSInt,
      atomicReadWord, atomicRequest.operand),
    AtomicMemoryOp.max -> Mux(atomicReadWord.asSInt > atomicRequest.operand.asSInt,
      atomicReadWord, atomicRequest.operand),
    AtomicMemoryOp.minu -> Mux(atomicReadWord < atomicRequest.operand,
      atomicReadWord, atomicRequest.operand),
    AtomicMemoryOp.maxu -> Mux(atomicReadWord > atomicRequest.operand,
      atomicReadWord, atomicRequest.operand)
  ))
  private val atomicWordMask = ("hf".U(lineBytes.W) << atomicRequest.address(offsetWidth - 1, 0))
  private val atomicWriteData = atomicNewValue <<
    (atomicRequest.address(offsetWidth - 1, 0) << 3)
  private val atomicMergedBytes = Wire(Vec(lineBytes, UInt(8.W)))
  private val atomicOldBytes = atomicLineData.asTypeOf(Vec(lineBytes, UInt(8.W)))
  private val atomicByteOffset = atomicRequest.address(offsetWidth - 1, 0)
  for (byte <- 0 until lineBytes) {
    val relative = byte.U - atomicByteOffset
    val replace = byte.U >= atomicByteOffset && byte.U < atomicByteOffset + 4.U
    atomicMergedBytes(byte) := Mux(replace,
      (atomicNewValue >> (relative << 3))(7, 0), atomicOldBytes(byte))
  }
  private val atomicMergedLine = atomicMergedBytes.asUInt

  private val mergedBytes = Wire(Vec(lineBytes, UInt(8.W)))
  private val oldBytes = lookupHitData.asTypeOf(Vec(lineBytes, UInt(8.W)))
  private val writeBytes = request.writeData.asTypeOf(Vec(lineBytes, UInt(8.W)))
  for (byte <- 0 until lineBytes) {
    mergedBytes(byte) := Mux(request.byteMask(byte), writeBytes(byte), oldBytes(byte))
  }

  private val incomingLine = Cat(
    io.request.bits.address(config.xLen - 1, offsetWidth), 0.U(offsetWidth.W))
  private val mergeLoad = state === State.lower && !request.isWrite &&
    request.sizeLog2 === offsetWidth.U && !io.request.bits.isWrite &&
    io.request.bits.sizeLog2 === offsetWidth.U && incomingLine === activeLine &&
    !responseWaiters(io.request.bits.transactionId)
  private val incomingRequesterCu =
    io.request.bits.transactionId / transactionsPerCu.U
  private val incomingRequesterOH = UIntToOH(
    incomingRequesterCu, numComputeUnits)
  io.request.ready := (state === State.idle && !atomicArbiter.io.out.valid &&
    (!io.request.bits.isWrite || !loadMissesActive)) ||
    mergeLoad
  private val regularResponse = Wire(Decoupled(
    new ComputeMemoryResponse(lineBytes, maxOutstanding)))
  regularResponse.valid := state === State.respond
  regularResponse.bits := response
  regularResponse.bits.transactionId := PriorityEncoder(responseWaiters)
  private val responseArbiter = Module(new RRArbiter(
    new ComputeMemoryResponse(lineBytes, maxOutstanding), 2))
  responseArbiter.io.in(0) <> regularResponse
  responseArbiter.io.in(1).valid := missEngine.io.response.valid
  responseArbiter.io.in(1).bits.readData := missEngine.io.response.bits.readData
  responseArbiter.io.in(1).bits.transactionId :=
    missEngine.io.response.bits.transactionId
  responseArbiter.io.in(1).bits.fault := missEngine.io.response.bits.fault
  missEngine.io.response.ready := responseArbiter.io.in(1).ready
  io.response <> responseArbiter.io.out

  private val blockingMemoryRequest = Wire(Decoupled(
    new ComputeMemoryRequest(config, lineBytes, maxOutstanding)))
  blockingMemoryRequest.valid := (state === State.lower ||
    state === State.atomicRead || state === State.atomicWrite) && !lowerIssued
  blockingMemoryRequest.bits := request
  blockingMemoryRequest.bits.transactionId := 4.U
  when(state === State.atomicRead) {
    blockingMemoryRequest.bits.address := atomicLineAddress
    blockingMemoryRequest.bits.writeData := 0.U
    blockingMemoryRequest.bits.byteMask := 0.U
    blockingMemoryRequest.bits.isWrite := false.B
    blockingMemoryRequest.bits.sizeLog2 := offsetWidth.U
    blockingMemoryRequest.bits.cacheClient := false.B
    blockingMemoryRequest.bits.cacheResident := false.B
  }
  when(state === State.atomicWrite) {
    blockingMemoryRequest.bits.address := atomicLineAddress
    blockingMemoryRequest.bits.writeData := atomicWriteData
    blockingMemoryRequest.bits.byteMask := atomicWordMask
    blockingMemoryRequest.bits.isWrite := true.B
    blockingMemoryRequest.bits.sizeLog2 := offsetWidth.U
    blockingMemoryRequest.bits.cacheClient := false.B
    blockingMemoryRequest.bits.cacheResident := false.B
  }
  private val memoryRequestArbiter = Module(new RRArbiter(
    new ComputeMemoryRequest(config, lineBytes, maxOutstanding), 2))
  memoryRequestArbiter.io.in(0) <> blockingMemoryRequest
  memoryRequestArbiter.io.in(1) <> missEngine.io.lowerRequest
  io.memoryRequest <> memoryRequestArbiter.io.out
  when(blockingMemoryRequest.fire) { lowerIssued := true.B }

  private val responseIsMiss = io.memoryResponse.bits.transactionId < 4.U
  missEngine.io.lowerResponse.valid := io.memoryResponse.valid && responseIsMiss
  missEngine.io.lowerResponse.bits := io.memoryResponse.bits
  private val blockingMemoryResponseReady = state === State.lower ||
    state === State.atomicRead || state === State.atomicWrite
  io.memoryResponse.ready := Mux(responseIsMiss,
    missEngine.io.lowerResponse.ready, blockingMemoryResponseReady)
  private val blockingMemoryResponseFire = io.memoryResponse.fire &&
    !responseIsMiss
  when(io.memoryResponse.valid) {
    assert(responseIsMiss || io.memoryResponse.bits.transactionId === 4.U,
      "L2 slice response must identify a load MSHR or blocking transaction")
  }
  missEngine.io.miss.valid := state === State.lookup && !atomicMode &&
    cacheable && !request.isWrite && !hit
  missEngine.io.miss.bits.lineAddress := activeLine
  missEngine.io.miss.bits.transactionId := request.transactionId
  missEngine.io.miss.bits.requester := requesterCu
  missEngine.io.miss.bits.trackSharer := request.cacheClient
  missEngine.io.miss.bits.victimSet := requestIndex
  missEngine.io.miss.bits.victimWay := selectedMissWay
  missEngine.io.authorize.valid := false.B
  missEngine.io.authorize.bits := missEntry
  missEngine.io.fill.ready := true.B
  private val invalidateFire = Wire(Vec(numComputeUnits, Bool()))
  private val invalidateAckFire = Wire(Vec(numComputeUnits, Bool()))
  for (cu <- 0 until numComputeUnits) {
    io.invalidate(cu).valid := state === State.invalidate &&
      invalidateTargets(cu) && !invalidateSent(cu)
    io.invalidate(cu).bits.lineAddress := invalidateAddress
    invalidateFire(cu) := io.invalidate(cu).fire
    io.invalidateDone(cu).ready := state === State.invalidate &&
      invalidateTargets(cu) && invalidateSent(cu) && !invalidateAcked(cu)
    invalidateAckFire(cu) := io.invalidateDone(cu).fire
    when(io.invalidateDone(cu).fire) {
      assert(io.invalidateDone(cu).bits.lineAddress === invalidateAddress,
        "L1 invalidation acknowledgement must match the active L2 line")
    }
    io.atomicResponse(cu).valid := state === State.atomicRespond &&
      atomicOwner === cu.U
    io.atomicResponse(cu).bits.warpId := atomicRequest.warpId
    io.atomicResponse(cu).bits.oldValue := atomicOldValue
    io.atomicResponse(cu).bits.fault := atomicFault
  }

  when(io.request.fire) {
    when(state === State.idle) {
      missVictimRotated := false.B
      request := io.request.bits
      responseWaiters := UIntToOH(
        io.request.bits.transactionId, maxOutstanding)
      waiterSharers := Mux(
        io.request.bits.cacheClient, incomingRequesterOH, 0.U)
      atomicMode := false.B
      state := State.lookup
    }.otherwise {
      responseWaiters := responseWaiters | UIntToOH(
        io.request.bits.transactionId, maxOutstanding)
      when(io.request.bits.cacheClient) {
        waiterSharers := waiterSharers | incomingRequesterOH
      }
    }
  }
  when(atomicArbiter.io.out.fire) {
    atomicRequest := atomicArbiter.io.out.bits
    atomicOwner := atomicArbiter.io.chosen
    atomicMode := true.B
    atomicFault := false.B
    // Reuse the normal lookup address/index machinery without admitting this
    // request to the normal response path.
    request.address := atomicArbiter.io.out.bits.address
    request.transactionId := Cat(atomicArbiter.io.chosen,
      0.U(math.max(1, log2Ceil(transactionsPerCu)).W))
    request.sizeLog2 := offsetWidth.U
    request.isWrite := false.B
    request.cacheClient := false.B
    request.cacheResident := false.B
    request.writeData := 0.U
    request.byteMask := 0.U
    state := State.lookup
  }

  when(state === State.lookup) {
    lookupHit := hit
    lookupHitWay := hitWay
    lookupHitData := hitData
    lookupVictimWay := victimWay
    when(atomicMode) {
      when(atomicRequest.address(1, 0).orR) {
        atomicFault := true.B
        atomicOldValue := 0.U
        state := State.atomicRespond
      }.elsewhen(hit) {
        atomicTargetWay := hitWay
        atomicLineData := hitData
        when(hitSharers.orR) {
          invalidateTargets := hitSharers
          invalidateSent := 0.U
          invalidateAcked := 0.U
          invalidateAddress := atomicLineAddress
          invalidateForReplacement := false.B
          state := State.invalidate
        }.otherwise {
          state := State.atomicExecute
        }
      }.elsewhen(victimValid && victimSharers.orR) {
        atomicTargetWay := victimWay
        invalidateTargets := victimSharers
        invalidateSent := 0.U
        invalidateAcked := 0.U
        invalidateAddress := victimAddress
        invalidateForReplacement := true.B
        state := State.invalidate
      }.otherwise {
        atomicTargetWay := victimWay
        state := State.atomicRead
      }
    }.elsewhen(cacheable && !request.isWrite && hit) {
      response.readData := hitData
      response.fault := false.B
      response.transactionId := request.transactionId
      nextVictim(requestIndex) := followingWay(hitWay)
      when(request.cacheClient) {
        for (way <- 0 until ways) {
          when(hitWay === way.U) {
            sharers(way)(requestIndex) := sharers(way)(requestIndex) | requesterOH
          }
        }
      }
      state := State.respond
    }.elsewhen(cacheable && request.isWrite && hit && otherSharers.orR) {
      invalidateTargets := otherSharers
      invalidateSent := 0.U
      invalidateAcked := 0.U
      invalidateAddress := request.address
      invalidateForReplacement := false.B
      state := State.invalidate
    }.elsewhen(cacheable && !request.isWrite && !hit) {
      when(missEngine.io.miss.fire) {
        lookupVictimWay := selectedMissWay
        allocatedVictimValid := selectedMissValid
        allocatedVictimSharers := selectedMissSharers
        allocatedVictimAddress := selectedMissAddress
        missVictimRotated := false.B
        state := State.missAllocate
      }.otherwise {
        missVictimWay := followingWay(selectedMissWay)
        missVictimRotated := true.B
      }
    }.otherwise {
      state := State.lower
    }
  }

  when(state === State.missAllocate && missEngine.io.allocation.valid) {
    when(missEngine.io.allocation.bits.merged) {
      state := State.idle
    }.otherwise {
      missEntry := missEngine.io.allocation.bits.entryId
      when(allocatedVictimValid && allocatedVictimSharers.orR) {
        invalidateTargets := allocatedVictimSharers
        invalidateSent := 0.U
        invalidateAcked := 0.U
        invalidateAddress := allocatedVictimAddress
        invalidateForReplacement := true.B
        invalidateForMiss := true.B
        state := State.invalidate
      }.otherwise {
        missEngine.io.authorize.valid := true.B
        missEngine.io.authorize.bits := missEngine.io.allocation.bits.entryId
        state := State.idle
      }
    }
  }

  when(state === State.invalidate) {
    val nextSent = invalidateSent | invalidateFire.asUInt
    val nextAcked = invalidateAcked | invalidateAckFire.asUInt
    invalidateSent := nextSent
    invalidateAcked := nextAcked
    when((nextAcked & invalidateTargets) === invalidateTargets) {
      when(invalidateForReplacement) {
        for (way <- 0 until ways) {
          when(Mux(atomicMode, atomicTargetWay, lookupVictimWay) === way.U) {
            sharers(way)(requestIndex) := 0.U
          }
        }
      }.otherwise {
        for (way <- 0 until ways) {
          when(lookupHitWay === way.U) {
            sharers(way)(requestIndex) := Mux(
              request.cacheClient && request.cacheResident, requesterOH, 0.U)
          }
        }
      }
      when(invalidateForMiss) {
        missEngine.io.authorize.valid := true.B
        missEngine.io.authorize.bits := missEntry
        invalidateForMiss := false.B
        state := State.idle
      }.otherwise {
        state := Mux(atomicMode,
          Mux(invalidateForReplacement, State.atomicRead, State.atomicExecute),
          State.lower)
      }
    }
  }

  when(blockingMemoryResponseFire) {
    lowerIssued := false.B
    when(state === State.atomicRead) {
      atomicFault := io.memoryResponse.bits.fault
      atomicLineData := io.memoryResponse.bits.readData
      state := Mux(io.memoryResponse.bits.fault,
        State.atomicRespond, State.atomicExecute)
    }.elsewhen(state === State.atomicWrite) {
      atomicFault := io.memoryResponse.bits.fault
      when(!io.memoryResponse.bits.fault) {
        for (way <- 0 until ways) {
          when(atomicTargetWay === way.U) {
            tags(way).write(requestIndex, requestTag)
            data(way).write(requestIndex, atomicMergedLine)
            valid(way)(requestIndex) := true.B
            sharers(way)(requestIndex) := 0.U
          }
        }
      }
      state := State.atomicRespond
    }.otherwise {
      response := io.memoryResponse.bits
      response.transactionId := request.transactionId
      when(!io.memoryResponse.bits.fault && cacheable) {
      when(!request.isWrite) {
        for (way <- 0 until ways) {
          when(lookupVictimWay === way.U) {
            tags(way).write(requestIndex, requestTag)
            data(way).write(requestIndex, io.memoryResponse.bits.readData)
            valid(way)(requestIndex) := true.B
            sharers(way)(requestIndex) := waiterSharers
          }
        }
        nextVictim(requestIndex) := followingWay(lookupVictimWay)
      }.elsewhen(lookupHit) {
        for (way <- 0 until ways) {
          when(lookupHitWay === way.U) {
            data(way).write(requestIndex, mergedBytes.asUInt)
            sharers(way)(requestIndex) := Mux(
              request.cacheClient && request.cacheResident, requesterOH, 0.U)
          }
        }
        nextVictim(requestIndex) := followingWay(lookupHitWay)
      }
      }
      state := State.respond
    }
  }

  when(state === State.atomicExecute) {
    atomicOldValue := atomicReadWord
    atomicNewValue := atomicResult
    state := State.atomicWrite
  }

  private val anyAtomicResponseFire =
    VecInit(io.atomicResponse.map(_.fire)).asUInt.orR
  when(state === State.atomicRespond && anyAtomicResponseFire) {
    atomicMode := false.B
    state := State.idle
  }

  when(regularResponse.fire) {
    val remaining = responseWaiters &
      ~UIntToOH(regularResponse.bits.transactionId, maxOutstanding)
    responseWaiters := remaining
    when(!remaining.orR) { state := State.idle }
  }

  when(missEngine.io.fill.fire && !missEngine.io.fill.bits.fault) {
    val fillSet = missEngine.io.fill.bits.victimSet
    val fillWay = missEngine.io.fill.bits.victimWay
    val fillTag = missEngine.io.fill.bits.lineAddress(
      config.xLen - 1, offsetWidth + indexWidth)
    for (way <- 0 until ways) {
      when(fillWay === way.U) {
        tags(way).write(fillSet, fillTag)
        data(way).write(fillSet, missEngine.io.fill.bits.readData)
        valid(way)(fillSet) := true.B
        sharers(way)(fillSet) := missEngine.io.fill.bits.sharers
      }
    }
    nextVictim(fillSet) := followingWay(fillWay)
  }
}

/** Address-interleaved collection of independent L2 slices.
  *
  * Hits and coherence work in different slices proceed independently. The
  * lower-memory adapter allocates global transaction IDs and restores each
  * slice's original MSHR/blocking ID on an out-of-order response.
  */
class SharedL2Cache(
  config: GpuConfig = GpuConfig(),
  sets: Int = 128,
  ways: Int = 4,
  lineBytes: Int = 64,
  maxOutstanding: Int = 8,
  numComputeUnits: Int = 2,
  transactionsPerCu: Int = 4,
  banks: Int = 2,
  requestQueueDepth: Int = 2
) extends Module {
  require(isPow2(banks) && banks > 0)
  require(sets % banks == 0)
  require(requestQueueDepth > 0)
  private val setsPerBank = sets / banks
  require(setsPerBank > 1 && isPow2(setsPerBank))
  private val offsetWidth = log2Ceil(lineBytes)
  private val bankWidth = math.max(1, log2Ceil(banks))
  private val bankBit = offsetWidth + log2Ceil(setsPerBank)

  val io = IO(new Bundle {
    val request = Flipped(Decoupled(
      new ComputeMemoryRequest(config, lineBytes, maxOutstanding)))
    val response = Decoupled(
      new ComputeMemoryResponse(lineBytes, maxOutstanding))
    val memoryRequest = Decoupled(
      new ComputeMemoryRequest(config, lineBytes, maxOutstanding))
    val memoryResponse = Flipped(Decoupled(
      new ComputeMemoryResponse(lineBytes, maxOutstanding)))
    val invalidate = Vec(numComputeUnits,
      Decoupled(new CacheLineInvalidate(config)))
    val invalidateDone = Vec(numComputeUnits,
      Flipped(Decoupled(new CacheLineInvalidate(config))))
    val atomicRequest = Vec(numComputeUnits,
      Flipped(Decoupled(new SharedAtomicRequest(config))))
    val atomicResponse = Vec(numComputeUnits,
      Decoupled(new SharedAtomicResponse(config)))
  })

  private val slices = Seq.fill(banks)(Module(new SharedL2Slice(
    config, setsPerBank, ways, lineBytes, maxOutstanding,
    numComputeUnits, transactionsPerCu)))
  /** XOR interleaving keeps the slice's existing index/tag decomposition while
    * spreading adjacent cache lines across banks. Using only the upper set
    * bits made every short sequential DMA burst target one blocking slice.
    */
  private def selectBank(address: UInt): UInt =
    if (banks == 1) 0.U
    else address(bankBit + bankWidth - 1, bankBit) ^
      address(offsetWidth + bankWidth - 1, offsetWidth)

  private val requestBank = selectBank(io.request.bits.address)
  private val requestQueues = Seq.fill(banks)(Module(new Queue(
    new ComputeMemoryRequest(config, lineBytes, maxOutstanding),
    requestQueueDepth, pipe = true, flow = true)))
  for (bank <- 0 until banks) {
    requestQueues(bank).io.enq.valid :=
      io.request.valid && requestBank === bank.U
    requestQueues(bank).io.enq.bits := io.request.bits
    slices(bank).io.request <> requestQueues(bank).io.deq
  }
  io.request.ready :=
    VecInit(requestQueues.map(_.io.enq.ready))(requestBank)

  private val responseArbiter = Module(new RRArbiter(
    new ComputeMemoryResponse(lineBytes, maxOutstanding), banks))
  for (bank <- 0 until banks) {
    responseArbiter.io.in(bank) <> slices(bank).io.response
  }
  io.response <> responseArbiter.io.out

  for (cu <- 0 until numComputeUnits) {
    val atomicBank = selectBank(io.atomicRequest(cu).bits.address)
    for (bank <- 0 until banks) {
      slices(bank).io.atomicRequest(cu).valid :=
        io.atomicRequest(cu).valid && atomicBank === bank.U
      slices(bank).io.atomicRequest(cu).bits := io.atomicRequest(cu).bits
    }
    io.atomicRequest(cu).ready :=
      VecInit(slices.map(_.io.atomicRequest(cu).ready))(atomicBank)

    val atomicResponseArbiter = Module(new RRArbiter(
      new SharedAtomicResponse(config), banks))
    for (bank <- 0 until banks) {
      atomicResponseArbiter.io.in(bank) <> slices(bank).io.atomicResponse(cu)
    }
    io.atomicResponse(cu) <> atomicResponseArbiter.io.out
  }

  // One outstanding invalidation per L1 is sufficient because each private
  // DCache itself accepts a new probe only after acknowledging the previous.
  for (cu <- 0 until numComputeUnits) {
    val invalidateArbiter = Module(new RRArbiter(
      new CacheLineInvalidate(config), banks))
    val invalidateBusy = RegInit(false.B)
    val invalidateOwner = Reg(UInt(bankWidth.W))
    for (bank <- 0 until banks) {
      invalidateArbiter.io.in(bank).valid :=
        slices(bank).io.invalidate(cu).valid && !invalidateBusy
      invalidateArbiter.io.in(bank).bits := slices(bank).io.invalidate(cu).bits
      slices(bank).io.invalidate(cu).ready :=
        invalidateArbiter.io.in(bank).ready && !invalidateBusy
      slices(bank).io.invalidateDone(cu).valid :=
        io.invalidateDone(cu).valid && invalidateBusy &&
          invalidateOwner === bank.U
      slices(bank).io.invalidateDone(cu).bits := io.invalidateDone(cu).bits
    }
    io.invalidate(cu).valid := invalidateArbiter.io.out.valid && !invalidateBusy
    io.invalidate(cu).bits := invalidateArbiter.io.out.bits
    invalidateArbiter.io.out.ready := io.invalidate(cu).ready && !invalidateBusy
    when(io.invalidate(cu).fire) {
      invalidateBusy := true.B
      invalidateOwner := invalidateArbiter.io.chosen
    }
    io.invalidateDone(cu).ready := invalidateBusy &&
      VecInit(slices.map(_.io.invalidateDone(cu).ready))(invalidateOwner)
    when(io.invalidateDone(cu).fire) { invalidateBusy := false.B }
  }

  // Allocate lower-port IDs independently of CU transaction IDs. This avoids
  // collisions with internal AMO refill/write traffic and permits responses
  // from different slices to return out of order.
  private val memoryArbiter = Module(new RRArbiter(
    new ComputeMemoryRequest(config, lineBytes, maxOutstanding), banks))
  private val lowerValid =
    RegInit(VecInit(Seq.fill(maxOutstanding)(false.B)))
  private val lowerOwner = Reg(Vec(maxOutstanding, UInt(bankWidth.W)))
  private val lowerOriginalId = Reg(Vec(maxOutstanding,
    UInt(math.max(1, log2Ceil(maxOutstanding)).W)))
  private val freeLower = ~lowerValid.asUInt
  private val hasFreeLower = freeLower.orR
  private val allocatedLowerId = PriorityEncoder(freeLower)
  for (bank <- 0 until banks) {
    memoryArbiter.io.in(bank).valid := slices(bank).io.memoryRequest.valid
    memoryArbiter.io.in(bank).bits := slices(bank).io.memoryRequest.bits
    slices(bank).io.memoryRequest.ready := memoryArbiter.io.in(bank).ready
    val responseId = io.memoryResponse.bits.transactionId
    val responseInRange = responseId < maxOutstanding.U
    val responseValid = responseInRange && lowerValid(responseId)
    slices(bank).io.memoryResponse.valid :=
      io.memoryResponse.valid && responseValid && lowerOwner(responseId) === bank.U
    slices(bank).io.memoryResponse.bits := io.memoryResponse.bits
    slices(bank).io.memoryResponse.bits.transactionId :=
      lowerOriginalId(responseId)
  }
  io.memoryRequest.valid := memoryArbiter.io.out.valid && hasFreeLower
  io.memoryRequest.bits := memoryArbiter.io.out.bits
  io.memoryRequest.bits.transactionId := allocatedLowerId
  memoryArbiter.io.out.ready := io.memoryRequest.ready && hasFreeLower
  when(io.memoryRequest.fire) {
    lowerValid(allocatedLowerId) := true.B
    lowerOwner(allocatedLowerId) := memoryArbiter.io.chosen
    lowerOriginalId(allocatedLowerId) :=
      memoryArbiter.io.out.bits.transactionId
  }
  private val responseId = io.memoryResponse.bits.transactionId
  private val responseInRange = responseId < maxOutstanding.U
  private val responseValid = responseInRange && lowerValid(responseId)
  io.memoryResponse.ready := responseValid &&
    VecInit(slices.map(_.io.memoryResponse.ready))(lowerOwner(responseId))
  when(io.memoryResponse.fire) { lowerValid(responseId) := false.B }
  when(io.memoryResponse.valid) {
    assert(responseValid,
      "L2 lower response must reference an outstanding transaction")
  }
}
