package opengpu.dispatch

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig

/** Converts a workgroup into ceil(local-work-items / lanes) logical warps.
  * Warps are streamed independently and completion is counted separately, so
  * up to the CU's hardware-warp capacity may reside concurrently. This is the
  * execution model required by workgroup barriers.
  */
class WorkgroupDispatcher(config: GpuConfig = GpuConfig()) extends Module {
  val io = IO(new Bundle {
    val workgroup = Flipped(Decoupled(new WorkgroupTask(config)))
    val warp = Decoupled(new WarpTask(config))
    val warpCompletion = Flipped(Decoupled(new WarpCompletion))
    val completion = Decoupled(new WorkgroupCompletion)
  })

  private val idle :: prepare :: settle :: active :: finish :: Nil = Enum(5)
  private val state = RegInit(idle)
  private val task = Reg(new WorkgroupTask(config))
  private val partialProduct = Reg(UInt(32.W))
  private val taskLocalSize2 = Reg(UInt(16.W))
  private val mulLo = Reg(UInt(32.W))
  private val mulHi = Reg(UInt(32.W))
  private val taskCount = RegInit(0.U(48.W))
  private val totalWarps = RegInit(0.U(48.W))
  private val issuedWarps = RegInit(0.U(48.W))
  private val completedWarps = RegInit(0.U(48.W))
  private val accumulatedSuccess = RegInit(true.B)

  private val linearBase = issuedWarps * config.lanes.U
  private val remaining = taskCount - linearBase
  private val fullMask = Fill(config.lanes, 1.U(1.W))
  private val tailCountWidth = math.max(1, log2Ceil(config.lanes + 1))
  private val tailCount = remaining(tailCountWidth - 1, 0)
  private val tailMask = ((1.U((config.lanes + 1).W) << tailCount) - 1.U)(config.lanes - 1, 0)
  private val lastWarp = remaining <= config.lanes.U

  io.workgroup.ready := state === idle
  io.warp.valid := state === active && issuedWarps < totalWarps
  io.warp.bits.kernelPc := task.kernelPc
  io.warp.bits.kernargAddress := task.kernargAddress
  io.warp.bits.gridSize := task.gridSize
  io.warp.bits.localSize := task.localSize
  io.warp.bits.groupId := task.groupId
  io.warp.bits.localLinearBase := linearBase
  io.warp.bits.activeMask := Mux(lastWarp, tailMask, fullMask)
  io.warp.bits.firstWarp := linearBase === 0.U
  io.warp.bits.lastWarp := lastWarp
  io.warpCompletion.ready := state === active
  io.completion.valid := state === finish
  io.completion.bits.success := accumulatedSuccess

  // Split XY product accept, then Z as two 16x16 partials, then add+warp
  // count so no register-to-register edge sees a 32x16 mul into state
  // (writeuse limiter was taskLocalSize2 → workgroups.state).
  when(io.workgroup.fire) {
    task := io.workgroup.bits
    partialProduct := io.workgroup.bits.localSize(0) *
      io.workgroup.bits.localSize(1)
    taskLocalSize2 := io.workgroup.bits.localSize(2)
    state := prepare
  }

  when(state === prepare) {
    mulLo := partialProduct(15, 0) * taskLocalSize2
    mulHi := partialProduct(31, 16) * taskLocalSize2
    state := settle
  }

  when(state === settle) {
    val count = Cat(0.U(16.W), mulLo) + Cat(mulHi, 0.U(16.W))
    val warps = (count + (config.lanes - 1).U) / config.lanes.U
    val fits = warps <= config.warps.U
    taskCount := count
    totalWarps := warps
    issuedWarps := 0.U
    completedWarps := 0.U
    accumulatedSuccess := count =/= 0.U && fits
    state := Mux(count === 0.U || !fits, finish, active)
  }

  when(io.warp.fire) {
    issuedWarps := issuedWarps + 1.U
  }

  when(io.warpCompletion.fire) {
    accumulatedSuccess := accumulatedSuccess && io.warpCompletion.bits.success
    completedWarps := completedWarps + 1.U
    when(completedWarps + 1.U === totalWarps) {
      state := finish
    }
    assert(completedWarps < issuedWarps,
      "a workgroup cannot complete more warps than it dispatched")
  }

  when(io.completion.fire) {
    state := idle
  }
}
