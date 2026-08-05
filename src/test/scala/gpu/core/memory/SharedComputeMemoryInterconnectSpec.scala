package gpu.core.memory

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import gpu.config.GpuConfig
import org.scalatest.flatspec.AnyFlatSpec

class SharedComputeMemoryInterconnectSpec extends AnyFlatSpec {
  behavior of "SharedComputeMemoryInterconnect"

  it should "route globally tagged responses back to their originating CU" in {
    val config = GpuConfig(lanes = 4, warps = 2)
    simulate(new SharedComputeMemoryInterconnect(config, 2, 64, 4)) { dut =>
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      dut.io.memoryRequest.ready.poke(true.B)
      dut.io.memoryResponse.valid.poke(false.B)
      dut.io.memoryResponse.bits.readData.poke(0.U)
      dut.io.memoryResponse.bits.fault.poke(false.B)
      dut.io.memoryResponse.bits.transactionId.poke(0.U)
      (0 until 2).foreach { cu =>
        dut.io.cuRequest(cu).valid.poke(false.B)
        dut.io.cuRequest(cu).bits.poke(0.U.asTypeOf(dut.io.cuRequest(cu).bits))
        dut.io.cuResponse(cu).ready.poke(true.B)
      }

      dut.io.cuRequest(1).valid.poke(true.B)
      dut.io.cuRequest(1).bits.address.poke(0x4000.U)
      dut.io.cuRequest(1).bits.transactionId.poke(3.U)
      dut.io.memoryRequest.valid.expect(true.B)
      dut.io.memoryRequest.bits.address.expect(0x4000.U)
      // CU1 owns IDs 4..7; local ID 3 maps to global ID 7.
      dut.io.memoryRequest.bits.transactionId.expect(7.U)
      dut.clock.step()
      dut.io.cuRequest(1).valid.poke(false.B)

      dut.io.memoryResponse.valid.poke(true.B)
      dut.io.memoryResponse.bits.transactionId.poke(7.U)
      dut.io.memoryResponse.bits.readData.poke("hdeadbeef".U)
      dut.io.cuResponse(0).valid.expect(false.B)
      dut.io.cuResponse(1).valid.expect(true.B)
      dut.io.cuResponse(1).bits.transactionId.expect(3.U)
      dut.io.cuResponse(1).bits.readData.expect("hdeadbeef".U)
      dut.clock.step()
    }
  }
}
