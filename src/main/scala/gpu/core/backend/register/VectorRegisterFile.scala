package gpu.core.backend.register

import chisel3._
import chisel3.util._
import gpu.config.GpuConfig

class VectorRegisterRead(config: GpuConfig) extends Bundle {
  val warpId = UInt(config.warpIdWidth.W)
  val vs1 = UInt(5.W)
  val vs2 = UInt(5.W)
  val vd = UInt(5.W)
}

class VectorRegisterWrite(config: GpuConfig) extends Bundle {
  val warpId = UInt(config.warpIdWidth.W)
  val vd = UInt(5.W)
  val data = Vec(config.lanes, UInt(config.xLen.W))
}

class VectorRegisterBankWrite(config: GpuConfig) extends Bundle {
  val vd = UInt(5.W)
  val data = Vec(config.lanes, UInt(config.xLen.W))
}

/** One warp-local 32 x VLEN vector register bank with three reads and one write. */
class VectorRegisterBank(config: GpuConfig) extends Module {
  private val vectorWidth = config.lanes * config.xLen
  val io = IO(new Bundle {
    val vs1 = Input(UInt(5.W))
    val vs2 = Input(UInt(5.W))
    val vd = Input(UInt(5.W))
    val vs1Data = Output(Vec(config.lanes, UInt(config.xLen.W)))
    val vs2Data = Output(Vec(config.lanes, UInt(config.xLen.W)))
    val oldVdData = Output(Vec(config.lanes, UInt(config.xLen.W)))
    val predicateMask = Output(UInt(config.lanes.W))
    val write = Flipped(Valid(new VectorRegisterBankWrite(config)))
  })

  private val storage = Mem(32, UInt(vectorWidth.W))
  when(io.write.valid) {
    storage(io.write.bits.vd) := io.write.bits.data.asUInt
  }

  private def readPort(address: UInt): Vec[UInt] = {
    val stored = storage(address)
    val bypass = io.write.valid && io.write.bits.vd === address
    Mux(bypass, io.write.bits.data.asUInt, stored)
      .asTypeOf(Vec(config.lanes, UInt(config.xLen.W)))
  }

  io.vs1Data := readPort(io.vs1)
  io.vs2Data := readPort(io.vs2)
  io.oldVdData := readPort(io.vd)
  io.predicateMask := readPort(0.U).asUInt
}

/** Per-warp vector RF. Unlike the scalar RF, v0 is ordinary writable state. */
class VectorRegisterFile(config: GpuConfig = GpuConfig()) extends Module {
  val io = IO(new Bundle {
    val read = Input(new VectorRegisterRead(config))
    val vs1Data = Output(Vec(config.lanes, UInt(config.xLen.W)))
    val vs2Data = Output(Vec(config.lanes, UInt(config.xLen.W)))
    val oldVdData = Output(Vec(config.lanes, UInt(config.xLen.W)))
    val predicateMask = Output(UInt(config.lanes.W))
    val write = Flipped(Valid(new VectorRegisterWrite(config)))
  })

  private val readWarpValid = io.read.warpId < config.warps.U
  private val writeWarpValid = io.write.bits.warpId < config.warps.U
  private val zeroVector =
    0.U.asTypeOf(Vec(config.lanes, UInt(config.xLen.W)))

  private val banks = Seq.tabulate(config.warps) { warp =>
    val bank = Module(new VectorRegisterBank(config))
    bank.io.vs1 := io.read.vs1
    bank.io.vs2 := io.read.vs2
    bank.io.vd := io.read.vd
    bank.io.write.valid :=
      io.write.valid && writeWarpValid && io.write.bits.warpId === warp.U
    bank.io.write.bits.vd := io.write.bits.vd
    bank.io.write.bits.data := io.write.bits.data
    bank
  }

  private def select(values: Seq[Vec[UInt]]): Vec[UInt] = {
    val byWarp = VecInit(values)
    val selected =
      if (config.warps == 1) byWarp(0) else byWarp(io.read.warpId)
    Mux(readWarpValid, selected.asUInt, zeroVector.asUInt)
      .asTypeOf(Vec(config.lanes, UInt(config.xLen.W)))
  }

  io.vs1Data := select(banks.map(_.io.vs1Data))
  io.vs2Data := select(banks.map(_.io.vs2Data))
  io.oldVdData := select(banks.map(_.io.oldVdData))
  private val masksByWarp = VecInit(banks.map(_.io.predicateMask))
  private val selectedMask =
    if (config.warps == 1) masksByWarp(0) else masksByWarp(io.read.warpId)
  io.predicateMask := Mux(readWarpValid, selectedMask, 0.U)
}
