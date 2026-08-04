package gpu.core.backend.writeback

import chisel3._
import chisel3.util._
import gpu.config.GpuConfig
import gpu.core.backend.register.ScalarRegisterWrite
import gpu.core.execute.control.SimtPath

class ScalarCommitRequest(config: GpuConfig) extends Bundle {
  val warpId = UInt(config.warpIdWidth.W)
  val nextPc = UInt(config.xLen.W)
  val activeMask = UInt(config.lanes.W)
  val writeRd = Bool()
  val rd = UInt(5.W)
  val data = UInt(config.xLen.W)
}

/** Atomically commits a scalar result and makes its warp runnable.
  *
  * A destination write and scheduler redirect transfer in the same cycle.
  * Instructions without a destination require only redirect acceptance. This
  * prevents a warp from observing its next instruction before the previous
  * destination has reached the RF/scoreboard writeback port.
  */
class ScalarCommitStage(config: GpuConfig = GpuConfig()) extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new ScalarCommitRequest(config)))
    val writeback = Decoupled(new ScalarRegisterWrite(config))
    val redirect = Decoupled(new SimtPath(config))
  })

  private val needsWrite = io.in.bits.writeRd && io.in.bits.rd =/= 0.U
  private val bothReady = io.redirect.ready && (!needsWrite || io.writeback.ready)

  io.in.ready := bothReady
  io.writeback.valid := io.in.valid && needsWrite && io.redirect.ready
  io.writeback.bits.warpId := io.in.bits.warpId
  io.writeback.bits.rd := io.in.bits.rd
  io.writeback.bits.data := io.in.bits.data

  io.redirect.valid :=
    io.in.valid && (!needsWrite || io.writeback.ready)
  io.redirect.bits.warpId := io.in.bits.warpId
  io.redirect.bits.pc := io.in.bits.nextPc
  io.redirect.bits.activeMask := io.in.bits.activeMask

  assert(io.writeback.fire === (io.in.fire && needsWrite))
  assert(io.redirect.fire === io.in.fire)
}
