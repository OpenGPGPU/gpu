package opengpu.system

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.memory.{ComputeMemoryRequest, ComputeMemoryResponse}

/** Converts the cache hierarchy's private lower-memory protocol into an AXI4
  * master port. The private protocol never crosses the product boundary.
  *
  * The current bridge deliberately allows one AXI transaction at a time. A
  * 64-byte cache line is emitted as an INCR burst when the AXI data bus is
  * narrower than a cache line; four-byte page-table accesses use AXI narrow
  * transfers and are shifted to/from the addressed byte lane.
  */
class ComputeMemoryAxiMaster(
  config: GpuConfig = GpuConfig(),
  lineBytes: Int = 64,
  maxOutstanding: Int = 4,
  axiDataBytes: Int = 8
) extends Module {
  require(lineBytes == 64, "current cache hierarchy uses 64-byte lines")
  require(maxOutstanding > 0)
  require(axiDataBytes >= 4 && axiDataBytes <= lineBytes)
  require(isPow2(axiDataBytes), "AXI data width must be a power of two")
  require(lineBytes % axiDataBytes == 0)

  private val axiDataWidth = axiDataBytes * 8
  private val idWidth = math.max(1, log2Ceil(maxOutstanding))
  private val beatIndexWidth = math.max(1, log2Ceil(lineBytes / axiDataBytes))
  private val laneOffsetWidth = log2Ceil(axiDataBytes)

  val io = IO(new Bundle {
    val request = Flipped(Decoupled(
      new ComputeMemoryRequest(config, lineBytes, maxOutstanding)))
    val response = Decoupled(
      new ComputeMemoryResponse(lineBytes, maxOutstanding))

    val m_axi_awid = Output(UInt(idWidth.W))
    val m_axi_awaddr = Output(UInt(config.xLen.W))
    val m_axi_awlen = Output(UInt(8.W))
    val m_axi_awsize = Output(UInt(3.W))
    val m_axi_awburst = Output(UInt(2.W))
    val m_axi_awlock = Output(Bool())
    val m_axi_awcache = Output(UInt(4.W))
    val m_axi_awprot = Output(UInt(3.W))
    val m_axi_awqos = Output(UInt(4.W))
    val m_axi_awvalid = Output(Bool())
    val m_axi_awready = Input(Bool())

    val m_axi_wdata = Output(UInt(axiDataWidth.W))
    val m_axi_wstrb = Output(UInt(axiDataBytes.W))
    val m_axi_wlast = Output(Bool())
    val m_axi_wvalid = Output(Bool())
    val m_axi_wready = Input(Bool())

    val m_axi_bid = Input(UInt(idWidth.W))
    val m_axi_bresp = Input(UInt(2.W))
    val m_axi_bvalid = Input(Bool())
    val m_axi_bready = Output(Bool())

    val m_axi_arid = Output(UInt(idWidth.W))
    val m_axi_araddr = Output(UInt(config.xLen.W))
    val m_axi_arlen = Output(UInt(8.W))
    val m_axi_arsize = Output(UInt(3.W))
    val m_axi_arburst = Output(UInt(2.W))
    val m_axi_arlock = Output(Bool())
    val m_axi_arcache = Output(UInt(4.W))
    val m_axi_arprot = Output(UInt(3.W))
    val m_axi_arqos = Output(UInt(4.W))
    val m_axi_arvalid = Output(Bool())
    val m_axi_arready = Input(Bool())

    val m_axi_rid = Input(UInt(idWidth.W))
    val m_axi_rdata = Input(UInt(axiDataWidth.W))
    val m_axi_rresp = Input(UInt(2.W))
    val m_axi_rlast = Input(Bool())
    val m_axi_rvalid = Input(Bool())
    val m_axi_rready = Output(Bool())
  })

  private val states = Enum(6)
  private val idle = states(0)
  private val readAddress = states(1)
  private val readData = states(2)
  private val writeData = states(3)
  private val writeResponse = states(4)
  private val returnResponse = states(5)
  private val state = RegInit(idle)
  private val request = Reg(new ComputeMemoryRequest(
    config, lineBytes, maxOutstanding))
  private val beat = RegInit(0.U(beatIndexWidth.W))
  private val readResult = RegInit(0.U((lineBytes * 8).W))
  private val responseFault = RegInit(false.B)
  private val addressSent = RegInit(false.B)
  private val dataSent = RegInit(false.B)

  private val requestBytes = (1.U(8.W) << request.sizeLog2)(7, 0)
  private val lineTransfer = requestBytes > axiDataBytes.U
  private val lineBeats = lineBytes / axiDataBytes
  private val transferBeats = Mux(lineTransfer, lineBeats.U(8.W), 1.U(8.W))
  private val lastBeat = beat === (transferBeats - 1.U)
  private val laneOffset = if (laneOffsetWidth == 0) 0.U else
    request.address(laneOffsetWidth - 1, 0)
  private val laneShift = laneOffset << 3
  private val beatShift = beat << log2Ceil(axiDataWidth)

  io.request.ready := state === idle
  io.response.valid := state === returnResponse
  io.response.bits.readData := readResult
  io.response.bits.fault := responseFault
  io.response.bits.transactionId := request.transactionId

  io.m_axi_awid := request.transactionId
  io.m_axi_awaddr := request.address
  io.m_axi_awlen := transferBeats - 1.U
  io.m_axi_awsize := Mux(lineTransfer, log2Ceil(axiDataBytes).U,
    request.sizeLog2)
  io.m_axi_awburst := 1.U // INCR
  io.m_axi_awlock := false.B
  io.m_axi_awcache := "b0011".U // normal non-cacheable, bufferable
  io.m_axi_awprot := 0.U
  io.m_axi_awqos := 0.U
  io.m_axi_awvalid := state === writeData && !addressSent

  private val lineWriteData = (request.writeData >> beatShift)(axiDataWidth - 1, 0)
  private val narrowWriteData =
    (request.writeData(axiDataWidth - 1, 0) << laneShift)(axiDataWidth - 1, 0)
  private val lineWriteMask = (request.byteMask >>
    (beat << log2Ceil(axiDataBytes)))(axiDataBytes - 1, 0)
  private val narrowWriteMask =
    (request.byteMask(axiDataBytes - 1, 0) << laneOffset)(axiDataBytes - 1, 0)
  io.m_axi_wdata := Mux(lineTransfer, lineWriteData, narrowWriteData)
  io.m_axi_wstrb := Mux(lineTransfer, lineWriteMask, narrowWriteMask)
  io.m_axi_wlast := lastBeat
  io.m_axi_wvalid := state === writeData && !dataSent

  io.m_axi_bready := state === writeResponse

  io.m_axi_arid := request.transactionId
  io.m_axi_araddr := request.address
  io.m_axi_arlen := transferBeats - 1.U
  io.m_axi_arsize := Mux(lineTransfer, log2Ceil(axiDataBytes).U,
    request.sizeLog2)
  io.m_axi_arburst := 1.U // INCR
  io.m_axi_arlock := false.B
  io.m_axi_arcache := "b0011".U
  io.m_axi_arprot := 0.U
  io.m_axi_arqos := 0.U
  io.m_axi_arvalid := state === readAddress
  io.m_axi_rready := state === readData

  when(io.request.fire) {
    request := io.request.bits
    beat := 0.U
    readResult := 0.U
    responseFault := false.B
    addressSent := false.B
    dataSent := false.B
    assert(io.request.bits.sizeLog2 === 2.U ||
      io.request.bits.sizeLog2 === log2Ceil(lineBytes).U,
      "lower memory supports four-byte words and full cache lines")
    when(io.request.bits.sizeLog2 === log2Ceil(lineBytes).U) {
      assert(io.request.bits.address(log2Ceil(lineBytes) - 1, 0) === 0.U,
        "cache-line AXI bursts must be naturally aligned")
    }
    state := Mux(io.request.bits.isWrite, writeData, readAddress)
  }

  when(state === readAddress && io.m_axi_arready) {
    state := readData
  }

  when(state === readData && io.m_axi_rvalid) {
    val responseError = io.m_axi_rresp(1)
    val narrowData = (io.m_axi_rdata >> laneShift)(axiDataWidth - 1, 0)
    val lineData = readResult | (io.m_axi_rdata << beatShift)
    val nextData = Mux(lineTransfer, lineData, narrowData)
    readResult := nextData
    responseFault := responseFault || responseError ||
      (io.m_axi_rlast =/= lastBeat)
    assert(io.m_axi_rid === request.transactionId,
      "AXI read response ID must match the active lower-memory request")
    assert(io.m_axi_rlast === lastBeat,
      "AXI RLAST must match the programmed burst length")
    when(io.m_axi_rlast) {
      state := returnResponse
    }.otherwise {
      beat := beat + 1.U
    }
  }

  when(state === writeData) {
    val addressFire = io.m_axi_awvalid && io.m_axi_awready
    val dataFire = io.m_axi_wvalid && io.m_axi_wready
    when(addressFire) { addressSent := true.B }
    when(dataFire) {
      when(lastBeat) { dataSent := true.B }
        .otherwise { beat := beat + 1.U }
    }
    when((addressSent || addressFire) &&
        (dataSent || (dataFire && lastBeat))) {
      state := writeResponse
    }
  }

  when(state === writeResponse && io.m_axi_bvalid) {
    assert(io.m_axi_bid === request.transactionId,
      "AXI write response ID must match the active lower-memory request")
    readResult := 0.U
    responseFault := io.m_axi_bresp(1)
    state := returnResponse
  }

  when(io.response.fire) {
    state := idle
  }
}
