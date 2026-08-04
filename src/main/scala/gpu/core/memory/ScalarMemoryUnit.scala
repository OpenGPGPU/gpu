package gpu.core.memory

import chisel3._
import chisel3.util._
import gpu.config.GpuConfig
import gpu.core.backend.issue.ScalarIssuedInstruction
import gpu.core.backend.writeback.ScalarCommitRequest

class ScalarMemoryFault(config: GpuConfig) extends Bundle {
  val warpId = UInt(config.warpIdWidth.W)
  val pc = UInt(config.xLen.W)
  val activeMask = UInt(config.lanes.W)
  val address = UInt(config.xLen.W)
  val isStore = Bool()
  val misaligned = Bool()
  val pageFault = Bool()
  val rd = UInt(5.W)
  val writeRd = Bool()
}

class ScalarMemoryUnit(
  config: GpuConfig = GpuConfig(),
  lineBytes: Int = 64
) extends Module {
  private val offsetWidth = log2Ceil(lineBytes)
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new ScalarIssuedInstruction(config)))
    val cacheRequest = Decoupled(new VectorCacheLineRequest(config, lineBytes))
    val cacheResponse = Flipped(Decoupled(new VectorCacheLineResponse(lineBytes)))
    val commit = Decoupled(new ScalarCommitRequest(config))
    val fault = Decoupled(new ScalarMemoryFault(config))
  })

  private object State extends ChiselEnum {
    val idle, request, response, commit, fault = Value
  }
  private val state = RegInit(State.idle)
  private val instruction = Reg(new ScalarIssuedInstruction(config))
  private val address = Reg(UInt(config.xLen.W))
  private val misaligned = Reg(Bool())
  private val response = Reg(new VectorCacheLineResponse(lineBytes))

  private val funct3 = instruction.decode.instruction(14, 12)
  private val size = funct3(1, 0)
  private val isStore = instruction.decode.decoded.memoryWrite
  private val byteOffset = address(offsetWidth - 1, 0)
  private val shiftedRead = response.readData >> (byteOffset << 3)
  private val byteValue = shiftedRead(7, 0)
  private val halfValue = shiftedRead(15, 0)
  private val loadValue = MuxLookup(funct3, shiftedRead(31, 0))(Seq(
    "b000".U -> Cat(Fill(24, byteValue(7)), byteValue),
    "b001".U -> Cat(Fill(16, halfValue(15)), halfValue),
    "b010".U -> shiftedRead(31, 0),
    "b100".U -> Cat(0.U(24.W), byteValue),
    "b101".U -> Cat(0.U(16.W), halfValue)
  ))
  private val bytes = 1.U << size
  private val baseMask = MuxLookup(size, 1.U(lineBytes.W))(Seq(
    0.U -> 1.U(lineBytes.W),
    1.U -> 3.U(lineBytes.W),
    2.U -> 15.U(lineBytes.W)
  ))

  io.in.ready := state === State.idle
  io.cacheRequest.valid := state === State.request && !misaligned
  io.cacheRequest.bits.warpId := instruction.decode.warpId
  io.cacheRequest.bits.lineAddress :=
    Cat(address(config.xLen - 1, offsetWidth), 0.U(offsetWidth.W))
  io.cacheRequest.bits.byteMask := baseMask << byteOffset
  io.cacheRequest.bits.writeData :=
    (instruction.rs2Data.pad(lineBytes * 8) << (byteOffset << 3))(
      lineBytes * 8 - 1,
      0
    )
  io.cacheRequest.bits.isStore := isStore
  io.cacheResponse.ready := state === State.response

  io.commit.valid := state === State.commit
  io.commit.bits.warpId := instruction.decode.warpId
  io.commit.bits.nextPc := instruction.decode.pc + 4.U
  io.commit.bits.activeMask := instruction.decode.activeMask
  io.commit.bits.writeRd := !isStore
  io.commit.bits.rd := instruction.decode.decoded.rd
  io.commit.bits.data := loadValue

  io.fault.valid := state === State.fault
  io.fault.bits.warpId := instruction.decode.warpId
  io.fault.bits.pc := instruction.decode.pc
  io.fault.bits.activeMask := instruction.decode.activeMask
  io.fault.bits.address := address
  io.fault.bits.isStore := isStore
  io.fault.bits.misaligned := misaligned
  io.fault.bits.pageFault := response.pageFault
  io.fault.bits.rd := instruction.decode.decoded.rd
  io.fault.bits.writeRd := !isStore

  when(io.in.fire) {
    instruction := io.in.bits
    val effectiveAddress = io.in.bits.rs1Data + io.in.bits.decode.decoded.immediate
    address := effectiveAddress
    val requestSize = io.in.bits.decode.instruction(13, 12)
    misaligned := MuxLookup(requestSize, false.B)(Seq(
      1.U -> effectiveAddress(0),
      2.U -> effectiveAddress(1, 0).orR
    ))
    state := State.request
  }
  when(state === State.request) {
    when(misaligned) { state := State.fault }
      .elsewhen(io.cacheRequest.fire) { state := State.response }
  }
  when(io.cacheResponse.fire) {
    response := io.cacheResponse.bits
    state := Mux(io.cacheResponse.bits.fault, State.fault, State.commit)
  }
  when(io.commit.fire || io.fault.fire) { state := State.idle }
}

/** Locks the selected requester until its response returns. */
class SharedCacheLinePort(
  config: GpuConfig = GpuConfig(),
  lineBytes: Int = 64
) extends Module {
  val io = IO(new Bundle {
    val scalarRequest = Flipped(Decoupled(new VectorCacheLineRequest(config, lineBytes)))
    val scalarResponse = Decoupled(new VectorCacheLineResponse(lineBytes))
    val vectorRequest = Flipped(Decoupled(new VectorCacheLineRequest(config, lineBytes)))
    val vectorResponse = Decoupled(new VectorCacheLineResponse(lineBytes))
    val sharedRequest = Decoupled(new VectorCacheLineRequest(config, lineBytes))
    val sharedResponse = Flipped(Decoupled(new VectorCacheLineResponse(lineBytes)))
  })
  private val arbiter = Module(new RRArbiter(new VectorCacheLineRequest(config, lineBytes), 2))
  private val busy = RegInit(false.B)
  private val source = Reg(Bool())
  arbiter.io.in(0) <> io.scalarRequest
  arbiter.io.in(1) <> io.vectorRequest
  io.sharedRequest.valid := arbiter.io.out.valid && !busy
  io.sharedRequest.bits := arbiter.io.out.bits
  arbiter.io.out.ready := io.sharedRequest.ready && !busy
  when(io.sharedRequest.fire) {
    busy := true.B
    source := arbiter.io.chosen
  }
  io.scalarResponse.valid := io.sharedResponse.valid && busy && !source
  io.vectorResponse.valid := io.sharedResponse.valid && busy && source
  io.scalarResponse.bits := io.sharedResponse.bits
  io.vectorResponse.bits := io.sharedResponse.bits
  io.sharedResponse.ready := Mux(source, io.vectorResponse.ready, io.scalarResponse.ready) && busy
  when(io.sharedResponse.fire) { busy := false.B }
}
