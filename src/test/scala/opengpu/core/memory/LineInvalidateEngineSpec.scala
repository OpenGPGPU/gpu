package opengpu.core.memory

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import opengpu.config.GpuConfig
import org.scalatest.flatspec.AnyFlatSpec

class LineInvalidateEngineSpec extends AnyFlatSpec {
  behavior of "LineInvalidateEngine"

  it should "walk one host invalidate per line and report the aligned extent" in {
    simulate(new LineInvalidateEngine(GpuConfig(lanes = 4),
      commandIdWidth = 8)) { dut =>
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      dut.io.request.valid.poke(false.B)
      dut.io.request.bits.poke(0.U.asTypeOf(dut.io.request.bits))
      dut.io.completion.ready.poke(true.B)
      dut.io.hostInvalidate.ready.poke(true.B)
      dut.io.hostInvalidateDone.valid.poke(false.B)
      dut.io.hostInvalidateDone.bits.poke(
        0.U.asTypeOf(dut.io.hostInvalidateDone.bits))

      dut.io.request.bits.descriptorId.poke(5.U)
      dut.io.request.bits.address.poke(0x2000.U)
      dut.io.request.bits.bytes.poke(192.U)
      dut.io.request.valid.poke(true.B)
      dut.clock.step()
      dut.io.request.valid.poke(false.B)

      for (i <- 0 until 3) {
        var cycles = 0
        while (!dut.io.hostInvalidate.valid.peek().litToBoolean &&
               cycles < 10) {
          dut.clock.step(); cycles += 1
        }
        dut.io.hostInvalidate.valid.expect(true.B)
        dut.io.hostInvalidate.bits.lineAddress.expect((0x2000 + i * 64).U)
        dut.clock.step()
        dut.io.hostInvalidateDone.bits.lineAddress.poke((0x2000 + i * 64).U)
        dut.io.hostInvalidateDone.valid.poke(true.B)
        dut.clock.step()
        dut.io.hostInvalidateDone.valid.poke(false.B)
      }

      var cycles = 0
      while (!dut.io.completion.valid.peek().litToBoolean && cycles < 10) {
        dut.clock.step(); cycles += 1
      }
      dut.io.completion.valid.expect(true.B)
      dut.io.completion.bits.descriptorId.expect(5.U)
      dut.io.completion.bits.bytesInvalidated.expect(192.U)
      dut.io.completion.bits.success.expect(true.B)
    }
  }

  it should "complete a zero-length request without issuing any line" in {
    simulate(new LineInvalidateEngine(GpuConfig(lanes = 4),
      commandIdWidth = 8)) { dut =>
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      dut.io.request.valid.poke(false.B)
      dut.io.request.bits.poke(0.U.asTypeOf(dut.io.request.bits))
      dut.io.completion.ready.poke(true.B)
      dut.io.hostInvalidate.ready.poke(true.B)
      dut.io.hostInvalidateDone.valid.poke(false.B)
      dut.io.hostInvalidateDone.bits.poke(
        0.U.asTypeOf(dut.io.hostInvalidateDone.bits))

      dut.io.request.bits.descriptorId.poke(1.U)
      dut.io.request.bits.address.poke(0x1000.U)
      dut.io.request.bits.bytes.poke(0.U)
      dut.io.request.valid.poke(true.B)
      dut.clock.step()
      dut.io.request.valid.poke(false.B)

      var cycles = 0
      while (!dut.io.completion.valid.peek().litToBoolean && cycles < 10) {
        dut.clock.step(); cycles += 1
      }
      dut.io.completion.valid.expect(true.B)
      dut.io.completion.bits.bytesInvalidated.expect(0.U)
      dut.io.hostInvalidate.valid.expect(false.B)
    }
  }
}
