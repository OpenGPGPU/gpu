package gpu.core.backend.register

import chisel3._
import chisel3.util.Valid
import gpu.config.GpuConfig

class FpuRegisterRead(config: GpuConfig) extends Bundle {
  val warpId = UInt(config.warpIdWidth.W)
  val rs1 = UInt(5.W)
  val rs2 = UInt(5.W)
  val rs3 = UInt(5.W)
}

class FpuRegisterWrite(config: GpuConfig) extends Bundle {
  val warpId = UInt(config.warpIdWidth.W)
  val rd = UInt(5.W)
  val data = UInt(32.W)
}

/** Per-warp scalar floating-point register file.
  *
  * Unlike the integer RF, f0 is an ordinary writable register. Three
  * combinational read ports are required by fused multiply-add instructions.
  */
class FpuRegisterFile(config: GpuConfig = GpuConfig()) extends Module {
  val io = IO(new Bundle {
    val read = Input(new FpuRegisterRead(config))
    val rs1Data = Output(UInt(32.W))
    val rs2Data = Output(UInt(32.W))
    val rs3Data = Output(UInt(32.W))
    // Dedicated single-register read port for RVV FVF scalar operands.
    val fvfRead = Input(new FpuRegisterRead(config))
    val fvfData = Output(UInt(32.W))
    val write = Flipped(Valid(new FpuRegisterWrite(config)))
  })

  private val registers = RegInit(VecInit(Seq.fill(config.warps)(
    VecInit(Seq.fill(32)(0.U(32.W))))))
  private val readWarpValid = io.read.warpId < config.warps.U
  private val fvfReadWarpValid = io.fvfRead.warpId < config.warps.U
  private val writeEnabled = io.write.valid && io.write.bits.warpId < config.warps.U

  private def stored(warpId: UInt, index: UInt): UInt =
    if (config.warps == 1) {
      registers(0)(index)
    } else {
      registers(warpId)(index)
    }
  private def readWithBypass(
    warpId: UInt,
    warpValid: Bool,
    index: UInt
  ): UInt = {
    val registerData = Mux(warpValid, stored(warpId, index), 0.U)
    Mux(
      writeEnabled && io.write.bits.warpId === warpId &&
        io.write.bits.rd === index,
      io.write.bits.data,
      registerData
    )
  }

  io.rs1Data := readWithBypass(
    io.read.warpId, readWarpValid, io.read.rs1)
  io.rs2Data := readWithBypass(
    io.read.warpId, readWarpValid, io.read.rs2)
  io.rs3Data := readWithBypass(
    io.read.warpId, readWarpValid, io.read.rs3)
  io.fvfData := readWithBypass(
    io.fvfRead.warpId, fvfReadWarpValid, io.fvfRead.rs1)

  when(writeEnabled) {
    registers(io.write.bits.warpId)(io.write.bits.rd) := io.write.bits.data
  }
}
