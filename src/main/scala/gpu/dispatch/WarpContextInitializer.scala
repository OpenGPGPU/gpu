package gpu.dispatch

import chisel3._
import chisel3.util._
import gpu.config.GpuConfig
import gpu.core.backend.register.{ScalarRegisterWrite, VectorRegisterWrite}
import gpu.core.frontend.warp.WarpLaunch

/** Initializes the minimal kernel ABI before making a warp runnable.
  *
  * Scalar ABI:
  *   x1=kernarg, x2..x4=group xyz, x5..x7=local-size xyz,
  *   x8=local-linear-base.
  * Vector v1 contains local-linear IDs. v0 remains reserved for RVV masks.
  *
  * This module owns the frontend launch port. It snapshots the lowest free
  * hardware slot before initialization, and dispatch serialization guarantees
  * that no competing launch can consume that slot.
  */
class WarpContextInitializer(config: GpuConfig = GpuConfig()) extends Module {
  val io = IO(new Bundle {
    val task = Flipped(Decoupled(new WarpTask(config)))
    val activeWarps = Input(UInt(config.warps.W))
    val scalar = Decoupled(new ScalarRegisterWrite(config))
    val vector = Decoupled(new VectorRegisterWrite(config))
    val launch = Decoupled(new WarpLaunch(config))
    val assignedWarpId = Valid(UInt(config.warpIdWidth.W))
  })

  private val idle :: scalarInit :: vectorInit :: launchWarp :: Nil = Enum(4)
  private val state = RegInit(idle)
  private val task = Reg(new WarpTask(config))
  private val warpId = Reg(UInt(config.warpIdWidth.W))
  private val scalarIndex = RegInit(0.U(3.W))
  private val free = ~io.activeWarps
  private val selectedWarp = PriorityEncoder(free)

  io.task.ready := state === idle && free.orR
  when(io.task.fire) {
    task := io.task.bits
    warpId := selectedWarp
    scalarIndex := 0.U
    state := scalarInit
  }

  private val scalarValues = Wire(Vec(8, UInt(config.xLen.W)))
  scalarValues(0) := task.kernargAddress
  scalarValues(1) := task.groupId(0)
  scalarValues(2) := task.groupId(1)
  scalarValues(3) := task.groupId(2)
  scalarValues(4) := task.localSize(0)
  scalarValues(5) := task.localSize(1)
  scalarValues(6) := task.localSize(2)
  scalarValues(7) := task.localLinearBase

  io.scalar.valid := state === scalarInit
  io.scalar.bits.warpId := warpId
  io.scalar.bits.rd := scalarIndex +& 1.U
  io.scalar.bits.data := scalarValues(scalarIndex)
  when(io.scalar.fire) {
    when(scalarIndex === 7.U) {
      state := vectorInit
    }.otherwise {
      scalarIndex := scalarIndex + 1.U
    }
  }

  io.vector.valid := state === vectorInit
  io.vector.bits.warpId := warpId
  io.vector.bits.vd := 1.U
  for (lane <- 0 until config.lanes) {
    io.vector.bits.data(lane) := task.localLinearBase + lane.U
  }
  when(io.vector.fire) {
    state := launchWarp
  }

  io.launch.valid := state === launchWarp
  io.launch.bits.startPc := task.kernelPc
  io.launch.bits.activeMask := task.activeMask
  when(io.launch.fire) {
    state := idle
  }

  io.assignedWarpId.valid := state =/= idle
  io.assignedWarpId.bits := warpId
}
