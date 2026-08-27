package opengpu.core.backend.issue

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.frontend.decode.FpuDecodeResponse
import opengpu.core.memory.{
  ScalarMemoryFault,
  VectorCacheLineRequest,
  VectorCacheLineResponse
}

class FpuMemoryCommit(config: GpuConfig) extends Bundle {
  val decode = new FpuDecodeResponse(config)
  val loadData = UInt(32.W)
  val isLoad = Bool()
}

/** Scalar FP32 load/store unit for flw/fsw through the shared cache port. */
class FpuMemoryUnit(
  config: GpuConfig = GpuConfig(),
  lineBytes: Int = 64
) extends Module {
  private val offsetWidth = log2Ceil(lineBytes)
  private val lineWidth = lineBytes * 8

  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new FpuIssuedInstruction(config)))
    val cacheRequest = Decoupled(new VectorCacheLineRequest(config, lineBytes))
    val cacheResponse = Flipped(Decoupled(
      new VectorCacheLineResponse(lineBytes)))
    val commit = Decoupled(new FpuMemoryCommit(config))
    val fault = Decoupled(new ScalarMemoryFault(config))
  })

  private object State extends ChiselEnum {
    val idle, request, response, commit, fault = Value
  }
  private val state = RegInit(State.idle)
  private val instruction = Reg(new FpuIssuedInstruction(config))
  private val address = Reg(UInt(config.xLen.W))
  private val misaligned = RegInit(false.B)
  private val response = Reg(new VectorCacheLineResponse(lineBytes))
  private val requestValid = RegInit(false.B)
  private val requestBits = Reg(new VectorCacheLineRequest(config, lineBytes))

  private val isStore = instruction.decode.decoded.memoryWrite
  private val byteOffset = address(offsetWidth - 1, 0)
  private val shiftedRead = response.readData >> (byteOffset << 3)

  io.in.ready := state === State.idle
  io.cacheRequest.valid :=
    state === State.request && !misaligned && requestValid
  io.cacheRequest.bits := requestBits
  io.cacheResponse.ready := state === State.response

  io.commit.valid := state === State.commit
  io.commit.bits.decode := instruction.decode
  io.commit.bits.loadData := shiftedRead(31, 0)
  io.commit.bits.isLoad := !isStore

  io.fault.valid := state === State.fault
  io.fault.bits.warpId := instruction.decode.warpId
  io.fault.bits.pc := instruction.decode.pc
  io.fault.bits.activeMask := instruction.decode.activeMask
  io.fault.bits.address := address
  io.fault.bits.isStore := isStore
  io.fault.bits.misaligned := misaligned
  io.fault.bits.pageFault := response.pageFault
  io.fault.bits.rd := instruction.decode.instruction(11, 7)
  io.fault.bits.writeRd := !isStore

  when(io.in.fire) {
    instruction := io.in.bits
    val immediate = Cat(
      Fill(20, io.in.bits.decode.instruction(31)),
      io.in.bits.decode.instruction(31, 20)
    )
    val effectiveAddress = io.in.bits.scalarRs1Data + immediate
    address := effectiveAddress
    misaligned := effectiveAddress(1, 0).orR
    requestValid := false.B
    state := State.request
  }
  when(state === State.request) {
    when(misaligned) { state := State.fault }
      .elsewhen(!requestValid) {
        requestValid := true.B
        requestBits.warpId := instruction.decode.warpId
        requestBits.lineAddress :=
          Cat(address(config.xLen - 1, offsetWidth), 0.U(offsetWidth.W))
        requestBits.byteMask := 0xf.U(lineBytes.W) << byteOffset
        requestBits.writeData :=
          (instruction.rs2Data.pad(lineWidth) << (byteOffset << 3))(
            lineWidth - 1,
            0
          )
        requestBits.isStore := isStore
      }
      .elsewhen(io.cacheRequest.fire) { state := State.response }
  }
  when(io.cacheResponse.fire) {
    response := io.cacheResponse.bits
    state := Mux(io.cacheResponse.bits.fault, State.fault, State.commit)
  }
  when(io.commit.fire || io.fault.fire) { state := State.idle }
}
