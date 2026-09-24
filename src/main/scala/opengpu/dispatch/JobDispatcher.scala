package opengpu.dispatch

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig

/** Serial single-CU grid dispatcher.
  *
  * It keeps at most one workgroup outstanding. This deliberately simple
  * contract makes completion accounting exact and is the base for a future
  * multi-CU dispatcher with tags.
  *
  * `atLast*` flags are maintained alongside `groupId` so the completion
  * advance path is a mux on registered one-bit lasts, not a 32-bit
  * `groupId === gridSize - 1` compare (resolveaddr binding cone was
  * `command_gridSize_1` → `workgroup_bits_groupId_2`).
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
  private val limit = Reg(Vec(3, UInt(32.W)))
  private val atLastX = RegInit(false.B)
  private val atLastY = RegInit(false.B)
  private val atLastZ = RegInit(false.B)
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

  private val lastGroup = atLastX && atLastY && atLastZ

  when(io.launch.fire) {
    command := io.launch.bits
    groupId.foreach(_ := 0.U)
    for (axis <- 0 until 3) {
      limit(axis) := io.launch.bits.gridSize(axis) - 1.U
    }
    // groupId resets to 0, so each axis is already last iff extent is 1.
    atLastX := io.launch.bits.gridSize(0) === 1.U
    atLastY := io.launch.bits.gridSize(1) === 1.U
    atLastZ := io.launch.bits.gridSize(2) === 1.U
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
      when(!atLastX) {
        val nextX = groupId(0) + 1.U
        groupId(0) := nextX
        atLastX := nextX === limit(0)
      }.otherwise {
        groupId(0) := 0.U
        atLastX := limit(0) === 0.U
        when(!atLastY) {
          val nextY = groupId(1) + 1.U
          groupId(1) := nextY
          atLastY := nextY === limit(1)
        }.otherwise {
          groupId(1) := 0.U
          atLastY := limit(1) === 0.U
          val nextZ = groupId(2) + 1.U
          groupId(2) := nextZ
          atLastZ := nextZ === limit(2)
        }
      }
      state := dispatch
    }
  }

  when(io.completion.fire) {
    state := idle
  }
}
