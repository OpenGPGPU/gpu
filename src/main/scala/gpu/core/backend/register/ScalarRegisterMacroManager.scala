package gpu.core.backend.register

import chisel3._
import chisel3.util._
import gpu.config.GpuConfig
import gpu.core.backend.scoreboard.{
  RegisterScoreboardState,
  RegisterReservation,
  RegisterRelease
}

/** FakeRAM2.0-compatible 32 x 32, synchronous 1RW macro interface. */
class FakeRam7OneRw32x32 extends BlackBox {
  override def desiredName = "fakeram7_1rw_32x32"

  val io = IO(new Bundle {
    val rd_out = Output(UInt(32.W))
    val addr_in = Input(UInt(5.W))
    val we_in = Input(Bool())
    val wd_in = Input(UInt(32.W))
    val clk = Input(Clock())
    val ce_in = Input(Bool())
  })
}

/** Per-warp synchronous 2R1W RF made from two mirrored 1RW arrays.
  *
  * A write occupies both copies for one cycle. Otherwise each copy serves one
  * read port. `useBlackBoxes=false` is the cycle-accurate simulation model;
  * ASIC timing emission selects the FakeRAM black boxes.
  */
class ScalarRegisterMacroFile(
  config: GpuConfig,
  useBlackBoxes: Boolean = false
) extends Module {
  require(config.xLen == 32, "FakeRAM RF macro is fixed at XLEN=32")

  val io = IO(new Bundle {
    val read = Flipped(Valid(new ScalarRegisterRead(config)))
    val responseWarpId = Input(UInt(config.warpIdWidth.W))
    val responseRs1 = Input(UInt(5.W))
    val responseRs2 = Input(UInt(5.W))
    val rs1Data = Output(UInt(32.W))
    val rs2Data = Output(UInt(32.W))
    val write = Flipped(Valid(new ScalarRegisterWrite(config)))
  })

  val rs1ByWarp = Wire(Vec(config.warps, UInt(32.W)))
  val rs2ByWarp = Wire(Vec(config.warps, UInt(32.W)))

  for (warp <- 0 until config.warps) {
    val selectedRead = io.read.valid && io.read.bits.warpId === warp.U
    val selectedWrite =
      io.write.valid && io.write.bits.rd =/= 0.U &&
        io.write.bits.warpId === warp.U

    if (useBlackBoxes) {
      val rs1Macro = Module(new FakeRam7OneRw32x32)
      val rs2Macro = Module(new FakeRam7OneRw32x32)
      for ((macroPort, address) <- Seq(
        (rs1Macro.io, io.read.bits.rs1),
        (rs2Macro.io, io.read.bits.rs2)
      )) {
        macroPort.clk := clock
        macroPort.ce_in := selectedRead || selectedWrite
        macroPort.we_in := selectedWrite
        macroPort.addr_in := Mux(selectedWrite, io.write.bits.rd, address)
        macroPort.wd_in := io.write.bits.data
      }
      rs1ByWarp(warp) := rs1Macro.io.rd_out
      rs2ByWarp(warp) := rs2Macro.io.rd_out
    } else {
      val rs1Memory = SyncReadMem(32, UInt(32.W))
      val rs2Memory = SyncReadMem(32, UInt(32.W))
      when(selectedWrite) {
        rs1Memory.write(io.write.bits.rd, io.write.bits.data)
        rs2Memory.write(io.write.bits.rd, io.write.bits.data)
      }
      rs1ByWarp(warp) :=
        rs1Memory.read(io.read.bits.rs1, selectedRead && !selectedWrite)
      rs2ByWarp(warp) :=
        rs2Memory.read(io.read.bits.rs2, selectedRead && !selectedWrite)
    }
  }

  val responseWarpValid = io.responseWarpId < config.warps.U
  val selectedRs1 =
    if (config.warps == 1) rs1ByWarp(0)
    else rs1ByWarp(io.responseWarpId)
  val selectedRs2 =
    if (config.warps == 1) rs2ByWarp(0)
    else rs2ByWarp(io.responseWarpId)

  io.rs1Data :=
    Mux(responseWarpValid && io.responseRs1 =/= 0.U, selectedRs1, 0.U)
  io.rs2Data :=
    Mux(responseWarpValid && io.responseRs2 =/= 0.U, selectedRs2, 0.U)
}

/** Three-stage manager for a synchronous, per-warp RF macro.
  *
  * request -> macro address/scoreboard reservation -> issue output.
  */
class ScalarRegisterMacroManager(
  config: GpuConfig = GpuConfig(),
  useBlackBoxes: Boolean = false
) extends Module {
  val io = IO(new Bundle {
    val request = Flipped(Decoupled(new RegisterReservation(config)))
    val issue = Decoupled(new ScalarIssueOperands(config))
    val writeback = Flipped(Valid(new ScalarRegisterWrite(config)))
    val cancel = Flipped(Valid(new RegisterRelease(config)))
    val rawHazard = Output(Bool())
    val wawHazard = Output(Bool())
  })

  val registerFile =
    Module(new ScalarRegisterMacroFile(config, useBlackBoxes))
  val scoreboard = Module(new RegisterScoreboardState(config))

  val requestValid = RegInit(false.B)
  val requestBits = Reg(new RegisterReservation(config))
  val requestBusy = Reg(UInt(32.W))
  val requestForwardValid = RegInit(false.B)
  val requestForwardRd = Reg(UInt(5.W))
  val readValid = RegInit(false.B)
  val readBits = Reg(new RegisterReservation(config))
  val issueValid = RegInit(false.B)
  val issueBits = Reg(new ScalarIssueOperands(config))
  val writebackValid = RegNext(io.writeback.valid, false.B)
  val writebackBits = RegEnable(io.writeback.bits, io.writeback.valid)

  val canLoadIssue = !issueValid || io.issue.ready
  val moveRead = readValid && canLoadIssue
  val requestWarpValid = requestBits.warpId < config.warps.U
  val requestEffectiveBusy = Mux(
    requestForwardValid,
    requestBusy | UIntToOH(requestForwardRd, 32),
    requestBusy
  )
  val rawHazard =
    (requestBits.useRs1 && requestEffectiveBusy(requestBits.rs1)) ||
      (requestBits.useRs2 && requestEffectiveBusy(requestBits.rs2))
  val wawHazard =
    requestBits.writeRd && requestEffectiveBusy(requestBits.rd)
  val writeConflict =
    writebackValid && writebackBits.rd =/= 0.U &&
      writebackBits.warpId === requestBits.warpId
  val canLaunchRead = !readValid || moveRead
  val launchRead =
    requestValid && requestWarpValid && canLaunchRead && !writeConflict &&
      !rawHazard && !wawHazard

  scoreboard.io.reserve.valid := launchRead
  scoreboard.io.reserve.bits := requestBits
  scoreboard.io.release.valid := writebackValid
  scoreboard.io.release.bits.warpId := writebackBits.warpId
  scoreboard.io.release.bits.rd := writebackBits.rd
  scoreboard.io.cancel := io.cancel

  private def selectedBusy(warpId: UInt): UInt =
    if (config.warps == 1) scoreboard.io.busyByWarp(0)
    else Mux(warpId < config.warps.U, scoreboard.io.busyByWarp(warpId), 0.U)

  private def busyAfterRelease(warpId: UInt): UInt = {
    val current = selectedBusy(warpId)
    val releaseThisWarp =
      writebackValid && writebackBits.rd =/= 0.U &&
        writebackBits.warpId === warpId
    val released = Mux(
      releaseThisWarp,
      current & ~UIntToOH(writebackBits.rd, 32),
      current
    )
    Mux(
      io.cancel.valid && io.cancel.bits.rd =/= 0.U &&
        io.cancel.bits.warpId === warpId,
      released & ~UIntToOH(io.cancel.bits.rd, 32),
      released
    )
  }

  registerFile.io.read.valid := launchRead
  registerFile.io.read.bits.warpId := requestBits.warpId
  registerFile.io.read.bits.rs1 := requestBits.rs1
  registerFile.io.read.bits.rs2 := requestBits.rs2
  registerFile.io.responseWarpId := readBits.warpId
  registerFile.io.responseRs1 := readBits.rs1
  registerFile.io.responseRs2 := readBits.rs2
  registerFile.io.write.valid := writebackValid
  registerFile.io.write.bits := writebackBits

  io.request.ready := !requestValid || launchRead
  io.issue.valid := issueValid
  io.issue.bits := issueBits

  when(io.request.fire) {
    requestValid := true.B
    requestBits := io.request.bits
    requestBusy := busyAfterRelease(io.request.bits.warpId)
    requestForwardValid :=
      requestValid && requestBits.writeRd && requestBits.rd =/= 0.U &&
        requestBits.warpId === io.request.bits.warpId
    requestForwardRd := requestBits.rd
  }.elsewhen(launchRead) {
    requestValid := false.B
    requestForwardValid := false.B
  }.elsewhen(requestValid) {
    requestBusy := busyAfterRelease(requestBits.warpId)
    requestForwardValid := false.B
  }

  when(launchRead) {
    readValid := true.B
    readBits := requestBits
  }.elsewhen(moveRead) {
    readValid := false.B
  }

  when(canLoadIssue) {
    issueValid := readValid
    when(readValid) {
      issueBits.request := readBits
      issueBits.rs1Data := registerFile.io.rs1Data
      issueBits.rs2Data := registerFile.io.rs2Data
    }
  }

  io.rawHazard := requestValid && rawHazard
  io.wawHazard := requestValid && wawHazard

  assert(!scoreboard.io.busyByWarp.map(_(0)).reduce(_ || _))
}
