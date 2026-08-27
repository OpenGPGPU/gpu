package opengpu.core.system

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.execute.control.SimtPath

/** CU-local workgroup barrier.
  *
  * The dispatcher guarantees that all resident warps belong to one workgroup.
  * Arriving warps remain blocked in the frontend. Once the final warp has been
  * dispatched and every resident warp has arrived, saved next-PC/mask records
  * are replayed one per cycle to make the entire workgroup runnable again.
  */
class WorkgroupBarrierController(config: GpuConfig = GpuConfig()) extends Module {
  val io = IO(new Bundle {
    val arrive = Flipped(Decoupled(new SimtPath(config)))
    val residentWarps = Input(UInt(config.warps.W))
    val dispatchComplete = Input(Bool())
    val memoryIdle = Input(Bool())
    val release = Decoupled(new SimtPath(config))
    val waiting = Output(UInt(config.warps.W))
  })

  private val collecting :: releasing :: Nil = Enum(2)
  private val state = RegInit(collecting)
  private val arrived = RegInit(0.U(config.warps.W))
  private val paths = Reg(Vec(config.warps, new SimtPath(config)))
  private val releasePending = RegInit(0.U(config.warps.W))
  private val arrivalInRange = io.arrive.bits.warpId < config.warps.U
  private val arrivalOH = UIntToOH(io.arrive.bits.warpId, config.warps)
  private val alreadyArrived = (arrived & arrivalOH).orR

  io.arrive.ready := state === collecting && arrivalInRange && !alreadyArrived
  when(io.arrive.fire) {
    if (config.warps == 1) paths(0) := io.arrive.bits
    else paths(io.arrive.bits.warpId) := io.arrive.bits
    arrived := arrived | arrivalOH
  }

  private val arrivedAfterInput = Mux(io.arrive.fire, arrived | arrivalOH, arrived)
  private val allResidentArrived = io.residentWarps.orR &&
    (arrivedAfterInput & io.residentWarps) === io.residentWarps
  when(state === collecting && io.dispatchComplete && io.memoryIdle &&
      allResidentArrived) {
    releasePending := arrivedAfterInput & io.residentWarps
    state := releasing
  }

  private val releaseWarp = PriorityEncoder(releasePending)
  io.release.valid := state === releasing && releasePending.orR
  io.release.bits := (if (config.warps == 1) paths(0) else paths(releaseWarp))
  when(io.release.fire) {
    val remaining = releasePending & ~UIntToOH(releaseWarp, config.warps)
    releasePending := remaining
    when(!remaining.orR) {
      arrived := 0.U
      state := collecting
    }
  }

  io.waiting := Mux(state === collecting, arrived, releasePending)
  when(io.arrive.valid) {
    assert(arrivalInRange, "barrier arrival must identify a resident warp")
    assert((io.residentWarps & arrivalOH).orR,
      "only a resident warp may enter its workgroup barrier")
  }
}
