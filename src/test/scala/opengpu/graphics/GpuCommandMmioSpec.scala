package opengpu.graphics

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import opengpu.command.GpuCommandOpcode
import opengpu.config.GpuConfig
import org.scalatest.flatspec.AnyFlatSpec

class GpuCommandMmioSpec extends AnyFlatSpec {
  behavior of "GpuCommandMmio"

  private def write(dut: GpuCommandMmio, address: Int, data: BigInt): Unit = {
    dut.io.reg.req.bits.addr.poke(address.U)
    dut.io.reg.req.bits.data.poke(data.U)
    dut.io.reg.req.bits.strb.poke(0xf.U)
    dut.io.reg.req.bits.isWrite.poke(true.B)
    dut.io.reg.req.valid.poke(true.B)
    dut.clock.step()
    dut.io.reg.req.valid.poke(false.B)
  }

  private def read(dut: GpuCommandMmio, address: Int): BigInt = {
    dut.io.reg.req.bits.addr.poke(address.U)
    dut.io.reg.req.bits.data.poke(0.U)
    dut.io.reg.req.bits.strb.poke(0xf.U)
    dut.io.reg.req.bits.isWrite.poke(false.B)
    dut.io.reg.req.valid.poke(true.B)
    dut.io.reg.resp.valid.expect(true.B)
    val result = dut.io.reg.resp.bits.data.peek().litValue
    dut.clock.step()
    dut.io.reg.req.valid.poke(false.B)
    result
  }

  it should "snapshot commands and retain completions until software pops them" in {
    simulate(new GpuCommandMmio(
      GpuConfig(lanes = 4, warps = 2), queueDepth = 2)) { dut =>
      dut.io.reg.req.valid.poke(false.B)
      dut.io.reg.resp.ready.poke(true.B)
      dut.io.command.ready.poke(false.B)
      dut.io.completion.valid.poke(false.B)
      dut.io.completion.bits.poke(0.U.asTypeOf(dut.io.completion.bits))
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)

      write(dut, GpuCommandMmioRegs.COMMAND_ID, 0x2a)
      write(dut, GpuCommandMmioRegs.OPCODE,
        GpuCommandOpcode.stridedCopy.litValue)
      write(dut, GpuCommandMmioRegs.SOURCE, 0x1000)
      write(dut, GpuCommandMmioRegs.DESTINATION, 0x4000)
      write(dut, GpuCommandMmioRegs.WIDTH, 128)
      write(dut, GpuCommandMmioRegs.HEIGHT, 7)
      write(dut, GpuCommandMmioRegs.SOURCE_STRIDE, 192)
      write(dut, GpuCommandMmioRegs.DESTINATION_STRIDE, 256)
      write(dut, GpuCommandMmioRegs.FLAGS, 0x7)
      write(dut, GpuCommandMmioRegs.DMA_DEPENDENCY, 0x5502)
      write(dut, GpuCommandMmioRegs.WAIT_EVENT, 0x0321)
      write(dut, GpuCommandMmioRegs.SIGNAL_EVENT, 0x0443)
      write(dut, GpuCommandMmioRegs.SUBMIT, 1)

      dut.io.command.valid.expect(true.B)
      dut.io.command.bits.commandId.expect(0x2a.U)
      dut.io.command.bits.opcode.expect(GpuCommandOpcode.stridedCopy)
      dut.io.command.bits.sourceAddress.expect(0x1000.U)
      dut.io.command.bits.destinationAddress.expect(0x4000.U)
      dut.io.command.bits.widthBytes.expect(128.U)
      dut.io.command.bits.height.expect(7.U)
      dut.io.command.bits.sourceStride.expect(192.U)
      dut.io.command.bits.destinationStride.expect(256.U)
      dut.io.command.bits.waitForDma.expect(true.B)
      dut.io.command.bits.dmaSource.expect(2.U)
      dut.io.command.bits.dmaDescriptorId.expect(0x55.U)
      dut.io.command.bits.waitForEvent.expect(true.B)
      dut.io.command.bits.waitEventId.expect(0x21.U)
      dut.io.command.bits.waitEventGeneration.expect(3.U)
      dut.io.command.bits.signalEvent.expect(true.B)
      dut.io.command.bits.signalEventId.expect(0x43.U)
      dut.io.command.bits.signalEventGeneration.expect(4.U)
      dut.io.command.ready.poke(true.B)
      dut.clock.step()
      dut.io.command.ready.poke(false.B)

      dut.io.completion.bits.commandId.poke(0x2a.U)
      dut.io.completion.bits.opcode.poke(GpuCommandOpcode.stridedCopy)
      dut.io.completion.bits.status.poke(0.U)
      dut.io.completion.bits.success.poke(true.B)
      dut.io.completion.bits.bytesProcessed.poke("h1234567887654321".U)
      dut.io.completion.valid.poke(true.B)
      dut.io.completion.ready.expect(true.B)
      dut.io.completionEvent.expect(true.B)
      dut.clock.step()
      dut.io.completion.valid.poke(false.B)

      assert((read(dut, GpuCommandMmioRegs.STATUS) & 0x2) != 0)
      val meta = read(dut, GpuCommandMmioRegs.COMPLETION)
      assert((meta & 0xff) == 0x2a)
      assert(((meta >> 8) & 0x7) == GpuCommandOpcode.stridedCopy.litValue)
      assert(((meta >> 15) & 1) == 1)
      assert(read(dut, GpuCommandMmioRegs.COMPLETION_BYTES_LO) == 0x87654321L)
      assert(read(dut, GpuCommandMmioRegs.COMPLETION_BYTES_HI) == 0x12345678L)

      write(dut, GpuCommandMmioRegs.COMPLETION_POP, 1)
      assert((read(dut, GpuCommandMmioRegs.STATUS) & 0x2) == 0)
    }
  }

  it should "report a sticky overflow when software submits to a full queue" in {
    simulate(new GpuCommandMmio(queueDepth = 1)) { dut =>
      dut.io.reg.req.valid.poke(false.B)
      dut.io.reg.resp.ready.poke(true.B)
      dut.io.command.ready.poke(false.B)
      dut.io.completion.valid.poke(false.B)
      dut.io.completion.bits.poke(0.U.asTypeOf(dut.io.completion.bits))
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)

      write(dut, GpuCommandMmioRegs.SUBMIT, 1)
      write(dut, GpuCommandMmioRegs.SUBMIT, 1)
      assert((read(dut, GpuCommandMmioRegs.STATUS) & 0x4) != 0)
      write(dut, GpuCommandMmioRegs.STATUS, 0x4)
      assert((read(dut, GpuCommandMmioRegs.STATUS) & 0x4) == 0)
    }
  }
}
