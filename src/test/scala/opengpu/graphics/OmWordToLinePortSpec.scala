package opengpu.graphics

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import opengpu.config.GpuConfig
import org.scalatest.flatspec.AnyFlatSpec

class OmWordToLinePortSpec extends AnyFlatSpec {
  behavior of "OmWordToLinePort"

  it should "issue a line read for a word request and trim the response" in {
    val config = GpuConfig(xLen = 32)
    simulate(new OmWordToLinePort(config)) { dut =>
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      dut.io.in.valid.poke(false.B)
      dut.io.memoryResponse.valid.poke(false.B)
      dut.io.memoryResponse.bits.readData.poke(0.U)
      dut.io.memoryResponse.bits.fault.poke(false.B)
      dut.io.memoryResponse.bits.transactionId.poke(0.U)
      dut.io.out.ready.poke(true.B)
      dut.io.memoryRequest.ready.poke(true.B)

      // Request the word at byte offset 40 (word index 10) of line 0x1000.
      dut.io.in.valid.poke(true.B)
      dut.io.in.bits.write.poke(false.B)
      dut.io.in.bits.addr.poke(0x1028.U)
      dut.io.in.bits.data.poke(0.U)
      dut.io.memoryRequest.valid.expect(true.B)
      dut.io.memoryRequest.bits.address.expect(0x1000.U)
      dut.io.memoryRequest.bits.isWrite.expect(false.B)
      val reId = dut.io.memoryRequest.bits.transactionId.peek().litValue
      dut.clock.step(); dut.io.in.valid.poke(false.B)

      // Response: put a known value in word 10.
      dut.io.memoryResponse.valid.poke(true.B)
      dut.io.memoryResponse.bits.readData.poke(
        (BigInt("deadbeef", 16) << (10 * 32)).U)
      dut.io.memoryResponse.bits.transactionId.poke(reId.U)
      dut.io.in.valid.poke(false.B)
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.data.expect("hdeadbeef".U)
      dut.io.memoryResponse.ready.expect(true.B)
      dut.clock.step(); dut.io.memoryResponse.valid.poke(false.B)
    }
  }

  it should "issue a line write with byte mask at the word's byte offset" in {
    val config = GpuConfig(xLen = 32)
    simulate(new OmWordToLinePort(config)) { dut =>
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      dut.io.in.valid.poke(false.B)
      dut.io.memoryResponse.valid.poke(false.B)
      dut.io.memoryResponse.bits.readData.poke(0.U)
      dut.io.memoryResponse.bits.fault.poke(false.B)
      dut.io.memoryResponse.bits.transactionId.poke(0.U)
      dut.io.out.ready.poke(true.B)
      dut.io.memoryRequest.ready.poke(true.B)

      // Write the word at byte offset 12 (word index 3) of line 0x3000.
      dut.io.in.valid.poke(true.B)
      dut.io.in.bits.write.poke(true.B)
      dut.io.in.bits.addr.poke(0x300c.U)
      dut.io.in.bits.data.poke("h76543210".U)
      dut.io.memoryRequest.valid.expect(true.B)
      dut.io.memoryRequest.bits.address.expect(0x3000.U)
      dut.io.memoryRequest.bits.isWrite.expect(true.B)
      dut.io.memoryRequest.bits.byteMask.expect(0xf000L.U)
      dut.io.memoryRequest.bits.writeData.expect(
        (BigInt("76543210", 16) << (3 * 32)).U)
      val wrId = dut.io.memoryRequest.bits.transactionId.peek().litValue
      dut.clock.step(); dut.io.in.valid.poke(false.B)

      // Acknowledge the write; the word port must present a response (the OM
      // retires its RMW only on a write completion too).
      dut.io.memoryResponse.valid.poke(true.B)
      dut.io.memoryResponse.bits.readData.poke(0.U)
      dut.io.memoryResponse.bits.transactionId.poke(wrId.U)
      dut.io.out.valid.expect(true.B)
      dut.io.memoryResponse.ready.expect(true.B)
      dut.clock.step(); dut.io.memoryResponse.valid.poke(false.B)
    }
  }

  it should "retain a write's slot until acknowledged (not fire-and-forget)" in {
    val config = GpuConfig(xLen = 32)
    simulate(new OmWordToLinePort(config)) { dut =>
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      dut.io.in.valid.poke(false.B)
      dut.io.memoryResponse.valid.poke(false.B)
      dut.io.memoryResponse.bits.readData.poke(0.U)
      dut.io.memoryResponse.bits.fault.poke(false.B)
      dut.io.memoryResponse.bits.transactionId.poke(0.U)
      dut.io.out.ready.poke(true.B)
      dut.io.memoryRequest.ready.poke(true.B)

      dut.io.in.valid.poke(true.B)
      dut.io.in.bits.write.poke(true.B)
      dut.io.in.bits.addr.poke(0x300c.U)
      dut.io.in.bits.data.poke("h76543210".U)
      val wrId = dut.io.memoryRequest.bits.transactionId.peek().litValue
      dut.clock.step(); dut.io.in.valid.poke(false.B)

      // While the write is unacknowledged, a second request gets a *different*
      // slot (the write keeps its slot until its response is accepted).
      dut.io.in.valid.poke(true.B)
      dut.io.in.bits.write.poke(false.B)
      dut.io.in.bits.addr.poke(0x3100.U)
      dut.io.in.bits.data.poke(0.U)
      val rdId = dut.io.memoryRequest.bits.transactionId.peek().litValue
      assert(rdId != wrId, "unacknowledged write must keep its transaction slot")
      dut.clock.step(); dut.io.in.valid.poke(false.B)

      dut.io.memoryResponse.valid.poke(true.B)
      dut.io.memoryResponse.bits.readData.poke(0.U)
      dut.io.memoryResponse.bits.transactionId.poke(wrId.U)
      dut.clock.step(); dut.io.memoryResponse.valid.poke(false.B)

      // The freed write slot is the lowest free id and is immediately reusable.
      dut.io.in.valid.poke(true.B)
      dut.io.in.bits.write.poke(true.B)
      dut.io.in.bits.addr.poke(0x3200.U)
      dut.io.in.bits.data.poke("h0000cafe".U)
      dut.io.memoryRequest.bits.transactionId.expect(wrId.U)
      dut.clock.step(); dut.io.in.valid.poke(false.B)
    }
  }
}
