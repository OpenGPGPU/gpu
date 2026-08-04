package gpu.core.memory

import chisel3._
import chisel3.util._
import gpu.config.GpuConfig
import gpu.core.frontend.{InstructionFetchRequest, InstructionFetchResponse}

/** Fully-associative blocking instruction TLB. */
class InstructionTlb(config: GpuConfig = GpuConfig(), entries: Int = 16)
    extends Module {
  require(entries > 0)
  private val vpnWidth = config.xLen - 12
  private val entryWidth = math.max(1, log2Ceil(entries))

  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new InstructionFetchRequest(config)))
    val translationEnabled = Input(Bool())
    val asid = Input(UInt(9.W))
    val flush = Flipped(Valid(new VectorTlbFlush(config)))
    val out = Decoupled(new InstructionFetchResponse(config))
    val physicalRequest = Decoupled(new InstructionFetchRequest(config))
    val physicalResponse = Flipped(Decoupled(new InstructionFetchResponse(config)))
    val pageWalkRequest = Decoupled(new VectorPageWalkRequest(config))
    val pageWalkResponse = Flipped(Decoupled(new VectorPageWalkResponse(config)))
  })

  private val valid = RegInit(VecInit(Seq.fill(entries)(false.B)))
  private val vpn = Reg(Vec(entries, UInt(vpnWidth.W)))
  private val ppn = Reg(Vec(entries, UInt(vpnWidth.W)))
  private val executable = Reg(Vec(entries, Bool()))
  private val entryAsid = Reg(Vec(entries, UInt(9.W)))
  private val global = Reg(Vec(entries, Bool()))
  private val replacement = RegInit(0.U(entryWidth.W))
  private val request = Reg(new InstructionFetchRequest(config))
  private val physicalPc = Reg(UInt(config.xLen.W))
  private val fault = Reg(new InstructionFetchResponse(config))

  private object State extends ChiselEnum {
    val idle, lookup, walkRequest, walkResponse, forward, memoryResponse,
      faultResponse = Value
  }
  private val state = RegInit(State.idle)
  private val requestVpn = request.pc(config.xLen - 1, 12)
  private val hitByEntry = VecInit((0 until entries).map { entry =>
    valid(entry) && vpn(entry) === requestVpn &&
      (global(entry) || entryAsid(entry) === io.asid)
  })
  private val hit = hitByEntry.asUInt.orR
  private val hitPpn = Mux1H(hitByEntry, ppn)
  private val hitExecutable = Mux1H(hitByEntry, executable)

  io.in.ready := state === State.idle
  io.out.valid := state === State.faultResponse ||
    (state === State.memoryResponse && io.physicalResponse.valid)
  io.out.bits := Mux(state === State.faultResponse, fault, io.physicalResponse.bits)
  io.physicalRequest.valid := state === State.forward
  io.physicalRequest.bits.warpId := request.warpId
  io.physicalRequest.bits.pc := physicalPc
  io.physicalResponse.ready := state === State.memoryResponse && io.out.ready
  io.pageWalkRequest.valid := state === State.walkRequest
  io.pageWalkRequest.bits.virtualPageNumber := requestVpn
  io.pageWalkRequest.bits.isStore := false.B
  io.pageWalkRequest.bits.isInstruction := true.B
  io.pageWalkResponse.ready := state === State.walkResponse

  when(io.in.fire) {
    request := io.in.bits
    state := State.lookup
  }

  when(state === State.lookup) {
    when(!io.translationEnabled) {
      physicalPc := request.pc
      state := State.forward
    }.elsewhen(hit && hitExecutable) {
      physicalPc := Cat(hitPpn, request.pc(11, 0))
      state := State.forward
    }.elsewhen(hit) {
      fault.warpId := request.warpId
      fault.instruction := 0.U
      fault.accessFault := true.B
      state := State.faultResponse
    }.otherwise {
      state := State.walkRequest
    }
  }

  when(io.pageWalkRequest.fire) { state := State.walkResponse }
  when(io.pageWalkResponse.fire) {
    when(io.pageWalkResponse.bits.fault || !io.pageWalkResponse.bits.executable) {
      fault.warpId := request.warpId
      fault.instruction := 0.U
      fault.accessFault := true.B
      state := State.faultResponse
    }.otherwise {
      valid(replacement) := true.B
      vpn(replacement) := requestVpn
      ppn(replacement) := io.pageWalkResponse.bits.physicalPageNumber
      executable(replacement) := io.pageWalkResponse.bits.executable
      entryAsid(replacement) := io.asid
      global(replacement) := io.pageWalkResponse.bits.global
      replacement := Mux(replacement === (entries - 1).U, 0.U, replacement + 1.U)
      physicalPc := Cat(io.pageWalkResponse.bits.physicalPageNumber, request.pc(11, 0))
      state := State.forward
    }
  }

  when(io.physicalRequest.fire) { state := State.memoryResponse }
  when(state === State.memoryResponse && io.physicalResponse.fire) { state := State.idle }
  when(state === State.faultResponse && io.out.fire) { state := State.idle }

  when(io.flush.valid) {
    assert(state === State.idle, "ITLB flush requires an idle translation port")
    for (entry <- 0 until entries) {
      val vpnMatches = !io.flush.bits.virtualPageNumberValid ||
        vpn(entry) === io.flush.bits.virtualPageNumber
      val asidMatches = !io.flush.bits.asidValid ||
        (!global(entry) && entryAsid(entry) === io.flush.bits.asid)
      when(vpnMatches && asidMatches) { valid(entry) := false.B }
    }
  }
}
