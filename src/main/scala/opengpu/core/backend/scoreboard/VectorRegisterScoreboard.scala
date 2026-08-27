package opengpu.core.backend.scoreboard

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig

class VectorRegisterReservation(config: GpuConfig) extends Bundle {
  val warpId = UInt(config.warpIdWidth.W)
  val vs1 = UInt(5.W)
  val vs2 = UInt(5.W)
  val vd = UInt(5.W)
  val useVs1 = Bool()
  val useVs2 = Bool()
  val readVd = Bool()
  val useMask = Bool()
  val writeVd = Bool()
}

class VectorRegisterRelease(config: GpuConfig) extends Bundle {
  val warpId = UInt(config.warpIdWidth.W)
  val vd = UInt(5.W)
}

/** Per-warp vector dependency scoreboard; all v0-v31 registers are tracked. */
class VectorRegisterScoreboard(config: GpuConfig = GpuConfig()) extends Module {
  val io = IO(new Bundle {
    val reserve = Flipped(Decoupled(new VectorRegisterReservation(config)))
    val release = Flipped(Valid(new VectorRegisterRelease(config)))
    val cancel = Flipped(Valid(new VectorRegisterRelease(config)))
    val rawHazard = Output(Bool())
    val wawHazard = Output(Bool())
    val busyByWarp = Output(Vec(config.warps, UInt(32.W)))
  })

  private val busy = RegInit(VecInit(Seq.fill(config.warps)(0.U(32.W))))
  private val reserveWarpValid = io.reserve.bits.warpId < config.warps.U
  private val releaseWarpValid = io.release.bits.warpId < config.warps.U
  private val releaseEnabled = io.release.valid && releaseWarpValid
  private val releaseOH = UIntToOH(io.release.bits.vd, 32)
  private val cancelEnabled =
    io.cancel.valid && io.cancel.bits.warpId < config.warps.U
  private val cancelOH = UIntToOH(io.cancel.bits.vd, 32)
  private val selectedBusy =
    if (config.warps == 1) busy(0)
    else Mux(reserveWarpValid, busy(io.reserve.bits.warpId), 0.U)
  private val sameWarpRelease =
    releaseEnabled && io.release.bits.warpId === io.reserve.bits.warpId
  private val effectiveBusy =
    Mux(sameWarpRelease, selectedBusy & ~releaseOH, selectedBusy) &
      Mux(
        cancelEnabled && io.cancel.bits.warpId === io.reserve.bits.warpId,
        ~cancelOH,
        Fill(32, 1.U)
      )

  io.rawHazard :=
    (io.reserve.bits.useVs1 && effectiveBusy(io.reserve.bits.vs1)) ||
      (io.reserve.bits.useVs2 && effectiveBusy(io.reserve.bits.vs2)) ||
      (io.reserve.bits.readVd && effectiveBusy(io.reserve.bits.vd)) ||
      (io.reserve.bits.useMask && effectiveBusy(0))
  io.wawHazard :=
    io.reserve.bits.writeVd && effectiveBusy(io.reserve.bits.vd)
  io.reserve.ready := reserveWarpValid && !io.rawHazard && !io.wawHazard

  private val reserveEnabled = io.reserve.fire && io.reserve.bits.writeVd
  private val reserveOH = UIntToOH(io.reserve.bits.vd, 32)
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
