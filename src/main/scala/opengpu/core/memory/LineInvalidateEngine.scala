package opengpu.core.memory

import chisel3._
import chisel3.util._
import opengpu.command.{InvalidateCompletion, InvalidateDescriptor, InvalidateStatus}
import opengpu.config.GpuConfig

/** Walks a byte range and invalidates one 64-byte L2 line per step.
  *
  * Used by the driver-visible invalidate command and by the resolve adapter's
  * implicit source invalidate.  It issues exactly one host invalidate at a time
  * and waits for the L2 acknowledgement before advancing, so it needs no
  * tagging and never blocks an unrelated L2 client.
  */
class LineInvalidateEngine(
  config: GpuConfig = GpuConfig(),
  commandIdWidth: Int = 8
) extends Module {
  val io = IO(new Bundle {
    val request = Flipped(Decoupled(
      new InvalidateDescriptor(config, commandIdWidth)))
    val completion = Decoupled(new InvalidateCompletion(commandIdWidth))
    val hostInvalidate = Decoupled(new CacheLineInvalidate(config))
    val hostInvalidateDone = Flipped(Decoupled(
      new CacheLineInvalidate(config)))
  })

  private object State extends ChiselEnum {
    val idle, issue, waiting, respond = Value
  }
  require(commandIdWidth > 0)
  private val offsetWidth = 6
  private val state = RegInit(State.idle)
  private val address = Reg(UInt(config.xLen.W))
  private val descriptorId = Reg(UInt(commandIdWidth.W))
  private val lines = Reg(UInt(32.W))
  private val index = Reg(UInt(32.W))

  private val lineCount = (io.request.bits.bytes + 63.U) >> offsetWidth

  io.request.ready := state === State.idle
  io.hostInvalidate.valid := state === State.issue
  io.hostInvalidate.bits.lineAddress := address + (index << offsetWidth)
  io.hostInvalidateDone.ready := state === State.waiting
  io.completion.valid := state === State.respond
  io.completion.bits.descriptorId := descriptorId
  io.completion.bits.status := InvalidateStatus.success
  io.completion.bits.success := true.B
  io.completion.bits.bytesInvalidated := (lines << offsetWidth).pad(64)

  switch(state) {
    is(State.idle) {
      when(io.request.fire) {
        address := io.request.bits.address
        descriptorId := io.request.bits.descriptorId
        lines := lineCount
        index := 0.U
        state := Mux(io.request.bits.bytes === 0.U,
          State.respond, State.issue)
      }
    }
    is(State.issue) {
      when(io.hostInvalidate.fire) { state := State.waiting }
    }
    is(State.waiting) {
      when(io.hostInvalidateDone.fire) {
        when(index === lines - 1.U) {
          state := State.respond
        }.otherwise {
          index := index + 1.U
          state := State.issue
        }
      }
    }
    is(State.respond) {
      when(io.completion.fire) { state := State.idle }
    }
  }
}
