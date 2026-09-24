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

  // Split the queue read from the deep descriptor/dependency decode: pop the
  // command into hold0, run the checks from hold0, and dispatch from hold1.
  // This keeps the dequeue-pointer cone off the multiply/DMA-lookup logic.
  // localSize product is itself split across two hold0 cycles (16x16 then
  // 32x16) so the integrated-top limiter through hold0Cmd_launch_localSize
  // cannot run both multiplies into the hold1 capture in one period.
  val hold0Cmd = Reg(new KernelCommand(config, commandIdWidth))
  val hold0Valid = RegInit(false.B)
  val h0 = hold0Cmd
  val h0LocalXY = Reg(UInt(32.W))
  val h0LocalItems = Reg(UInt(48.W))
  val h0MulReady = RegInit(false.B)
  val h0PcAligned = h0.launch.kernelPc(1, 0) === 0.U
  val h0KernargAligned = h0.launch.kernargAddress(1, 0) === 0.U
  val h0GridValid = h0.launch.gridSize.map(_.orR).reduce(_ && _)
  val h0LocalValid = h0.launch.localSize.map(_.orR).reduce(_ && _)
  val h0ResidentCapacity = (config.lanes * config.warps).U
  val h0LocalFits = h0LocalItems <= h0ResidentCapacity
  val h0DescriptorValid = h0PcAligned && h0KernargAligned && h0GridValid &&
    h0LocalValid && h0LocalFits
  val h0DependencySourceValid = h0.dmaSource < DmaEventSource.count.U
  val h0DependencySucceeded = h0DependencySourceValid &&
    dmaSucceeded(h0.dmaSource)(h0.dmaDescriptorId)
  val h0DependencyFailed = !h0DependencySourceValid ||
    dmaFailed(h0.dmaSource)(h0.dmaDescriptorId)
  val h0DependencyKnown = !h0.waitForDma || h0DependencySucceeded ||
    h0DependencyFailed
  val h0InvalidStatus = Mux(!h0PcAligned,
    KernelCommandStatus.invalidProgramCounter,
    Mux(!h0GridValid, KernelCommandStatus.invalidGrid,
      Mux(!h0LocalValid || !h0LocalFits, KernelCommandStatus.invalidLocalSize,
        KernelCommandStatus.misalignedKernarg)))

  val hold1Valid = RegInit(false.B)
  val hold1Failed = RegInit(false.B)
  val hold1DescriptorValid = RegInit(false.B)
  val hold1Id = Reg(UInt(commandIdWidth.W))
  val hold1Launch = Reg(new KernelLaunch(config))
  val hold1Status = Reg(UInt(KernelCommandStatus.width.W))

  io.dispatch.valid := hold1Valid && hold1DescriptorValid && !hold1Failed
  io.dispatch.bits.commandId := hold1Id
  io.dispatch.bits.launch := hold1Launch

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

  completionEvents.io.in(1).valid := hold1Valid &&
    (!hold1DescriptorValid || hold1Failed)
  completionEvents.io.in(1).bits.commandId := hold1Id
  completionEvents.io.in(1).bits.success := false.B
  completionEvents.io.in(1).bits.status := Mux(hold1Failed,
    KernelCommandStatus.dmaDependencyFailed, hold1Status)

  val dispatchFire = io.dispatch.fire
  val errorFire = completionEvents.io.in(1).fire
  val consumeFire = dispatchFire || errorFire
  val shiftFire = hold0Valid && h0MulReady && h0DependencyKnown &&
    (!hold1Valid || consumeFire)
  commands.io.deq.ready := !hold0Valid || shiftFire
  hold0Valid := (hold0Valid && !shiftFire) || commands.io.deq.fire
  when(commands.io.deq.fire) {
    hold0Cmd := commands.io.deq.bits
    h0LocalXY := commands.io.deq.bits.launch.localSize(0) *
      commands.io.deq.bits.launch.localSize(1)
    h0MulReady := false.B
  }.elsewhen(hold0Valid && !h0MulReady) {
    h0LocalItems := h0LocalXY * h0.launch.localSize(2)
    h0MulReady := true.B
  }
  when(shiftFire) {
    hold1Valid := true.B
    hold1Failed := h0DependencyFailed
    hold1DescriptorValid := h0DescriptorValid
    hold1Id := h0.commandId
    hold1Launch := h0.launch
    hold1Status := h0InvalidStatus
  }.elsewhen(consumeFire) {
    hold1Valid := false.B
  }
  completions.io.enq <> completionEvents.io.out
  io.completion <> completions.io.deq

  val inFlight = RegInit(0.U(log2Ceil(idCount + 1).W))
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

  val dependencyConsumed = shiftFire && h0.waitForDma
  for (source <- 0 until DmaEventSource.count) {
    val event = io.dmaCompletion(source)
    val sourceMatches = h0.dmaSource === source.U
    val clearMask = Mux(dependencyConsumed && sourceMatches,
      UIntToOH(h0.dmaDescriptorId, idCount), 0.U)
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
