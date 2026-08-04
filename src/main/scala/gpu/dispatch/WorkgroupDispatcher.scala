package gpu.dispatch

import chisel3._
import chisel3.util._
import gpu.config.GpuConfig

/** Converts a workgroup into ceil(local-work-items / lanes) logical warps.
  * Only one warp is outstanding in the initial single-CU implementation.
  */
class WorkgroupDispatcher(config: GpuConfig = GpuConfig()) extends Module {
  val io = IO(new Bundle {
    val workgroup = Flipped(Decoupled(new WorkgroupTask(config)))
    val warp = Decoupled(new WarpTask(config))
    val warpCompletion = Flipped(Decoupled(new WarpCompletion))
    val completion = Decoupled(new WorkgroupCompletion)
  })

  private val idle :: dispatch :: waitForWarp :: finish :: Nil = Enum(4)
  private val state = RegInit(idle)
  private val task = Reg(new WorkgroupTask(config))
  private val linearBase = RegInit(0.U(48.W))
  private val accumulatedSuccess = RegInit(true.B)

  private val incomingCount =
    io.workgroup.bits.localSize(0) * io.workgroup.bits.localSize(1) *
      io.workgroup.bits.localSize(2)
  private val taskCount =
    task.localSize(0) * task.localSize(1) * task.localSize(2)
  private val remaining = taskCount - linearBase
  private val fullMask = Fill(config.lanes, 1.U(1.W))
  private val tailCountWidth = math.max(1, log2Ceil(config.lanes + 1))
  private val tailCount = remaining(tailCountWidth - 1, 0)
  private val tailMask = ((1.U((config.lanes + 1).W) << tailCount) - 1.U)(config.lanes - 1, 0)
  private val lastWarp = remaining <= config.lanes.U

  io.workgroup.ready := state === idle
  io.warp.valid := state === dispatch
  io.warp.bits.kernelPc := task.kernelPc
  io.warp.bits.kernargAddress := task.kernargAddress
  io.warp.bits.gridSize := task.gridSize
  io.warp.bits.localSize := task.localSize
  io.warp.bits.groupId := task.groupId
  io.warp.bits.localLinearBase := linearBase
  io.warp.bits.activeMask := Mux(lastWarp, tailMask, fullMask)
  io.warp.bits.firstWarp := linearBase === 0.U
  io.warp.bits.lastWarp := lastWarp
  io.warpCompletion.ready := state === waitForWarp
  io.completion.valid := state === finish
  io.completion.bits.success := accumulatedSuccess

  when(io.workgroup.fire) {
    task := io.workgroup.bits
    linearBase := 0.U
    accumulatedSuccess := incomingCount =/= 0.U
    state := Mux(incomingCount === 0.U, finish, dispatch)
  }

  when(io.warp.fire) {
    state := waitForWarp
  }

  when(io.warpCompletion.fire) {
    accumulatedSuccess := accumulatedSuccess && io.warpCompletion.bits.success
    when(lastWarp) {
      state := finish
    }.otherwise {
      linearBase := linearBase + config.lanes.U
      state := dispatch
    }
  }

  when(io.completion.fire) {
    state := idle
  }
}
