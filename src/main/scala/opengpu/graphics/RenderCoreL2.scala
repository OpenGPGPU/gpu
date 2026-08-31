package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.{
  ComputeMemoryRequest,
  ComputeMemoryResponse,
  L2PerformanceCounters,
  SharedL2Cache
}

/** Top-level renderer with one shared off-chip memory port.
  *
  * Composes `RenderCore` with a `SharedL2Cache` so that the command-buffer,
  * framebuffer, and the core-backed shader kernel (program fetch / kernarg /
  * output write) all arbitrate onto ONE physical memory port.  This is the
  * integrated-SoC morphology: the graphics fixed-function stages and the SIMT
  * shader kernel are separate engines that share a single coherent L2, and the
  * host driver treats the command ring, render targets, and kernarg regions as
  * ordinary software-allocated memory in that shared region.
  *
  * Four line-level clients are multiplexed onto the L2's single request port,
  * each owning a disjoint slice of the L2 transaction-ID space (so the L2's
  * per-requester sharer tracking and response-waiter vector never alias):
  *   index 0  command-buffer word bridge      txns [0,          n)
  *   index 1  framebuffer word bridge         txns [n,         2n)
  *   index 2  shader compute-unit line port   txns [2n,        3n)
  *   index 3  kernarg word->line bridge       txns [3n,        4n)
  * where `n = perClientOutstanding`.  Only the compute-unit data traffic is a
  * cache client (`cacheClient`); the word bridges bypass L1 coherence and the
  * L2 invalidates the shader unit's L1 before accepting an external write to a
  * line it shares.  The L2's per-CU invalidate/atomic ports are therefore
  * connected to the shader unit's coherency ports.
  *
  * Exposes the original `RenderCore` control knobs plus a single
  * `memoryRequest`/`memoryResponse` line port to the off-chip hierarchy and the
  * L2 performance counters.
  */
class RenderCoreL2(
  gfxConfig: GraphicsConfig = GraphicsConfig(),
  gpuConfig: GpuConfig = GpuConfig(),
  fragCore: Boolean = false,
  lineBytes: Int = 64,
  sets: Int = 8,
  ways: Int = 2,
  banks: Int = 2,
  requestQueueDepth: Int = 2,
  useSramBlackBoxes: Boolean = false
) extends Module {
  require(lineBytes == 64, "current cache hierarchy uses 64-byte lines")
  private val perClientOutstanding = 4
  private val numClients = 5
  private val totalOutstanding = perClientOutstanding * numClients
  private val txnWidth = math.max(1, log2Ceil(totalOutstanding))
  // Each of the four line clients occupies its own L2 requester slot
  // (`transactionsPerCu` divides a transaction ID into a CU index).  The shader
  // compute unit is slot 0 (the only private-L1 holder); the word bridges are
  // slots 1..3 after the compute unit's range, so when a bridge writes a line
  // the compute unit has cached, the L2 sees a foreign requester and invalidates
  // the shader unit's L1 copy (real coherence rather than a silent share).
  private val cuSlots = numClients

  val io = IO(new Bundle {
    val cmdBase = Input(UInt(32.W))
    val cmdCount = Input(UInt(16.W))
    val start = Input(Bool())
    val colorBase = Input(UInt(32.W))
    val depthBase = Input(UInt(32.W))
    val stride = Input(UInt(32.W))
    val depthTestEnable = Input(Bool())
    val depthFunc = Input(UInt(3.W))
    val depthWriteEnable = Input(Bool())
    val cullMode = Input(UInt(2.W))
    /** Texture sampling config forwarded to the core's fragment stage. */
    val texEnable = Input(Bool())
    val texBase = Input(UInt(32.W))
    val texWidth = Input(UInt(14.W))
    val texHeight = Input(UInt(14.W))
    val texWrapClamp = Input(Bool())
    val texMaxLevel = Input(UInt(4.W))
    val done = Output(Bool())
    val memoryRequest = Decoupled(
      new ComputeMemoryRequest(gpuConfig, lineBytes, totalOutstanding))
    val memoryResponse = Flipped(Decoupled(
      new ComputeMemoryResponse(lineBytes, totalOutstanding)))
    val clearPerformanceCounters = Input(Bool())
    val performance = Output(new L2PerformanceCounters)
  })

  private val core = Module(new RenderCore(gfxConfig, gpuConfig, fragCore))
  private val l2 = Module(new SharedL2Cache(
    gpuConfig, sets, ways, lineBytes, maxOutstanding = totalOutstanding,
    numComputeUnits = cuSlots, transactionsPerCu = perClientOutstanding,
    banks = banks, requestQueueDepth = requestQueueDepth,
    useSramBlackBoxes = useSramBlackBoxes))

  core.io.cmdBase := io.cmdBase
  core.io.cmdCount := io.cmdCount
  core.io.start := io.start
  core.io.colorBase := io.colorBase
  core.io.depthBase := io.depthBase
  core.io.stride := io.stride
  core.io.depthTestEnable := io.depthTestEnable
  core.io.depthFunc := io.depthFunc
  core.io.depthWriteEnable := io.depthWriteEnable
  core.io.cullMode := io.cullMode
  core.io.texEnable := io.texEnable
  core.io.texBase := io.texBase
  core.io.texWidth := io.texWidth
  core.io.texHeight := io.texHeight
  core.io.texWrapClamp := io.texWrapClamp
  core.io.texMaxLevel := io.texMaxLevel
  io.done := core.io.done

  // Word-level clients (command buffer, framebuffer, texture) behind bridges.
  private val cbBridge = Module(new OmWordToLinePort(
    gpuConfig, lineBytes, perClientOutstanding))
  private val fbBridge = Module(new OmWordToLinePort(
    gpuConfig, lineBytes, perClientOutstanding))
  private val texBridge = Module(new OmWordToLinePort(
    gpuConfig, lineBytes, perClientOutstanding))
  cbBridge.io.in <> core.io.cbMem.req
  core.io.cbMem.resp <> cbBridge.io.out
  fbBridge.io.in <> core.io.fbMem.req
  core.io.fbMem.resp <> fbBridge.io.out
  texBridge.io.in <> core.io.texMem.req
  core.io.texMem.resp <> texBridge.io.out

  private val l2RequestArbiter = Module(new RRArbiter(
    new ComputeMemoryRequest(gpuConfig, lineBytes, totalOutstanding),
    numClients))

  // Present each client's request with its transaction ID offset into the L2's
  // global space.  Chisel's `:=` connects matching fields and zero-extends the
  // narrower per-client transaction ID before the base is applied.
  private def tieRequest(
    mux: Int,
    client: DecoupledIO[ComputeMemoryRequest],
    base: Int
  ): Unit = {
    l2RequestArbiter.io.in(mux).valid := client.valid
    l2RequestArbiter.io.in(mux).bits := client.bits
    l2RequestArbiter.io.in(mux).bits.transactionId :=
      (base.U + client.bits.transactionId).asUInt
    client.ready := l2RequestArbiter.io.in(mux).ready
  }
  // Client 0 (base [0,n)) is the shader compute unit — the only `cacheClient`,
  // so it must own the transaction space that maps to CU slot 0 (transactionId
  // / transactionsPerCu == 0).  The word bridges (cacheClient=false) keep their
  // own disjoint ranges; their requester index is irrelevant to sharer tracking.
  tieRequest(0, core.io.kernelMemReq, 0)
  tieRequest(1, cbBridge.io.memoryRequest, perClientOutstanding)
  tieRequest(2, fbBridge.io.memoryRequest, 2 * perClientOutstanding)
  tieRequest(3, core.io.kernelWordMemReq, 3 * perClientOutstanding)
  tieRequest(4, texBridge.io.memoryRequest, 4 * perClientOutstanding)

  l2.io.request <> l2RequestArbiter.io.out

  // Route each L2 response to the owning client by its base, and subtract the
  // base so the client sees its original local transaction ID.
  private val respId = l2.io.response.bits.transactionId
  private val respFor =
    Seq.tabulate(numClients)(i => {
      val lo = (i * perClientOutstanding).U((txnWidth + 1).W)
      val hi = ((i + 1) * perClientOutstanding).U((txnWidth + 1).W)
      respId >= lo && respId < hi
    })

  private def tieResponse[T <: Data](
    from: DecoupledIO[ComputeMemoryResponse],
    base: Int
  ): Unit = {
    from.valid := l2.io.response.valid && respFor(base / perClientOutstanding)
    from.bits.readData := l2.io.response.bits.readData
    from.bits.fault := l2.io.response.bits.fault
    from.bits.transactionId :=
      (l2.io.response.bits.transactionId - (base.U(txnWidth.W))).asUInt
  }
  tieResponse(core.io.kernelMemResp, 0)
  tieResponse(cbBridge.io.memoryResponse, perClientOutstanding)
  tieResponse(fbBridge.io.memoryResponse, 2 * perClientOutstanding)
  tieResponse(core.io.kernelWordMemResp, 3 * perClientOutstanding)
  tieResponse(texBridge.io.memoryResponse, 4 * perClientOutstanding)
  when(l2.io.response.valid) {
    assert(PopCount(VecInit(respFor)) === 1.U,
      "L2 response must belong to exactly one graphics client")
  }
  l2.io.response.ready := MuxCase(false.B, (0 until numClients).map { i =>
    respFor(i) -> (i match {
      case 0 => core.io.kernelMemResp.ready
      case 1 => cbBridge.io.memoryResponse.ready
      case 2 => fbBridge.io.memoryResponse.ready
      case 3 => core.io.kernelWordMemResp.ready
      case _ => texBridge.io.memoryResponse.ready
    })
  })

  // Coherence: the shader compute unit (requester slot 0) is the only private
  // L1-cache client.  The L2 invalidates its line before a word-bridge client
  // (a foreign requester slot) writes a line it holds, and serializes global
  // atomics against that invalidation.  Slots 1..3 have no L1 (the bridges are
  // `cacheClient=false`), so their invalidate/atomic ports are tied off.
  l2.io.invalidate(0) <> core.io.kernelL1Invalidate
  core.io.kernelL1InvalidateDone <> l2.io.invalidateDone(0)
  core.io.kernelGlobalAtomicRequest <> l2.io.atomicRequest(0)
  l2.io.atomicResponse(0) <> core.io.kernelGlobalAtomicResponse
  for (cu <- 1 until cuSlots) {
    l2.io.invalidate(cu).ready := true.B
    l2.io.invalidateDone(cu).valid := false.B
    l2.io.invalidateDone(cu).bits :=
      0.U.asTypeOf(l2.io.invalidateDone(cu).bits)
    l2.io.atomicRequest(cu).valid := false.B
    l2.io.atomicRequest(cu).bits :=
      0.U.asTypeOf(l2.io.atomicRequest(cu).bits)
    l2.io.atomicResponse(cu).ready := true.B
  }

  l2.io.clearPerformanceCounters := io.clearPerformanceCounters
  io.performance := l2.io.performance
  io.memoryRequest <> l2.io.memoryRequest
  l2.io.memoryResponse <> io.memoryResponse
}
