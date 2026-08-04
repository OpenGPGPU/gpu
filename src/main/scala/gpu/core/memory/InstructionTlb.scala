package gpu.core.memory

import chisel3._
import chisel3.util._
import gpu.config.GpuConfig
import gpu.core.frontend.{InstructionFetchRequest, InstructionFetchResponse}

/** Fully-associative instruction TLB with one page-walk miss slot.
  *
  * Cached translations and Bare requests may continue to reach the ICache
  * while a different virtual page is being walked.  A completed walk is held
  * in a replay register, so downstream backpressure cannot lose the request.
  */
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

  private val inputVpn = io.in.bits.pc(config.xLen - 1, 12)
  private val hitByEntry = VecInit((0 until entries).map { entry =>
    valid(entry) && vpn(entry) === inputVpn &&
      (global(entry) || entryAsid(entry) === io.asid)
  })
  private val inputHit = hitByEntry.asUInt.orR
  private val inputPpn = Mux1H(hitByEntry, ppn)
  private val inputExecutable = Mux1H(hitByEntry, executable)
  private val inputCanForward = !io.translationEnabled ||
    (inputHit && inputExecutable)
  private val inputFault = io.translationEnabled && inputHit && !inputExecutable
  private val inputMiss = io.translationEnabled && !inputHit

  private val missValid = RegInit(false.B)
  private val missWalkIssued = RegInit(false.B)
  private val missRequest = Reg(new InstructionFetchRequest(config))
  private val missAsid = Reg(UInt(9.W))
  private val missVpn = missRequest.pc(config.xLen - 1, 12)

  private val replayValid = RegInit(false.B)
  private val replayRequest = Reg(new InstructionFetchRequest(config))
  private val faultValid = RegInit(false.B)
  private val fault = Reg(new InstructionFetchResponse(config))

  // A walked request has priority so a stream of hits cannot starve it.
  io.physicalRequest.valid := replayValid || (io.in.valid && inputCanForward)
  io.physicalRequest.bits := Mux(replayValid, replayRequest, io.in.bits)
  when(!replayValid && io.translationEnabled) {
    io.physicalRequest.bits.pc := Cat(inputPpn, io.in.bits.pc(11, 0))
  }

  private val faultSlotAvailable = !faultValid ||
    (io.out.ready && faultValid)
  io.in.ready := !io.flush.valid && MuxCase(false.B, Seq(
    inputCanForward -> (!replayValid && io.physicalRequest.ready),
    inputFault -> faultSlotAvailable,
    inputMiss -> !missValid
  ))

  io.out.valid := faultValid || io.physicalResponse.valid
  io.out.bits := Mux(faultValid, fault, io.physicalResponse.bits)
  io.physicalResponse.ready := !faultValid && io.out.ready

  io.pageWalkRequest.valid := missValid && !missWalkIssued
  io.pageWalkRequest.bits.virtualPageNumber := missVpn
  io.pageWalkRequest.bits.isStore := false.B
  io.pageWalkRequest.bits.isInstruction := true.B
  io.pageWalkResponse.ready := missValid && missWalkIssued &&
    !replayValid && faultSlotAvailable

  when(io.in.fire && inputMiss) {
    missValid := true.B
    missWalkIssued := false.B
    missRequest := io.in.bits
    missAsid := io.asid
  }

  when(io.in.fire && inputFault) {
    faultValid := true.B
    fault.warpId := io.in.bits.warpId
    fault.instruction := 0.U
    fault.accessFault := true.B
  }.elsewhen(faultValid && io.out.ready) {
    faultValid := false.B
  }

  when(io.pageWalkRequest.fire) {
    missWalkIssued := true.B
  }

  when(io.pageWalkResponse.fire) {
    missValid := false.B
    missWalkIssued := false.B
    when(io.pageWalkResponse.bits.fault ||
      !io.pageWalkResponse.bits.executable) {
      faultValid := true.B
      fault.warpId := missRequest.warpId
      fault.instruction := 0.U
      fault.accessFault := true.B
    }.otherwise {
      valid(replacement) := true.B
      vpn(replacement) := missVpn
      ppn(replacement) := io.pageWalkResponse.bits.physicalPageNumber
      executable(replacement) := io.pageWalkResponse.bits.executable
      entryAsid(replacement) := missAsid
      global(replacement) := io.pageWalkResponse.bits.global
      replacement := Mux(replacement === (entries - 1).U,
        0.U, replacement + 1.U)
      replayValid := true.B
      replayRequest.warpId := missRequest.warpId
      replayRequest.pc := Cat(io.pageWalkResponse.bits.physicalPageNumber,
        missRequest.pc(11, 0))
    }
  }

  when(replayValid && io.physicalRequest.ready) {
    replayValid := false.B
  }

  when(io.flush.valid) {
    assert(!missValid && !replayValid && !faultValid,
      "ITLB flush requires no pending translation")
    for (entry <- 0 until entries) {
      val vpnMatches = !io.flush.bits.virtualPageNumberValid ||
        vpn(entry) === io.flush.bits.virtualPageNumber
      val asidMatches = !io.flush.bits.asidValid ||
        (!global(entry) && entryAsid(entry) === io.flush.bits.asid)
      when(vpnMatches && asidMatches) { valid(entry) := false.B }
    }
  }
}
