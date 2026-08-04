package gpu.core.memory

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import gpu.config.GpuConfig
import org.scalatest.flatspec.AnyFlatSpec

class ComputeUnitMemoryInterconnectSpec extends AnyFlatSpec {
  behavior of "ComputeUnitMemoryInterconnect"

  it should "accept independent sources and route out-of-order responses" in {
    val config = GpuConfig(warps = 1)
    simulate(new ComputeUnitMemoryInterconnect(config)) { dut =>
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      dut.io.instructionRequest.valid.poke(false.B)
      dut.io.dataRequest.valid.poke(false.B)
      dut.io.instructionPageTableRequest.valid.poke(false.B)
      dut.io.dataPageTableRequest.valid.poke(false.B)
      dut.io.instructionResponse.ready.poke(false.B)
      dut.io.dataResponse.ready.poke(true.B)
      dut.io.instructionPageTableResponse.ready.poke(true.B)
      dut.io.dataPageTableResponse.ready.poke(true.B)
      dut.io.memoryRequest.ready.poke(true.B)
      dut.io.memoryResponse.valid.poke(false.B)
      dut.io.memoryResponse.bits.readData.poke(0.U)
      dut.io.memoryResponse.bits.fault.poke(false.B)
      dut.io.memoryResponse.bits.transactionId.poke(0.U)

      dut.io.instructionRequest.valid.poke(true.B)
      dut.io.instructionRequest.bits.lineAddress.poke(0x1000.U)
      dut.io.instructionRequest.bits.requestId.poke(2.U)
      dut.io.memoryRequest.valid.expect(true.B)
      dut.io.memoryRequest.bits.address.expect(0x1000.U)
      dut.io.memoryRequest.bits.sizeLog2.expect(6.U)
      val instructionId = dut.io.memoryRequest.bits.transactionId.peek().litValue
      dut.clock.step(); dut.io.instructionRequest.valid.poke(false.B)

      // A different blocking source may become outstanding concurrently.
      dut.io.dataRequest.valid.poke(true.B)
      dut.io.dataRequest.bits.poke(0.U.asTypeOf(dut.io.dataRequest.bits))
      dut.io.dataRequest.bits.lineAddress.poke(0x2000.U)
      dut.io.memoryRequest.valid.expect(true.B)
      val dataId = dut.io.memoryRequest.bits.transactionId.peek().litValue
      assert(dataId != instructionId)
      dut.clock.step(); dut.io.dataRequest.valid.poke(false.B)

      // Return data first even though instruction was requested first.
      dut.io.memoryResponse.valid.poke(true.B)
      dut.io.memoryResponse.bits.readData.poke("h12345678".U)
      dut.io.memoryResponse.bits.transactionId.poke(dataId.U)
      dut.io.instructionResponse.valid.expect(false.B)
      dut.io.dataResponse.valid.expect(true.B)
      dut.io.memoryResponse.ready.expect(true.B)
      dut.clock.step()

      dut.io.memoryResponse.bits.transactionId.poke(instructionId.U)
      dut.io.instructionResponse.valid.expect(true.B)
      dut.io.instructionResponse.bits.requestId.expect(2.U)
      dut.io.dataResponse.valid.expect(false.B)
      dut.io.memoryResponse.ready.expect(false.B)
      dut.io.instructionResponse.ready.poke(true.B)
      dut.clock.step(); dut.io.memoryResponse.valid.poke(false.B)
    }
  }

  it should "adapt page-table reads to word transfers" in {
    val config = GpuConfig(warps = 1)
    simulate(new ComputeUnitMemoryInterconnect(config)) { dut =>
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      dut.io.instructionRequest.valid.poke(false.B)
      dut.io.dataRequest.valid.poke(false.B)
      dut.io.instructionPageTableRequest.valid.poke(true.B)
      dut.io.instructionPageTableRequest.bits.address.poke(0x3004.U)
      dut.io.dataPageTableRequest.valid.poke(false.B)
      dut.io.instructionResponse.ready.poke(true.B)
      dut.io.dataResponse.ready.poke(true.B)
      dut.io.instructionPageTableResponse.ready.poke(true.B)
      dut.io.dataPageTableResponse.ready.poke(true.B)
      dut.io.memoryRequest.ready.poke(true.B)
      dut.io.memoryResponse.valid.poke(false.B)
      dut.io.memoryRequest.valid.expect(true.B)
      dut.io.memoryRequest.bits.address.expect(0x3004.U)
      dut.io.memoryRequest.bits.sizeLog2.expect(2.U)
      dut.clock.step(); dut.io.instructionPageTableRequest.valid.poke(false.B)
      dut.io.memoryResponse.valid.poke(true.B)
      dut.io.memoryResponse.bits.readData.poke("hdeadbeef".U)
      dut.io.memoryResponse.bits.fault.poke(false.B)
      dut.io.memoryResponse.bits.transactionId.poke(0.U)
      dut.io.instructionPageTableResponse.valid.expect(true.B)
      dut.io.instructionPageTableResponse.bits.pte.expect("hdeadbeef".U)
    }
  }
}
