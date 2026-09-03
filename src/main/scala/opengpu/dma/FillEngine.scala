package opengpu.dma

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.{ComputeMemoryRequest, ComputeMemoryResponse}

class FillDescriptor(config: GpuConfig, val descriptorIdWidth: Int) extends Bundle {
  val descriptorId = UInt(descriptorIdWidth.W)
  val destinationAddress = UInt(config.xLen.W)
  val bytes = UInt(32.W)
  val pattern = UInt(32.W)
}

class FillCompletion(val descriptorIdWidth: Int) extends Bundle {
  val descriptorId = UInt(descriptorIdWidth.W)
  val status = UInt(CopyStatus.width.W)
  val success = Bool()
  val bytesFilled = UInt(32.W)
}

/** Full-cache-line fill engine with multiple writes in flight.
  *
  * `transactionIdBase` offsets the engine's transaction IDs on a shared line
  * port so several clients can hold transactions simultaneously without ID
  * aliasing; the owner routes responses back by subtracting the base.
  */
class FillEngine(
  config: GpuConfig = GpuConfig(),
  descriptorIdWidth: Int = 8,
  lineBytes: Int = 64,
  maxOutstanding: Int = 2,
  descriptorQueueDepth: Int = 4,
  lineSlots: Int = 2,
  transactionIdBase: Int = 0
) extends Module {
  require(lineBytes == 64 && isPow2(lineBytes))
  require(lineSlots > 0 && isPow2(lineSlots))
  require(maxOutstanding >= lineSlots)
  require(descriptorQueueDepth > 0)
  require(transactionIdBase >= 0 && transactionIdBase + lineSlots <= maxOutstanding,
    "transaction ID range must fit the port's outstanding space")
  private val offsetWidth = log2Ceil(lineBytes)

  val io = IO(new Bundle {
    val descriptor = Flipped(Decoupled(
      new FillDescriptor(config, descriptorIdWidth)))
    val completion = Decoupled(new FillCompletion(descriptorIdWidth))
    val memoryRequest = Decoupled(new ComputeMemoryRequest(
      config, lineBytes, maxOutstanding))
    val memoryResponse = Flipped(Decoupled(new ComputeMemoryResponse(
      lineBytes, maxOutstanding)))
    val busy = Output(Bool())
  })

  private val descriptors = Module(new Queue(
    new FillDescriptor(config, descriptorIdWidth), descriptorQueueDepth,
    pipe = true, flow = true))
  descriptors.io.enq <> io.descriptor

  private val active = RegInit(false.B)
  private val aborting = RegInit(false.B)
  private val completionValid = RegInit(false.B)
  private val descriptorId = Reg(UInt(descriptorIdWidth.W))
  private val nextAddress = Reg(UInt(config.xLen.W))
  private val remainingBytes = Reg(UInt(32.W))
  private val copiedBytes = RegInit(0.U(32.W))
  private val pattern = Reg(UInt(32.W))
  private val resultStatus = RegInit(CopyStatus.success)
  private val pending = RegInit(VecInit(Seq.fill(lineSlots)(false.B)))

  private val destinationEnd = Cat(0.U(1.W),
    descriptors.io.deq.bits.destinationAddress) + descriptors.io.deq.bits.bytes
  private val aligned =
    descriptors.io.deq.bits.destinationAddress(offsetWidth - 1, 0) === 0.U
  private val lengthValid = descriptors.io.deq.bits.bytes.orR &&
    descriptors.io.deq.bits.bytes(offsetWidth - 1, 0) === 0.U
  private val overflow = destinationEnd(config.xLen)
  private val descriptorValid = aligned && lengthValid && !overflow
  private val descriptorError = Mux(!aligned, CopyStatus.invalidAlignment,
    Mux(!lengthValid, CopyStatus.invalidLength, CopyStatus.addressOverflow))

  descriptors.io.deq.ready := !active && !completionValid
  when(descriptors.io.deq.fire) {
    descriptorId := descriptors.io.deq.bits.descriptorId
    nextAddress := descriptors.io.deq.bits.destinationAddress
    remainingBytes := descriptors.io.deq.bits.bytes
    pattern := descriptors.io.deq.bits.pattern
    copiedBytes := 0.U
    aborting := false.B
    resultStatus := Mux(descriptorValid, CopyStatus.success, descriptorError)
    when(descriptorValid) { active := true.B }
      .otherwise { completionValid := true.B }
  }

  private val free = VecInit(pending.map(!_))
  private val hasFree = free.asUInt.orR
  private val requestSlot = PriorityEncoder(free)
  io.memoryRequest.valid := active && !aborting && remainingBytes.orR && hasFree
  io.memoryRequest.bits.address := nextAddress
  io.memoryRequest.bits.writeData := Fill(lineBytes / 4, pattern)
  io.memoryRequest.bits.byteMask := Fill(lineBytes, 1.U(1.W))
  io.memoryRequest.bits.isWrite := true.B
  io.memoryRequest.bits.sizeLog2 := offsetWidth.U
  io.memoryRequest.bits.cacheClient := false.B
  io.memoryRequest.bits.cacheResident := false.B
  io.memoryRequest.bits.transactionId := (transactionIdBase.U +
    requestSlot).asUInt
  when(io.memoryRequest.fire) {
    pending(requestSlot) := true.B
    nextAddress := nextAddress + lineBytes.U
    remainingBytes := remainingBytes - lineBytes.U
  }

  private val responseId = io.memoryResponse.bits.transactionId - transactionIdBase.U
  private val responseInRange = responseId < lineSlots.U
  private val responseExpected = responseInRange && pending(responseId)
  io.memoryResponse.ready := active && responseExpected
  when(io.memoryResponse.valid) {
    assert(active && responseExpected,
      "fill response must identify an outstanding write slot")
  }
  when(io.memoryResponse.fire) {
    pending(responseId) := false.B
    when(io.memoryResponse.bits.fault) {
      when(!aborting) {
        aborting := true.B
        resultStatus := CopyStatus.writeFault
      }
    }.otherwise {
      copiedBytes := copiedBytes + lineBytes.U
    }
  }

  private val allSlotsFree = !pending.asUInt.orR
  when(active && allSlotsFree && (aborting || !remainingBytes.orR)) {
    active := false.B
    completionValid := true.B
  }

  io.completion.valid := completionValid
  io.completion.bits.descriptorId := descriptorId
  io.completion.bits.status := resultStatus
  io.completion.bits.success := resultStatus === CopyStatus.success
  io.completion.bits.bytesFilled := copiedBytes
  when(io.completion.fire) { completionValid := false.B }
  io.busy := active || completionValid || descriptors.io.deq.valid
}
