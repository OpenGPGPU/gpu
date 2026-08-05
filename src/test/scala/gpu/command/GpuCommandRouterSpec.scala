package gpu.command

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import gpu.config.GpuConfig
import gpu.dispatch.KernelCommandStatus
import gpu.dma.CopyStatus
import org.scalatest.flatspec.AnyFlatSpec

class GpuCommandRouterSpec extends AnyFlatSpec {
  behavior of "GpuCommandRouter"

  private def initialize(dut: GpuCommandRouter): Unit = {
    dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
    dut.io.command.valid.poke(false.B)
    dut.io.command.bits.poke(0.U.asTypeOf(dut.io.command.bits))
    dut.io.completion.ready.poke(false.B)
    dut.io.kernel.ready.poke(false.B)
    dut.io.copy.ready.poke(false.B)
    dut.io.fill.ready.poke(false.B)
    dut.io.stridedCopy.ready.poke(false.B)
    dut.io.kernelCompletion.valid.poke(false.B)
    dut.io.kernelCompletion.bits.poke(
      0.U.asTypeOf(dut.io.kernelCompletion.bits))
    dut.io.copyCompletion.valid.poke(false.B)
    dut.io.copyCompletion.bits.poke(0.U.asTypeOf(dut.io.copyCompletion.bits))
    dut.io.fillCompletion.valid.poke(false.B)
    dut.io.fillCompletion.bits.poke(0.U.asTypeOf(dut.io.fillCompletion.bits))
    dut.io.stridedCopyCompletion.valid.poke(false.B)
    dut.io.stridedCopyCompletion.bits.poke(
      0.U.asTypeOf(dut.io.stridedCopyCompletion.bits))
  }

  private def submit(dut: GpuCommandRouter, id: Int, opcode: UInt): Unit = {
    dut.io.command.bits.commandId.poke(id.U)
    dut.io.command.bits.opcode.poke(opcode)
    dut.io.command.valid.poke(true.B)
    dut.io.command.ready.expect(true.B)
    dut.clock.step(); dut.io.command.valid.poke(false.B)
  }

  it should "route payloads and retain IDs until unified completion is consumed" in {
    simulate(new GpuCommandRouter(
      GpuConfig(lanes = 4), commandIdWidth = 4,
      commandQueueDepth = 2, completionQueueDepth = 2)) { dut =>
      initialize(dut)
      dut.io.command.bits.sourceAddress.poke(0x1000.U)
      dut.io.command.bits.destinationAddress.poke(0x2000.U)
      dut.io.command.bits.bytes.poke(128.U)
      submit(dut, 3, GpuCommandOpcode.copy)
      dut.io.copy.valid.expect(true.B)
      dut.io.copy.bits.descriptorId.expect(3.U)
      dut.io.copy.bits.sourceAddress.expect(0x1000.U)
      dut.io.copy.bits.destinationAddress.expect(0x2000.U)
      dut.io.copy.bits.bytes.expect(128.U)
      dut.io.copy.ready.poke(true.B); dut.clock.step()

      dut.io.copyCompletion.bits.descriptorId.poke(3.U)
      dut.io.copyCompletion.bits.status.poke(CopyStatus.success)
      dut.io.copyCompletion.bits.success.poke(true.B)
      dut.io.copyCompletion.bits.bytesCopied.poke(128.U)
      dut.io.copyCompletion.valid.poke(true.B)
      dut.clock.step(); dut.io.copyCompletion.valid.poke(false.B)
      dut.io.completion.valid.expect(true.B)
      dut.io.completion.bits.opcode.expect(GpuCommandOpcode.copy)
      dut.io.completion.bits.bytesProcessed.expect(128.U)

      dut.io.command.bits.commandId.poke(3.U)
      dut.io.command.valid.poke(true.B)
      dut.io.command.ready.expect(false.B)
      dut.io.duplicateCommandId.expect(true.B)
      dut.io.completion.ready.poke(true.B)
      dut.clock.step()
      dut.io.command.ready.expect(true.B)
    }
  }

  it should "merge engine completions and reject an invalid opcode" in {
    simulate(new GpuCommandRouter(
      GpuConfig(lanes = 4), commandIdWidth = 4,
      commandQueueDepth = 3, completionQueueDepth = 3)) { dut =>
      initialize(dut)
      dut.io.kernel.ready.poke(true.B)
      submit(dut, 1, GpuCommandOpcode.kernel)
      dut.clock.step()
      dut.io.kernelCompletion.bits.commandId.poke(1.U)
      dut.io.kernelCompletion.bits.status.poke(KernelCommandStatus.success)
      dut.io.kernelCompletion.bits.success.poke(true.B)
      dut.io.kernelCompletion.valid.poke(true.B)
      dut.clock.step(); dut.io.kernelCompletion.valid.poke(false.B)
      dut.io.completion.bits.commandId.expect(1.U)
      dut.io.completion.bits.opcode.expect(GpuCommandOpcode.kernel)
      dut.io.completion.ready.poke(true.B); dut.clock.step()

      submit(dut, 2, 7.U)
      dut.clock.step()
      dut.io.completion.valid.expect(true.B)
      dut.io.completion.bits.commandId.expect(2.U)
      dut.io.completion.bits.status.expect(GpuCommandResultStatus.invalidOpcode)
    }
  }
}
