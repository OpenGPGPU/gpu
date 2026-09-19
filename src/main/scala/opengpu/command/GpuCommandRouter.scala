package opengpu.command

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.dispatch.{DmaEventSource, KernelCommand, KernelCommandResult, KernelLaunch}
import opengpu.dma._

object GpuCommandOpcode {
  val width = 3
  val kernel = 0.U(width.W)
  val copy = 1.U(width.W)
  val fill = 2.U(width.W)
  val stridedCopy = 3.U(width.W)
  val resolve = 4.U(width.W)
  val invalidate = 5.U(width.W)
  val render = 6.U(width.W)
}

/** One software queue format. Unused payload fields are ignored by opcode. */
class GpuCommand(config: GpuConfig, val commandIdWidth: Int) extends Bundle {
  val commandId = UInt(commandIdWidth.W)
  val opcode = UInt(GpuCommandOpcode.width.W)
  val launch = new KernelLaunch(config)
  val waitForDma = Bool()
  val dmaSource = UInt(DmaEventSource.width.W)
  val dmaDescriptorId = UInt(commandIdWidth.W)
  val sourceAddress = UInt(config.xLen.W)
  val destinationAddress = UInt(config.xLen.W)
  val bytes = UInt(32.W)
  val pattern = UInt(32.W)
  val widthBytes = UInt(32.W)
  val height = UInt(32.W)
  val sourceStride = UInt(32.W)
  val destinationStride = UInt(32.W)
  /** MSAA resolve sample mode (0 = 1x, 1 = 2x, 2 = 4x). */
  val sampleMode = UInt(2.W)
  val waitForEvent = Bool()
  val waitEventId = UInt(commandIdWidth.W)
  val waitEventGeneration = UInt(8.W)
  val signalEvent = Bool()
  val signalEventId = UInt(commandIdWidth.W)
  val signalEventGeneration = UInt(8.W)
}

object GpuCommandResultStatus {
  val width = 4
  val eventDependencyFailed = 14.U(width.W)
  val invalidOpcode = 15.U(width.W)
}

class GpuCommandResult(val commandIdWidth: Int) extends Bundle {
  val commandId = UInt(commandIdWidth.W)
  val opcode = UInt(GpuCommandOpcode.width.W)
  val status = UInt(GpuCommandResultStatus.width.W)
  val success = Bool()
  val bytesProcessed = UInt(64.W)
}

/** Routes one ordered software command queue onto independent engines.
  * Execution may complete out of order; command IDs remain reserved until the
  * corresponding unified completion is consumed by software.
  */
class GpuCommandRouter(
  config: GpuConfig = GpuConfig(),
  commandIdWidth: Int = 8,
  commandQueueDepth: Int = 8,
  completionQueueDepth: Int = 8
) extends Module {
  require(commandIdWidth > 0 && commandIdWidth <= 12)
  require(commandQueueDepth > 0 && completionQueueDepth > 0)
  val idCount = 1 << commandIdWidth

  val io = IO(new Bundle {
    val command = Flipped(Decoupled(new GpuCommand(config, commandIdWidth)))
    val completion = Decoupled(new GpuCommandResult(commandIdWidth))
    val kernel = Decoupled(new KernelCommand(config, commandIdWidth))
    val copy = Decoupled(new CopyDescriptor(config, commandIdWidth))
    val fill = Decoupled(new FillDescriptor(config, commandIdWidth))
    val stridedCopy = Decoupled(
      new StridedCopyDescriptor(config, commandIdWidth))
    val resolve = Decoupled(
      new ResolveDescriptor(config, commandIdWidth))
    val invalidate = Decoupled(
      new InvalidateDescriptor(config, commandIdWidth))
    val kernelCompletion = Flipped(Decoupled(
      new KernelCommandResult(commandIdWidth)))
    val copyCompletion = Flipped(Decoupled(new CopyCompletion(commandIdWidth)))
    val fillCompletion = Flipped(Decoupled(new FillCompletion(commandIdWidth)))
    val stridedCopyCompletion = Flipped(Decoupled(
      new StridedCopyCompletion(commandIdWidth)))
    val resolveCompletion = Flipped(Decoupled(
      new ResolveCompletion(commandIdWidth)))
    val invalidateCompletion = Flipped(Decoupled(
      new InvalidateCompletion(commandIdWidth)))
    val duplicateCommandId = Output(Bool())
    val busy = Output(Bool())
    /** While high the router stops dispatching queued commands to the
      * engines; completion drain continues so in-flight work can retire. */
    val blockDispatch = Input(Bool())
    /** Synchronous command-path reset pulse: flushes both queues and clears
      * every reserved ID and event record.  Assert only once in-flight work
      * has drained; executing engines are not affected. */
    val pathReset = Input(Bool())
  })

  withReset(reset.asBool || io.pathReset) {

  val commands = Module(new Queue(
    new GpuCommand(config, commandIdWidth), commandQueueDepth))
  val completions = Module(new Queue(
    new GpuCommandResult(commandIdWidth), completionQueueDepth))
  val reservedIds = RegInit(0.U(idCount.W))
  val commandOpcode = Reg(Vec(idCount,
    UInt(GpuCommandOpcode.width.W)))
  val commandSignalsEvent = Reg(Vec(idCount, Bool()))
  val commandSignalId = Reg(Vec(idCount, UInt(commandIdWidth.W)))
  val commandSignalGeneration = Reg(Vec(idCount, UInt(8.W)))
  val eventValid = RegInit(0.U(idCount.W))
  val eventSuccess = RegInit(0.U(idCount.W))
  val eventGeneration = Reg(Vec(idCount, UInt(8.W)))
  val duplicate = reservedIds(io.command.bits.commandId)
  commands.io.enq.valid := io.command.valid && !duplicate
  commands.io.enq.bits := io.command.bits
  io.command.ready := commands.io.enq.ready && !duplicate
  io.duplicateCommandId := io.command.valid && duplicate

  val head = commands.io.deq.bits
  val isKernel = head.opcode === GpuCommandOpcode.kernel
  val isCopy = head.opcode === GpuCommandOpcode.copy
  val isFill = head.opcode === GpuCommandOpcode.fill
  val isStrided = head.opcode === GpuCommandOpcode.stridedCopy
  val isResolve = head.opcode === GpuCommandOpcode.resolve
  val isInvalidate = head.opcode === GpuCommandOpcode.invalidate
  val opcodeValid = isKernel || isCopy || isFill || isStrided || isResolve ||
    isInvalidate
  val waitedEventMatches = eventValid(head.waitEventId) &&
    eventGeneration(head.waitEventId) === head.waitEventGeneration
  val dependencyKnown = !head.waitForEvent || waitedEventMatches
  val dependencyFailed = head.waitForEvent && waitedEventMatches &&
    !eventSuccess(head.waitEventId)

  io.kernel.valid := commands.io.deq.valid && dependencyKnown &&
    !dependencyFailed && isKernel && !io.blockDispatch
  io.kernel.bits.commandId := head.commandId
  io.kernel.bits.launch := head.launch
  io.kernel.bits.waitForDma := head.waitForDma
  io.kernel.bits.dmaSource := head.dmaSource
  io.kernel.bits.dmaDescriptorId := head.dmaDescriptorId
  io.copy.valid := commands.io.deq.valid && dependencyKnown &&
    !dependencyFailed && isCopy && !io.blockDispatch
  io.copy.bits.descriptorId := head.commandId
  io.copy.bits.sourceAddress := head.sourceAddress
  io.copy.bits.destinationAddress := head.destinationAddress
  io.copy.bits.bytes := head.bytes
  io.fill.valid := commands.io.deq.valid && dependencyKnown &&
    !dependencyFailed && isFill && !io.blockDispatch
  io.fill.bits.descriptorId := head.commandId
  io.fill.bits.destinationAddress := head.destinationAddress
  io.fill.bits.bytes := head.bytes
  io.fill.bits.pattern := head.pattern
  io.stridedCopy.valid := commands.io.deq.valid && dependencyKnown &&
    !dependencyFailed && isStrided && !io.blockDispatch
  io.stridedCopy.bits.descriptorId := head.commandId
  io.stridedCopy.bits.sourceAddress := head.sourceAddress
  io.stridedCopy.bits.destinationAddress := head.destinationAddress
  io.stridedCopy.bits.widthBytes := head.widthBytes
  io.stridedCopy.bits.height := head.height
  io.stridedCopy.bits.sourceStride := head.sourceStride
  io.stridedCopy.bits.destinationStride := head.destinationStride
  io.resolve.valid := commands.io.deq.valid && dependencyKnown &&
    !dependencyFailed && isResolve && !io.blockDispatch
  io.resolve.bits.descriptorId := head.commandId
  io.resolve.bits.sourceAddress := head.sourceAddress
  io.resolve.bits.destinationAddress := head.destinationAddress
  io.resolve.bits.imgWidth := head.widthBytes(15, 0)
  io.resolve.bits.imgHeight := head.height(15, 0)
  io.resolve.bits.sourceStride := head.sourceStride
  io.resolve.bits.destinationStride := head.destinationStride
  io.resolve.bits.sampleMode := head.sampleMode
  io.invalidate.valid := commands.io.deq.valid && dependencyKnown &&
    !dependencyFailed && isInvalidate && !io.blockDispatch
  io.invalidate.bits.descriptorId := head.commandId
  io.invalidate.bits.address := head.sourceAddress
  io.invalidate.bits.bytes := head.bytes

  val completionEvents = Module(new RRArbiter(
    new GpuCommandResult(commandIdWidth), 7))
  def mapCompletion(index: Int, valid: Bool, commandId: UInt,
                            status: UInt, success: Bool,
                            bytes: UInt): Unit = {
    completionEvents.io.in(index).valid := valid
    completionEvents.io.in(index).bits.commandId := commandId
    completionEvents.io.in(index).bits.opcode := commandOpcode(commandId)
    completionEvents.io.in(index).bits.status := status
    completionEvents.io.in(index).bits.success := success
    completionEvents.io.in(index).bits.bytesProcessed := bytes
  }
  mapCompletion(0, io.kernelCompletion.valid,
    io.kernelCompletion.bits.commandId, io.kernelCompletion.bits.status,
    io.kernelCompletion.bits.success, 0.U)
  io.kernelCompletion.ready := completionEvents.io.in(0).ready
  mapCompletion(1, io.copyCompletion.valid,
    io.copyCompletion.bits.descriptorId, io.copyCompletion.bits.status,
    io.copyCompletion.bits.success, io.copyCompletion.bits.bytesCopied)
  io.copyCompletion.ready := completionEvents.io.in(1).ready
  mapCompletion(2, io.fillCompletion.valid,
    io.fillCompletion.bits.descriptorId, io.fillCompletion.bits.status,
    io.fillCompletion.bits.success, io.fillCompletion.bits.bytesFilled)
  io.fillCompletion.ready := completionEvents.io.in(2).ready
  mapCompletion(3, io.stridedCopyCompletion.valid,
    io.stridedCopyCompletion.bits.descriptorId,
    io.stridedCopyCompletion.bits.status,
    io.stridedCopyCompletion.bits.success,
    io.stridedCopyCompletion.bits.bytesCopied)
  io.stridedCopyCompletion.ready := completionEvents.io.in(3).ready
  mapCompletion(4, io.resolveCompletion.valid,
    io.resolveCompletion.bits.descriptorId,
    io.resolveCompletion.bits.status,
    io.resolveCompletion.bits.success,
    io.resolveCompletion.bits.bytesResolved)
  io.resolveCompletion.ready := completionEvents.io.in(4).ready
  mapCompletion(5, io.invalidateCompletion.valid,
    io.invalidateCompletion.bits.descriptorId,
    io.invalidateCompletion.bits.status,
    io.invalidateCompletion.bits.success,
    io.invalidateCompletion.bits.bytesInvalidated)
  io.invalidateCompletion.ready := completionEvents.io.in(5).ready
  mapCompletion(6, commands.io.deq.valid && dependencyKnown &&
    (!opcodeValid || dependencyFailed) && !io.blockDispatch, head.commandId,
    Mux(dependencyFailed, GpuCommandResultStatus.eventDependencyFailed,
      GpuCommandResultStatus.invalidOpcode), false.B, 0.U)

  commands.io.deq.ready := !io.blockDispatch && dependencyKnown && MuxCase(
    completionEvents.io.in(6).ready, Seq(
    isKernel -> io.kernel.ready,
    isCopy -> io.copy.ready,
    isFill -> io.fill.ready,
    isStrided -> io.stridedCopy.ready,
    isResolve -> io.resolve.ready,
    isInvalidate -> io.invalidate.ready))
  completions.io.enq <> completionEvents.io.out
  io.completion <> completions.io.deq

  when(io.command.fire) {
    reservedIds := reservedIds |
      UIntToOH(io.command.bits.commandId, idCount)
    commandOpcode(io.command.bits.commandId) := io.command.bits.opcode
    commandSignalsEvent(io.command.bits.commandId) :=
      io.command.bits.signalEvent
    commandSignalId(io.command.bits.commandId) :=
      io.command.bits.signalEventId
    commandSignalGeneration(io.command.bits.commandId) :=
      io.command.bits.signalEventGeneration
  }
  when(io.completion.fire) {
    assert(reservedIds(io.completion.bits.commandId),
      "unified completion must reference a reserved command ID")
    reservedIds := reservedIds &
      ~UIntToOH(io.completion.bits.commandId, idCount)
  }
  // Preserve a simultaneous new reservation when an old completion is read.
  when(io.command.fire && io.completion.fire) {
    reservedIds := (reservedIds |
      UIntToOH(io.command.bits.commandId, idCount)) &
      ~UIntToOH(io.completion.bits.commandId, idCount)
  }
  val completedId = completionEvents.io.out.bits.commandId
  when(completionEvents.io.out.fire && commandSignalsEvent(completedId)) {
    val eventId = commandSignalId(completedId)
    eventValid := eventValid | UIntToOH(eventId, idCount)
    eventSuccess := Mux(completionEvents.io.out.bits.success,
      eventSuccess | UIntToOH(eventId, idCount),
      eventSuccess & ~UIntToOH(eventId, idCount))
    eventGeneration(eventId) := commandSignalGeneration(completedId)
  }
  io.busy := reservedIds.orR
  }
}
