package opengpu.system

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.{
  ComputeMemoryRequest, ComputeMemoryResponse, TranslatedLineClient,
  TranslatedWordClient
}
import opengpu.graphics.GraphicsConfig

/** Optional simulation counters; the default product port list is unchanged. */
class GpuHostPerformanceCounters extends Bundle {
  val system = new GpuPerformanceCounters
  val omStallCycles = UInt(64.W)
  val omConflictCycles = UInt(64.W)
  val rasterStallCycles = UInt(64.W)
  val stagingReadBytes = UInt(64.W)
  val stagingWriteBytes = UInt(64.W)
  val lowerReadBytes = UInt(64.W)
  val lowerWriteBytes = UInt(64.W)
  val translationStallCycles = Vec(4, UInt(64.W))
  val translationMisses = Vec(4, UInt(64.W))
  val walkCycles = Vec(4, UInt(64.W))
}

/** AXI-controlled graphics host attached to the GPU-internal shared L2.
  *
  * CPU and GPU L2 caches are separate. The AXI memory master carries GPU L2
  * lower-memory traffic to the SoC fabric and shared DRAM; the control slave
  * provides no CPU data-access path into the GPU L2. Coherence below is limited
  * to GPU clients and does not snoop CPU caches.
  *
  * The graphics host's eight-ID cache-line port and the compute/DMA clients
  * share one lower-memory port. Command-buffer, framebuffer and texture word
  * clients are adapted internally. The graphics shader is an additional
  * coherent L2 client, including private-cache invalidation and global atomic
  * channels, so the top exposes only one lower-memory port.
  */
class GpuHostSystemAxi(
  graphicsConfig: GraphicsConfig = GraphicsConfig(),
  gpuConfig: GpuConfig = GpuConfig(),
  numComputeUnits: Int = 1,
  commandIdWidth: Int = 8,
  transactionsPerCu: Int = 4,
  fragCore: Boolean = false,
  vertCore: Boolean = false,
  deviceId: Int = 0x4755,
  version: Int = 0x0001,
  useBlackBoxes: Boolean = false,
  enableFpuBackend: Boolean = false,
  instructionCacheSets: Int = 64,
  instructionCacheWays: Int = 2,
  instructionCacheMissEntries: Int = 4,
  vectorCacheSets: Int = 64,
  vectorCacheWays: Int = 2,
  memoryAxiDataBytes: Int = 8,
  exposePerformance: Boolean = false
) extends RawModule {
  require(numComputeUnits > 0)
  require(!vertCore || fragCore,
    "vertex-core graphics requires fragment-core graphics")

  // Kernel-word port IDs [0,8): frag+vert staging share translated [0,4);
  // fill/blit/strided DMA keeps physical [4,8). Command, framebuffer and
  // texture word clients each own a translated range plus a PTE-walk range.
  // Staging walks sit with the other TLB clients.
  private val translatedWordClients = Seq("command", "framebuffer", "texture")
  private val wordPortTransactions = math.max(4, graphicsConfig.omInflight)
  private val kernelWordTransactions = 8
  private val stagingTransactions = 4
  private val usedExact =
    kernelWordTransactions +
      wordPortTransactions * translatedWordClients.size * 2 +
      stagingTransactions
  private val graphicsHostTransactions = 1 << log2Ceil(usedExact)
  private val clientBases: Seq[Int] = translatedWordClients.indices
    .scanLeft(kernelWordTransactions)((base, _) => base + wordPortTransactions)
  private val cbBase = clientBases(0)
  private val fbBase = clientBases(1)
  private val texBase = clientBases(2)
  private val usedGraphicsTransactions = clientBases.last
  private val stagingTlbBase = usedGraphicsTransactions
  private val tlbBases: Seq[Int] = translatedWordClients.indices
    .scanLeft(stagingTlbBase + stagingTransactions)(
      (base, _) => base + wordPortTransactions)
  private val graphicsTlbBase = tlbBases(0)
  private val cbTlbBase = tlbBases(1)
  private val fbTlbBase = tlbBases(2)
  private val usedGraphicsTransactionsWithTlb = tlbBases.last
  require(usedGraphicsTransactionsWithTlb <= graphicsHostTransactions,
    s"graphics host ID budget $graphicsHostTransactions < used " +
      s"$usedGraphicsTransactionsWithTlb")
  private val systemTransactions = GpuSystem.totalMemoryTransactions(
    numComputeUnits, transactionsPerCu, graphicsHostTransactions)
  private val memoryAxiIdWidth = math.max(1, log2Ceil(systemTransactions))
  private val memoryAxiDataWidth = memoryAxiDataBytes * 8

  override def desiredName: String = "GpuHostSystemAxi"

  val io = IO(new Bundle {
    val s_axi_aclk = Input(Clock())
    val s_axi_aresetn = Input(Bool())

    val s_axi_awaddr = Input(UInt(32.W))
    val s_axi_awlen = Input(UInt(8.W))
    val s_axi_awsize = Input(UInt(3.W))
    val s_axi_awburst = Input(UInt(2.W))
    val s_axi_awvalid = Input(Bool())
    val s_axi_awready = Output(Bool())
    val s_axi_wdata = Input(UInt(32.W))
    val s_axi_wstrb = Input(UInt(4.W))
    val s_axi_wlast = Input(Bool())
    val s_axi_wvalid = Input(Bool())
    val s_axi_wready = Output(Bool())
    val s_axi_bresp = Output(UInt(2.W))
    val s_axi_bvalid = Output(Bool())
    val s_axi_bready = Input(Bool())
    val s_axi_araddr = Input(UInt(32.W))
    val s_axi_arlen = Input(UInt(8.W))
    val s_axi_arsize = Input(UInt(3.W))
    val s_axi_arburst = Input(UInt(2.W))
    val s_axi_arvalid = Input(Bool())
    val s_axi_arready = Output(Bool())
    val s_axi_rdata = Output(UInt(32.W))
    val s_axi_rresp = Output(UInt(2.W))
    val s_axi_rlast = Output(Bool())
    val s_axi_rvalid = Output(Bool())
    val s_axi_rready = Input(Bool())
    val m_irq = Output(Bool())
    val performance = if (exposePerformance) Some(Output(new GpuHostPerformanceCounters)) else None

    val m_axi_awid = Output(UInt(memoryAxiIdWidth.W))
    val m_axi_awaddr = Output(UInt(gpuConfig.xLen.W))
    val m_axi_awlen = Output(UInt(8.W))
    val m_axi_awsize = Output(UInt(3.W))
    val m_axi_awburst = Output(UInt(2.W))
    val m_axi_awlock = Output(Bool())
    val m_axi_awcache = Output(UInt(4.W))
    val m_axi_awprot = Output(UInt(3.W))
    val m_axi_awqos = Output(UInt(4.W))
    val m_axi_awvalid = Output(Bool())
    val m_axi_awready = Input(Bool())
    val m_axi_wdata = Output(UInt(memoryAxiDataWidth.W))
    val m_axi_wstrb = Output(UInt(memoryAxiDataBytes.W))
    val m_axi_wlast = Output(Bool())
    val m_axi_wvalid = Output(Bool())
    val m_axi_wready = Input(Bool())
    val m_axi_bid = Input(UInt(memoryAxiIdWidth.W))
    val m_axi_bresp = Input(UInt(2.W))
    val m_axi_bvalid = Input(Bool())
    val m_axi_bready = Output(Bool())
    val m_axi_arid = Output(UInt(memoryAxiIdWidth.W))
    val m_axi_araddr = Output(UInt(gpuConfig.xLen.W))
    val m_axi_arlen = Output(UInt(8.W))
    val m_axi_arsize = Output(UInt(3.W))
    val m_axi_arburst = Output(UInt(2.W))
    val m_axi_arlock = Output(Bool())
    val m_axi_arcache = Output(UInt(4.W))
    val m_axi_arprot = Output(UInt(3.W))
    val m_axi_arqos = Output(UInt(4.W))
    val m_axi_arvalid = Output(Bool())
    val m_axi_arready = Input(Bool())
    val m_axi_rid = Input(UInt(memoryAxiIdWidth.W))
    val m_axi_rdata = Input(UInt(memoryAxiDataWidth.W))
    val m_axi_rresp = Input(UInt(2.W))
    val m_axi_rlast = Input(Bool())
    val m_axi_rvalid = Input(Bool())
    val m_axi_rready = Output(Bool())
  })

  withClockAndReset(io.s_axi_aclk, !io.s_axi_aresetn) {
    val host = Module(new GpuHostAxi(
      graphicsConfig, gpuConfig, fragCore, vertCore,
      deviceId = deviceId, version = version,
      unifiedCommandMmio = true, commandIdWidth = commandIdWidth,
      textureFaultReporting = true))
    val system = Module(new GpuSystem(
      gpuConfig,
      numComputeUnits = numComputeUnits,
      commandIdWidth = commandIdWidth,
      transactionsPerCu = transactionsPerCu,
      useBlackBoxes = useBlackBoxes,
      enableFpuBackend = enableFpuBackend,
      enableUnifiedCommands = true,
      graphicsHostTransactions = graphicsHostTransactions,
      instructionCacheSets = instructionCacheSets,
      instructionCacheWays = instructionCacheWays,
      instructionCacheMissEntries = instructionCacheMissEntries,
      vectorCacheSets = vectorCacheSets,
      vectorCacheWays = vectorCacheWays))

    host.io.s_axi_aclk := io.s_axi_aclk
    host.io.s_axi_aresetn := io.s_axi_aresetn
    host.io.s_axi_awaddr := io.s_axi_awaddr
    host.io.s_axi_awlen := io.s_axi_awlen
    host.io.s_axi_awsize := io.s_axi_awsize
    host.io.s_axi_awburst := io.s_axi_awburst
    host.io.s_axi_awvalid := io.s_axi_awvalid
    io.s_axi_awready := host.io.s_axi_awready
    host.io.s_axi_wdata := io.s_axi_wdata
    host.io.s_axi_wstrb := io.s_axi_wstrb
    host.io.s_axi_wlast := io.s_axi_wlast
    host.io.s_axi_wvalid := io.s_axi_wvalid
    io.s_axi_wready := host.io.s_axi_wready
    io.s_axi_bresp := host.io.s_axi_bresp
    io.s_axi_bvalid := host.io.s_axi_bvalid
    host.io.s_axi_bready := io.s_axi_bready
    host.io.s_axi_araddr := io.s_axi_araddr
    host.io.s_axi_arlen := io.s_axi_arlen
    host.io.s_axi_arsize := io.s_axi_arsize
    host.io.s_axi_arburst := io.s_axi_arburst
    host.io.s_axi_arvalid := io.s_axi_arvalid
    io.s_axi_arready := host.io.s_axi_arready
    io.s_axi_rdata := host.io.s_axi_rdata
    io.s_axi_rresp := host.io.s_axi_rresp
    io.s_axi_rlast := host.io.s_axi_rlast
    io.s_axi_rvalid := host.io.s_axi_rvalid
    host.io.s_axi_rready := io.s_axi_rready
    io.m_irq := host.io.m_irq

    system.io.gpuCommand <> host.io.gpuCommand.get
    host.io.gpuCompletion.get <> system.io.gpuCompletion
    system.io.graphicsDrained := host.io.graphicsDrained
    system.io.commandResetActive := host.io.commandResetActive.get
    host.io.commandResetDone.get := system.io.commandResetDone

    system.io.graphicsShaderRequest <> host.io.kernelMemReq
    host.io.kernelMemResp <> system.io.graphicsShaderResponse
    system.io.graphicsShaderL1Invalidate <> host.io.kernelL1Invalidate
    host.io.kernelL1InvalidateDone <>
      system.io.graphicsShaderL1InvalidateDone
    system.io.graphicsShaderAtomicRequest <>
      host.io.kernelGlobalAtomicRequest
    host.io.kernelGlobalAtomicResponse <>
      system.io.graphicsShaderAtomicResponse
    // Staging (kernarg/VB) line traffic is Sv32-translated; fill/blit/strided
    // DMA on the same port (IDs >= 4) stays physical. Command, framebuffer and
    // texture word clients each keep their own TLB.
    val stagingClient = Module(new TranslatedLineClient(
      gpuConfig, graphicsHostTransactions))
    val cbClient = Module(new TranslatedWordClient(
      gpuConfig, wordPortTransactions, graphicsHostTransactions,
      uncached = true, preserveCachePolicy = true))
    val fbClient = Module(new TranslatedWordClient(
      gpuConfig, wordPortTransactions, graphicsHostTransactions))
    val texClient = Module(new TranslatedWordClient(
      gpuConfig, wordPortTransactions, graphicsHostTransactions))
    val stagingSelect =
      host.io.kernelWordMemReq.bits.transactionId < stagingTransactions.U
    stagingClient.io.in.valid :=
      host.io.kernelWordMemReq.valid && stagingSelect
    stagingClient.io.in.bits := host.io.kernelWordMemReq.bits
    val dmaReq = Wire(Decoupled(
      new ComputeMemoryRequest(gpuConfig, 64, graphicsHostTransactions)))
    dmaReq.valid := host.io.kernelWordMemReq.valid && !stagingSelect
    dmaReq.bits := host.io.kernelWordMemReq.bits
    host.io.kernelWordMemReq.ready := Mux(stagingSelect,
      stagingClient.io.in.ready, dmaReq.ready)
    cbClient.io.in <> host.io.cbMem.req
    host.io.cbMem.resp <> cbClient.io.out
    fbClient.io.in <> host.io.fbMem.req
    host.io.fbMem.resp <> fbClient.io.out
    texClient.io.in <> host.io.texMem.req
    host.io.texMem.resp <> texClient.io.out
    stagingClient.io.satp := host.io.vectorSatp
    stagingClient.io.flush := host.io.tlbFlush
    for (client <- Seq(cbClient, fbClient, texClient)) {
      client.io.satp := host.io.vectorSatp
      client.io.flush := host.io.tlbFlush
    }

    val graphicsRequestArbiter = Module(new RRArbiter(
      new ComputeMemoryRequest(gpuConfig, 64, graphicsHostTransactions), 9))
    def attachGraphicsRequest(
      index: Int,
      request: DecoupledIO[ComputeMemoryRequest],
      base: Int
    ): Unit = {
      graphicsRequestArbiter.io.in(index).valid := request.valid
      graphicsRequestArbiter.io.in(index).bits := request.bits
      graphicsRequestArbiter.io.in(index).bits.transactionId :=
        base.U + request.bits.transactionId
      request.ready := graphicsRequestArbiter.io.in(index).ready
    }
    attachGraphicsRequest(0, stagingClient.io.memReq, 0)
    attachGraphicsRequest(1, dmaReq, 0)
    attachGraphicsRequest(2, cbClient.io.memReq, cbBase)
    attachGraphicsRequest(3, fbClient.io.memReq, fbBase)
    attachGraphicsRequest(4, texClient.io.memReq, texBase)
    attachGraphicsRequest(5, stagingClient.io.pageWalk, stagingTlbBase)
    attachGraphicsRequest(6, texClient.io.pageWalk, graphicsTlbBase)
    attachGraphicsRequest(7, cbClient.io.pageWalk, cbTlbBase)
    attachGraphicsRequest(8, fbClient.io.pageWalk, fbTlbBase)
    system.io.graphicsHostRequest <> graphicsRequestArbiter.io.out

    val graphicsResponse = system.io.graphicsHostResponse
    val responseId = graphicsResponse.bits.transactionId
    val responseForStaging = responseId < stagingTransactions.U
    val responseForDma = responseId >= stagingTransactions.U &&
      responseId < cbBase.U
    val responseForCb = responseId >= cbBase.U && responseId < fbBase.U
    val responseForFb = responseId >= fbBase.U && responseId < texBase.U
    val responseForTex = responseId >= texBase.U &&
      responseId < stagingTlbBase.U
    val responseForStagingTlb = responseId >= stagingTlbBase.U &&
      responseId < graphicsTlbBase.U
    val responseForTlb = responseId >= graphicsTlbBase.U &&
      responseId < cbTlbBase.U
    val responseForCbTlb = responseId >= cbTlbBase.U &&
      responseId < fbTlbBase.U
    val responseForFbTlb = responseId >= fbTlbBase.U &&
      responseId < usedGraphicsTransactionsWithTlb.U
    def attachGraphicsResponse(
      response: DecoupledIO[ComputeMemoryResponse],
      select: Bool,
      base: Int
    ): Unit = {
      response.valid := graphicsResponse.valid && select
      response.bits.readData := graphicsResponse.bits.readData
      response.bits.fault := graphicsResponse.bits.fault
      response.bits.transactionId := responseId - base.U
    }
    attachGraphicsResponse(
      stagingClient.io.memResp, responseForStaging, 0)
    val dmaResp = Wire(Decoupled(
      new ComputeMemoryResponse(64, graphicsHostTransactions)))
    attachGraphicsResponse(dmaResp, responseForDma, 0)
    attachGraphicsResponse(cbClient.io.memResp, responseForCb, cbBase)
    attachGraphicsResponse(fbClient.io.memResp, responseForFb, fbBase)
    attachGraphicsResponse(texClient.io.memResp, responseForTex, texBase)
    // Merge translated staging completions (including local faults) with DMA
    // responses back onto the shared kernel-word response port.
    val kernelWordRespArb = Module(new RRArbiter(
      new ComputeMemoryResponse(64, graphicsHostTransactions), 2))
    kernelWordRespArb.io.in(0) <> stagingClient.io.out
    kernelWordRespArb.io.in(1) <> dmaResp
    host.io.kernelWordMemResp <> kernelWordRespArb.io.out
    host.io.textureFault.get :=
      stagingClient.io.fault || cbClient.io.fault || fbClient.io.fault ||
        texClient.io.fault
    stagingClient.io.pageWalkResp.valid :=
      graphicsResponse.valid && responseForStagingTlb
    stagingClient.io.pageWalkResp.bits.readData := graphicsResponse.bits.readData
    stagingClient.io.pageWalkResp.bits.fault := graphicsResponse.bits.fault
    stagingClient.io.pageWalkResp.bits.transactionId :=
      responseId - stagingTlbBase.U
    texClient.io.pageWalkResp.valid := graphicsResponse.valid && responseForTlb
    texClient.io.pageWalkResp.bits.readData := graphicsResponse.bits.readData
    texClient.io.pageWalkResp.bits.fault := graphicsResponse.bits.fault
    texClient.io.pageWalkResp.bits.transactionId :=
      responseId - graphicsTlbBase.U
    cbClient.io.pageWalkResp.valid :=
      graphicsResponse.valid && responseForCbTlb
    cbClient.io.pageWalkResp.bits.readData := graphicsResponse.bits.readData
    cbClient.io.pageWalkResp.bits.fault := graphicsResponse.bits.fault
    cbClient.io.pageWalkResp.bits.transactionId := responseId - cbTlbBase.U
    fbClient.io.pageWalkResp.valid :=
      graphicsResponse.valid && responseForFbTlb
    fbClient.io.pageWalkResp.bits.readData := graphicsResponse.bits.readData
    fbClient.io.pageWalkResp.bits.fault := graphicsResponse.bits.fault
    fbClient.io.pageWalkResp.bits.transactionId := responseId - fbTlbBase.U
    graphicsResponse.ready := MuxCase(false.B, Seq(
      responseForStaging -> stagingClient.io.memResp.ready,
      responseForDma -> dmaResp.ready,
      responseForCb -> cbClient.io.memResp.ready,
      responseForFb -> fbClient.io.memResp.ready,
      responseForTex -> texClient.io.memResp.ready,
      responseForStagingTlb -> stagingClient.io.pageWalkResp.ready,
      responseForTlb -> texClient.io.pageWalkResp.ready,
      responseForCbTlb -> cbClient.io.pageWalkResp.ready,
      responseForFbTlb -> fbClient.io.pageWalkResp.ready))
    when(graphicsResponse.valid) {
      assert(responseForStaging || responseForDma || responseForCb ||
        responseForFb || responseForTex || responseForStagingTlb ||
        responseForTlb || responseForCbTlb || responseForFbTlb,
        "graphics response must target an attached line, word or TLB client")
    }

    val memoryAxi = Module(new ComputeMemoryAxiMaster(
      gpuConfig, 64, systemTransactions, memoryAxiDataBytes))
    memoryAxi.io.request <> system.io.memoryRequest
    system.io.memoryResponse <> memoryAxi.io.response
    io.m_axi_awid := memoryAxi.io.m_axi_awid
    io.m_axi_awaddr := memoryAxi.io.m_axi_awaddr
    io.m_axi_awlen := memoryAxi.io.m_axi_awlen
    io.m_axi_awsize := memoryAxi.io.m_axi_awsize
    io.m_axi_awburst := memoryAxi.io.m_axi_awburst
    io.m_axi_awlock := memoryAxi.io.m_axi_awlock
    io.m_axi_awcache := memoryAxi.io.m_axi_awcache
    io.m_axi_awprot := memoryAxi.io.m_axi_awprot
    io.m_axi_awqos := memoryAxi.io.m_axi_awqos
    io.m_axi_awvalid := memoryAxi.io.m_axi_awvalid
    memoryAxi.io.m_axi_awready := io.m_axi_awready
    io.m_axi_wdata := memoryAxi.io.m_axi_wdata
    io.m_axi_wstrb := memoryAxi.io.m_axi_wstrb
    io.m_axi_wlast := memoryAxi.io.m_axi_wlast
    io.m_axi_wvalid := memoryAxi.io.m_axi_wvalid
    memoryAxi.io.m_axi_wready := io.m_axi_wready
    memoryAxi.io.m_axi_bid := io.m_axi_bid
    memoryAxi.io.m_axi_bresp := io.m_axi_bresp
    memoryAxi.io.m_axi_bvalid := io.m_axi_bvalid
    io.m_axi_bready := memoryAxi.io.m_axi_bready
    io.m_axi_arid := memoryAxi.io.m_axi_arid
    io.m_axi_araddr := memoryAxi.io.m_axi_araddr
    io.m_axi_arlen := memoryAxi.io.m_axi_arlen
    io.m_axi_arsize := memoryAxi.io.m_axi_arsize
    io.m_axi_arburst := memoryAxi.io.m_axi_arburst
    io.m_axi_arlock := memoryAxi.io.m_axi_arlock
    io.m_axi_arcache := memoryAxi.io.m_axi_arcache
    io.m_axi_arprot := memoryAxi.io.m_axi_arprot
    io.m_axi_arqos := memoryAxi.io.m_axi_arqos
    io.m_axi_arvalid := memoryAxi.io.m_axi_arvalid
    memoryAxi.io.m_axi_arready := io.m_axi_arready
    memoryAxi.io.m_axi_rid := io.m_axi_rid
    memoryAxi.io.m_axi_rdata := io.m_axi_rdata
    memoryAxi.io.m_axi_rresp := io.m_axi_rresp
    memoryAxi.io.m_axi_rlast := io.m_axi_rlast
    memoryAxi.io.m_axi_rvalid := io.m_axi_rvalid
    io.m_axi_rready := memoryAxi.io.m_axi_rready
    system.io.command.valid := false.B
    system.io.command.bits := 0.U.asTypeOf(system.io.command.bits)
    system.io.commandCompletion.ready := true.B
    system.io.copyDescriptor.valid := false.B
    system.io.copyDescriptor.bits :=
      0.U.asTypeOf(system.io.copyDescriptor.bits)
    system.io.copyCompletion.ready := true.B
    system.io.fillDescriptor.valid := false.B
    system.io.fillDescriptor.bits :=
      0.U.asTypeOf(system.io.fillDescriptor.bits)
    system.io.fillCompletion.ready := true.B
    system.io.stridedCopyDescriptor.valid := false.B
    system.io.stridedCopyDescriptor.bits :=
      0.U.asTypeOf(system.io.stridedCopyDescriptor.bits)
    system.io.stridedCopyCompletion.ready := true.B

    io.performance.foreach { perf =>
      def count(value: UInt): UInt = {
        val counter = RegInit(0.U(64.W))
        counter := counter + value
        counter
      }
      perf.system := system.io.performance
      perf.omStallCycles := count(host.io.performance.omStall.asUInt)
      perf.omConflictCycles := count(host.io.performance.omConflict.asUInt)
      perf.rasterStallCycles := count(host.io.performance.rasterStall.asUInt)
      perf.stagingReadBytes := count(host.io.performance.stagingReadBytes)
      perf.stagingWriteBytes := count(host.io.performance.stagingWriteBytes)
      perf.translationStallCycles(0) :=
        count(stagingClient.io.translationStall.asUInt)
      perf.translationMisses(0) :=
        count(stagingClient.io.translationMiss.asUInt)
      perf.walkCycles(0) := count(stagingClient.io.walkActive.asUInt)
      for ((client, index) <-
          Seq(cbClient, fbClient, texClient).zipWithIndex) {
        val slot = index + 1
        perf.translationStallCycles(slot) :=
          count(client.io.translationStall.asUInt)
        perf.translationMisses(slot) :=
          count(client.io.translationMiss.asUInt)
        perf.walkCycles(slot) := count(client.io.walkActive.asUInt)
      }
      val request = system.io.memoryRequest
      val bytes = 1.U(8.W) << request.bits.sizeLog2
      perf.lowerReadBytes := count(Mux(request.fire && !request.bits.isWrite, bytes, 0.U))
      perf.lowerWriteBytes := count(Mux(request.fire && request.bits.isWrite, bytes, 0.U))
    }
    system.io.clearPerformanceCounters := false.B
    // Remapped executable snapshots may recycle physical lines; drop I-cache
    // contents whenever software shoots down translation.
    system.io.invalidateInstructionCache := host.io.tlbFlush.valid
    system.io.instructionSatp := host.io.instructionSatp
    system.io.vectorSatp := host.io.vectorSatp
    // `TLB_FLUSH` forwards its scope to both CU MMUs and the graphics
    // word/line clients, so an ASID- or VPN-scoped shootdown leaves other
    // entries warm everywhere.
    system.io.vectorTlbFlush := host.io.tlbFlush
    system.io.instructionTlbFlush := host.io.tlbFlush

    for (cu <- 0 until numComputeUnits) {
      system.io.fpu(cu).ready := false.B
      system.io.vector(cu).ready := false.B
      system.io.scalarMemory(cu).ready := false.B
      system.io.unsupportedSystem(cu).ready := false.B
      system.io.trap(cu).ready := false.B
      system.io.simtBranch(cu).valid := false.B
      system.io.simtBranch(cu).bits :=
        0.U.asTypeOf(system.io.simtBranch(cu).bits)
    }

  }
}
