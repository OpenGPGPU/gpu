package gpu.core.frontend.simt

import chisel3._
import chisel3.util._
import gpu.config.GpuConfig

/** Saved SIMT control-flow state.
  *
  * `activeMask` is the path restored by this entry. `originalMask` records the
  * complete pre-divergence warp mask for reconvergence checks.
  */
class SimtStackEntry(config: GpuConfig) extends Bundle {
  val pc = UInt(config.xLen.W)
  val activeMask = UInt(config.lanes.W)
  val originalMask = UInt(config.lanes.W)
  val divergent = Bool()
}

/** Small per-warp LIFO for divergent control flow.
  *
  * Unlike the original OpenGPU implementation, a successful pop always
  * removes exactly one entry. A full stack can accept a push when its current
  * top is popped in the same cycle, replacing that entry without a bubble.
  * `clear` supports recycling a hardware warp without resetting the core.
  */
class SimtStack(config: GpuConfig = GpuConfig()) extends Module {
  private val depth = config.simtStackDepth
  private val indexWidth = math.max(1, log2Ceil(depth))
  private val countWidth = math.max(1, log2Ceil(depth + 1))

  val io = IO(new Bundle {
    val clear = Input(Bool())
    val push = Flipped(Decoupled(new SimtStackEntry(config)))
    val pop = Decoupled(new SimtStackEntry(config))
    val count = Output(UInt(countWidth.W))
    val empty = Output(Bool())
    val full = Output(Bool())
  })

  private val entries = Reg(Vec(depth, new SimtStackEntry(config)))
  private val count = RegInit(0.U(countWidth.W))
  private val empty = count === 0.U
  private val full = count === depth.U
  private val topIndex = Mux(empty, 0.U, count - 1.U)(indexWidth - 1, 0)

  io.pop.valid := !empty
  io.pop.bits := entries(topIndex)
  io.push.ready := !full || io.pop.fire
  io.count := count
  io.empty := empty
  io.full := full

  private val pushEntry = io.push.fire
  private val popEntry = io.pop.fire
  private val pushIndex =
    Mux(popEntry, count - 1.U, count)(indexWidth - 1, 0)

  when(io.clear) {
    count := 0.U
  }.otherwise {
    when(pushEntry) {
      entries(pushIndex) := io.push.bits
    }

    switch(Cat(pushEntry, popEntry)) {
      is("b10".U) { count := count + 1.U }
      is("b01".U) { count := count - 1.U }
    }
  }

  assert(count <= depth.U)
}
