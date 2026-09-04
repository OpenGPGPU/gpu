package opengpu.dma

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import opengpu.config.GpuConfig
import org.scalatest.flatspec.AnyFlatSpec

class CopyEngineSpec extends AnyFlatSpec {
  behavior of "CopyEngine"

  private def initialize(dut: CopyEngine): Unit = {
    dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
    dut.io.descriptor.valid.poke(false.B)
    dut.io.descriptor.bits.poke(0.U.asTypeOf(dut.io.descriptor.bits))
    dut.io.completion.ready.poke(false.B)
    dut.io.memoryRequest.ready.poke(true.B)
    dut.io.memoryResponse.valid.poke(false.B)
    dut.io.memoryResponse.bits.poke(
      0.U.asTypeOf(dut.io.memoryResponse.bits))
  }

  private def submit(dut: CopyEngine, id: Int, source: BigInt,
                     destination: BigInt, bytes: Int): Unit = {
    dut.io.descriptor.bits.descriptorId.poke(id.U)
    dut.io.descriptor.bits.sourceAddress.poke(source.U)
    dut.io.descriptor.bits.destinationAddress.poke(destination.U)
    dut.io.descriptor.bits.bytes.poke(bytes.U)
    dut.io.descriptor.valid.poke(true.B)
    dut.io.descriptor.ready.expect(true.B)
    dut.clock.step(); dut.io.descriptor.valid.poke(false.B)
  }

  private def request(dut: CopyEngine, address: BigInt,
                      write: Boolean, data: BigInt = 0): BigInt = {
    var cycles = 0
    while (!dut.io.memoryRequest.valid.peek().litToBoolean && cycles < 8) {
      dut.clock.step(); cycles += 1
    }
    dut.io.memoryRequest.valid.expect(true.B)
    dut.io.memoryRequest.bits.address.expect(address.U)
    dut.io.memoryRequest.bits.isWrite.expect(write.B)
    if (write) {
      dut.io.memoryRequest.bits.writeData.expect(data.U)
      dut.io.memoryRequest.bits.byteMask.expect(
        ((BigInt(1) << 64) - 1).U)
    }
    val id = dut.io.memoryRequest.bits.transactionId.peek().litValue
    dut.clock.step()
    id
  }

  private def response(dut: CopyEngine, id: BigInt, data: BigInt = 0,
                       fault: Boolean = false): Unit = {
    dut.io.memoryResponse.bits.transactionId.poke(id.U)
    dut.io.memoryResponse.bits.readData.poke(data.U)
    dut.io.memoryResponse.bits.fault.poke(fault.B)
    dut.io.memoryResponse.valid.poke(true.B)
    dut.io.memoryResponse.ready.expect(true.B)
    dut.clock.step(); dut.io.memoryResponse.valid.poke(false.B)
  }

  private def waitForCompletion(dut: CopyEngine): Unit = {
    var cycles = 0
    while (!dut.io.completion.valid.peek().litToBoolean && cycles < 8) {
      dut.clock.step(); cycles += 1
    }
    dut.io.completion.valid.expect(true.B)
  }

  it should "issue two reads and retire out-of-order read and write responses" in {
    simulate(new CopyEngine(GpuConfig(lanes = 4), descriptorIdWidth = 4)) { dut =>
      initialize(dut)
      val first = BigInt("0123456789abcdef", 16)
      val second = BigInt("fedcba9876543210", 16)
      submit(dut, id = 3, source = 0x1000, destination = 0x2000,
        bytes = 128)

      val firstRead = request(dut, 0x1000, write = false)
      val secondRead = request(dut, 0x1040, write = false)
      assert(firstRead != secondRead)

      response(dut, secondRead, second)
      val secondWrite = request(dut, 0x2040, write = true, second)
      response(dut, firstRead, first)
      val firstWrite = request(dut, 0x2000, write = true, first)
      assert(firstWrite != secondWrite)

      response(dut, firstWrite)
      response(dut, secondWrite)
      waitForCompletion(dut)
      dut.io.completion.bits.descriptorId.expect(3.U)
      dut.io.completion.bits.status.expect(CopyStatus.success)
      dut.io.completion.bits.bytesCopied.expect(128.U)
      dut.clock.step(3)
      dut.io.completion.valid.expect(true.B)
      dut.io.busy.expect(true.B)
      dut.io.completion.ready.poke(true.B)
      dut.clock.step()
      dut.io.busy.expect(false.B)
    }
  }

  it should "stop issuing and drain outstanding reads after a fault" in {
    simulate(new CopyEngine(GpuConfig(lanes = 4), descriptorIdWidth = 4)) { dut =>
      initialize(dut)
      submit(dut, id = 5, source = 0x3000, destination = 0x4000,
        bytes = 128)
      val firstRead = request(dut, 0x3000, write = false)
      val secondRead = request(dut, 0x3040, write = false)

      response(dut, secondRead, fault = true)
      dut.io.memoryRequest.valid.expect(false.B)
      dut.io.completion.valid.expect(false.B)
      response(dut, firstRead, data = 0xaa)
      dut.io.memoryRequest.valid.expect(false.B)
      waitForCompletion(dut)
      dut.io.completion.bits.status.expect(CopyStatus.readFault)
      dut.io.completion.bits.bytesCopied.expect(0.U)
    }
  }

  it should "reject malformed and overlapping descriptors without memory traffic" in {
    simulate(new CopyEngine(GpuConfig(lanes = 4), descriptorIdWidth = 4)) { dut =>
      initialize(dut)
      submit(dut, id = 1, source = 0x1004, destination = 0x2000,
        bytes = 64)
      dut.io.memoryRequest.valid.expect(false.B)
      dut.io.completion.valid.expect(true.B)
      dut.io.completion.bits.status.expect(CopyStatus.invalidAlignment)
      dut.io.completion.ready.poke(true.B); dut.clock.step()

      submit(dut, id = 2, source = 0x1000, destination = 0x1040,
        bytes = 128)
      dut.io.memoryRequest.valid.expect(false.B)
      dut.io.completion.bits.status.expect(CopyStatus.overlapUnsupported)
    }
  }

  it should "accept another descriptor while the current copy is busy" in {
    simulate(new CopyEngine(GpuConfig(lanes = 4), descriptorIdWidth = 4,
      descriptorQueueDepth = 2)) { dut =>
      initialize(dut)
      submit(dut, id = 1, source = 0x5000, destination = 0x6000,
        bytes = 64)
      val readId = request(dut, 0x5000, write = false)
      submit(dut, id = 2, source = 0x7000, destination = 0x8000,
        bytes = 64)
      dut.io.descriptor.ready.expect(true.B)

      response(dut, readId, 0x55)
      val writeId = request(dut, 0x6000, write = true, 0x55)
      response(dut, writeId)
      waitForCompletion(dut)
      dut.io.completion.bits.descriptorId.expect(1.U)
      dut.io.completion.ready.poke(true.B)
      dut.clock.step()

      request(dut, 0x7000, write = false)
      dut.io.completion.valid.expect(false.B)
    }
  }

  it should "offset transaction IDs when sharing a memory port" in {
    simulate(new CopyEngine(GpuConfig(lanes = 4), descriptorIdWidth = 4,
      maxOutstanding = 8, lineSlots = 2, transactionIdBase = 4)) { dut =>
      initialize(dut)
      submit(dut, id = 7, source = 0x9000, destination = 0xa000,
        bytes = 64)
      val readId = request(dut, 0x9000, write = false)
      assert(readId == 4)
      response(dut, readId, 0x1234)
      val writeId = request(dut, 0xa000, write = true, 0x1234)
      assert(writeId == 6)
      response(dut, writeId)
      waitForCompletion(dut)
      dut.io.completion.bits.status.expect(CopyStatus.success)
    }
  }
}
