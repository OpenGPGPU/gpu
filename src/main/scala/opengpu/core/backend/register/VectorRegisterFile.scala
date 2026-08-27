package opengpu.core.backend.register

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.{Asap7Sram1Rw256x32, Asap7Sram1Rw256x64}

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

/** One warp-local 32 x VLEN vector register bank with three reads and one write.
  *
  * The physical bank mirrors one 1RW macro set per read port plus one macro
  * set for writes. Writes remain visible to the same-cycle read via bypass,
  * preserving the latency contract of the behavioral register file.
  *
  * `predicateMask` is the architectural v0 mask: the low `lanes` bits of v0's
  * flat data (lane 0's word), matching the packed layout mask-producing
  * instructions write back.  It reads register v0 regardless of the
  * instruction's vs1 field — for loads/stores that field encodes the scalar
  * base register rs1, not a vector operand.
  */
class VectorRegisterBank(config: GpuConfig, useBlackBox: Boolean = false)
    extends Module {
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

  private def writeThrough(address: UInt, macroRead: UInt): UInt = {
    val conflict = io.write.valid && io.write.bits.vd === address
    Mux(conflict, io.write.bits.data.asUInt, macroRead)
  }

  if (useBlackBox) {
    val physicalWidth = if (vectorWidth <= 32) 32 else 64
    require(vectorWidth % physicalWidth == 0,
      s"ASAP7 vector RF macro mapping requires a multiple of $physicalWidth bits per register")
    val chunks = vectorWidth / physicalWidth

    def macroSet(readAddress: UInt, hasRead: Boolean): Vec[UInt] = {
      val chunkData = Wire(Vec(chunks, UInt(physicalWidth.W)))
      val selectedAddress = Mux(io.write.valid, io.write.bits.vd, readAddress).pad(8)
      for (chunk <- 0 until chunks) {
        if (physicalWidth == 32) {
          val memory = Module(new Asap7Sram1Rw256x32)
          memory.io.clk := clock
          memory.io.ADDRESS := selectedAddress
          memory.io.wd := io.write.bits.data.asUInt(31, 0)
          memory.io.banksel := hasRead.B | io.write.valid
          memory.io.read := hasRead.B & !io.write.valid
          memory.io.write := io.write.valid
          chunkData(chunk) := memory.io.dataout
        } else {
          val memory = Module(new Asap7Sram1Rw256x64)
          memory.io.clk := clock
          memory.io.ADDRESS := selectedAddress
          memory.io.wd := io.write.bits.data.asUInt
            ((chunk + 1) * 64 - 1, chunk * 64)
          memory.io.banksel := hasRead.B | io.write.valid
          memory.io.read := hasRead.B & !io.write.valid
          memory.io.write := io.write.valid
          chunkData(chunk) := memory.io.dataout
        }
      }
      chunkData
    }

    def readPort(address: UInt, hasRead: Boolean): Vec[UInt] = {
      val macroRead = macroSet(address, hasRead).asUInt
      writeThrough(address, macroRead)
        .asTypeOf(Vec(config.lanes, UInt(config.xLen.W)))
    }

    io.vs1Data := readPort(io.vs1, true)
    io.vs2Data := readPort(io.vs2, true)
    io.oldVdData := readPort(io.vd, true)
    io.predicateMask :=
      readPort(0.U, true).asUInt(config.lanes - 1, 0)
  } else {
    val storage = Mem(32, UInt(vectorWidth.W))
    when(io.write.valid) {
      storage(io.write.bits.vd) := io.write.bits.data.asUInt
    }

    def readPort(address: UInt): Vec[UInt] = {
      val stored = storage(address)
      writeThrough(address, stored)
        .asTypeOf(Vec(config.lanes, UInt(config.xLen.W)))
    }

    io.vs1Data := readPort(io.vs1)
    io.vs2Data := readPort(io.vs2)
    io.oldVdData := readPort(io.vd)
    io.predicateMask := readPort(0.U).asUInt(config.lanes - 1, 0)
  }
}

/** Per-warp vector RF. Unlike the scalar RF, v0 is ordinary writable state. */
class VectorRegisterFile(
  config: GpuConfig = GpuConfig(),
  useBlackBox: Boolean = false
) extends Module {
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
    val bank = Module(new VectorRegisterBank(config, useBlackBox))
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
