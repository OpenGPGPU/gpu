package gpu.core.frontend.warp

import chisel3._
import chisel3.util._
import gpu.config.GpuConfig

/** Round-robin scheduler and single source of truth for warp frontend state.
  *
  * A successful issue blocks that warp, preventing multiple unresolved
  * frontend transactions for the same warp. `resume` clears the block and
  * updates the PC; `finish` releases the hardware warp for a future launch.
  *
  * Launches name their target slot explicitly (`WarpLaunch.warpId`): the
  * dispatcher initializes that slot's registers while other warps may still
  * finish and free lower slots, so the scheduler must not re-derive the slot
  * at launch time. `launch.ready` backpressures unless the named slot is free.
  */
class WarpScheduler(config: GpuConfig = GpuConfig()) extends Module {
  val io = IO(new Bundle {
    val launch = Flipped(Decoupled(new WarpLaunch(config)))
    val issue = Decoupled(new WarpIssue(config))
    val resume = Flipped(Valid(new WarpResume(config)))
    val finish = Flipped(Valid(UInt(config.warpIdWidth.W)))

    val active = Output(UInt(config.warps.W))
    val blocked = Output(UInt(config.warps.W))
  })

  private val warpIdWidth = config.warpIdWidth
  private val active = RegInit(0.U(config.warps.W))
  private val blocked = RegInit(0.U(config.warps.W))
  private val pcs = Reg(Vec(config.warps, UInt(config.xLen.W)))
  private val masks = Reg(Vec(config.warps, UInt(config.lanes.W)))
  private val roundRobinHead = RegInit(0.U(warpIdWidth.W))

  private def validWarpId(id: UInt): Bool = id < config.warps.U
  private val finishOH = Mux(
    io.finish.valid && validWarpId(io.finish.bits),
    UIntToOH(io.finish.bits, config.warps),
    0.U(config.warps.W)
  )

  // `finish` is only legal after cease has blocked the warp and its pipeline
  // has drained. Keep it off the combinational grant path; the clocked state
  // update releases the slot without creating finish -> issue timing paths.
  private val eligible = active & ~blocked
  private val doubledEligible = Cat(eligible, eligible)
  private val rotatedEligible =
    (doubledEligible >> roundRobinHead)(config.warps - 1, 0)
  private val selectedOffset = PriorityEncoder(rotatedEligible)
  private val selectedSum = roundRobinHead +& selectedOffset
  private val selectedWarp = Mux(
    selectedSum >= config.warps.U,
    selectedSum - config.warps.U,
    selectedSum
  )(warpIdWidth - 1, 0)

  private val selectedPc = if (config.warps == 1) pcs(0) else pcs(selectedWarp)
  private val selectedMask = if (config.warps == 1) masks(0) else masks(selectedWarp)
  private val issueValid = RegInit(false.B)
  private val issueBits = Reg(new WarpIssue(config))
  private val canLoadIssue = !issueValid || io.issue.ready
  private val loadSelectedWarp = canLoadIssue && eligible.orR

  io.issue.valid := issueValid
  io.issue.bits := issueBits

  private val freeWarps = ~active
  // The launch names its target slot; only that slot's freeness backpressures
  // the launch, so a lower slot freed by a concurrent `finish` cannot steal
  // the launch away from the slot the dispatcher already initialized.
  private val launchTargetOH = UIntToOH(io.launch.bits.warpId, config.warps)
  io.launch.ready := (freeWarps & launchTargetOH).orR

  // `active`/`blocked` are whole-register state: compose every event into a
  // single next-state assignment. Separate `when` blocks would make a
  // coincident resume/finish/launch silently drop the block bit set by an
  // issue load in the same cycle (last write wins), re-exposing an in-flight
  // warp to the selector.
  private val launchFireOH =
    Mux(io.launch.fire, launchTargetOH, 0.U(config.warps.W))
  private val resumeClearOH = Mux(
    io.resume.valid && validWarpId(io.resume.bits.warpId),
    UIntToOH(io.resume.bits.warpId, config.warps),
    0.U(config.warps.W)
  )
  private val issueBlockOH = Mux(
    loadSelectedWarp,
    UIntToOH(selectedWarp, config.warps),
    0.U(config.warps.W)
  )
  active := (active | launchFireOH) & ~finishOH
  blocked :=
    (blocked & ~(resumeClearOH | finishOH | launchFireOH)) | issueBlockOH

  when(io.launch.fire) {
    if (config.warps == 1) {
      pcs(0) := io.launch.bits.startPc
      masks(0) := io.launch.bits.activeMask
    } else {
      pcs(io.launch.bits.warpId) := io.launch.bits.startPc
      masks(io.launch.bits.warpId) := io.launch.bits.activeMask
    }
  }

  when(canLoadIssue) {
    issueValid := eligible.orR
  }

  when(loadSelectedWarp) {
    issueBits.warpId := selectedWarp
    issueBits.pc := selectedPc
    issueBits.activeMask := selectedMask
    roundRobinHead := Mux(
      selectedWarp === (config.warps - 1).U,
      0.U,
      selectedWarp + 1.U
    )
  }

  when(io.resume.valid && validWarpId(io.resume.bits.warpId)) {
    if (config.warps == 1) {
      pcs(0) := io.resume.bits.nextPc
      masks(0) := io.resume.bits.activeMask
    } else {
      pcs(io.resume.bits.warpId) := io.resume.bits.nextPc
      masks(io.resume.bits.warpId) := io.resume.bits.activeMask
    }
  }

  io.active := active
  io.blocked := blocked
}
