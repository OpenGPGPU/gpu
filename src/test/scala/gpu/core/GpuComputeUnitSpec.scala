package gpu.core

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import gpu.config.GpuConfig
import org.scalatest.flatspec.AnyFlatSpec

class GpuComputeUnitSpec extends AnyFlatSpec {
  behavior of "GpuComputeUnit"

  it should "run a dispatched warp through cease and complete its kernel" in {
    val config = GpuConfig(lanes = 4, warps = 2)
    simulate(new GpuComputeUnit(config)) { dut =>
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      dut.io.kernel.valid.poke(false.B)
      dut.io.completion.ready.poke(true.B)
      dut.io.memoryRequest.ready.poke(true.B)
      dut.io.memoryResponse.valid.poke(false.B)
      dut.io.memoryResponse.bits.readData.poke(0.U)
      dut.io.memoryResponse.bits.fault.poke(false.B)
      dut.io.memoryResponse.bits.transactionId.poke(0.U)
      dut.io.invalidateInstructionCache.poke(false.B)
      dut.io.instructionSatp.poke(0.U)
      dut.io.instructionTlbFlush.valid.poke(false.B)
      dut.io.instructionTlbFlush.bits.poke(
        0.U.asTypeOf(dut.io.instructionTlbFlush.bits))
      dut.io.vectorSatp.poke(0.U)
      dut.io.vectorTlbFlush.valid.poke(false.B)
      dut.io.vectorTlbFlush.bits.poke(0.U.asTypeOf(dut.io.vectorTlbFlush.bits))
      dut.io.fpu.ready.poke(false.B)
      dut.io.vector.ready.poke(false.B)
      dut.io.memory.ready.poke(false.B)
      dut.io.unsupportedSystem.ready.poke(false.B)
      dut.io.trap.ready.poke(false.B)
      dut.io.simtBranch.valid.poke(false.B)
      dut.io.simtBranch.bits.poke(0.U.asTypeOf(dut.io.simtBranch.bits))
      dut.io.l1Invalidate.valid.poke(false.B)
      dut.io.l1Invalidate.bits.lineAddress.poke(0.U)
      dut.io.l1InvalidateDone.ready.poke(true.B)
      dut.io.globalAtomicRequest.ready.poke(true.B)
      dut.io.globalAtomicResponse.valid.poke(false.B)
      dut.io.globalAtomicResponse.bits.poke(
        0.U.asTypeOf(dut.io.globalAtomicResponse.bits))

      dut.io.kernel.valid.poke(true.B)
      dut.io.kernel.bits.kernelPc.poke(0x1000.U)
      dut.io.kernel.bits.kernargAddress.poke(0x8000.U)
      (0 until 3).foreach { i =>
        dut.io.kernel.bits.gridSize(i).poke(1.U)
        dut.io.kernel.bits.localSize(i).poke(Seq(3, 1, 1)(i).U)
      }
      dut.clock.step(); dut.io.kernel.valid.poke(false.B)

      var cycles = 0
      while (!dut.io.memoryRequest.valid.peek().litToBoolean && cycles < 40) {
        dut.clock.step(); cycles += 1
      }
      assert(dut.io.memoryRequest.valid.peek().litToBoolean)
      dut.io.memoryRequest.bits.address.expect(0x1000.U)
      dut.io.memoryRequest.bits.sizeLog2.expect(6.U)
      val fetchTransactionId =
        dut.io.memoryRequest.bits.transactionId.peek().litValue
      dut.clock.step()
      dut.io.memoryResponse.valid.poke(true.B)
      dut.io.memoryResponse.bits.readData.poke("h30500073".U)
      dut.io.memoryResponse.bits.transactionId.poke(fetchTransactionId.U)
      dut.clock.step(); dut.io.memoryResponse.valid.poke(false.B)

      cycles = 0
      while (!dut.io.completion.valid.peek().litToBoolean && cycles < 40) {
        dut.clock.step(); cycles += 1
      }
      dut.io.completion.valid.expect(true.B)
      dut.io.completion.bits.success.expect(true.B)
      dut.io.active.expect(0.U)
    }
  }
}
