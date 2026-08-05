package gpu.dispatch

import chisel3._
import chisel3.util._
import gpu.config.GpuConfig

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

  private val idle :: active :: finish :: Nil = Enum(3)
  private val state = RegInit(idle)
  private val task = Reg(new WorkgroupTask(config))
  private val totalWarps = RegInit(0.U(48.W))
  private val issuedWarps = RegInit(0.U(48.W))
  private val completedWarps = RegInit(0.U(48.W))
  private val accumulatedSuccess = RegInit(true.B)

  private val incomingCount =
    io.workgroup.bits.localSize(0) * io.workgroup.bits.localSize(1) *
      io.workgroup.bits.localSize(2)
  private val incomingWarps =
    (incomingCount + (config.lanes - 1).U) / config.lanes.U
  private val fitsComputeUnit = incomingWarps <= config.warps.U
  private val linearBase = issuedWarps * config.lanes.U
  private val taskCount = task.localSize(0) * task.localSize(1) * task.localSize(2)
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

  when(io.workgroup.fire) {
    task := io.workgroup.bits
    totalWarps := incomingWarps
    issuedWarps := 0.U
    completedWarps := 0.U
    accumulatedSuccess := incomingCount =/= 0.U && fitsComputeUnit
    state := Mux(incomingCount === 0.U || !fitsComputeUnit, finish, active)
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
