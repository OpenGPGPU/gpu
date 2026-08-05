package gpu.system

import chisel3._
import chisel3.util._
import gpu.config.GpuConfig
import gpu.core.GpuComputeUnit
import gpu.core.backend.FpuFlags
import gpu.core.backend.issue.{ScalarIssuedInstruction, VectorIssuedInstruction}
import gpu.core.backend.register.{FpuRegisterWrite, ScalarRegisterWrite, VectorRegisterWrite}
import gpu.core.execute.control.SimtBranchRequest
import gpu.core.frontend.decode.FpuDecodeResponse
import gpu.core.memory._
import gpu.core.trap.CoreTrapEvent
import gpu.dispatch._
import gpu.dma._

/** Scalable compute-first GPU integration point.
  *
  * Kernels carry host command tags, CUs execute independently, and one shared
  * memory port supports out-of-order responses through globally unique
  * transaction IDs. Graphics fixed-function blocks can later attach outside
  * this boundary without changing the compute-unit contract.
  */
class GpuSystem(
  config: GpuConfig = GpuConfig(),
  numComputeUnits: Int = 2,
  commandIdWidth: Int = 8,
  transactionsPerCu: Int = 4,
  useBlackBoxes: Boolean = false,
  enableFpuBackend: Boolean = false
) extends Module {
  require(numComputeUnits > 0)
  private val totalTransactions = numComputeUnits * transactionsPerCu
  private val copyTransactions = 4
  private val fillTransactions = 2
  private val stridedCopyTransactions = 4
  private val copyBase = totalTransactions
  private val fillBase = copyBase + copyTransactions
  private val stridedCopyBase = fillBase + fillTransactions
  private val totalSystemTransactions =
    stridedCopyBase + stridedCopyTransactions
  private val systemTransactionWidth =
    math.max(1, log2Ceil(totalSystemTransactions))

  val io = IO(new Bundle {
    val command = Flipped(Decoupled(
      new KernelCommand(config, commandIdWidth)))
    val commandCompletion = Decoupled(
      new KernelCommandResult(commandIdWidth))
    val copyDescriptor = Flipped(Decoupled(
      new CopyDescriptor(config, commandIdWidth)))
    val copyCompletion = Decoupled(new CopyCompletion(commandIdWidth))
    val fillDescriptor = Flipped(Decoupled(
      new FillDescriptor(config, commandIdWidth)))
    val fillCompletion = Decoupled(new FillCompletion(commandIdWidth))
    val stridedCopyDescriptor = Flipped(Decoupled(
      new StridedCopyDescriptor(config, commandIdWidth)))
    val stridedCopyCompletion = Decoupled(
      new StridedCopyCompletion(commandIdWidth))

    val memoryRequest = Decoupled(new ComputeMemoryRequest(
      config, 64, totalSystemTransactions))
    val memoryResponse = Flipped(Decoupled(new ComputeMemoryResponse(
      64, totalSystemTransactions)))
    val invalidateInstructionCache = Input(Bool())
    val instructionSatp = Input(UInt(32.W))
    val instructionTlbFlush = Flipped(Valid(new VectorTlbFlush(config)))
    val vectorSatp = Input(UInt(32.W))
    val vectorTlbFlush = Flipped(Valid(new VectorTlbFlush(config)))

    // Until all decoded operations have native backends, preserve each CU's
    // explicit extension points instead of silently dropping instructions.
    val fpu = Vec(numComputeUnits, Decoupled(new FpuDecodeResponse(config)))
    val vector = Vec(numComputeUnits,
      Decoupled(new VectorIssuedInstruction(config)))
    val scalarMemory = Vec(numComputeUnits,
      Decoupled(new ScalarIssuedInstruction(config)))
    val unsupportedSystem = Vec(numComputeUnits,
      Decoupled(new ScalarIssuedInstruction(config)))
    val trap = Vec(numComputeUnits, Decoupled(new CoreTrapEvent(config)))
    val simtBranch = Vec(numComputeUnits,
      Flipped(Decoupled(new SimtBranchRequest(config))))

    val committedWriteback = Output(Vec(numComputeUnits,
      Valid(new ScalarRegisterWrite(config))))
    val committedVectorWriteback = Output(Vec(numComputeUnits,
      Valid(new VectorRegisterWrite(config))))
    val committedFpuWriteback = Output(Vec(numComputeUnits,
      Valid(new FpuRegisterWrite(config))))
    val committedFpuFlags = Output(Vec(numComputeUnits,
      Valid(new FpuFlags(config))))
    val activeWarps = Output(Vec(numComputeUnits, UInt(config.warps.W)))
    val blockedWarps = Output(Vec(numComputeUnits, UInt(config.warps.W)))
    val barrierWaiting = Output(Vec(numComputeUnits, UInt(config.warps.W)))
    val busyComputeUnits = Output(UInt(numComputeUnits.W))
    val commandProcessorBusy = Output(Bool())
    val queuedCommands = Output(UInt(
      math.max(1, log2Ceil(config.commandQueueDepth + 1)).W))
    val inFlightCommands = Output(UInt((commandIdWidth + 1).W))
    val duplicateCommandId = Output(Bool())
    val copyEngineBusy = Output(Bool())
    val fillEngineBusy = Output(Bool())
    val stridedCopyEngineBusy = Output(Bool())
  })

  private val commandProcessor = Module(new GpuCommandProcessor(
    config, commandIdWidth, commandQueueDepth = config.commandQueueDepth,
    completionQueueDepth = config.completionQueueDepth))
  private val dispatcher = Module(new MultiCuKernelDispatcher(
    config, numComputeUnits, commandIdWidth))
  private val memory = Module(new SharedComputeMemoryInterconnect(
    config, numComputeUnits, 64, transactionsPerCu))
  private val l2 = Module(new SharedL2Cache(
    config, sets = config.l2Sets, ways = config.l2Ways, lineBytes = 64,
    maxOutstanding = totalSystemTransactions, numComputeUnits = numComputeUnits,
    transactionsPerCu = transactionsPerCu, banks = config.l2Banks,
    requestQueueDepth = config.l2RequestQueueDepth))
  private val computeUnits = Seq.fill(numComputeUnits) {
    Module(new GpuComputeUnit(config, useBlackBoxes, enableFpuBackend))
  }
  private val copyEngine = Module(new CopyEngine(
    config, descriptorIdWidth = commandIdWidth, lineBytes = 64,
    maxOutstanding = copyTransactions,
    descriptorQueueDepth = config.copyDescriptorQueueDepth))
  private val fillEngine = Module(new FillEngine(
    config, descriptorIdWidth = commandIdWidth, lineBytes = 64,
    maxOutstanding = fillTransactions,
    descriptorQueueDepth = config.fillDescriptorQueueDepth))
  private val stridedCopyEngine = Module(new StridedCopyEngine(
    config, descriptorIdWidth = commandIdWidth, lineBytes = 64,
    maxOutstanding = stridedCopyTransactions,
    descriptorQueueDepth = config.stridedCopyDescriptorQueueDepth))

  commandProcessor.io.command <> io.command
  io.commandCompletion <> commandProcessor.io.completion
  dispatcher.io.launch <> commandProcessor.io.dispatch
  commandProcessor.io.dispatchCompletion <> dispatcher.io.completion
  io.busyComputeUnits := dispatcher.io.busy
  io.commandProcessorBusy := commandProcessor.io.busy
  io.queuedCommands := commandProcessor.io.queued
  io.inFlightCommands := commandProcessor.io.inFlight
  io.duplicateCommandId := commandProcessor.io.duplicateCommandId
  copyEngine.io.descriptor <> io.copyDescriptor
  io.copyCompletion <> copyEngine.io.completion
  io.copyEngineBusy := copyEngine.io.busy
  fillEngine.io.descriptor <> io.fillDescriptor
  io.fillCompletion <> fillEngine.io.completion
  io.fillEngineBusy := fillEngine.io.busy
  stridedCopyEngine.io.descriptor <> io.stridedCopyDescriptor
  io.stridedCopyCompletion <> stridedCopyEngine.io.completion
  io.stridedCopyEngineBusy := stridedCopyEngine.io.busy

  private val l2RequestArbiter = Module(new RRArbiter(
    new ComputeMemoryRequest(config, 64, totalSystemTransactions), 4))
  l2RequestArbiter.io.in(0).valid := memory.io.memoryRequest.valid
  l2RequestArbiter.io.in(0).bits := memory.io.memoryRequest.bits
  memory.io.memoryRequest.ready := l2RequestArbiter.io.in(0).ready
  l2RequestArbiter.io.in(1).valid := copyEngine.io.memoryRequest.valid
  l2RequestArbiter.io.in(1).bits := copyEngine.io.memoryRequest.bits
  l2RequestArbiter.io.in(1).bits.transactionId :=
    copyBase.U(systemTransactionWidth.W) +
    copyEngine.io.memoryRequest.bits.transactionId
  copyEngine.io.memoryRequest.ready := l2RequestArbiter.io.in(1).ready
  l2RequestArbiter.io.in(2).valid := fillEngine.io.memoryRequest.valid
  l2RequestArbiter.io.in(2).bits := fillEngine.io.memoryRequest.bits
  l2RequestArbiter.io.in(2).bits.transactionId :=
    fillBase.U(systemTransactionWidth.W) +
    fillEngine.io.memoryRequest.bits.transactionId
  fillEngine.io.memoryRequest.ready := l2RequestArbiter.io.in(2).ready
  l2RequestArbiter.io.in(3).valid := stridedCopyEngine.io.memoryRequest.valid
  l2RequestArbiter.io.in(3).bits := stridedCopyEngine.io.memoryRequest.bits
  l2RequestArbiter.io.in(3).bits.transactionId :=
    stridedCopyBase.U(systemTransactionWidth.W) +
    stridedCopyEngine.io.memoryRequest.bits.transactionId
  stridedCopyEngine.io.memoryRequest.ready := l2RequestArbiter.io.in(3).ready
  l2.io.request <> l2RequestArbiter.io.out

  private val l2ResponseForCu =
    l2.io.response.bits.transactionId < totalTransactions.U
  private val l2ResponseForCopy =
    l2.io.response.bits.transactionId >= copyBase.U &&
      l2.io.response.bits.transactionId < fillBase.U
  private val l2ResponseForFill =
    l2.io.response.bits.transactionId >= fillBase.U &&
      l2.io.response.bits.transactionId < stridedCopyBase.U
  private val l2ResponseForStridedCopy =
    l2.io.response.bits.transactionId >= stridedCopyBase.U &&
      l2.io.response.bits.transactionId < totalSystemTransactions.U
  memory.io.memoryResponse.valid := l2.io.response.valid && l2ResponseForCu
  memory.io.memoryResponse.bits.readData := l2.io.response.bits.readData
  memory.io.memoryResponse.bits.fault := l2.io.response.bits.fault
  memory.io.memoryResponse.bits.transactionId :=
    l2.io.response.bits.transactionId
  copyEngine.io.memoryResponse.valid :=
    l2.io.response.valid && l2ResponseForCopy
  copyEngine.io.memoryResponse.bits.readData := l2.io.response.bits.readData
  copyEngine.io.memoryResponse.bits.fault := l2.io.response.bits.fault
  copyEngine.io.memoryResponse.bits.transactionId :=
    l2.io.response.bits.transactionId - copyBase.U
  fillEngine.io.memoryResponse.valid :=
    l2.io.response.valid && l2ResponseForFill
  fillEngine.io.memoryResponse.bits.readData := l2.io.response.bits.readData
  fillEngine.io.memoryResponse.bits.fault := l2.io.response.bits.fault
  fillEngine.io.memoryResponse.bits.transactionId :=
    l2.io.response.bits.transactionId - fillBase.U
  stridedCopyEngine.io.memoryResponse.valid :=
    l2.io.response.valid && l2ResponseForStridedCopy
  stridedCopyEngine.io.memoryResponse.bits.readData :=
    l2.io.response.bits.readData
  stridedCopyEngine.io.memoryResponse.bits.fault :=
    l2.io.response.bits.fault
  stridedCopyEngine.io.memoryResponse.bits.transactionId :=
    l2.io.response.bits.transactionId - stridedCopyBase.U
  l2.io.response.ready := Mux(l2ResponseForCu,
    memory.io.memoryResponse.ready, Mux(l2ResponseForCopy,
      copyEngine.io.memoryResponse.ready,
      Mux(l2ResponseForFill, fillEngine.io.memoryResponse.ready,
        l2ResponseForStridedCopy && stridedCopyEngine.io.memoryResponse.ready)))
  when(l2.io.response.valid) {
    assert(l2ResponseForCu || l2ResponseForCopy || l2ResponseForFill ||
      l2ResponseForStridedCopy,
      "L2 response must target a CU or DMA transaction")
  }
  io.memoryRequest <> l2.io.memoryRequest
  l2.io.memoryResponse <> io.memoryResponse

  for (cu <- 0 until numComputeUnits) {
    val unit = computeUnits(cu)
    unit.io.kernel <> dispatcher.io.cuLaunch(cu)
    dispatcher.io.cuCompletion(cu) <> unit.io.completion
    memory.io.cuRequest(cu) <> unit.io.memoryRequest
    unit.io.memoryResponse <> memory.io.cuResponse(cu)
    unit.io.l1Invalidate <> l2.io.invalidate(cu)
    l2.io.invalidateDone(cu) <> unit.io.l1InvalidateDone
    l2.io.atomicRequest(cu) <> unit.io.globalAtomicRequest
    unit.io.globalAtomicResponse <> l2.io.atomicResponse(cu)

    unit.io.invalidateInstructionCache := io.invalidateInstructionCache
    unit.io.instructionSatp := io.instructionSatp
    unit.io.instructionTlbFlush := io.instructionTlbFlush
    unit.io.vectorSatp := io.vectorSatp
    unit.io.vectorTlbFlush := io.vectorTlbFlush
    io.fpu(cu) <> unit.io.fpu
    io.vector(cu) <> unit.io.vector
    io.scalarMemory(cu) <> unit.io.memory
    io.unsupportedSystem(cu) <> unit.io.unsupportedSystem
    io.trap(cu) <> unit.io.trap
    unit.io.simtBranch <> io.simtBranch(cu)

    io.committedWriteback(cu) := unit.io.committedWriteback
    io.committedVectorWriteback(cu) := unit.io.committedVectorWriteback
    io.committedFpuWriteback(cu) := unit.io.committedFpuWriteback
    io.committedFpuFlags(cu) := unit.io.committedFpuFlags
    io.activeWarps(cu) := unit.io.active
    io.blockedWarps(cu) := unit.io.blocked
    io.barrierWaiting(cu) := unit.io.barrierWaiting
  }
}
