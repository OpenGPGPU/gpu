package gpu.system

import chisel3._
import chisel3.util._
import gpu.config.GpuConfig
import gpu.command.{GpuCommand, GpuCommandResult, GpuCommandRouter}
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

class GpuPerformanceCounters extends Bundle {
  val cycles = UInt(64.W)
  val activeCuCycles = UInt(64.W)
  val lowerReadRequests = UInt(64.W)
  val lowerWriteRequests = UInt(64.W)
  val dmaBytesCompleted = UInt(64.W)
  val l2 = new L2PerformanceCounters
}

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
  enableFpuBackend: Boolean = false,
  enableUnifiedCommands: Boolean = false
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
    val gpuCommand = Flipped(Decoupled(
      new GpuCommand(config, commandIdWidth)))
    val gpuCompletion = Decoupled(new GpuCommandResult(commandIdWidth))

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
    val unifiedCommandRouterBusy = Output(Bool())
    val duplicateUnifiedCommandId = Output(Bool())
    val clearPerformanceCounters = Input(Bool())
    val performance = Output(new GpuPerformanceCounters)
  })

  private val commandProcessor = Module(new GpuCommandProcessor(
    config, commandIdWidth, commandQueueDepth = config.commandQueueDepth,
    completionQueueDepth = config.completionQueueDepth))
  private val commandRouter = Module(new GpuCommandRouter(
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
    requestQueueDepth = config.l2RequestQueueDepth,
    useSramBlackBoxes = useBlackBoxes))
  l2.io.clearPerformanceCounters := io.clearPerformanceCounters
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

  dispatcher.io.launch <> commandProcessor.io.dispatch
  commandProcessor.io.dispatchCompletion <> dispatcher.io.completion
  io.busyComputeUnits := dispatcher.io.busy
  io.commandProcessorBusy := commandProcessor.io.busy
  io.queuedCommands := commandProcessor.io.queued
  io.inFlightCommands := commandProcessor.io.inFlight
  io.duplicateCommandId := commandProcessor.io.duplicateCommandId
  io.copyEngineBusy := copyEngine.io.busy
  io.fillEngineBusy := fillEngine.io.busy
  io.stridedCopyEngineBusy := stridedCopyEngine.io.busy
  io.unifiedCommandRouterBusy := commandRouter.io.busy
  io.duplicateUnifiedCommandId := commandRouter.io.duplicateCommandId
  private val cycleCount = RegInit(0.U(64.W))
  private val activeCuCycleCount = RegInit(0.U(64.W))
  private val lowerReadCount = RegInit(0.U(64.W))
  private val lowerWriteCount = RegInit(0.U(64.W))
  private val dmaByteCount = RegInit(0.U(64.W))
  when(io.clearPerformanceCounters) {
    cycleCount := 0.U
    activeCuCycleCount := 0.U
    lowerReadCount := 0.U
    lowerWriteCount := 0.U
    dmaByteCount := 0.U
  }.otherwise {
    cycleCount := cycleCount + 1.U
    activeCuCycleCount := activeCuCycleCount + PopCount(dispatcher.io.busy)
    when(io.memoryRequest.fire && io.memoryRequest.bits.isWrite) {
      lowerWriteCount := lowerWriteCount + 1.U
    }
    when(io.memoryRequest.fire && !io.memoryRequest.bits.isWrite) {
      lowerReadCount := lowerReadCount + 1.U
    }
    dmaByteCount := dmaByteCount +
      Mux(copyEngine.io.completion.fire,
        copyEngine.io.completion.bits.bytesCopied, 0.U) +
      Mux(fillEngine.io.completion.fire,
        fillEngine.io.completion.bits.bytesFilled, 0.U) +
      Mux(stridedCopyEngine.io.completion.fire,
        stridedCopyEngine.io.completion.bits.bytesCopied, 0.U)
  }
  io.performance.cycles := cycleCount
  io.performance.activeCuCycles := activeCuCycleCount
  io.performance.lowerReadRequests := lowerReadCount
  io.performance.lowerWriteRequests := lowerWriteCount
  io.performance.dmaBytesCompleted := dmaByteCount
  io.performance.l2 := l2.io.performance

  if (enableUnifiedCommands) {
    commandRouter.io.command <> io.gpuCommand
    io.gpuCompletion <> commandRouter.io.completion
    commandProcessor.io.command <> commandRouter.io.kernel
    commandRouter.io.kernelCompletion <> commandProcessor.io.completion
    copyEngine.io.descriptor <> commandRouter.io.copy
    commandRouter.io.copyCompletion <> copyEngine.io.completion
    fillEngine.io.descriptor <> commandRouter.io.fill
    commandRouter.io.fillCompletion <> fillEngine.io.completion
    stridedCopyEngine.io.descriptor <> commandRouter.io.stridedCopy
    commandRouter.io.stridedCopyCompletion <> stridedCopyEngine.io.completion

    io.command.ready := false.B
    io.commandCompletion.valid := false.B
    io.commandCompletion.bits := 0.U.asTypeOf(io.commandCompletion.bits)
    io.copyDescriptor.ready := false.B
    io.copyCompletion.valid := false.B
    io.copyCompletion.bits := 0.U.asTypeOf(io.copyCompletion.bits)
    io.fillDescriptor.ready := false.B
    io.fillCompletion.valid := false.B
    io.fillCompletion.bits := 0.U.asTypeOf(io.fillCompletion.bits)
    io.stridedCopyDescriptor.ready := false.B
    io.stridedCopyCompletion.valid := false.B
    io.stridedCopyCompletion.bits :=
      0.U.asTypeOf(io.stridedCopyCompletion.bits)
  } else {
    commandProcessor.io.command <> io.command
    io.commandCompletion <> commandProcessor.io.completion
    copyEngine.io.descriptor <> io.copyDescriptor
    io.copyCompletion <> copyEngine.io.completion
    fillEngine.io.descriptor <> io.fillDescriptor
    io.fillCompletion <> fillEngine.io.completion
    stridedCopyEngine.io.descriptor <> io.stridedCopyDescriptor
    io.stridedCopyCompletion <> stridedCopyEngine.io.completion

    io.gpuCommand.ready := false.B
    io.gpuCompletion.valid := false.B
    io.gpuCompletion.bits := 0.U.asTypeOf(io.gpuCompletion.bits)
    commandRouter.io.command.valid := false.B
    commandRouter.io.command.bits :=
      0.U.asTypeOf(commandRouter.io.command.bits)
    commandRouter.io.completion.ready := false.B
    commandRouter.io.kernel.ready := false.B
    commandRouter.io.copy.ready := false.B
    commandRouter.io.fill.ready := false.B
    commandRouter.io.stridedCopy.ready := false.B
    commandRouter.io.kernelCompletion.valid := false.B
    commandRouter.io.kernelCompletion.bits :=
      0.U.asTypeOf(commandRouter.io.kernelCompletion.bits)
    commandRouter.io.copyCompletion.valid := false.B
    commandRouter.io.copyCompletion.bits :=
      0.U.asTypeOf(commandRouter.io.copyCompletion.bits)
    commandRouter.io.fillCompletion.valid := false.B
    commandRouter.io.fillCompletion.bits :=
      0.U.asTypeOf(commandRouter.io.fillCompletion.bits)
    commandRouter.io.stridedCopyCompletion.valid := false.B
    commandRouter.io.stridedCopyCompletion.bits :=
      0.U.asTypeOf(commandRouter.io.stridedCopyCompletion.bits)
  }

  commandProcessor.io.dmaCompletion(DmaEventSource.copy.litValue.toInt).valid :=
    copyEngine.io.completion.fire
  commandProcessor.io.dmaCompletion(DmaEventSource.copy.litValue.toInt).bits.source :=
    DmaEventSource.copy
  commandProcessor.io.dmaCompletion(DmaEventSource.copy.litValue.toInt).bits.descriptorId :=
    copyEngine.io.completion.bits.descriptorId
  commandProcessor.io.dmaCompletion(DmaEventSource.copy.litValue.toInt).bits.success :=
    copyEngine.io.completion.bits.success
  commandProcessor.io.dmaCompletion(DmaEventSource.fill.litValue.toInt).valid :=
    fillEngine.io.completion.fire
  commandProcessor.io.dmaCompletion(DmaEventSource.fill.litValue.toInt).bits.source :=
    DmaEventSource.fill
  commandProcessor.io.dmaCompletion(DmaEventSource.fill.litValue.toInt).bits.descriptorId :=
    fillEngine.io.completion.bits.descriptorId
  commandProcessor.io.dmaCompletion(DmaEventSource.fill.litValue.toInt).bits.success :=
    fillEngine.io.completion.bits.success
  commandProcessor.io.dmaCompletion(
    DmaEventSource.stridedCopy.litValue.toInt).valid :=
    stridedCopyEngine.io.completion.fire
  commandProcessor.io.dmaCompletion(
    DmaEventSource.stridedCopy.litValue.toInt).bits.source :=
    DmaEventSource.stridedCopy
  commandProcessor.io.dmaCompletion(
    DmaEventSource.stridedCopy.litValue.toInt).bits.descriptorId :=
    stridedCopyEngine.io.completion.bits.descriptorId
  commandProcessor.io.dmaCompletion(
    DmaEventSource.stridedCopy.litValue.toInt).bits.success :=
    stridedCopyEngine.io.completion.bits.success

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
