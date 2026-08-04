package gpu.core.frontend.simt

import chisel3._
import chisel3.util._
import gpu.config.GpuConfig
import gpu.core.execute.control._

/** Branch resolution and independent per-warp SIMT stack state.
  *
  * A divergent result reserves space in the addressed warp's stack, writes
  * reconvergence and alternate entries in LIFO order, then emits the path to
  * execute. `restore` pops exactly one saved path for a join/completed path.
  */
class SimtControlFlow(config: GpuConfig = GpuConfig()) extends Module {
  private val countWidth = math.max(1, log2Ceil(config.simtStackDepth + 1))

  val io = IO(new Bundle {
    val branch = Flipped(Decoupled(new SimtBranchRequest(config)))
    val current = Decoupled(new SimtPath(config))
    val restoreWarpId = Input(UInt(config.warpIdWidth.W))
    val restore = Decoupled(new SimtPath(config))
    val clear = Flipped(Valid(UInt(config.warpIdWidth.W)))
    val stackCounts = Output(Vec(config.warps, UInt(countWidth.W)))
  })

  private val resolver = Module(new SimtBranchResolver(config))
  private val sequencer = Module(new SimtBranchStackSequencer(config))
  private val stacks = Module(new SimtStackBank(config))

  resolver.io.in <> io.branch
  sequencer.io.in <> resolver.io.out
  io.current <> sequencer.io.current

  private val sequencerWarpId = resolver.io.out.bits.warpId
  private val validSequencerWarp = sequencerWarpId < config.warps.U
  sequencer.io.freeEntries := 0.U
  when(validSequencerWarp) {
    sequencer.io.freeEntries := stacks.io.freeEntries(sequencerWarpId)
  }

  stacks.io.push.valid := sequencer.io.stackPush.valid
  stacks.io.push.bits.warpId := sequencer.io.current.bits.warpId
  stacks.io.push.bits.entry := sequencer.io.stackPush.bits
  sequencer.io.stackPush.ready := stacks.io.push.ready

  stacks.io.popWarpId := io.restoreWarpId
  io.restore.valid := stacks.io.pop.valid
  io.restore.bits.warpId := stacks.io.pop.bits.warpId
  io.restore.bits.pc := stacks.io.pop.bits.entry.pc
  io.restore.bits.activeMask := stacks.io.pop.bits.entry.activeMask
  stacks.io.pop.ready := io.restore.ready

  stacks.io.clear <> io.clear
  io.stackCounts := stacks.io.counts
}
