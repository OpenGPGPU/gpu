package opengpu.core.memory

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig

object AtomicMemoryOp {
  val width = 4
  val swap = 0.U(width.W)
  val add = 1.U(width.W)
  val xor = 2.U(width.W)
  val or = 3.U(width.W)
  val and = 4.U(width.W)
  val min = 5.U(width.W)
  val max = 6.U(width.W)
  val minu = 7.U(width.W)
  val maxu = 8.U(width.W)
}

class SharedAtomicRequest(config: GpuConfig) extends Bundle {
  val warpId = UInt(config.warpIdWidth.W)
  val address = UInt(config.xLen.W)
  val operand = UInt(32.W)
  val operation = UInt(AtomicMemoryOp.width.W)
}

class SharedAtomicResponse(config: GpuConfig) extends Bundle {
  val warpId = UInt(config.warpIdWidth.W)
  val oldValue = UInt(32.W)
  val fault = Bool()
}

/** Byte-interleaved CU-local shared memory.
  *
  * Every bank accepts one byte operation per cycle. Conflict-free lane bytes
  * therefore proceed in parallel, while accesses mapping to the same bank are
  * replayed until the captured vector transaction is complete.
  */
class BankedSharedMemory(config: GpuConfig = GpuConfig()) extends Module {
  private val bytesPerBank = config.sharedMemoryBytes / config.sharedMemoryBanks
  private val bankWidth = log2Ceil(config.sharedMemoryBanks)
  private val laneWidth = math.max(1, log2Ceil(config.lanes))
  private val operationCount = config.lanes * 4
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new VectorMemoryTransaction(config)))
    val out = Decoupled(new VectorMemoryResponse(config))
    val atomicIn = Flipped(Decoupled(new SharedAtomicRequest(config)))
    val atomicOut = Decoupled(new SharedAtomicResponse(config))
    val idle = Output(Bool())
  })

  private val banks = Seq.fill(config.sharedMemoryBanks)(
    SyncReadMem(bytesPerBank, UInt(8.W)))
  private val transaction = Reg(new VectorMemoryTransaction(config))
  private val pending = RegInit(0.U(operationCount.W))
  private val busy = RegInit(false.B)
  private val result = RegInit(VecInit(Seq.fill(config.lanes)(0.U(32.W))))
  private val faultMask = RegInit(0.U(config.lanes.W))
  private val outputValid = RegInit(false.B)
  private object AtomicState extends ChiselEnum {
    val idle, read, waitForRead, write, respond = Value
  }
  private val atomicState = RegInit(AtomicState.idle)
  private val atomicRequest = Reg(new SharedAtomicRequest(config))
  private val atomicOldValue = Reg(UInt(32.W))
  private val atomicNewValue = Reg(UInt(32.W))
  private val atomicFault = RegInit(false.B)

  io.in.ready := !busy && !outputValid && atomicState === AtomicState.idle &&
    !io.atomicIn.valid
  io.atomicIn.ready := !busy && !outputValid && atomicState === AtomicState.idle
  when(io.in.fire) {
    transaction := io.in.bits
    busy := true.B
    result.foreach(_ := 0.U)
    val pendingInit = Wire(Vec(operationCount, Bool()))
    val faults = Wire(Vec(config.lanes, Bool()))
    for (lane <- 0 until config.lanes) {
      val bytes = 1.U << io.in.bits.elementSize
      val offset = io.in.bits.addresses(lane) - config.sharedMemoryBase.U
      faults(lane) := io.in.bits.laneMask(lane) &&
        (offset + bytes > config.sharedMemoryBytes.U)
      for (byte <- 0 until 4) {
        pendingInit(lane * 4 + byte) := io.in.bits.laneMask(lane) &&
          byte.U < bytes && !faults(lane)
      }
    }
    pending := pendingInit.asUInt
    faultMask := faults.asUInt
  }
  when(io.atomicIn.fire) {
    atomicRequest := io.atomicIn.bits
    atomicFault := io.atomicIn.bits.address(1, 0).orR ||
      io.atomicIn.bits.address < config.sharedMemoryBase.U ||
      io.atomicIn.bits.address + 4.U >
        (config.sharedMemoryBase + config.sharedMemoryBytes).U
    atomicState := Mux(
      io.atomicIn.bits.address(1, 0).orR ||
        io.atomicIn.bits.address < config.sharedMemoryBase.U ||
        io.atomicIn.bits.address + 4.U >
          (config.sharedMemoryBase + config.sharedMemoryBytes).U,
      AtomicState.respond,
      AtomicState.read
    )
  }

  val servicedByBank = Wire(Vec(config.sharedMemoryBanks, UInt(operationCount.W)))
  val readIssued = Wire(Vec(config.sharedMemoryBanks, Bool()))
  val readValid = Wire(Vec(config.sharedMemoryBanks, Bool()))
  val readLane = Wire(Vec(config.sharedMemoryBanks, UInt(laneWidth.W)))
  val readData = Wire(Vec(config.sharedMemoryBanks, UInt(32.W)))
  val atomicBytes = Wire(Vec(config.sharedMemoryBanks, UInt(32.W)))
  for (bank <- 0 until config.sharedMemoryBanks) {
    val candidates = Wire(Vec(operationCount, Bool()))
    for (operation <- 0 until operationCount) {
      val lane = operation / 4
      val byte = operation % 4
      val offset = transaction.addresses(lane) - config.sharedMemoryBase.U + byte.U
      candidates(operation) := pending(operation) &&
        offset(bankWidth - 1, 0) === bank.U
    }
    val candidateBits = candidates.asUInt
    val hasCandidate = candidateBits.orR
    val selected = PriorityEncoder(candidateBits)
    val selectedLane = selected >> 2
    val selectedByte = selected(1, 0)
    val selectedOffset =
      transaction.addresses(selectedLane) - config.sharedMemoryBase.U + selectedByte
    val bankAddress = selectedOffset >> bankWidth
    servicedByBank(bank) := Mux(hasCandidate,
      UIntToOH(selected, operationCount), 0.U)
    val issueVectorRead = hasCandidate && !transaction.isStore
    val atomicOffset = atomicRequest.address - config.sharedMemoryBase.U
    val atomicByteMatch = Wire(Vec(4, Bool()))
    for (byte <- 0 until 4) {
      atomicByteMatch(byte) :=
        (atomicOffset + byte.U)(bankWidth - 1, 0) === bank.U
    }
    val atomicByteBits = atomicByteMatch.asUInt
    val atomicHasByte = atomicByteBits.orR
    val atomicByte = PriorityEncoder(atomicByteBits)
    val atomicBankAddress = (atomicOffset + atomicByte) >> bankWidth
    val issueAtomicRead = atomicState === AtomicState.read && atomicHasByte
    val issueRead = issueVectorRead || issueAtomicRead
    val selectedReadAddress = Mux(issueAtomicRead, atomicBankAddress, bankAddress)
    val memoryRead = banks(bank).read(selectedReadAddress, issueRead)
    val responseValid = RegNext(issueVectorRead, false.B)
    val responseLane = RegEnable(selectedLane, issueVectorRead)
    val responseByte = RegEnable(selectedByte, issueVectorRead)
    val atomicResponseValid = RegNext(issueAtomicRead, false.B)
    val atomicResponseByte = RegEnable(atomicByte, issueAtomicRead)
    readIssued(bank) := issueVectorRead
    readValid(bank) := responseValid
    readLane(bank) := responseLane
    readData(bank) := memoryRead << (responseByte << 3)
    atomicBytes(bank) := Mux(
      atomicResponseValid,
      memoryRead << (atomicResponseByte << 3),
      0.U
    )
    val issueVectorWrite = hasCandidate && transaction.isStore
    val issueAtomicWrite =
      atomicState === AtomicState.write && atomicHasByte
    val writeAddress = Mux(issueAtomicWrite, atomicBankAddress, bankAddress)
    val vectorWriteData =
      (transaction.writeData(selectedLane) >> (selectedByte << 3))(7, 0)
    val atomicWriteData =
      (atomicNewValue >> (atomicByte << 3))(7, 0)
    when(issueAtomicWrite || issueVectorWrite) {
      banks(bank).write(
        writeAddress,
        Mux(issueAtomicWrite, atomicWriteData, vectorWriteData)
      )
    }
  }
  private val serviced = servicedByBank.reduce(_ | _)

  when(busy) {
    for (lane <- 0 until config.lanes) {
      val laneUpdates = (0 until config.sharedMemoryBanks).map { bank =>
        Mux(readValid(bank) && readLane(bank) === lane.U, readData(bank), 0.U)
      }.reduce(_ | _)
      result(lane) := result(lane) | laneUpdates
    }
    val remaining = pending & ~serviced
    pending := remaining
    // A synchronous read issued this cycle returns on the next cycle. Once no
    // request remains and no new read was launched, any prior read response is
    // captured at this edge and the architectural response may become valid.
    when(!remaining.orR && !readIssued.asUInt.orR) {
      busy := false.B
      outputValid := true.B
    }
  }

  io.out.valid := outputValid
  io.out.bits.readData := result
  io.out.bits.faultMask := faultMask
  io.out.bits.pageFault := false.B
  when(io.out.fire) { outputValid := false.B }
  private val atomicReadWord = atomicBytes.reduce(_ | _)
  when(atomicState === AtomicState.read) {
    atomicState := AtomicState.waitForRead
  }
  when(atomicState === AtomicState.waitForRead) {
    atomicOldValue := atomicReadWord
    atomicNewValue := MuxLookup(
      atomicRequest.operation,
      atomicRequest.operand
    )(Seq(
      AtomicMemoryOp.swap -> atomicRequest.operand,
      AtomicMemoryOp.add -> (atomicReadWord + atomicRequest.operand),
      AtomicMemoryOp.xor -> (atomicReadWord ^ atomicRequest.operand),
      AtomicMemoryOp.or -> (atomicReadWord | atomicRequest.operand),
      AtomicMemoryOp.and -> (atomicReadWord & atomicRequest.operand),
      AtomicMemoryOp.min -> Mux(
        atomicReadWord.asSInt < atomicRequest.operand.asSInt,
        atomicReadWord, atomicRequest.operand),
      AtomicMemoryOp.max -> Mux(
        atomicReadWord.asSInt > atomicRequest.operand.asSInt,
        atomicReadWord, atomicRequest.operand),
      AtomicMemoryOp.minu -> Mux(
        atomicReadWord < atomicRequest.operand,
        atomicReadWord, atomicRequest.operand),
      AtomicMemoryOp.maxu -> Mux(
        atomicReadWord > atomicRequest.operand,
        atomicReadWord, atomicRequest.operand)
    ))
    atomicState := AtomicState.write
  }
  when(atomicState === AtomicState.write) {
    atomicState := AtomicState.respond
  }
  io.atomicOut.valid := atomicState === AtomicState.respond
  io.atomicOut.bits.warpId := atomicRequest.warpId
  io.atomicOut.bits.oldValue := atomicOldValue
  io.atomicOut.bits.fault := atomicFault
  when(io.atomicOut.fire) {
    atomicState := AtomicState.idle
    atomicFault := false.B
  }
  io.idle := !busy && !outputValid && atomicState === AtomicState.idle
}

/** Routes a complete vector transaction to either CU-local shared memory or
  * the normal translated global-memory path and locks that choice to response.
  */
class VectorMemorySpaceRouter(config: GpuConfig = GpuConfig()) extends Module {
  private val idle :: global :: local :: Nil = Enum(3)
  private val state = RegInit(idle)
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new VectorMemoryTransaction(config)))
    val globalRequest = Decoupled(new VectorMemoryTransaction(config))
    val globalResponse = Flipped(Decoupled(new VectorMemoryResponse(config)))
    val localRequest = Decoupled(new VectorMemoryTransaction(config))
    val localResponse = Flipped(Decoupled(new VectorMemoryResponse(config)))
    val out = Decoupled(new VectorMemoryResponse(config))
  })

  private val localLane = Wire(Vec(config.lanes, Bool()))
  for (lane <- 0 until config.lanes) {
    localLane(lane) := io.in.bits.laneMask(lane) &&
      io.in.bits.addresses(lane) >= config.sharedMemoryBase.U &&
      io.in.bits.addresses(lane) <
        (config.sharedMemoryBase + config.sharedMemoryBytes).U
  }
  private val selectLocal = localLane.asUInt.orR
  io.globalRequest.valid := state === idle && io.in.valid && !selectLocal
  io.localRequest.valid := state === idle && io.in.valid && selectLocal
  io.globalRequest.bits := io.in.bits
  io.localRequest.bits := io.in.bits
  io.in.ready := Mux(selectLocal, io.localRequest.ready, io.globalRequest.ready) &&
    state === idle
  when(io.in.fire) { state := Mux(selectLocal, local, global) }

  io.out.valid := Mux(state === local, io.localResponse.valid,
    Mux(state === global, io.globalResponse.valid, false.B))
  io.out.bits := Mux(state === local, io.localResponse.bits, io.globalResponse.bits)
  io.localResponse.ready := state === local && io.out.ready
  io.globalResponse.ready := state === global && io.out.ready
  when(io.out.fire) { state := idle }
}
