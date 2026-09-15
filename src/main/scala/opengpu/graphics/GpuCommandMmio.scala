package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.command.{GpuCommand, GpuCommandResult}
import opengpu.config.GpuConfig

object GpuCommandMmioRegs {
  val COMMAND_ID = 0x0c4
  val OPCODE = 0x0c8
  val KERNEL_PC = 0x0cc
  val KERNARG = 0x0d0
  val GRID_X = 0x0d4
  val GRID_Y = 0x0d8
  val GRID_Z = 0x0dc
  val LOCAL_X = 0x0e0
  val LOCAL_Y = 0x0e4
  val LOCAL_Z = 0x0e8
  val FLAGS = 0x0ec
  val DMA_DEPENDENCY = 0x0f0
  val SOURCE = 0x0f4
  val DESTINATION = 0x0f8
  val BYTES = 0x0fc
  val PATTERN = 0x100
  val WIDTH = 0x104
  val HEIGHT = 0x108
  val SOURCE_STRIDE = 0x10c
  val DESTINATION_STRIDE = 0x110
  val WAIT_EVENT = 0x114
  val SIGNAL_EVENT = 0x118
  val SUBMIT = 0x11c
  val STATUS = 0x120
  val COMPLETION = 0x124
  val COMPLETION_BYTES_LO = 0x128
  val COMPLETION_BYTES_HI = 0x12c
  val COMPLETION_POP = 0x130
  /** W1P bit0: request a safe unified-command reset.  The hardware stops
    * accepting and dispatching new commands, drains in-flight work and memory
    * transactions, resets the command-path state, then raises the completion
    * event.  Lives outside the contiguous staging bank (MSAA_CONFIG owns
    * 0x134); the AXI top routes this one address here explicitly. */
  val RESET = 0x138
  val END = 0x13c
}

/** Register-programmed bridge to the ordered unified GPU command stream. */
class GpuCommandMmio(
  config: GpuConfig = GpuConfig(),
  commandIdWidth: Int = 8,
  queueDepth: Int = 4
) extends Module {
  require(commandIdWidth > 0 && commandIdWidth <= 8)
  require(queueDepth > 0)

  val io = IO(new Bundle {
    val reg = new Bundle {
      val req = Flipped(Decoupled(new RenderHostRegRequest))
      val resp = Decoupled(new RenderHostRegResponse)
    }
    val command = Decoupled(new GpuCommand(config, commandIdWidth))
    val completion = Flipped(Decoupled(
      new GpuCommandResult(commandIdWidth)))
    val completionEvent = Output(Bool())
    /** Held from a RESET request until the system's command path reports a
      * completed drain and state reset via `resetDone`.  While active this
      * bridge drops staged commands, rejects submissions and discards late
      * completions. */
    val resetActive = Output(Bool())
    /** Single-cycle pulse from the system: every in-flight command and memory
      * transaction has drained and the command-path state has been reset. */
    val resetDone = Input(Bool())
  })

  private def merge(old: UInt, data: UInt, strb: UInt): UInt =
    Cat((0 until 4).reverse.map { byte =>
      Mux(strb(byte), data(8 * byte + 7, 8 * byte),
        old(8 * byte + 7, 8 * byte))
    })

  private val commandId = RegInit(0.U(32.W))
  private val opcode = RegInit(0.U(32.W))
  private val kernelPc = RegInit(0.U(32.W))
  private val kernarg = RegInit(0.U(32.W))
  private val grid = RegInit(VecInit(Seq.fill(3)(1.U(32.W))))
  private val local = RegInit(VecInit(Seq.fill(3)(1.U(32.W))))
  private val flags = RegInit(0.U(32.W))
  private val dmaDependency = RegInit(0.U(32.W))
  private val source = RegInit(0.U(32.W))
  private val destination = RegInit(0.U(32.W))
  private val bytes = RegInit(0.U(32.W))
  private val pattern = RegInit(0.U(32.W))
  private val width = RegInit(0.U(32.W))
  private val height = RegInit(0.U(32.W))
  private val sourceStride = RegInit(0.U(32.W))
  private val destinationStride = RegInit(0.U(32.W))
  private val waitEvent = RegInit(0.U(32.W))
  private val signalEvent = RegInit(0.U(32.W))
  private val submitOverflow = RegInit(false.B)
  private val resetActive = RegInit(false.B)
  private val resetRejected = RegInit(false.B)

  private val queue = Module(new Queue(
    new GpuCommand(config, commandIdWidth), queueDepth))

  private val wFire = io.reg.req.fire && io.reg.req.bits.isWrite
  private val resetRequest = wFire &&
    io.reg.req.bits.addr === GpuCommandMmioRegs.RESET.U &&
    io.reg.req.bits.data(0)
  when(resetRequest) { resetActive := true.B }
  when(io.resetDone) { resetActive := false.B }
  io.resetActive := resetActive

  // Staged commands belong to the pre-reset command stream: while a reset
  // drains, pop them into the bit bucket instead of dispatching them.
  io.command.valid := queue.io.deq.valid && !resetActive
  io.command.bits := queue.io.deq.bits
  queue.io.deq.ready := resetActive || io.command.ready

  private val submit = wFire &&
    io.reg.req.bits.addr === GpuCommandMmioRegs.SUBMIT.U &&
    io.reg.req.bits.data(0)
  queue.io.enq.valid := submit && !submitOverflow && !resetActive
  when(submit && resetActive) { resetRejected := true.B }
  queue.io.enq.bits.commandId := commandId(commandIdWidth - 1, 0)
  queue.io.enq.bits.opcode := opcode
  queue.io.enq.bits.launch.kernelPc := kernelPc
  queue.io.enq.bits.launch.kernargAddress := kernarg
  for (axis <- 0 until 3) {
    queue.io.enq.bits.launch.gridSize(axis) := grid(axis)
    queue.io.enq.bits.launch.localSize(axis) := local(axis)(15, 0)
  }
  queue.io.enq.bits.waitForDma := flags(0)
  queue.io.enq.bits.dmaSource := dmaDependency(1, 0)
  queue.io.enq.bits.dmaDescriptorId :=
    dmaDependency(8 + commandIdWidth - 1, 8)
  queue.io.enq.bits.sourceAddress := source
  queue.io.enq.bits.destinationAddress := destination
  queue.io.enq.bits.bytes := bytes
  queue.io.enq.bits.pattern := pattern
  queue.io.enq.bits.widthBytes := width
  queue.io.enq.bits.height := height
  queue.io.enq.bits.sourceStride := sourceStride
  queue.io.enq.bits.destinationStride := destinationStride
  // Resolve sample mode has no staged register yet; typed resolve submission is
  // wired through the command router before this MMIO bridge exposes it.
  queue.io.enq.bits.sampleMode := 0.U
  queue.io.enq.bits.waitForEvent := flags(1)
  queue.io.enq.bits.waitEventId := waitEvent(commandIdWidth - 1, 0)
  queue.io.enq.bits.waitEventGeneration := waitEvent(15, 8)
  queue.io.enq.bits.signalEvent := flags(2)
  queue.io.enq.bits.signalEventId := signalEvent(commandIdWidth - 1, 0)
  queue.io.enq.bits.signalEventGeneration := signalEvent(15, 8)
  when(submit && !queue.io.enq.ready) { submitOverflow := true.B }

  private val completionValid = RegInit(false.B)
  private val completion = Reg(new GpuCommandResult(commandIdWidth))
  private val popCompletion = wFire &&
    io.reg.req.bits.addr === GpuCommandMmioRegs.COMPLETION_POP.U &&
    io.reg.req.bits.data(0)
  // Completions arriving mid-reset belong to the aborted stream; consume and
  // discard them so the post-reset slot starts empty.
  io.completion.ready := resetActive || !completionValid || popCompletion
  io.completionEvent := (io.completion.fire && !resetActive) || io.resetDone
  when(io.completion.fire && !resetActive) {
    completion := io.completion.bits
    completionValid := true.B
  }.elsewhen(popCompletion || resetRequest ||
    (io.completion.fire && resetActive)) {
    completionValid := false.B
  }

  when(wFire) {
    switch(io.reg.req.bits.addr) {
      is(GpuCommandMmioRegs.COMMAND_ID.U) {
        commandId := merge(commandId, io.reg.req.bits.data, io.reg.req.bits.strb)
      }
      is(GpuCommandMmioRegs.OPCODE.U) {
        opcode := merge(opcode, io.reg.req.bits.data, io.reg.req.bits.strb)
      }
      is(GpuCommandMmioRegs.KERNEL_PC.U) {
        kernelPc := merge(kernelPc, io.reg.req.bits.data, io.reg.req.bits.strb)
      }
      is(GpuCommandMmioRegs.KERNARG.U) {
        kernarg := merge(kernarg, io.reg.req.bits.data, io.reg.req.bits.strb)
      }
      is(GpuCommandMmioRegs.GRID_X.U) { grid(0) := io.reg.req.bits.data }
      is(GpuCommandMmioRegs.GRID_Y.U) { grid(1) := io.reg.req.bits.data }
      is(GpuCommandMmioRegs.GRID_Z.U) { grid(2) := io.reg.req.bits.data }
      is(GpuCommandMmioRegs.LOCAL_X.U) { local(0) := io.reg.req.bits.data }
      is(GpuCommandMmioRegs.LOCAL_Y.U) { local(1) := io.reg.req.bits.data }
      is(GpuCommandMmioRegs.LOCAL_Z.U) { local(2) := io.reg.req.bits.data }
      is(GpuCommandMmioRegs.FLAGS.U) { flags := io.reg.req.bits.data }
      is(GpuCommandMmioRegs.DMA_DEPENDENCY.U) {
        dmaDependency := io.reg.req.bits.data
      }
      is(GpuCommandMmioRegs.SOURCE.U) { source := io.reg.req.bits.data }
      is(GpuCommandMmioRegs.DESTINATION.U) {
        destination := io.reg.req.bits.data
      }
      is(GpuCommandMmioRegs.BYTES.U) { bytes := io.reg.req.bits.data }
      is(GpuCommandMmioRegs.PATTERN.U) { pattern := io.reg.req.bits.data }
      is(GpuCommandMmioRegs.WIDTH.U) { width := io.reg.req.bits.data }
      is(GpuCommandMmioRegs.HEIGHT.U) { height := io.reg.req.bits.data }
      is(GpuCommandMmioRegs.SOURCE_STRIDE.U) {
        sourceStride := io.reg.req.bits.data
      }
      is(GpuCommandMmioRegs.DESTINATION_STRIDE.U) {
        destinationStride := io.reg.req.bits.data
      }
      is(GpuCommandMmioRegs.WAIT_EVENT.U) { waitEvent := io.reg.req.bits.data }
      is(GpuCommandMmioRegs.SIGNAL_EVENT.U) {
        signalEvent := io.reg.req.bits.data
      }
      is(GpuCommandMmioRegs.STATUS.U) {
        when(io.reg.req.bits.data(2)) { submitOverflow := false.B }
        when(io.reg.req.bits.data(4)) { resetRejected := false.B }
      }
    }
  }

  private val completionMeta = Cat(
    0.U(16.W), completion.success, completion.status, completion.opcode,
    completion.commandId)
  private val status = Cat(
    0.U(27.W), resetRejected, resetActive, submitOverflow, completionValid,
    queue.io.enq.ready)
  private val readData = MuxLookup(io.reg.req.bits.addr, 0.U(32.W))(Seq(
    GpuCommandMmioRegs.COMMAND_ID.U -> commandId,
    GpuCommandMmioRegs.OPCODE.U -> opcode,
    GpuCommandMmioRegs.KERNEL_PC.U -> kernelPc,
    GpuCommandMmioRegs.KERNARG.U -> kernarg,
    GpuCommandMmioRegs.GRID_X.U -> grid(0),
    GpuCommandMmioRegs.GRID_Y.U -> grid(1),
    GpuCommandMmioRegs.GRID_Z.U -> grid(2),
    GpuCommandMmioRegs.LOCAL_X.U -> local(0),
    GpuCommandMmioRegs.LOCAL_Y.U -> local(1),
    GpuCommandMmioRegs.LOCAL_Z.U -> local(2),
    GpuCommandMmioRegs.FLAGS.U -> flags,
    GpuCommandMmioRegs.DMA_DEPENDENCY.U -> dmaDependency,
    GpuCommandMmioRegs.SOURCE.U -> source,
    GpuCommandMmioRegs.DESTINATION.U -> destination,
    GpuCommandMmioRegs.BYTES.U -> bytes,
    GpuCommandMmioRegs.PATTERN.U -> pattern,
    GpuCommandMmioRegs.WIDTH.U -> width,
    GpuCommandMmioRegs.HEIGHT.U -> height,
    GpuCommandMmioRegs.SOURCE_STRIDE.U -> sourceStride,
    GpuCommandMmioRegs.DESTINATION_STRIDE.U -> destinationStride,
    GpuCommandMmioRegs.WAIT_EVENT.U -> waitEvent,
    GpuCommandMmioRegs.SIGNAL_EVENT.U -> signalEvent,
    GpuCommandMmioRegs.STATUS.U -> status,
    GpuCommandMmioRegs.COMPLETION.U -> completionMeta,
    GpuCommandMmioRegs.COMPLETION_BYTES_LO.U -> completion.bytesProcessed(31, 0),
    GpuCommandMmioRegs.COMPLETION_BYTES_HI.U -> completion.bytesProcessed(63, 32)))

  io.reg.req.ready := true.B
  io.reg.resp.valid := io.reg.req.valid && !io.reg.req.bits.isWrite
  io.reg.resp.bits.data := readData
  io.reg.resp.bits.ok := io.reg.req.bits.addr >= GpuCommandMmioRegs.COMMAND_ID.U &&
    io.reg.req.bits.addr < GpuCommandMmioRegs.END.U &&
    io.reg.req.bits.addr(1, 0) === 0.U
}
