package gpu.core.frontend.simt

import chisel3._
import chisel3.util._
import gpu.config.GpuConfig

class SimtStackWrite(config: GpuConfig) extends Bundle {
  val warpId = UInt(config.warpIdWidth.W)
  val entry = new SimtStackEntry(config)
}

class SimtStackRead(config: GpuConfig) extends Bundle {
  val warpId = UInt(config.warpIdWidth.W)
  val entry = new SimtStackEntry(config)
}

/** One independent divergence stack per hardware warp.
  *
  * Only the addressed bank participates in a push or pop. A same-cycle clear
  * blocks handshakes to the cleared warp so no transaction is acknowledged
  * and then discarded by the stack's clear priority.
  */
class SimtStackBank(config: GpuConfig = GpuConfig()) extends Module {
  private val countWidth = math.max(1, log2Ceil(config.simtStackDepth + 1))

  val io = IO(new Bundle {
    val clear = Flipped(Valid(UInt(config.warpIdWidth.W)))
    val push = Flipped(Decoupled(new SimtStackWrite(config)))
    val popWarpId = Input(UInt(config.warpIdWidth.W))
    val pop = Decoupled(new SimtStackRead(config))
    val counts = Output(Vec(config.warps, UInt(countWidth.W)))
    val freeEntries = Output(Vec(config.warps, UInt(countWidth.W)))
  })

  private val stacks = Seq.fill(config.warps)(Module(new SimtStack(config)))
  private def validWarpId(id: UInt): Bool = id < config.warps.U

  private val pushValidId = validWarpId(io.push.bits.warpId)
  private val popValidId = validWarpId(io.popWarpId)
  private val clearingPushWarp =
    io.clear.valid && io.clear.bits === io.push.bits.warpId
  private val clearingPopWarp =
    io.clear.valid && io.clear.bits === io.popWarpId

  for ((stack, index) <- stacks.zipWithIndex) {
    stack.io.clear := io.clear.valid && io.clear.bits === index.U

    stack.io.push.valid :=
      io.push.valid && pushValidId && io.push.bits.warpId === index.U &&
        !clearingPushWarp
    stack.io.push.bits := io.push.bits.entry

    stack.io.pop.ready :=
      io.pop.ready && popValidId && io.popWarpId === index.U &&
        !clearingPopWarp

    io.counts(index) := stack.io.count
    io.freeEntries(index) := config.simtStackDepth.U - stack.io.count
  }

  io.push.ready := false.B
  when(pushValidId && !clearingPushWarp) {
    io.push.ready := VecInit(stacks.map(_.io.push.ready))(io.push.bits.warpId)
  }

  io.pop.valid := false.B
  io.pop.bits := 0.U.asTypeOf(io.pop.bits)
  when(popValidId && !clearingPopWarp) {
    io.pop.valid := VecInit(stacks.map(_.io.pop.valid))(io.popWarpId)
    io.pop.bits.warpId := io.popWarpId
    io.pop.bits.entry :=
      VecInit(stacks.map(_.io.pop.bits))(io.popWarpId)
  }
}
