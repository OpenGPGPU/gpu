package opengpu.dma

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import opengpu.config.GpuConfig
import org.scalatest.flatspec.AnyFlatSpec

class StridedCopyEngineSpec extends AnyFlatSpec {
  behavior of "StridedCopyEngine"

  it should "advance source and destination strides after each completed row" in {
    simulate(new StridedCopyEngine(
      GpuConfig(lanes = 4), descriptorIdWidth = 4)) { dut =>
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      dut.io.descriptor.valid.poke(false.B)
      dut.io.descriptor.bits.poke(0.U.asTypeOf(dut.io.descriptor.bits))
      dut.io.completion.ready.poke(false.B)
      dut.io.memoryRequest.ready.poke(true.B)
      dut.io.memoryResponse.valid.poke(false.B)
      dut.io.memoryResponse.bits.poke(0.U.asTypeOf(dut.io.memoryResponse.bits))

      dut.io.descriptor.bits.descriptorId.poke(7.U)
      dut.io.descriptor.bits.sourceAddress.poke(0x1000.U)
      dut.io.descriptor.bits.destinationAddress.poke(0x4000.U)
      dut.io.descriptor.bits.widthBytes.poke(64.U)
      dut.io.descriptor.bits.height.poke(2.U)
      dut.io.descriptor.bits.sourceStride.poke(0x100.U)
      dut.io.descriptor.bits.destinationStride.poke(0x180.U)
      dut.io.descriptor.valid.poke(true.B)
      dut.clock.step(); dut.io.descriptor.valid.poke(false.B)

      val rows = Seq((BigInt(0x1000), BigInt(0x4000), BigInt(0x11)),
        (BigInt(0x1100), BigInt(0x4180), BigInt(0x22)))
      rows.foreach { case (source, destination, data) =>
        var cycles = 0
        while (!dut.io.memoryRequest.valid.peek().litToBoolean && cycles < 10) {
          dut.clock.step(); cycles += 1
        }
        dut.io.memoryRequest.bits.address.expect(source.U)
        dut.io.memoryRequest.bits.isWrite.expect(false.B)
        val readId = dut.io.memoryRequest.bits.transactionId.peek().litValue
        dut.clock.step()
        dut.io.memoryResponse.bits.transactionId.poke(readId.U)
        dut.io.memoryResponse.bits.readData.poke(data.U)
        dut.io.memoryResponse.bits.fault.poke(false.B)
        dut.io.memoryResponse.valid.poke(true.B)
        dut.clock.step(); dut.io.memoryResponse.valid.poke(false.B)

        cycles = 0
        while (!dut.io.memoryRequest.valid.peek().litToBoolean && cycles < 10) {
          dut.clock.step(); cycles += 1
        }
        dut.io.memoryRequest.bits.address.expect(destination.U)
        dut.io.memoryRequest.bits.isWrite.expect(true.B)
        dut.io.memoryRequest.bits.writeData.expect(data.U)
        val writeId = dut.io.memoryRequest.bits.transactionId.peek().litValue
        dut.clock.step()
        dut.io.memoryResponse.bits.transactionId.poke(writeId.U)
        dut.io.memoryResponse.bits.readData.poke(0.U)
        dut.io.memoryResponse.valid.poke(true.B)
        dut.clock.step(); dut.io.memoryResponse.valid.poke(false.B)
      }

      var cycles = 0
      while (!dut.io.completion.valid.peek().litToBoolean && cycles < 10) {
        dut.clock.step(); cycles += 1
      }
      dut.io.completion.bits.descriptorId.expect(7.U)
      dut.io.completion.bits.success.expect(true.B)
      dut.io.completion.bits.bytesCopied.expect(128.U)
    }
  }

  it should "reject overlapping row footprints without memory traffic" in {
    simulate(new StridedCopyEngine(
      GpuConfig(lanes = 4), descriptorIdWidth = 4)) { dut =>
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      dut.io.descriptor.bits.poke(0.U.asTypeOf(dut.io.descriptor.bits))
      dut.io.descriptor.bits.sourceAddress.poke(0x1000.U)
      dut.io.descriptor.bits.destinationAddress.poke(0x1040.U)
      dut.io.descriptor.bits.widthBytes.poke(64.U)
      dut.io.descriptor.bits.height.poke(2.U)
      dut.io.descriptor.bits.sourceStride.poke(64.U)
      dut.io.descriptor.bits.destinationStride.poke(64.U)
      dut.io.descriptor.valid.poke(true.B)
      dut.io.completion.ready.poke(false.B)
      dut.io.memoryRequest.ready.poke(true.B)
      dut.io.memoryResponse.valid.poke(false.B)
      dut.io.memoryResponse.bits.poke(0.U.asTypeOf(dut.io.memoryResponse.bits))
      dut.clock.step(); dut.io.descriptor.valid.poke(false.B)
      dut.io.memoryRequest.valid.expect(false.B)
      dut.io.completion.valid.expect(true.B)
      dut.io.completion.bits.status.expect(CopyStatus.overlapUnsupported)
    }
  }

  it should "reject a two-dimensional range that crosses the address space" in {
    simulate(new StridedCopyEngine(
      GpuConfig(lanes = 4), descriptorIdWidth = 4)) { dut =>
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      dut.io.descriptor.bits.poke(0.U.asTypeOf(dut.io.descriptor.bits))
      dut.io.descriptor.bits.sourceAddress.poke("hfffff000".U)
      dut.io.descriptor.bits.destinationAddress.poke(0x1000.U)
      dut.io.descriptor.bits.widthBytes.poke(64.U)
      dut.io.descriptor.bits.height.poke(2.U)
      dut.io.descriptor.bits.sourceStride.poke(0x1000.U)
      dut.io.descriptor.bits.destinationStride.poke(64.U)
      dut.io.descriptor.valid.poke(true.B)
      dut.io.completion.ready.poke(false.B)
      dut.io.memoryRequest.ready.poke(true.B)
      dut.io.memoryResponse.valid.poke(false.B)
      dut.io.memoryResponse.bits.poke(0.U.asTypeOf(dut.io.memoryResponse.bits))
      dut.clock.step(); dut.io.descriptor.valid.poke(false.B)
      dut.io.memoryRequest.valid.expect(false.B)
      dut.io.completion.bits.status.expect(CopyStatus.addressOverflow)
    }
  }
}
