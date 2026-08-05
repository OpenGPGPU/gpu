package gpu.core.memory

import chisel3._
import chisel3.util._

/** One of the characterized single-port macros from asap7_sram_0p0. */
class Asap7Sram1Rw256x32 extends BlackBox {
  override def desiredName = "srambank_64x4x32_6t122"
  val io = IO(new Bundle {
    val clk = Input(Clock())
    val ADDRESS = Input(UInt(8.W))
    val wd = Input(UInt(32.W))
    val banksel = Input(Bool())
    val read = Input(Bool())
    val write = Input(Bool())
    val dataout = Output(UInt(32.W))
  })
}

class Asap7Sram1Rw256x64 extends BlackBox {
  override def desiredName = "srambank_64x4x64_6t122"
  val io = IO(new Bundle {
    val clk = Input(Clock())
    val ADDRESS = Input(UInt(8.W))
    val wd = Input(UInt(64.W))
    val banksel = Input(Bool())
    val read = Input(Bool())
    val write = Input(Bool())
    val dataout = Output(UInt(64.W))
  })
}

/** Synchronous single-port L2 array.
  *
  * The physical implementation uses the smallest characterized ASAP7 SRAM
  * (256 words). Narrow tag arrays occupy one 32-bit macro; wider arrays are
  * striped over 64-bit macros sharing control and address. The behavioral
  * model deliberately gives writes priority, matching the supplied macro.
  */
class L2SramArray(
  depth: Int,
  width: Int,
  useBlackBox: Boolean = false
) extends Module {
  require(depth > 1 && isPow2(depth) && depth <= 256)
  require(width > 0 && width <= 512)
  private val addressWidth = log2Ceil(depth)

  val io = IO(new Bundle {
    val readEnable = Input(Bool())
    val readAddress = Input(UInt(addressWidth.W))
    val readData = Output(UInt(width.W))
    val writeEnable = Input(Bool())
    val writeAddress = Input(UInt(addressWidth.W))
    val writeData = Input(UInt(width.W))
  })

  if (useBlackBox) {
    val physicalWidth = if (width <= 32) 32 else 64
    val macroCount = (width + physicalWidth - 1) / physicalWidth
    val paddedWidth = macroCount * physicalWidth
    val selectedAddress = Mux(
      io.writeEnable, io.writeAddress, io.readAddress).pad(8)
    val paddedWriteData = io.writeData.pad(paddedWidth)
    val readChunks = Wire(Vec(macroCount, UInt(physicalWidth.W)))

    for (chunk <- 0 until macroCount) {
      if (physicalWidth == 32) {
        val memory = Module(new Asap7Sram1Rw256x32)
        memory.io.clk := clock
        memory.io.ADDRESS := selectedAddress
        memory.io.wd := paddedWriteData(31, 0)
        memory.io.banksel := io.readEnable || io.writeEnable
        memory.io.read := io.readEnable && !io.writeEnable
        memory.io.write := io.writeEnable
        readChunks(chunk) := memory.io.dataout
      } else {
        val memory = Module(new Asap7Sram1Rw256x64)
        memory.io.clk := clock
        memory.io.ADDRESS := selectedAddress
        memory.io.wd := paddedWriteData(64 * chunk + 63, 64 * chunk)
        memory.io.banksel := io.readEnable || io.writeEnable
        memory.io.read := io.readEnable && !io.writeEnable
        memory.io.write := io.writeEnable
        readChunks(chunk) := memory.io.dataout
      }
    }
    io.readData := readChunks.asUInt(width - 1, 0)
  } else {
    val memory = SyncReadMem(depth, UInt(width.W))
    io.readData := memory.read(
      io.readAddress, io.readEnable && !io.writeEnable)
    when(io.writeEnable) {
      memory.write(io.writeAddress, io.writeData)
    }
  }
}
