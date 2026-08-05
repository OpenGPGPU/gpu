package gpu.dma

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import gpu.config.GpuConfig
import org.scalatest.flatspec.AnyFlatSpec

class FillEngineSpec extends AnyFlatSpec {
  behavior of "FillEngine"

  private def initialize(dut: FillEngine): Unit = {
    dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
    dut.io.descriptor.valid.poke(false.B)
    dut.io.descriptor.bits.poke(0.U.asTypeOf(dut.io.descriptor.bits))
    dut.io.completion.ready.poke(false.B)
    dut.io.memoryRequest.ready.poke(true.B)
    dut.io.memoryResponse.valid.poke(false.B)
    dut.io.memoryResponse.bits.poke(0.U.asTypeOf(dut.io.memoryResponse.bits))
  }

  private def submit(dut: FillEngine, id: Int, address: BigInt,
                     bytes: Int, pattern: BigInt): Unit = {
    dut.io.descriptor.bits.descriptorId.poke(id.U)
    dut.io.descriptor.bits.destinationAddress.poke(address.U)
    dut.io.descriptor.bits.bytes.poke(bytes.U)
    dut.io.descriptor.bits.pattern.poke(pattern.U)
    dut.io.descriptor.valid.poke(true.B)
    dut.io.descriptor.ready.expect(true.B)
    dut.clock.step(); dut.io.descriptor.valid.poke(false.B)
  }

  private def respond(dut: FillEngine, id: BigInt,
                      fault: Boolean = false): Unit = {
    dut.io.memoryResponse.bits.transactionId.poke(id.U)
    dut.io.memoryResponse.bits.fault.poke(fault.B)
    dut.io.memoryResponse.valid.poke(true.B)
    dut.io.memoryResponse.ready.expect(true.B)
    dut.clock.step(); dut.io.memoryResponse.valid.poke(false.B)
  }

  it should "issue two patterned lines and accept out-of-order acknowledgements" in {
    simulate(new FillEngine(GpuConfig(lanes = 4), descriptorIdWidth = 4)) { dut =>
      initialize(dut)
      val word = BigInt("deadbeef", 16)
      val line = BigInt(List.fill(16)("deadbeef").mkString, 16)
      submit(dut, 3, 0x4000, 128, word)

      dut.io.memoryRequest.valid.expect(true.B)
      dut.io.memoryRequest.bits.address.expect(0x4000.U)
      dut.io.memoryRequest.bits.writeData.expect(line.U)
      val first = dut.io.memoryRequest.bits.transactionId.peek().litValue
      dut.clock.step()
      dut.io.memoryRequest.bits.address.expect(0x4040.U)
      val second = dut.io.memoryRequest.bits.transactionId.peek().litValue
      assert(first != second)
      dut.clock.step()

      respond(dut, second)
      respond(dut, first)
      dut.clock.step()
      dut.io.completion.valid.expect(true.B)
      dut.io.completion.bits.success.expect(true.B)
      dut.io.completion.bits.bytesFilled.expect(128.U)
    }
  }

  it should "drain outstanding writes after a fault" in {
    simulate(new FillEngine(GpuConfig(lanes = 4), descriptorIdWidth = 4)) { dut =>
      initialize(dut)
      submit(dut, 4, 0x5000, 128, 0)
      val first = dut.io.memoryRequest.bits.transactionId.peek().litValue
      dut.clock.step()
      val second = dut.io.memoryRequest.bits.transactionId.peek().litValue
      dut.clock.step()
      respond(dut, first, fault = true)
      dut.io.completion.valid.expect(false.B)
      respond(dut, second)
      dut.clock.step()
      dut.io.completion.bits.status.expect(CopyStatus.writeFault)
      dut.io.completion.bits.bytesFilled.expect(64.U)
    }
  }

  it should "reject unaligned and non-line-sized descriptors" in {
    simulate(new FillEngine(GpuConfig(lanes = 4), descriptorIdWidth = 4)) { dut =>
      initialize(dut)
      submit(dut, 1, 0x5004, 64, 0)
      dut.io.memoryRequest.valid.expect(false.B)
      dut.io.completion.bits.status.expect(CopyStatus.invalidAlignment)
      dut.io.completion.ready.poke(true.B); dut.clock.step()
      submit(dut, 2, 0x6000, 63, 0)
      dut.io.completion.bits.status.expect(CopyStatus.invalidLength)
    }
  }
}
