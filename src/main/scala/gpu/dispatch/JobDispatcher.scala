package gpu.dispatch

import chisel3._
import chisel3.util._
import gpu.config.GpuConfig

/** Serial single-CU grid dispatcher.
  *
  * It keeps at most one workgroup outstanding. This deliberately simple
  * contract makes completion accounting exact and is the base for a future
  * multi-CU dispatcher with tags.
  */
class JobDispatcher(config: GpuConfig = GpuConfig()) extends Module {
  val io = IO(new Bundle {
    val launch = Flipped(Decoupled(new KernelLaunch(config)))
    val workgroup = Decoupled(new WorkgroupTask(config))
    val workgroupCompletion = Flipped(Decoupled(new WorkgroupCompletion))
    val completion = Decoupled(new KernelCompletion)
  })

  private val idle :: dispatch :: waitForGroup :: finish :: Nil = Enum(4)
  private val state = RegInit(idle)
  private val command = Reg(new KernelLaunch(config))
  private val groupId = RegInit(VecInit(Seq.fill(3)(0.U(32.W))))
  private val accumulatedSuccess = RegInit(true.B)

  private val nonEmptyGrid = io.launch.bits.gridSize.map(_ =/= 0.U).reduce(_ && _)
  io.launch.ready := state === idle
  io.workgroup.valid := state === dispatch
  io.workgroup.bits.kernelPc := command.kernelPc
  io.workgroup.bits.kernargAddress := command.kernargAddress
  io.workgroup.bits.gridSize := command.gridSize
  io.workgroup.bits.localSize := command.localSize
  io.workgroup.bits.groupId := groupId
  io.workgroupCompletion.ready := state === waitForGroup
  io.completion.valid := state === finish
  io.completion.bits.success := accumulatedSuccess

  private val lastX = groupId(0) === command.gridSize(0) - 1.U
  private val lastY = groupId(1) === command.gridSize(1) - 1.U
  private val lastZ = groupId(2) === command.gridSize(2) - 1.U
  private val lastGroup = lastX && lastY && lastZ

  when(io.launch.fire) {
    command := io.launch.bits
    groupId.foreach(_ := 0.U)
    accumulatedSuccess := nonEmptyGrid
    state := Mux(nonEmptyGrid, dispatch, finish)
  }

  when(io.workgroup.fire) {
    state := waitForGroup
  }

  when(io.workgroupCompletion.fire) {
    accumulatedSuccess := accumulatedSuccess && io.workgroupCompletion.bits.success
    when(lastGroup) {
      state := finish
    }.otherwise {
      when(!lastX) {
        groupId(0) := groupId(0) + 1.U
      }.otherwise {
        groupId(0) := 0.U
        when(!lastY) {
          groupId(1) := groupId(1) + 1.U
        }.otherwise {
          groupId(1) := 0.U
          groupId(2) := groupId(2) + 1.U
        }
      }
      state := dispatch
    }
  }

  when(io.completion.fire) {
    state := idle
  }
}
