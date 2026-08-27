package opengpu.core.backend.register

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig

class ScalarRegisterRead(config: GpuConfig) extends Bundle {
  val warpId = UInt(config.warpIdWidth.W)
  val rs1 = UInt(5.W)
  val rs2 = UInt(5.W)
}

class ScalarRegisterWrite(config: GpuConfig) extends Bundle {
  val warpId = UInt(config.warpIdWidth.W)
  val rd = UInt(5.W)
  val data = UInt(config.xLen.W)
}

class RegisterBankWrite(config: GpuConfig) extends Bundle {
  val rd = UInt(5.W)
  val data = UInt(config.xLen.W)
}

/** One physical warp-local 32 x XLEN, 2R1W register-file bank. */
class ScalarRegisterBank(config: GpuConfig) extends Module {
  val io = IO(new Bundle {
    val rs1 = Input(UInt(5.W))
    val rs2 = Input(UInt(5.W))
    val rs1Data = Output(UInt(config.xLen.W))
    val rs2Data = Output(UInt(config.xLen.W))
    val write = Flipped(Valid(new RegisterBankWrite(config)))
  })

  private val storage = Mem(32, UInt(config.xLen.W))
  private val writeEnabled = io.write.valid && io.write.bits.rd =/= 0.U

  when(writeEnabled) {
    storage(io.write.bits.rd) := io.write.bits.data
  }

  private def readPort(register: UInt): UInt = {
    val bypass = writeEnabled && io.write.bits.rd === register
    Mux(
      register === 0.U,
      0.U,
      Mux(bypass, io.write.bits.data, storage(register))
    )
  }

  io.rs1Data := readPort(io.rs1)
  io.rs2Data := readPort(io.rs2)
}

/** Two-read/one-write scalar RF built from independent per-warp banks.
  *
  * Each hardware warp owns a distinct 32 x XLEN bank. Only the selected bank
  * receives write enable; read results are selected after the bank-local
  * 32-entry read mux. Same-cycle read/write is explicitly write-first.
  */
class ScalarRegisterFile(config: GpuConfig = GpuConfig()) extends Module {
  val io = IO(new Bundle {
    val read = Input(new ScalarRegisterRead(config))
    val rs1Data = Output(UInt(config.xLen.W))
    val rs2Data = Output(UInt(config.xLen.W))
    val write = Flipped(Valid(new ScalarRegisterWrite(config)))
  })

  private val readWarpValid = io.read.warpId < config.warps.U
  private val writeWarpValid = io.write.bits.warpId < config.warps.U

  private val banks = Seq.tabulate(config.warps) { warp =>
    val bank = Module(new ScalarRegisterBank(config))
    bank.io.rs1 := io.read.rs1
    bank.io.rs2 := io.read.rs2
    bank.io.write.valid :=
      io.write.valid && writeWarpValid && io.write.bits.warpId === warp.U
    bank.io.write.bits.rd := io.write.bits.rd
    bank.io.write.bits.data := io.write.bits.data
    bank
  }

  private val rs1ByWarp = VecInit(banks.map(_.io.rs1Data))
  private val rs2ByWarp = VecInit(banks.map(_.io.rs2Data))
  private val selectedRs1 =
    if (config.warps == 1) rs1ByWarp(0) else rs1ByWarp(io.read.warpId)
  private val selectedRs2 =
    if (config.warps == 1) rs2ByWarp(0) else rs2ByWarp(io.read.warpId)

  io.rs1Data := Mux(readWarpValid, selectedRs1, 0.U)
  io.rs2Data := Mux(readWarpValid, selectedRs2, 0.U)
}
