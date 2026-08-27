package opengpu.dispatch

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import opengpu.config.GpuConfig
import org.scalatest.flatspec.AnyFlatSpec

class GpuCommandProcessorSpec extends AnyFlatSpec {
  behavior of "GpuCommandProcessor"

  private def initialize(dut: GpuCommandProcessor): Unit = {
    dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
    dut.io.command.valid.poke(false.B)
    dut.io.command.bits.poke(0.U.asTypeOf(dut.io.command.bits))
    dut.io.completion.ready.poke(false.B)
    dut.io.dispatch.ready.poke(false.B)
    dut.io.dispatchCompletion.valid.poke(false.B)
    dut.io.dispatchCompletion.bits.poke(
      0.U.asTypeOf(dut.io.dispatchCompletion.bits))
    dut.io.dmaCompletion.foreach { event =>
      event.valid.poke(false.B)
      event.bits.poke(0.U.asTypeOf(event.bits))
    }
  }

  private def driveCommand(dut: GpuCommandProcessor, id: Int,
                           pc: Int = 0x1000, kernarg: Int = 0x8000,
                           grid: Seq[Int] = Seq(1, 1, 1),
                           local: Seq[Int] = Seq(1, 1, 1),
                           waitForDma: Boolean = false,
                           dmaSource: Int = 0,
                           dmaDescriptorId: Int = 0): Unit = {
    dut.io.command.bits.commandId.poke(id.U)
    dut.io.command.bits.launch.kernelPc.poke(pc.U)
    dut.io.command.bits.launch.kernargAddress.poke(kernarg.U)
    dut.io.command.bits.waitForDma.poke(waitForDma.B)
    dut.io.command.bits.dmaSource.poke(dmaSource.U)
    dut.io.command.bits.dmaDescriptorId.poke(dmaDescriptorId.U)
    for (dimension <- 0 until 3) {
      dut.io.command.bits.launch.gridSize(dimension).poke(grid(dimension).U)
      dut.io.command.bits.launch.localSize(dimension).poke(local(dimension).U)
    }
    dut.io.command.valid.poke(true.B)
  }

  private def dmaEvent(dut: GpuCommandProcessor, source: Int, id: Int,
                       success: Boolean): Unit = {
    dut.io.dmaCompletion(source).bits.source.poke(source.U)
    dut.io.dmaCompletion(source).bits.descriptorId.poke(id.U)
    dut.io.dmaCompletion(source).bits.success.poke(success.B)
    dut.io.dmaCompletion(source).valid.poke(true.B)
    dut.clock.step()
    dut.io.dmaCompletion(source).valid.poke(false.B)
  }

  it should "queue, dispatch, and reserve an ID until completion is consumed" in {
    simulate(new GpuCommandProcessor(
      GpuConfig(lanes = 4, warps = 2), commandIdWidth = 4,
      commandQueueDepth = 2, completionQueueDepth = 2)) { dut =>
      initialize(dut)
      driveCommand(dut, id = 3)
      dut.io.command.ready.expect(true.B)
      dut.clock.step(); dut.io.command.valid.poke(false.B)
      dut.io.busy.expect(true.B)
      dut.io.queued.expect(1.U)
      dut.io.dispatch.valid.expect(true.B)
      dut.io.dispatch.bits.commandId.expect(3.U)
      dut.io.dispatch.bits.launch.kernelPc.expect(0x1000.U)

      dut.io.dispatch.ready.poke(true.B)
      dut.clock.step()
      dut.io.inFlight.expect(1.U)

      dut.io.dispatchCompletion.valid.poke(true.B)
      dut.io.dispatchCompletion.bits.commandId.poke(3.U)
      dut.io.dispatchCompletion.bits.success.poke(true.B)
      dut.io.dispatchCompletion.ready.expect(true.B)
      dut.clock.step(); dut.io.dispatchCompletion.valid.poke(false.B)
      dut.io.inFlight.expect(0.U)
      dut.io.completion.valid.expect(true.B)
      dut.io.completion.bits.commandId.expect(3.U)
      dut.io.completion.bits.status.expect(KernelCommandStatus.success)

      // The result is visible but not consumed, so software cannot reuse ID 3.
      driveCommand(dut, id = 3, pc = 0x2000)
      dut.io.command.ready.expect(false.B)
      dut.io.duplicateCommandId.expect(true.B)
      dut.clock.step()

      dut.io.completion.ready.poke(true.B)
      dut.clock.step()
      dut.io.command.ready.expect(true.B)
    }
  }

  it should "complete invalid descriptors locally without dispatching them" in {
    simulate(new GpuCommandProcessor(
      GpuConfig(lanes = 4, warps = 2), commandIdWidth = 4,
      commandQueueDepth = 2, completionQueueDepth = 2)) { dut =>
      initialize(dut)
      dut.io.dispatch.ready.poke(true.B)
      driveCommand(dut, id = 5, pc = 0x1002)
      dut.clock.step(); dut.io.command.valid.poke(false.B)
      dut.io.dispatch.valid.expect(false.B)
      dut.clock.step()
      dut.io.completion.valid.expect(true.B)
      dut.io.completion.bits.commandId.expect(5.U)
      dut.io.completion.bits.success.expect(false.B)
      dut.io.completion.bits.status.expect(
        KernelCommandStatus.invalidProgramCounter)

      dut.io.completion.ready.poke(true.B)
      dut.clock.step()
      driveCommand(dut, id = 6, local = Seq(8, 2, 1))
      dut.clock.step(); dut.io.command.valid.poke(false.B)
      dut.clock.step()
      dut.io.completion.valid.expect(true.B)
      dut.io.completion.bits.status.expect(KernelCommandStatus.invalidLocalSize)
      dut.io.dispatch.valid.expect(false.B)
    }
  }

  it should "retain queued commands across dispatch backpressure" in {
    simulate(new GpuCommandProcessor(
      GpuConfig(lanes = 4, warps = 2), commandIdWidth = 4,
      commandQueueDepth = 2, completionQueueDepth = 2)) { dut =>
      initialize(dut)
      driveCommand(dut, id = 1, pc = 0x1000)
      dut.clock.step()
      driveCommand(dut, id = 2, pc = 0x2000)
      dut.clock.step(); dut.io.command.valid.poke(false.B)
      dut.io.queued.expect(2.U)
      dut.io.command.ready.expect(false.B)
      dut.clock.step(3)
      dut.io.dispatch.valid.expect(true.B)
      dut.io.dispatch.bits.commandId.expect(1.U)
      dut.io.dispatch.bits.launch.kernelPc.expect(0x1000.U)

      dut.io.dispatch.ready.poke(true.B)
      dut.clock.step()
      dut.io.dispatch.valid.expect(true.B)
      dut.io.dispatch.bits.commandId.expect(2.U)
      dut.io.dispatch.bits.launch.kernelPc.expect(0x2000.U)
    }
  }

  it should "dispatch only after consuming the requested successful DMA event" in {
    simulate(new GpuCommandProcessor(
      GpuConfig(lanes = 4, warps = 2), commandIdWidth = 4,
      commandQueueDepth = 2, completionQueueDepth = 2)) { dut =>
      initialize(dut)
      dut.io.dispatch.ready.poke(true.B)
      driveCommand(dut, id = 8, waitForDma = true,
        dmaSource = 1, dmaDescriptorId = 5)
      dut.clock.step(); dut.io.command.valid.poke(false.B)
      dut.io.dispatch.valid.expect(false.B)
      dut.clock.step(2)

      dmaEvent(dut, source = 1, id = 5, success = true)
      dut.io.dispatch.valid.expect(true.B)
      dut.io.dispatch.bits.commandId.expect(8.U)
      dut.clock.step()

      // Events are one-shot. Another command waiting on the same key blocks.
      driveCommand(dut, id = 9, waitForDma = true,
        dmaSource = 1, dmaDescriptorId = 5)
      dut.clock.step(); dut.io.command.valid.poke(false.B)
      dut.io.dispatch.valid.expect(false.B)
    }
  }

  it should "complete a kernel locally when its DMA dependency failed" in {
    simulate(new GpuCommandProcessor(
      GpuConfig(lanes = 4, warps = 2), commandIdWidth = 4,
      commandQueueDepth = 2, completionQueueDepth = 2)) { dut =>
      initialize(dut)
      dut.io.dispatch.ready.poke(true.B)
      driveCommand(dut, id = 10, waitForDma = true,
        dmaSource = 2, dmaDescriptorId = 6)
      dut.clock.step(); dut.io.command.valid.poke(false.B)
      dmaEvent(dut, source = 2, id = 6, success = false)
      dut.io.dispatch.valid.expect(false.B)
      dut.clock.step()
      dut.io.completion.valid.expect(true.B)
      dut.io.completion.bits.commandId.expect(10.U)
      dut.io.completion.bits.status.expect(
        KernelCommandStatus.dmaDependencyFailed)
    }
  }
}
