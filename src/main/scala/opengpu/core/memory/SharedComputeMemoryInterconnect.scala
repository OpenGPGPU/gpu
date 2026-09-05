package opengpu.core.memory

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig

/** Arbitrates independent CU memory ports onto one physical port. A global
  * transaction ID encodes both CU ownership and the CU-local transaction ID,
  * allowing responses to return out of order without a routing table.
  */
class SharedComputeMemoryInterconnect(
  config: GpuConfig = GpuConfig(),
  numComputeUnits: Int = 2,
  lineBytes: Int = 64,
  transactionsPerCu: Int = 4
) extends Module {
  require(numComputeUnits > 0)
  require(transactionsPerCu > 0)
  require(isPow2(transactionsPerCu),
    "CU-local transaction count must be a power of two")
  private val totalTransactions = numComputeUnits * transactionsPerCu
  private val localIdWidth = math.max(1, log2Ceil(transactionsPerCu))
  private val cuIdWidth = math.max(1, log2Ceil(numComputeUnits))

  val io = IO(new Bundle {
    val cuRequest = Vec(numComputeUnits,
      Flipped(Decoupled(new ComputeMemoryRequest(
        config, lineBytes, transactionsPerCu))))
    val cuResponse = Vec(numComputeUnits,
      Decoupled(new ComputeMemoryResponse(lineBytes, transactionsPerCu)))
    val memoryRequest = Decoupled(new ComputeMemoryRequest(
      config, lineBytes, totalTransactions))
    val memoryResponse = Flipped(Decoupled(new ComputeMemoryResponse(
      lineBytes, totalTransactions)))
  })

  private val arbiter = Module(new RRArbiter(
    new ComputeMemoryRequest(config, lineBytes, transactionsPerCu),
    numComputeUnits))
  for (cu <- 0 until numComputeUnits) {
    arbiter.io.in(cu) <> io.cuRequest(cu)
  }

  private val globalId = if (numComputeUnits == 1) {
    arbiter.io.out.bits.transactionId
  } else {
    Cat(arbiter.io.chosen, arbiter.io.out.bits.transactionId)
  }
  io.memoryRequest.valid := arbiter.io.out.valid
  io.memoryRequest.bits.address := arbiter.io.out.bits.address
  io.memoryRequest.bits.writeData := arbiter.io.out.bits.writeData
  io.memoryRequest.bits.byteMask := arbiter.io.out.bits.byteMask
  io.memoryRequest.bits.isWrite := arbiter.io.out.bits.isWrite
  io.memoryRequest.bits.sizeLog2 := arbiter.io.out.bits.sizeLog2
  io.memoryRequest.bits.cacheClient := arbiter.io.out.bits.cacheClient
  io.memoryRequest.bits.cacheResident := arbiter.io.out.bits.cacheResident
  io.memoryRequest.bits.transactionId := globalId
  arbiter.io.out.ready := io.memoryRequest.ready

  private val responseId = io.memoryResponse.bits.transactionId
  private val responseInRange = responseId < totalTransactions.U
  private val responseCu = if (numComputeUnits == 1) {
    0.U(cuIdWidth.W)
  } else {
    responseId(localIdWidth + cuIdWidth - 1, localIdWidth)
  }
  private val responseLocalId = responseId(localIdWidth - 1, 0)
  for (cu <- 0 until numComputeUnits) {
    io.cuResponse(cu).valid :=
      io.memoryResponse.valid && responseInRange && responseCu === cu.U
    io.cuResponse(cu).bits.readData := io.memoryResponse.bits.readData
    io.cuResponse(cu).bits.fault := io.memoryResponse.bits.fault
    io.cuResponse(cu).bits.transactionId := responseLocalId(localIdWidth - 1, 0)
  }
  io.memoryResponse.ready := responseInRange && (if (numComputeUnits == 1) {
    io.cuResponse.head.ready
  } else {
    io.cuResponse(responseCu).ready
  })

  when(io.memoryResponse.valid) {
    assert(responseInRange,
      "shared-memory response transaction ID must identify a compute unit")
  }
}
