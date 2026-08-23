package gpu.core.memory

import chisel3._
import chisel3.util._
import gpu.config.GpuConfig

class VectorMemoryExecuteRequest(config: GpuConfig) extends Bundle {
  val warpId = UInt(config.warpIdWidth.W)
  val pc = UInt(config.xLen.W)
  val warpActiveMask = UInt(config.lanes.W)
  val activeMask = UInt(config.lanes.W)
  val vd = UInt(5.W)
  val baseAddress = UInt(config.xLen.W)
  val storeData = Vec(config.lanes, UInt(config.xLen.W))
  val oldVd = Vec(config.lanes, UInt(config.xLen.W))
  val elementSize = UInt(2.W)
  val isStore = Bool()
}

/** Cache/coalescer-facing transaction. Each lane represents one architectural
  * element; inactive lanes must not cause a memory access.
  */
class VectorMemoryTransaction(config: GpuConfig) extends Bundle {
  val warpId = UInt(config.warpIdWidth.W)
  val addresses = Vec(config.lanes, UInt(config.xLen.W))
  val writeData = Vec(config.lanes, UInt(config.xLen.W))
  val laneMask = UInt(config.lanes.W)
  val elementSize = UInt(2.W)
  val isStore = Bool()
}

class VectorMemoryResponse(config: GpuConfig) extends Bundle {
  val readData = Vec(config.lanes, UInt(config.xLen.W))
  val faultMask = UInt(config.lanes.W)
  val pageFault = Bool()
}

class VectorMemoryResult(config: GpuConfig) extends Bundle {
  val warpId = UInt(config.warpIdWidth.W)
  val pc = UInt(config.xLen.W)
  val warpActiveMask = UInt(config.lanes.W)
  val vd = UInt(5.W)
  val data = Vec(config.lanes, UInt(config.xLen.W))
  val writesVd = Bool()
  val faultMask = UInt(config.lanes.W)
  val pageFault = Bool()
  val isStore = Bool()
  val addresses = Vec(config.lanes, UInt(config.xLen.W))
}

class VectorMemoryFault(config: GpuConfig) extends Bundle {
  val warpId = UInt(config.warpIdWidth.W)
  val pc = UInt(config.xLen.W)
  val warpActiveMask = UInt(config.lanes.W)
  val faultMask = UInt(config.lanes.W)
  val pageFault = Bool()
  val isStore = Bool()
  val addresses = Vec(config.lanes, UInt(config.xLen.W))
  val vd = UInt(5.W)
  val writeVd = Bool()
}

/** Single-outstanding vector memory execution boundary.
  *
  * Address generation is parallel across lanes. The external cache/coalescer
  * owns banking, replay, and physical memory timing; architectural completion
  * occurs only after its response.
  */
class VectorMemoryUnit(config: GpuConfig = GpuConfig()) extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new VectorMemoryExecuteRequest(config)))
    val memoryRequest = Decoupled(new VectorMemoryTransaction(config))
    val memoryResponse = Flipped(Decoupled(new VectorMemoryResponse(config)))
    val out = Decoupled(new VectorMemoryResult(config))
  })

  private val requestValid = RegInit(false.B)
  private val requestBits = Reg(new VectorMemoryExecuteRequest(config))
  private val issued = RegInit(false.B)
  private val responsePending = RegInit(false.B)
  private val responseBits = Reg(new VectorMemoryResponse(config))
  private val outputValid = RegInit(false.B)
  private val outputBits = Reg(new VectorMemoryResult(config))
  private val precomputedAddresses =
    Reg(Vec(config.lanes, UInt(config.xLen.W)))

  io.in.ready := !requestValid && !outputValid
  when(io.in.fire) {
    requestValid := true.B
    requestBits := io.in.bits
    issued := false.B
    for (lane <- 0 until config.lanes) {
      precomputedAddresses(lane) :=
        io.in.bits.baseAddress + (lane.U << io.in.bits.elementSize)
    }
  }

  io.memoryRequest.valid := requestValid && !issued
  io.memoryRequest.bits.warpId := requestBits.warpId
  io.memoryRequest.bits.laneMask := requestBits.activeMask
  io.memoryRequest.bits.elementSize := requestBits.elementSize
  io.memoryRequest.bits.isStore := requestBits.isStore
  for (lane <- 0 until config.lanes) {
    io.memoryRequest.bits.addresses(lane) :=
      requestBits.baseAddress + (lane.U << requestBits.elementSize)
    io.memoryRequest.bits.writeData(lane) := requestBits.storeData(lane)
  }
  when(io.memoryRequest.fire) {
    issued := true.B
  }

  io.memoryResponse.ready := requestValid && issued && !outputValid && !responsePending
  when(io.memoryResponse.fire) {
    responsePending := true.B
    responseBits := io.memoryResponse.bits
  }

  when(responsePending) {
    responsePending := false.B
    requestValid := false.B
    issued := false.B
    outputValid := true.B
    outputBits.warpId := requestBits.warpId
    outputBits.pc := requestBits.pc
    outputBits.warpActiveMask := requestBits.warpActiveMask
    outputBits.vd := requestBits.vd
    outputBits.writesVd := !requestBits.isStore
    outputBits.faultMask :=
      responseBits.faultMask & requestBits.activeMask
    outputBits.pageFault := responseBits.pageFault
    outputBits.isStore := requestBits.isStore
    outputBits.addresses := precomputedAddresses
    for (lane <- 0 until config.lanes) {
      val loaded = MuxLookup(
        requestBits.elementSize,
        responseBits.readData(lane)
      )(
        Seq(
          0.U -> responseBits.readData(lane)(7, 0),
          1.U -> responseBits.readData(lane)(15, 0),
          2.U -> responseBits.readData(lane)
        )
      )
      outputBits.data(lane) :=
        Mux(requestBits.activeMask(lane), loaded, requestBits.oldVd(lane))
    }
  }

  io.out.valid := outputValid
  io.out.bits := outputBits
  when(io.out.fire) {
    outputValid := false.B
  }
}
