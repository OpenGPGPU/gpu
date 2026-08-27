package opengpu.core.backend.scoreboard

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig

class RegisterReservation(config: GpuConfig) extends Bundle {
  val warpId = UInt(config.warpIdWidth.W)
  val rs1 = UInt(5.W)
  val rs2 = UInt(5.W)
  val rd = UInt(5.W)
  val useRs1 = Bool()
  val useRs2 = Bool()
  val writeRd = Bool()
}

class RegisterRelease(config: GpuConfig) extends Bundle {
  val warpId = UInt(config.warpIdWidth.W)
  val rd = UInt(5.W)
}

/** Per-warp scalar dependency scoreboard.
  *
  * `reserve.ready` is the hazard result and `reserve.fire` atomically marks
  * the destination busy. A same-cycle release is visible to the query, while
  * a simultaneous reservation of that register wins in the next state.
  */
class RegisterScoreboard(config: GpuConfig = GpuConfig()) extends Module {
  val io = IO(new Bundle {
    val reserve = Flipped(Decoupled(new RegisterReservation(config)))
    val release = Flipped(Valid(new RegisterRelease(config)))
    val cancel = Flipped(Valid(new RegisterRelease(config)))
    val rawHazard = Output(Bool())
    val wawHazard = Output(Bool())
    val busyByWarp = Output(Vec(config.warps, UInt(32.W)))
  })

  private val busy = RegInit(VecInit(Seq.fill(config.warps)(0.U(32.W))))
  private val reserveWarpValid = io.reserve.bits.warpId < config.warps.U
  private val releaseWarpValid = io.release.bits.warpId < config.warps.U
  private val releaseEnabled =
    io.release.valid && releaseWarpValid && io.release.bits.rd =/= 0.U
  private val cancelEnabled =
    io.cancel.valid && io.cancel.bits.warpId < config.warps.U &&
      io.cancel.bits.rd =/= 0.U

  private val releaseOH = UIntToOH(io.release.bits.rd, 32)
  private val cancelOH = UIntToOH(io.cancel.bits.rd, 32)
  private val selectedBusy = if (config.warps == 1) {
    busy(0)
  } else {
    Mux(reserveWarpValid, busy(io.reserve.bits.warpId), 0.U)
  }
  private val sameWarpRelease =
    releaseEnabled && io.release.bits.warpId === io.reserve.bits.warpId
  private val effectiveBusy =
    Mux(sameWarpRelease, selectedBusy & ~releaseOH, selectedBusy) &
      Mux(
        cancelEnabled && io.cancel.bits.warpId === io.reserve.bits.warpId,
        ~cancelOH,
        Fill(32, 1.U)
      )

  private def registerBusy(register: UInt): Bool =
    register =/= 0.U && effectiveBusy(register)

  io.rawHazard :=
    (io.reserve.bits.useRs1 && registerBusy(io.reserve.bits.rs1)) ||
      (io.reserve.bits.useRs2 && registerBusy(io.reserve.bits.rs2))
  io.wawHazard :=
    io.reserve.bits.writeRd && registerBusy(io.reserve.bits.rd)
  io.reserve.ready := reserveWarpValid && !io.rawHazard && !io.wawHazard

  private val reserveEnabled =
    io.reserve.fire && io.reserve.bits.writeRd && io.reserve.bits.rd =/= 0.U
  private val reserveOH = UIntToOH(io.reserve.bits.rd, 32)

  for (warp <- 0 until config.warps) {
    val clearThisWarp =
      releaseEnabled && io.release.bits.warpId === warp.U
    val reserveThisWarp =
      reserveEnabled && io.reserve.bits.warpId === warp.U
    val afterRelease = Mux(clearThisWarp, busy(warp) & ~releaseOH, busy(warp))
    val afterCancel = Mux(
      cancelEnabled && io.cancel.bits.warpId === warp.U,
      afterRelease & ~cancelOH,
      afterRelease
    )
    busy(warp) := Mux(reserveThisWarp, afterCancel | reserveOH, afterCancel)
    io.busyByWarp(warp) := busy(warp)
  }
}

/** Busy-state storage for a scoreboard whose dependency query is pipelined by
  * its caller.
  *
  * `reserve` is already known to be hazard-free. Release is applied first and
  * a simultaneous reservation wins, matching [[RegisterScoreboard]].
  */
class RegisterScoreboardState(config: GpuConfig = GpuConfig()) extends Module {
  val io = IO(new Bundle {
    val reserve = Flipped(Valid(new RegisterReservation(config)))
    val release = Flipped(Valid(new RegisterRelease(config)))
    val cancel = Flipped(Valid(new RegisterRelease(config)))
    val busyByWarp = Output(Vec(config.warps, UInt(32.W)))
  })

  private val busy = RegInit(VecInit(Seq.fill(config.warps)(0.U(32.W))))
  private val reserveEnabled =
    io.reserve.valid && io.reserve.bits.warpId < config.warps.U &&
      io.reserve.bits.writeRd && io.reserve.bits.rd =/= 0.U
  private val releaseEnabled =
    io.release.valid && io.release.bits.warpId < config.warps.U &&
      io.release.bits.rd =/= 0.U
  private val cancelEnabled =
    io.cancel.valid && io.cancel.bits.warpId < config.warps.U &&
      io.cancel.bits.rd =/= 0.U
  private val reserveOH = UIntToOH(io.reserve.bits.rd, 32)
  private val releaseOH = UIntToOH(io.release.bits.rd, 32)
  private val cancelOH = UIntToOH(io.cancel.bits.rd, 32)

  for (warp <- 0 until config.warps) {
    val afterRelease = Mux(
      releaseEnabled && io.release.bits.warpId === warp.U,
      busy(warp) & ~releaseOH,
      busy(warp)
    )
    val afterCancel = Mux(
      cancelEnabled && io.cancel.bits.warpId === warp.U,
      afterRelease & ~cancelOH,
      afterRelease
    )
    busy(warp) := Mux(
      reserveEnabled && io.reserve.bits.warpId === warp.U,
      afterCancel | reserveOH,
      afterCancel
    )
    io.busyByWarp(warp) := busy(warp)
  }
}
