package opengpu.dispatch

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig

/** Software-facing kernel command and completion queues.
  *
  * Command IDs remain reserved until software consumes the corresponding
  * completion, preventing ambiguous reuse across queued and executing work.
  * Invalid descriptors complete locally and never reach a compute unit.
  */
class GpuCommandProcessor(
  config: GpuConfig = GpuConfig(),
  commandIdWidth: Int = 8,
  commandQueueDepth: Int = 8,
  completionQueueDepth: Int = 8
) extends Module {
  require(commandIdWidth > 0 && commandIdWidth <= 12)
  require(commandQueueDepth > 0)
  require(completionQueueDepth > 0)
  val idCount = 1 << commandIdWidth
  val countWidth = math.max(1, log2Ceil(commandQueueDepth + 1))

  val io = IO(new Bundle {
    val command = Flipped(Decoupled(new KernelCommand(config, commandIdWidth)))
    val completion = Decoupled(new KernelCommandResult(commandIdWidth))
    val dispatch = Decoupled(new TaggedKernelLaunch(config, commandIdWidth))
    val dispatchCompletion = Flipped(Decoupled(
      new TaggedKernelCompletion(commandIdWidth)))
    val dmaCompletion = Input(Vec(DmaEventSource.count,
      Valid(new DmaCompletionEvent(commandIdWidth))))
    val queued = Output(UInt(countWidth.W))
    val inFlight = Output(UInt(log2Ceil(idCount + 1).W))
    val busy = Output(Bool())
    val duplicateCommandId = Output(Bool())
    /** Synchronous command-path reset pulse: flushes both queues and clears
      * every reserved ID and DMA-dependency record.  Assert only once
      * in-flight kernels have retired. */
    val pathReset = Input(Bool())
  })

  withReset(reset.asBool || io.pathReset) {

  val commands = Module(new Queue(
    new KernelCommand(config, commandIdWidth), commandQueueDepth))
  val completions = Module(new Queue(
    new KernelCommandResult(commandIdWidth), completionQueueDepth))
  val reservedIds = RegInit(0.U(idCount.W))
  val dmaSucceeded = RegInit(VecInit(
    Seq.fill(DmaEventSource.count)(0.U(idCount.W))))
  val dmaFailed = RegInit(VecInit(
    Seq.fill(DmaEventSource.count)(0.U(idCount.W))))
  val incomingDuplicate = reservedIds(io.command.bits.commandId)

  commands.io.enq.valid := io.command.valid && !incomingDuplicate
  commands.io.enq.bits := io.command.bits
  io.command.ready := commands.io.enq.ready && !incomingDuplicate
  io.duplicateCommandId := io.command.valid && incomingDuplicate

  val head = commands.io.deq.bits
  val pcAligned = head.launch.kernelPc(1, 0) === 0.U
  val kernargAligned = head.launch.kernargAddress(1, 0) === 0.U
  val gridValid = head.launch.gridSize.map(_.orR).reduce(_ && _)
  val localValid = head.launch.localSize.map(_.orR).reduce(_ && _)
  val localItems = head.launch.localSize(0) *
    head.launch.localSize(1) * head.launch.localSize(2)
  val residentCapacity = (config.lanes * config.warps).U
  val localFits = localItems <= residentCapacity
  val descriptorValid = pcAligned && kernargAligned &&
    gridValid && localValid && localFits
  val dependencySourceValid = head.dmaSource < DmaEventSource.count.U
  val dependencySucceeded = dependencySourceValid &&
    dmaSucceeded(head.dmaSource)(head.dmaDescriptorId)
  val dependencyFailed = !dependencySourceValid ||
    dmaFailed(head.dmaSource)(head.dmaDescriptorId)
  val dependencyKnown = !head.waitForDma ||
    dependencySucceeded || dependencyFailed
  val invalidStatus = Mux(!pcAligned,
    KernelCommandStatus.invalidProgramCounter,
    Mux(!gridValid, KernelCommandStatus.invalidGrid,
      Mux(!localValid || !localFits, KernelCommandStatus.invalidLocalSize,
        KernelCommandStatus.misalignedKernarg)))

  io.dispatch.valid := commands.io.deq.valid && descriptorValid &&
    dependencyKnown && !dependencyFailed
  io.dispatch.bits.commandId := head.commandId
  io.dispatch.bits.launch := head.launch

  val completionEvents = Module(new RRArbiter(
    new KernelCommandResult(commandIdWidth), 2))
  completionEvents.io.in(0).valid := io.dispatchCompletion.valid
  completionEvents.io.in(0).bits.commandId :=
    io.dispatchCompletion.bits.commandId
  completionEvents.io.in(0).bits.success :=
    io.dispatchCompletion.bits.success
  completionEvents.io.in(0).bits.status := Mux(
    io.dispatchCompletion.bits.success, KernelCommandStatus.success,
    KernelCommandStatus.executionFailed)
  io.dispatchCompletion.ready := completionEvents.io.in(0).ready

  completionEvents.io.in(1).valid := commands.io.deq.valid &&
    dependencyKnown && (!descriptorValid || dependencyFailed)
  completionEvents.io.in(1).bits.commandId := head.commandId
  completionEvents.io.in(1).bits.success := false.B
  completionEvents.io.in(1).bits.status := Mux(dependencyFailed,
    KernelCommandStatus.dmaDependencyFailed, invalidStatus)

  commands.io.deq.ready := dependencyKnown && Mux(
    descriptorValid && !dependencyFailed,
    io.dispatch.ready, completionEvents.io.in(1).ready)
  completions.io.enq <> completionEvents.io.out
  io.completion <> completions.io.deq

  val inFlight = RegInit(0.U(log2Ceil(idCount + 1).W))
  val dispatchFire = io.dispatch.fire
  val dispatchCompletionFire = io.dispatchCompletion.fire
  when(dispatchFire =/= dispatchCompletionFire) {
    inFlight := Mux(dispatchFire, inFlight + 1.U, inFlight - 1.U)
  }
  when(dispatchCompletionFire) {
    assert(inFlight.orR || dispatchFire,
      "kernel completion cannot retire without in-flight work")
  }
  when(io.completion.fire) {
    assert(reservedIds(io.completion.bits.commandId),
      "completion must reference a reserved command ID")
  }
  val reserveMask = Mux(io.command.fire,
    UIntToOH(io.command.bits.commandId, idCount), 0.U)
  val releaseMask = Mux(io.completion.fire,
    UIntToOH(io.completion.bits.commandId, idCount), 0.U)
  when(io.command.fire || io.completion.fire) {
    reservedIds := (reservedIds | reserveMask) & ~releaseMask
  }

  val dependencyConsumed = commands.io.deq.fire && head.waitForDma
  for (source <- 0 until DmaEventSource.count) {
    val event = io.dmaCompletion(source)
    val sourceMatches = head.dmaSource === source.U
    val clearMask = Mux(dependencyConsumed && sourceMatches,
      UIntToOH(head.dmaDescriptorId, idCount), 0.U)
    val setSuccess = Mux(event.valid && event.bits.success,
      UIntToOH(event.bits.descriptorId, idCount), 0.U)
    val setFailure = Mux(event.valid && !event.bits.success,
      UIntToOH(event.bits.descriptorId, idCount), 0.U)
    when(clearMask.orR || event.valid) {
      dmaSucceeded(source) := (dmaSucceeded(source) & ~clearMask) | setSuccess
      dmaFailed(source) := (dmaFailed(source) & ~clearMask) | setFailure
    }
    when(event.valid) {
      assert(event.bits.source === source.U,
        "DMA event vector index must match its source field")
    }
  }

  io.queued := commands.io.count
  io.inFlight := inFlight
  io.busy := reservedIds.orR
  }
}
