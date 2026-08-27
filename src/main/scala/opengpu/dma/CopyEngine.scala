package opengpu.dma

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.{ComputeMemoryRequest, ComputeMemoryResponse}

class CopyDescriptor(config: GpuConfig, val descriptorIdWidth: Int) extends Bundle {
  require(descriptorIdWidth > 0)
  val descriptorId = UInt(descriptorIdWidth.W)
  val sourceAddress = UInt(config.xLen.W)
  val destinationAddress = UInt(config.xLen.W)
  val bytes = UInt(32.W)
}

object CopyStatus {
  val width = 3
  val success = 0.U(width.W)
  val invalidAlignment = 1.U(width.W)
  val invalidLength = 2.U(width.W)
  val addressOverflow = 3.U(width.W)
  val overlapUnsupported = 4.U(width.W)
  val readFault = 5.U(width.W)
  val writeFault = 6.U(width.W)
}

class CopyCompletion(val descriptorIdWidth: Int) extends Bundle {
  val descriptorId = UInt(descriptorIdWidth.W)
  val status = UInt(CopyStatus.width.W)
  val success = Bool()
  val bytesCopied = UInt(32.W)
}

/** Cache-line copy engine with independent line slots.
  *
  * Read transaction IDs select slots 0 until lineSlots. Write IDs use the
  * same slot number offset by lineSlots, so responses may return out of order
  * without an associative lookup. A fault stops new work and drains every
  * already-issued request before reporting completion.
  */
class CopyEngine(
  config: GpuConfig = GpuConfig(),
  descriptorIdWidth: Int = 8,
  lineBytes: Int = 64,
  maxOutstanding: Int = 4,
  descriptorQueueDepth: Int = 4,
  lineSlots: Int = 2
) extends Module {
  require(lineBytes == 64 && isPow2(lineBytes))
  require(lineSlots > 0 && isPow2(lineSlots))
  require(maxOutstanding >= 2 * lineSlots)
  require(descriptorQueueDepth > 0)
  private val offsetWidth = log2Ceil(lineBytes)
  private val slotWidth = math.max(1, log2Ceil(lineSlots))

  val io = IO(new Bundle {
    val descriptor = Flipped(Decoupled(
      new CopyDescriptor(config, descriptorIdWidth)))
    val completion = Decoupled(new CopyCompletion(descriptorIdWidth))
    val memoryRequest = Decoupled(new ComputeMemoryRequest(
      config, lineBytes, maxOutstanding))
    val memoryResponse = Flipped(Decoupled(new ComputeMemoryResponse(
      lineBytes, maxOutstanding)))
    val busy = Output(Bool())
  })

  private object SlotState {
    val free = 0.U(2.W)
    val readPending = 1.U(2.W)
    val dataReady = 2.U(2.W)
    val writePending = 3.U(2.W)
  }

  private val active = RegInit(false.B)
  private val aborting = RegInit(false.B)
  private val descriptorId = Reg(UInt(descriptorIdWidth.W))
  private val sourceAddress = Reg(UInt(config.xLen.W))
  private val destinationAddress = Reg(UInt(config.xLen.W))
  private val remainingBytes = Reg(UInt(32.W))
  private val copiedBytes = RegInit(0.U(32.W))
  private val resultStatus = RegInit(CopyStatus.success)
  private val completionValid = RegInit(false.B)
  private val slotState = RegInit(VecInit(Seq.fill(lineSlots)(SlotState.free)))
  private val slotDestination = Reg(Vec(lineSlots, UInt(config.xLen.W)))
  private val slotData = Reg(Vec(lineSlots, UInt((lineBytes * 8).W)))

  private val descriptors = Module(new Queue(
    new CopyDescriptor(config, descriptorIdWidth), descriptorQueueDepth,
    pipe = true, flow = true))
  descriptors.io.enq <> io.descriptor

  private val sourceEnd = Cat(0.U(1.W), descriptors.io.deq.bits.sourceAddress) +
    descriptors.io.deq.bits.bytes
  private val destinationEnd = Cat(0.U(1.W),
    descriptors.io.deq.bits.destinationAddress) + descriptors.io.deq.bits.bytes
  private val aligned =
    descriptors.io.deq.bits.sourceAddress(offsetWidth - 1, 0) === 0.U &&
      descriptors.io.deq.bits.destinationAddress(offsetWidth - 1, 0) === 0.U
  private val lengthValid = descriptors.io.deq.bits.bytes.orR &&
    descriptors.io.deq.bits.bytes(offsetWidth - 1, 0) === 0.U
  private val overflow = sourceEnd(config.xLen) || destinationEnd(config.xLen)
  private val overlap =
    Cat(0.U(1.W), descriptors.io.deq.bits.sourceAddress) < destinationEnd &&
      Cat(0.U(1.W), descriptors.io.deq.bits.destinationAddress) < sourceEnd
  private val descriptorValid = aligned && lengthValid && !overflow && !overlap
  private val descriptorError = Mux(!aligned, CopyStatus.invalidAlignment,
    Mux(!lengthValid, CopyStatus.invalidLength,
      Mux(overflow, CopyStatus.addressOverflow,
        CopyStatus.overlapUnsupported)))

  descriptors.io.deq.ready := !active && !completionValid
  when(descriptors.io.deq.fire) {
    descriptorId := descriptors.io.deq.bits.descriptorId
    sourceAddress := descriptors.io.deq.bits.sourceAddress
    destinationAddress := descriptors.io.deq.bits.destinationAddress
    remainingBytes := descriptors.io.deq.bits.bytes
    copiedBytes := 0.U
    aborting := false.B
    resultStatus := Mux(descriptorValid, CopyStatus.success, descriptorError)
    when(descriptorValid) {
      active := true.B
    }.otherwise {
      completionValid := true.B
    }
  }

  private val freeSlots = VecInit(slotState.map(_ === SlotState.free))
  private val writeSlots = VecInit(slotState.map(_ === SlotState.dataReady))
  private val hasFreeSlot = freeSlots.asUInt.orR
  private val hasWriteSlot = writeSlots.asUInt.orR
  private val readSlot = PriorityEncoder(freeSlots)
  private val writeSlot = PriorityEncoder(writeSlots)
  private val issueRead = active && !aborting && remainingBytes.orR && hasFreeSlot
  private val issueWrite = active && !aborting && hasWriteSlot
  private val requestSlot = Mux(issueWrite, writeSlot, readSlot)

  io.memoryRequest.valid := issueWrite || issueRead
  io.memoryRequest.bits.address := Mux(issueWrite,
    slotDestination(writeSlot), sourceAddress)
  io.memoryRequest.bits.writeData := Mux(issueWrite, slotData(writeSlot), 0.U)
  io.memoryRequest.bits.byteMask := Mux(issueWrite,
    Fill(lineBytes, 1.U(1.W)), 0.U)
  io.memoryRequest.bits.isWrite := issueWrite
  io.memoryRequest.bits.sizeLog2 := offsetWidth.U
  io.memoryRequest.bits.cacheClient := false.B
  io.memoryRequest.bits.cacheResident := false.B
  io.memoryRequest.bits.transactionId := Mux(issueWrite,
    writeSlot + lineSlots.U, readSlot)

  when(io.memoryRequest.fire) {
    when(issueWrite) {
      slotState(requestSlot) := SlotState.writePending
    }.otherwise {
      slotState(requestSlot) := SlotState.readPending
      slotDestination(requestSlot) := destinationAddress
      sourceAddress := sourceAddress + lineBytes.U
      destinationAddress := destinationAddress + lineBytes.U
      remainingBytes := remainingBytes - lineBytes.U
    }
  }

  private val responseId = io.memoryResponse.bits.transactionId
  private val responseIsRead = responseId < lineSlots.U
  private val responseInRange = responseId < (2 * lineSlots).U
  private val responseSlot = Mux(responseIsRead, responseId,
    responseId - lineSlots.U)(slotWidth - 1, 0)
  private val responseExpected = responseInRange && Mux(responseIsRead,
    slotState(responseSlot) === SlotState.readPending,
    slotState(responseSlot) === SlotState.writePending)
  io.memoryResponse.ready := active && responseExpected

  when(io.memoryResponse.valid) {
    assert(active && responseExpected,
      "copy response must identify an outstanding read or write slot")
  }
  when(io.memoryResponse.fire) {
    when(responseIsRead) {
      when(io.memoryResponse.bits.fault) {
        slotState(responseSlot) := SlotState.free
        when(!aborting) {
          aborting := true.B
          resultStatus := CopyStatus.readFault
        }
      }.elsewhen(aborting) {
        slotState(responseSlot) := SlotState.free
      }.otherwise {
        slotData(responseSlot) := io.memoryResponse.bits.readData
        slotState(responseSlot) := SlotState.dataReady
      }
    }.otherwise {
      slotState(responseSlot) := SlotState.free
      when(io.memoryResponse.bits.fault) {
        when(!aborting) {
          aborting := true.B
          resultStatus := CopyStatus.writeFault
        }
      }.otherwise {
        copiedBytes := copiedBytes + lineBytes.U
      }
    }
  }

  // Data that was read but not issued for write is safe to discard after a
  // fault. Pending transactions remain allocated until their responses drain.
  when(aborting) {
    for (slot <- 0 until lineSlots) {
      when(slotState(slot) === SlotState.dataReady) {
        slotState(slot) := SlotState.free
      }
    }
  }

  private val allSlotsFree = slotState.map(_ === SlotState.free).reduce(_ && _)
  when(active && allSlotsFree && (aborting || !remainingBytes.orR)) {
    active := false.B
    completionValid := true.B
  }

  io.completion.valid := completionValid
  io.completion.bits.descriptorId := descriptorId
  io.completion.bits.status := resultStatus
  io.completion.bits.success := resultStatus === CopyStatus.success
  io.completion.bits.bytesCopied := copiedBytes
  when(io.completion.fire) { completionValid := false.B }

  io.busy := active || completionValid || descriptors.io.deq.valid
}
