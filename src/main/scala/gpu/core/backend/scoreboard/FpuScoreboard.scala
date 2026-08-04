package gpu.core.backend.scoreboard

import chisel3._
import chisel3.util._
import gpu.config.GpuConfig

class FpuReservation(config: GpuConfig) extends Bundle {
  val warpId = UInt(config.warpIdWidth.W)
  val rs1 = UInt(5.W)
  val rs2 = UInt(5.W)
  val rs3 = UInt(5.W)
  val useRs1 = Bool()
  val useRs2 = Bool()
  val useRs3 = Bool()
  val rd = UInt(5.W)
  val writeRd = Bool()
}

class FpuRelease(config: GpuConfig) extends Bundle {
  val warpId = UInt(config.warpIdWidth.W)
  val rd = UInt(5.W)
}

/** Three-source per-warp FP scoreboard. All 32 registers, including f0, track. */
class FpuScoreboard(config: GpuConfig = GpuConfig()) extends Module {
  val io = IO(new Bundle {
    val reserve = Flipped(Decoupled(new FpuReservation(config)))
    val release = Flipped(Valid(new FpuRelease(config)))
    val rawHazard = Output(Bool())
    val wawHazard = Output(Bool())
    val busyByWarp = Output(Vec(config.warps, UInt(32.W)))
  })

  private val busy = RegInit(VecInit(Seq.fill(config.warps)(0.U(32.W))))
  private val warpValid = io.reserve.bits.warpId < config.warps.U
  private val releaseEnabled = io.release.valid &&
    io.release.bits.warpId < config.warps.U
  private val selected = if (config.warps == 1) busy(0) else
    Mux(warpValid, busy(io.reserve.bits.warpId), 0.U)
  private val releaseOH = UIntToOH(io.release.bits.rd, 32)
  private val effective = Mux(
    releaseEnabled && io.release.bits.warpId === io.reserve.bits.warpId,
    selected & ~releaseOH,
    selected
  )

  io.rawHazard :=
    (io.reserve.bits.useRs1 && effective(io.reserve.bits.rs1)) ||
      (io.reserve.bits.useRs2 && effective(io.reserve.bits.rs2)) ||
      (io.reserve.bits.useRs3 && effective(io.reserve.bits.rs3))
  io.wawHazard := io.reserve.bits.writeRd && effective(io.reserve.bits.rd)
  io.reserve.ready := warpValid && !io.rawHazard && !io.wawHazard

  private val reserveEnabled = io.reserve.fire && io.reserve.bits.writeRd
  private val reserveOH = UIntToOH(io.reserve.bits.rd, 32)
  for (warp <- 0 until config.warps) {
    val afterRelease = Mux(
      releaseEnabled && io.release.bits.warpId === warp.U,
      busy(warp) & ~releaseOH,
      busy(warp)
    )
    busy(warp) := Mux(
      reserveEnabled && io.reserve.bits.warpId === warp.U,
      afterRelease | reserveOH,
      afterRelease
    )
    io.busyByWarp(warp) := busy(warp)
  }
}
