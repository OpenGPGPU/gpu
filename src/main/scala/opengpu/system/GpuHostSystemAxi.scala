package opengpu.system

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.{ComputeMemoryRequest, ComputeMemoryResponse}
import opengpu.graphics.{
  GpuHostAxi,
  GraphicsConfig,
  OmWordToLinePort
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
  memoryAxiDataBytes: Int = 8
) extends RawModule {
  require(numComputeUnits > 0)
  require(!vertCore || fragCore,
    "vertex-core graphics requires fragment-core graphics")

  // Eight direct graphics line transactions plus three four-entry word-port
  // bridges. Round up to a power of two for simple local-ID range checking.
  private val graphicsHostTransactions = 32
  private val wordPortTransactions = 4
  private val cbBase = 8
  private val fbBase = cbBase + wordPortTransactions
  private val texBase = fbBase + wordPortTransactions
  private val usedGraphicsTransactions = texBase + wordPortTransactions
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
      unifiedCommandMmio = true, commandIdWidth = commandIdWidth))
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

    system.io.graphicsShaderRequest <> host.io.kernelMemReq
    host.io.kernelMemResp <> system.io.graphicsShaderResponse
    system.io.graphicsShaderL1Invalidate <> host.io.kernelL1Invalidate
    host.io.kernelL1InvalidateDone <>
      system.io.graphicsShaderL1InvalidateDone
    system.io.graphicsShaderAtomicRequest <>
      host.io.kernelGlobalAtomicRequest
    host.io.kernelGlobalAtomicResponse <>
      system.io.graphicsShaderAtomicResponse
    val cbBridge = Module(new OmWordToLinePort(
      gpuConfig, 64, wordPortTransactions))
    val fbBridge = Module(new OmWordToLinePort(
      gpuConfig, 64, wordPortTransactions))
    val texBridge = Module(new OmWordToLinePort(
      gpuConfig, 64, wordPortTransactions))
    cbBridge.io.in <> host.io.cbMem.req
    host.io.cbMem.resp <> cbBridge.io.out
    fbBridge.io.in <> host.io.fbMem.req
    host.io.fbMem.resp <> fbBridge.io.out
    texBridge.io.in <> host.io.texMem.req
    host.io.texMem.resp <> texBridge.io.out

    val graphicsRequestArbiter = Module(new RRArbiter(
      new ComputeMemoryRequest(gpuConfig, 64, graphicsHostTransactions), 4))
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
    attachGraphicsRequest(0, host.io.kernelWordMemReq, 0)
    attachGraphicsRequest(1, cbBridge.io.memoryRequest, cbBase)
    attachGraphicsRequest(2, fbBridge.io.memoryRequest, fbBase)
    attachGraphicsRequest(3, texBridge.io.memoryRequest, texBase)
    system.io.graphicsHostRequest <> graphicsRequestArbiter.io.out

    val graphicsResponse = system.io.graphicsHostResponse
    val responseId = graphicsResponse.bits.transactionId
    val responseForKernelWord = responseId < cbBase.U
    val responseForCb = responseId >= cbBase.U && responseId < fbBase.U
    val responseForFb = responseId >= fbBase.U && responseId < texBase.U
    val responseForTex = responseId >= texBase.U &&
      responseId < usedGraphicsTransactions.U
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
      host.io.kernelWordMemResp, responseForKernelWord, 0)
    attachGraphicsResponse(
      cbBridge.io.memoryResponse, responseForCb, cbBase)
    attachGraphicsResponse(
      fbBridge.io.memoryResponse, responseForFb, fbBase)
    attachGraphicsResponse(
      texBridge.io.memoryResponse, responseForTex, texBase)
    graphicsResponse.ready := MuxCase(false.B, Seq(
      responseForKernelWord -> host.io.kernelWordMemResp.ready,
      responseForCb -> cbBridge.io.memoryResponse.ready,
      responseForFb -> fbBridge.io.memoryResponse.ready,
      responseForTex -> texBridge.io.memoryResponse.ready))
    when(graphicsResponse.valid) {
      assert(responseForKernelWord || responseForCb || responseForFb ||
        responseForTex,
        "graphics response must target an attached line or word client")
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

    system.io.clearPerformanceCounters := false.B
    system.io.invalidateInstructionCache := false.B
    system.io.instructionSatp := 0.U
    system.io.instructionTlbFlush.valid := false.B
    system.io.instructionTlbFlush.bits :=
      0.U.asTypeOf(system.io.instructionTlbFlush.bits)
    system.io.vectorSatp := 0.U
    system.io.vectorTlbFlush.valid := false.B
    system.io.vectorTlbFlush.bits :=
      0.U.asTypeOf(system.io.vectorTlbFlush.bits)

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
