package opengpu.core.memory

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.graphics.{OmMemoryRequest, OmMemoryResponse, OmWordToLinePort}

/** A graphics word-port client with its own Sv32 translation TLB.
  *
  * Wraps the word-to-line bridge, a `GraphicsAddressTranslator` and the
  * fault/translated response arbiter that the command, framebuffer and texture
  * clients otherwise repeat. The translated line request and its page-walk
  * request are exposed for the caller's shared request arbiter, and the
  * external responses are routed back by transaction-ID range; the client
  * reports a fault when a translated or fault response is consumed.
  */
class TranslatedWordClient(
  config: GpuConfig = GpuConfig(),
  wordTransactions: Int = 4,
  outstanding: Int = 32,
  uncached: Boolean = false,
  preserveCachePolicy: Boolean = false
) extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new OmMemoryRequest))
    val out = Decoupled(new OmMemoryResponse)
    /** Translated line request to the shared arbiter. */
    val memReq = Decoupled(new ComputeMemoryRequest(config, 64, outstanding))
    /** The client's translated response, routed back by ID range. */
    val memResp = Flipped(Decoupled(new ComputeMemoryResponse(64, outstanding)))
    /** PTE read request/response, kept on a reserved transaction range. */
    val pageWalk = Decoupled(new ComputeMemoryRequest(config, 64, outstanding))
    val pageWalkResp = Flipped(Decoupled(
      new ComputeMemoryResponse(64, outstanding)))
    val satp = Input(UInt(32.W))
    val flush = Input(Bool())
    /** A translated or fault response for this client was consumed. */
    val fault = Output(Bool())
  })

  private val bridge = Module(new OmWordToLinePort(
    config, 64, wordTransactions, uncached))
  bridge.io.in.valid := io.in.valid
  bridge.io.in.bits := io.in.bits
  io.in.ready := bridge.io.in.ready
  io.out.valid := bridge.io.out.valid
  io.out.bits := bridge.io.out.bits
  bridge.io.out.ready := io.out.ready

  private val translator = Module(new GraphicsAddressTranslator(
    config, entries = 16, lineBytes = 64, maxOutstanding = outstanding,
    preserveCachePolicy = preserveCachePolicy))
  translator.io.satp := io.satp
  translator.io.flush := io.flush
  translator.io.pageWalkTransactionId := 0.U
  translator.io.in <> bridge.io.memoryRequest

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
  bridge.io.memoryResponse <> responseArb.io.out
  io.fault := responseArb.io.out.fire && responseArb.io.out.bits.fault
}
