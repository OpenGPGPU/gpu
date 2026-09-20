package opengpu.dma

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.{ComputeMemoryRequest, ComputeMemoryResponse}

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
  descriptorQueueDepth: Int = 4,
  transactionIdBase: Int = 0
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
    descriptorQueueDepth = 1, lineSlots = 2,
    transactionIdBase = transactionIdBase))
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
  private val decodeStage = RegInit(0.U(3.W))
  private val holdId = Reg(UInt(descriptorIdWidth.W))
  private val holdSrcAddr = Reg(UInt(config.xLen.W))
  private val holdDstAddr = Reg(UInt(config.xLen.W))
  private val holdWidth = Reg(UInt(32.W))
  private val holdHeight = Reg(UInt(32.W))
  private val holdSrcStride = Reg(UInt(32.W))
  private val holdDstStride = Reg(UInt(32.W))
  private val holdLastRow = Reg(UInt(32.W))
  private val lineShift = offsetWidth
  private val lineAddrWidth = config.xLen - lineShift
  private val strideLineWidth = 32 - lineShift
  private val partWidth = 8 + strideLineWidth
  private val prodWidth = 32 + strideLineWidth
  private val endLineWidth = prodWidth + 1
  private val holdSrcBaseLine = holdSrcAddr(config.xLen - 1, lineShift)
  private val holdDstBaseLine = holdDstAddr(config.xLen - 1, lineShift)
  private val holdSrcStrideLineA = Reg(UInt(strideLineWidth.W))
  private val holdSrcStrideLineB = Reg(UInt(strideLineWidth.W))
  private val holdDstStrideLineA = Reg(UInt(strideLineWidth.W))
  private val holdDstStrideLineB = Reg(UInt(strideLineWidth.W))
  private val holdWidthLine = holdWidth(31, lineShift)
  private val lastRowB0 = holdLastRow(7, 0)
  private val lastRowB1 = holdLastRow(15, 8)
  private val lastRowB2 = holdLastRow(23, 16)
  private val lastRowB3 = holdLastRow(31, 24)
  private val srcP0 = Reg(UInt(partWidth.W))
  private val srcP1 = Reg(UInt(partWidth.W))
  private val srcP2 = Reg(UInt(partWidth.W))
  private val srcP3 = Reg(UInt(partWidth.W))
  private val dstP0 = Reg(UInt(partWidth.W))
  private val dstP1 = Reg(UInt(partWidth.W))
  private val dstP2 = Reg(UInt(partWidth.W))
  private val dstP3 = Reg(UInt(partWidth.W))
  private val pairWidth = partWidth + 8 + 1
  private val srcPairLo = Reg(UInt(pairWidth.W))
  private val srcPairHi = Reg(UInt(pairWidth.W))
  private val dstPairLo = Reg(UInt(pairWidth.W))
  private val dstPairHi = Reg(UInt(pairWidth.W))
  private val prodSrc = Reg(UInt(endLineWidth.W))
  private val prodDst = Reg(UInt(endLineWidth.W))
  private val sumSrcLine = Reg(UInt(endLineWidth.W))
  private val sumDstLine = Reg(UInt(endLineWidth.W))
  private val endLoWidth = 30
  private val endHiWidth = endLineWidth - endLoWidth
  private val srcEndLo = Reg(UInt(endLoWidth.W))
  private val dstEndLo = Reg(UInt(endLoWidth.W))
  private val srcEndCarry = Reg(Bool())
  private val dstEndCarry = Reg(Bool())
  private val srcEndHi = Reg(UInt(endHiWidth.W))
  private val dstEndHi = Reg(UInt(endHiWidth.W))
  private val holdSourceEndLine = Cat(srcEndHi, srcEndLo)
  private val holdDestinationEndLine = Cat(dstEndHi, dstEndLo)
  private val holdAligned = Reg(Bool())
  private val holdDimensionsValid = Reg(Bool())
  private val alignedNow = holdSrcAddr(lineShift - 1, 0) === 0.U &&
    holdDstAddr(lineShift - 1, 0) === 0.U &&
    holdSrcStride(lineShift - 1, 0) === 0.U &&
    holdDstStride(lineShift - 1, 0) === 0.U
  private val dimensionsNow = holdWidth.orR && holdHeight.orR &&
    holdWidth(lineShift - 1, 0) === 0.U &&
    holdSrcStride >= holdWidth && holdDstStride >= holdWidth
  private val overflow =
    holdSourceEndLine(endLineWidth - 1, lineAddrWidth).orR ||
      holdDestinationEndLine(endLineWidth - 1, lineAddrWidth).orR
  private val srcBaseWide = Cat(0.U((endLineWidth - lineAddrWidth).W), holdSrcBaseLine)
  private val dstBaseWide = Cat(0.U((endLineWidth - lineAddrWidth).W), holdDstBaseLine)
  private val overlap = srcBaseWide < holdDestinationEndLine &&
    dstBaseWide < holdSourceEndLine
  private val descriptorValid = holdAligned && holdDimensionsValid && !overflow && !overlap
  private val descriptorError = Mux(!holdAligned, CopyStatus.invalidAlignment,
    Mux(!holdDimensionsValid, CopyStatus.invalidLength,
      Mux(overflow, CopyStatus.addressOverflow, CopyStatus.overlapUnsupported)))

  descriptors.io.deq.ready := !active && !completionValid && decodeStage === 0.U
  dontTouch(holdSrcStrideLineA)
  dontTouch(holdSrcStrideLineB)
  dontTouch(holdDstStrideLineA)
  dontTouch(holdDstStrideLineB)
  when(descriptors.io.deq.fire) {
    holdId := d.descriptorId
    holdSrcAddr := d.sourceAddress
    holdDstAddr := d.destinationAddress
    holdWidth := d.widthBytes
    holdHeight := d.height
    holdSrcStride := d.sourceStride
    holdDstStride := d.destinationStride
    holdSrcStrideLineA := d.sourceStride(31, lineShift)
    holdSrcStrideLineB := d.sourceStride(31, lineShift)
    holdDstStrideLineA := d.destinationStride(31, lineShift)
    holdDstStrideLineB := d.destinationStride(31, lineShift)
    holdLastRow := d.height - 1.U
    decodeStage := 1.U
  }
  when(decodeStage === 1.U) {
    srcP0 := lastRowB0 * holdSrcStrideLineA
    srcP1 := lastRowB1 * holdSrcStrideLineA
    srcP2 := lastRowB2 * holdSrcStrideLineB
    srcP3 := lastRowB3 * holdSrcStrideLineB
    dstP0 := lastRowB0 * holdDstStrideLineA
    dstP1 := lastRowB1 * holdDstStrideLineA
    dstP2 := lastRowB2 * holdDstStrideLineB
    dstP3 := lastRowB3 * holdDstStrideLineB
    holdAligned := alignedNow
    holdDimensionsValid := dimensionsNow
    decodeStage := 2.U
  }
  when(decodeStage === 2.U) {
    srcPairLo := Cat(0.U(8.W), srcP0) + Cat(srcP1, 0.U(8.W))
    srcPairHi := Cat(0.U(8.W), srcP2) + Cat(srcP3, 0.U(8.W))
    dstPairLo := Cat(0.U(8.W), dstP0) + Cat(dstP1, 0.U(8.W))
    dstPairHi := Cat(0.U(8.W), dstP2) + Cat(dstP3, 0.U(8.W))
    decodeStage := 3.U
  }
  when(decodeStage === 3.U) {
    prodSrc := Cat(0.U(16.W), srcPairLo) + Cat(srcPairHi, 0.U(16.W))
    prodDst := Cat(0.U(16.W), dstPairLo) + Cat(dstPairHi, 0.U(16.W))
    decodeStage := 4.U
  }
  when(decodeStage === 4.U) {
    sumSrcLine := srcBaseWide + prodSrc
    sumDstLine := dstBaseWide + prodDst
    decodeStage := 5.U
  }
  private val widthWideLo = Cat(0.U((endLoWidth - strideLineWidth).W), holdWidthLine)
  when(decodeStage === 5.U) {
    val srcLoSum = sumSrcLine(endLoWidth - 1, 0) +& widthWideLo
    val dstLoSum = sumDstLine(endLoWidth - 1, 0) +& widthWideLo
    srcEndLo := srcLoSum(endLoWidth - 1, 0)
    dstEndLo := dstLoSum(endLoWidth - 1, 0)
    srcEndCarry := srcLoSum(endLoWidth)
    dstEndCarry := dstLoSum(endLoWidth)
    decodeStage := 6.U
  }
  when(decodeStage === 6.U) {
    srcEndHi := sumSrcLine(endLineWidth - 1, endLoWidth) + srcEndCarry
    dstEndHi := sumDstLine(endLineWidth - 1, endLoWidth) + dstEndCarry
    decodeStage := 7.U
  }
  when(decodeStage === 7.U) {
    descriptorId := holdId
    sourceAddress := holdSrcAddr
    destinationAddress := holdDstAddr
    widthBytes := holdWidth
    rowsRemaining := holdHeight
    sourceStride := holdSrcStride
    destinationStride := holdDstStride
    copiedBytes := 0.U
    resultStatus := Mux(descriptorValid, CopyStatus.success, descriptorError)
    when(descriptorValid) { active := true.B }
      .otherwise { completionValid := true.B }
    decodeStage := 0.U
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
  io.busy := active || completionValid || decodeStage =/= 0.U ||
    descriptors.io.deq.valid || rowCopy.io.busy
}
