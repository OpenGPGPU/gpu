package opengpu.graphics

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import opengpu.config.GpuConfig
import org.scalatest.flatspec.AnyFlatSpec

class KernelShaderStageSpec extends AnyFlatSpec {
  behavior of "KernelShaderStage"

  it should "emit a draw's shader descriptor via KernelEmit and run it to completion" in {
    val config = GpuConfig(lanes = 4, warps = 2)
    simulate(new KernelShaderStage(config)) { dut =>
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      dut.io.launch.valid.poke(false.B)
      dut.io.launch.kernelPc.poke(0.U)
      dut.io.launch.kernargAddress.poke(0.U)
      dut.io.launch.gridX.poke(1.U); dut.io.launch.gridY.poke(1.U); dut.io.launch.gridZ.poke(1.U)
      dut.io.launch.localX.poke(3.U); dut.io.launch.localY.poke(1.U); dut.io.launch.localZ.poke(1.U)
      dut.io.completion.ready.poke(true.B)
      dut.io.memoryRequest.ready.poke(true.B)
      dut.io.memoryResponse.valid.poke(false.B)
      dut.io.memoryResponse.bits.readData.poke(0.U)
      dut.io.memoryResponse.bits.fault.poke(false.B)
      dut.io.memoryResponse.bits.transactionId.poke(0.U)
      dut.io.trap.ready.poke(true.B)
      dut.io.simtBranch.valid.poke(false.B)
      dut.io.simtBranch.bits.poke(0.U.asTypeOf(dut.io.simtBranch.bits))

      // A draw's shader descriptor: entry PC + kernarg buffer + grid/local.
      dut.io.launch.kernelPc.poke(0x1000.U)
      dut.io.launch.kernargAddress.poke(0x8000.U)
      dut.io.launch.localX.poke(3.U)
      dut.io.launch.valid.poke(true.B)
      assert(dut.io.launch.ready.peek().litToBoolean, "launch must be accepted")
      dut.clock.step(); dut.io.launch.valid.poke(false.B)

      // The KernelEmit-produced launch must drive the instruction fetch at the
      // descriptor's kernel PC, exactly as the compute unit harness drove it.
      var cycles = 0
      while (!dut.io.memoryRequest.valid.peek().litToBoolean && cycles < 40) {
        dut.clock.step(); cycles += 1
      }
      assert(dut.io.memoryRequest.valid.peek().litToBoolean)
      dut.io.memoryRequest.bits.address.expect(0x1000.U)
      val fetchId = dut.io.memoryRequest.bits.transactionId.peek().litValue
      dut.clock.step()
      dut.io.memoryResponse.valid.poke(true.B)
      dut.io.memoryResponse.bits.transactionId.poke(fetchId.U)
      dut.io.memoryResponse.bits.readData.poke(
        (BigInt("30500073", 16) << 32 | BigInt("00500093", 16)).U)
      dut.clock.step(); dut.io.memoryResponse.valid.poke(false.B)

      cycles = 0
      while (!dut.io.completion.valid.peek().litToBoolean && cycles < 40) {
        dut.clock.step(); cycles += 1
      }
      dut.io.completion.valid.expect(true.B)
      dut.io.completion.bits.success.expect(true.B)
    }
  }
}
