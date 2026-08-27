package opengpu.core.execute.control

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.frontend.simt.SimtStackEntry

/** Serializes a resolved SIMT branch into per-warp stack operations.
  *
  * Reconvergence is pushed before the alternate path so that the alternate
  * path is popped first. The current path is released only after all required
  * stack writes complete. `freeEntries` prevents accepting a divergent branch
  * unless both entries can eventually be committed without depending on the
  * current path making progress.
  */
class SimtBranchStackSequencer(config: GpuConfig = GpuConfig())
    extends Module {
  private val freeWidth = math.max(1, log2Ceil(config.simtStackDepth + 1))

  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new SimtBranchResult(config)))
    val freeEntries = Input(UInt(freeWidth.W))
    val stackPush = Decoupled(new SimtStackEntry(config))
    val current = Decoupled(new SimtPath(config))
  })

  private val idle :: pushReconvergence :: pushAlternate :: emitCurrent :: Nil =
    Enum(4)
  private val state = RegInit(idle)
  private val saved = Reg(new SimtBranchResult(config))

  private val requiredEntries =
    io.in.bits.hasReconvergence.asUInt +& io.in.bits.hasAlternate.asUInt
  io.in.ready :=
    state === idle && io.freeEntries >= requiredEntries

  io.stackPush.valid :=
    state === pushReconvergence || state === pushAlternate
  io.stackPush.bits :=
    Mux(state === pushReconvergence, saved.reconvergence, saved.alternate)

  io.current.valid := state === emitCurrent
  io.current.bits.warpId := saved.warpId
  io.current.bits.pc := saved.currentPc
  io.current.bits.activeMask := saved.currentMask

  when(state === idle && io.in.fire) {
    saved := io.in.bits
    when(io.in.bits.hasReconvergence) {
      state := pushReconvergence
    }.elsewhen(io.in.bits.hasAlternate) {
      state := pushAlternate
    }.otherwise {
      state := emitCurrent
    }
  }

  when(state === pushReconvergence && io.stackPush.fire) {
    state := Mux(saved.hasAlternate, pushAlternate, emitCurrent)
  }

  when(state === pushAlternate && io.stackPush.fire) {
    state := emitCurrent
  }

  when(state === emitCurrent && io.current.fire) {
    state := idle
  }
}
