package opengpu.core.memory

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig

/** Sv32 translation for an already-line graphics client.
  *
  * Same contract as `TranslatedWordClient`, but the caller has already packed
  * words into cache-line requests (kernarg/vertex staging, DMA-adjacent ports).
  * Faults complete locally; PTE walks use a reserved transaction-ID range.
  */
class TranslatedLineClient(
  config: GpuConfig = GpuConfig(),
  outstanding: Int = 32,
  preserveCachePolicy: Boolean = false
) extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(
      new ComputeMemoryRequest(config, 64, outstanding)))
    val out = Decoupled(new ComputeMemoryResponse(64, outstanding))
    val memReq = Decoupled(new ComputeMemoryRequest(config, 64, outstanding))
    val memResp = Flipped(Decoupled(new ComputeMemoryResponse(64, outstanding)))
    val pageWalk = Decoupled(new ComputeMemoryRequest(config, 64, outstanding))
    val pageWalkResp = Flipped(Decoupled(
      new ComputeMemoryResponse(64, outstanding)))
    val satp = Input(UInt(32.W))
    val flush = Flipped(Valid(new VectorTlbFlush(config)))
    val fault = Output(Bool())
    val translationStall = Output(Bool())
    val translationMiss = Output(Bool())
    val walkActive = Output(Bool())
  })

  private val translator = Module(new GraphicsAddressTranslator(
    config, entries = 16, lineBytes = 64, maxOutstanding = outstanding,
    preserveCachePolicy = preserveCachePolicy))
  translator.io.satp := io.satp
  translator.io.flush := io.flush
  translator.io.pageWalkTransactionId := 0.U
  translator.io.in <> io.in
  io.translationStall := translator.io.in.valid && !translator.io.in.ready
  io.translationMiss := translator.io.miss
  io.walkActive := translator.io.walkActive

  io.memReq.valid := translator.io.out.valid
  io.memReq.bits := translator.io.out.bits
  translator.io.out.ready := io.memReq.ready
  io.pageWalk.valid := translator.io.pageWalk.valid
  io.pageWalk.bits := translator.io.pageWalk.bits
  translator.io.pageWalk.ready := io.pageWalk.ready
  translator.io.pageWalkResp.valid := io.pageWalkResp.valid
  translator.io.pageWalkResp.bits := io.pageWalkResp.bits
  io.pageWalkResp.ready := translator.io.pageWalkResp.ready

  private val responseArb = Module(new RRArbiter(
    new ComputeMemoryResponse(64, outstanding), 2))
  responseArb.io.in(0) <> translator.io.faultResponse
  responseArb.io.in(1).valid := io.memResp.valid
  responseArb.io.in(1).bits := io.memResp.bits
  io.memResp.ready := responseArb.io.in(1).ready
  io.out <> responseArb.io.out
  io.fault := io.out.fire && io.out.bits.fault
}
