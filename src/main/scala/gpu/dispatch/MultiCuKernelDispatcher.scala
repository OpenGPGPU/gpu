package gpu.dispatch

import chisel3._
import chisel3.util._
import gpu.config.GpuConfig

/** Assigns tagged host commands to idle compute units and restores their tags
  * on completion. Each CU may own one kernel, matching
  * [[SingleCuKernelController]]'s execution contract.
  */
class MultiCuKernelDispatcher(
  config: GpuConfig = GpuConfig(),
  numComputeUnits: Int = 2,
  commandIdWidth: Int = 8
) extends Module {
  require(numComputeUnits > 0)
  require(commandIdWidth > 0)

  val io = IO(new Bundle {
    val launch = Flipped(Decoupled(
      new TaggedKernelLaunch(config, commandIdWidth)))
    val completion = Decoupled(new TaggedKernelCompletion(commandIdWidth))
    val cuLaunch = Vec(numComputeUnits,
      Decoupled(new KernelLaunch(config)))
    val cuCompletion = Vec(numComputeUnits,
      Flipped(Decoupled(new KernelCompletion)))
    val busy = Output(UInt(numComputeUnits.W))
  })

  private val occupied = RegInit(VecInit(Seq.fill(numComputeUnits)(false.B)))
  private val commandIds = Reg(Vec(numComputeUnits, UInt(commandIdWidth.W)))
  private val free = ~occupied.asUInt
  private val hasFree = free.orR
  private val selected = PriorityEncoder(free)

  for (cu <- 0 until numComputeUnits) {
    io.cuLaunch(cu).valid := io.launch.valid && hasFree && selected === cu.U
    io.cuLaunch(cu).bits := io.launch.bits.launch
  }
  io.launch.ready := hasFree && io.cuLaunch(selected).ready

  when(io.launch.fire) {
    occupied(selected) := true.B
    commandIds(selected) := io.launch.bits.commandId
  }

  private val completions = Module(
    new RRArbiter(new TaggedKernelCompletion(commandIdWidth), numComputeUnits))
  for (cu <- 0 until numComputeUnits) {
    completions.io.in(cu).valid := io.cuCompletion(cu).valid && occupied(cu)
    completions.io.in(cu).bits.commandId := commandIds(cu)
    completions.io.in(cu).bits.success := io.cuCompletion(cu).bits.success
    io.cuCompletion(cu).ready := completions.io.in(cu).ready && occupied(cu)
  }
  io.completion <> completions.io.out

  when(io.completion.fire) {
    occupied(completions.io.chosen) := false.B
  }
  for (cu <- 0 until numComputeUnits) {
    when(io.cuCompletion(cu).valid) {
      assert(occupied(cu), "an idle compute unit cannot complete a kernel")
    }
  }
  io.busy := occupied.asUInt
}
