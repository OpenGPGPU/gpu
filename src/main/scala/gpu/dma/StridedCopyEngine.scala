package gpu.dma

import chisel3._
import chisel3.util._
import gpu.config.GpuConfig
import gpu.core.memory.{ComputeMemoryRequest, ComputeMemoryResponse}

class StridedCopyDescriptor(config: GpuConfig, val descriptorIdWidth: Int)
    extends Bundle {
  val descriptorId = UInt(descriptorIdWidth.W)
  val sourceAddress = UInt(config.xLen.W)
  val destinationAddress = UInt(config.xLen.W)
  val widthBytes = UInt(32.W)
  val height = UInt(32.W)
  val sourceStride = UInt(32.W)
  val destinationStride = UInt(32.W)
}

class StridedCopyCompletion(val descriptorIdWidth: Int) extends Bundle {
  val descriptorId = UInt(descriptorIdWidth.W)
  val status = UInt(CopyStatus.width.W)
  val success = Bool()
  val bytesCopied = UInt(64.W)
}

/** Two-dimensional cache-line copy sequencer.
  *
  * Rows execute in order through the non-blocking CopyEngine. This preserves
  * precise row-level fault semantics while each row retains two-line memory
  * parallelism and out-of-order response support.
  */
class StridedCopyEngine(
  config: GpuConfig = GpuConfig(),
  descriptorIdWidth: Int = 8,
  lineBytes: Int = 64,
  maxOutstanding: Int = 4,
  descriptorQueueDepth: Int = 4
) extends Module {
  require(lineBytes == 64 && isPow2(lineBytes))
  require(maxOutstanding >= 4)
  require(descriptorQueueDepth > 0)
  private val offsetWidth = log2Ceil(lineBytes)

  val io = IO(new Bundle {
    val descriptor = Flipped(Decoupled(
      new StridedCopyDescriptor(config, descriptorIdWidth)))
    val completion = Decoupled(new StridedCopyCompletion(descriptorIdWidth))
    val memoryRequest = Decoupled(new ComputeMemoryRequest(
      config, lineBytes, maxOutstanding))
    val memoryResponse = Flipped(Decoupled(new ComputeMemoryResponse(
      lineBytes, maxOutstanding)))
    val busy = Output(Bool())
  })

  private val descriptors = Module(new Queue(
    new StridedCopyDescriptor(config, descriptorIdWidth),
    descriptorQueueDepth, pipe = true, flow = true))
  descriptors.io.enq <> io.descriptor
  private val rowCopy = Module(new CopyEngine(
    config, descriptorIdWidth, lineBytes, maxOutstanding,
    descriptorQueueDepth = 1, lineSlots = 2))
  io.memoryRequest <> rowCopy.io.memoryRequest
  rowCopy.io.memoryResponse <> io.memoryResponse

  private val active = RegInit(false.B)
  private val rowInFlight = RegInit(false.B)
  private val completionValid = RegInit(false.B)
  private val descriptorId = Reg(UInt(descriptorIdWidth.W))
  private val sourceAddress = Reg(UInt(config.xLen.W))
  private val destinationAddress = Reg(UInt(config.xLen.W))
  private val widthBytes = Reg(UInt(32.W))
  private val rowsRemaining = Reg(UInt(32.W))
  private val sourceStride = Reg(UInt(32.W))
  private val destinationStride = Reg(UInt(32.W))
  private val copiedBytes = RegInit(0.U(64.W))
  private val resultStatus = RegInit(CopyStatus.success)

  private val d = descriptors.io.deq.bits
  private val aligned = d.sourceAddress(offsetWidth - 1, 0) === 0.U &&
    d.destinationAddress(offsetWidth - 1, 0) === 0.U &&
    d.sourceStride(offsetWidth - 1, 0) === 0.U &&
    d.destinationStride(offsetWidth - 1, 0) === 0.U
  private val dimensionsValid = d.widthBytes.orR && d.height.orR &&
    d.widthBytes(offsetWidth - 1, 0) === 0.U &&
    d.sourceStride >= d.widthBytes && d.destinationStride >= d.widthBytes
  private val lastRow = d.height - 1.U
  private val sourceOffset = lastRow * d.sourceStride
  private val destinationOffset = lastRow * d.destinationStride
  private val sourceEnd = (Cat(0.U(32.W), d.sourceAddress) +&
    sourceOffset) +& d.widthBytes
  private val destinationEnd = (Cat(0.U(32.W), d.destinationAddress) +&
    destinationOffset) +& d.widthBytes
  private val overflow =
    sourceEnd(sourceEnd.getWidth - 1, config.xLen).orR ||
      destinationEnd(destinationEnd.getWidth - 1, config.xLen).orR
  private val overlap = Cat(0.U(32.W), d.sourceAddress) < destinationEnd &&
    Cat(0.U(32.W), d.destinationAddress) < sourceEnd
  private val descriptorValid = aligned && dimensionsValid && !overflow && !overlap
  private val descriptorError = Mux(!aligned, CopyStatus.invalidAlignment,
    Mux(!dimensionsValid, CopyStatus.invalidLength,
      Mux(overflow, CopyStatus.addressOverflow, CopyStatus.overlapUnsupported)))

  descriptors.io.deq.ready := !active && !completionValid
  when(descriptors.io.deq.fire) {
    descriptorId := d.descriptorId
    sourceAddress := d.sourceAddress
    destinationAddress := d.destinationAddress
    widthBytes := d.widthBytes
    rowsRemaining := d.height
    sourceStride := d.sourceStride
    destinationStride := d.destinationStride
    copiedBytes := 0.U
    resultStatus := Mux(descriptorValid, CopyStatus.success, descriptorError)
    when(descriptorValid) { active := true.B }
      .otherwise { completionValid := true.B }
  }

  rowCopy.io.descriptor.valid := active && !rowInFlight
  rowCopy.io.descriptor.bits.descriptorId := descriptorId
  rowCopy.io.descriptor.bits.sourceAddress := sourceAddress
  rowCopy.io.descriptor.bits.destinationAddress := destinationAddress
  rowCopy.io.descriptor.bits.bytes := widthBytes
  when(rowCopy.io.descriptor.fire) { rowInFlight := true.B }

  rowCopy.io.completion.ready := active && rowInFlight
  when(rowCopy.io.completion.fire) {
    rowInFlight := false.B
    copiedBytes := copiedBytes + rowCopy.io.completion.bits.bytesCopied
    when(!rowCopy.io.completion.bits.success) {
      resultStatus := rowCopy.io.completion.bits.status
      active := false.B
      completionValid := true.B
    }.elsewhen(rowsRemaining === 1.U) {
      active := false.B
      completionValid := true.B
    }.otherwise {
      rowsRemaining := rowsRemaining - 1.U
      sourceAddress := sourceAddress + sourceStride
      destinationAddress := destinationAddress + destinationStride
    }
  }

  io.completion.valid := completionValid
  io.completion.bits.descriptorId := descriptorId
  io.completion.bits.status := resultStatus
  io.completion.bits.success := resultStatus === CopyStatus.success
  io.completion.bits.bytesCopied := copiedBytes
  when(io.completion.fire) { completionValid := false.B }
  io.busy := active || completionValid || descriptors.io.deq.valid || rowCopy.io.busy
}
