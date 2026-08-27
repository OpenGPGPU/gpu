package opengpu.core.backend.issue

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import opengpu.config.GpuConfig
import org.scalatest.flatspec.AnyFlatSpec

class FpuMemoryUnitSpec extends AnyFlatSpec {
  behavior of "FpuMemoryUnit"

  it should "load a word into the FP register file" in {
    simulate(new FpuMemoryUnit(GpuConfig(warps = 2))) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      dut.io.in.valid.poke(false.B)
      dut.io.in.bits.poke(0.U.asTypeOf(dut.io.in.bits))
      dut.io.cacheResponse.valid.poke(false.B)
      dut.io.cacheResponse.bits.poke(
        0.U.asTypeOf(dut.io.cacheResponse.bits))
      dut.io.commit.ready.poke(true.B)
      dut.io.fault.ready.poke(true.B)
      dut.io.cacheRequest.ready.poke(true.B)

      dut.io.in.bits.scalarRs1Data.poke("h1000".U)
      dut.io.in.bits.rs2Data.poke(0.U)
      dut.io.in.bits.decode.instruction.poke(
        "b000000000000_00001_010_00100_0000111".U)
      dut.io.in.bits.decode.pc.poke("h200".U)
      dut.io.in.bits.decode.activeMask.poke("hf".U)
      dut.io.in.bits.decode.decoded.memoryRead.poke(true.B)
      dut.io.in.valid.poke(true.B)
      dut.io.in.ready.expect(true.B)
      dut.clock.step()
      dut.io.in.valid.poke(false.B)
      dut.clock.step()

      dut.io.cacheRequest.valid.expect(true.B)
      dut.io.cacheRequest.bits.lineAddress.expect("h1000".U)
      dut.io.cacheRequest.bits.isStore.expect(false.B)
      dut.io.cacheRequest.bits.byteMask.expect("hf".U)
      dut.clock.step()

      dut.io.cacheResponse.valid.poke(true.B)
      dut.io.cacheResponse.bits.readData.poke(
        (BigInt("deadbeef", 16) << 0).U)
      dut.io.cacheResponse.bits.fault.poke(false.B)
      dut.clock.step()
      dut.io.cacheResponse.valid.poke(false.B)

      dut.io.commit.valid.expect(true.B)
      dut.io.commit.bits.isLoad.expect(true.B)
      dut.io.commit.bits.loadData.expect("hdeadbeef".U)
      dut.io.commit.bits.decode.warpId.expect(0.U)
    }
  }

  it should "store an FP word through the cache port" in {
    simulate(new FpuMemoryUnit(GpuConfig(warps = 2))) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      dut.io.in.valid.poke(false.B)
      dut.io.in.bits.poke(0.U.asTypeOf(dut.io.in.bits))
      dut.io.cacheResponse.valid.poke(false.B)
      dut.io.cacheResponse.bits.poke(
        0.U.asTypeOf(dut.io.cacheResponse.bits))
      dut.io.commit.ready.poke(true.B)
      dut.io.fault.ready.poke(true.B)
      dut.io.cacheRequest.ready.poke(true.B)

      dut.io.in.bits.scalarRs1Data.poke("h1004".U)
      dut.io.in.bits.rs2Data.poke("hcafebabe".U)
      dut.io.in.bits.decode.instruction.poke(
        "b000000000000_00001_010_00010_0100111".U)
      dut.io.in.bits.decode.decoded.memoryWrite.poke(true.B)
      dut.io.in.valid.poke(true.B)
      dut.io.in.ready.expect(true.B)
      dut.clock.step()
      dut.io.in.valid.poke(false.B)
      dut.clock.step()

      dut.io.cacheRequest.valid.expect(true.B)
      dut.io.cacheRequest.bits.lineAddress.expect("h1000".U)
      dut.io.cacheRequest.bits.isStore.expect(true.B)
      dut.io.cacheRequest.bits.byteMask.expect(BigInt("f0", 16).U)
      dut.io.cacheRequest.bits.writeData.expect(
        (BigInt("cafebabe", 16) << 32).U)
      dut.clock.step()

      dut.io.cacheResponse.valid.poke(true.B)
      dut.io.cacheResponse.bits.fault.poke(false.B)
      dut.io.cacheResponse.bits.readData.poke(0.U)
      dut.clock.step()
      dut.io.cacheResponse.valid.poke(false.B)

      dut.io.commit.valid.expect(true.B)
      dut.io.commit.bits.isLoad.expect(false.B)
    }
  }

  it should "report a misaligned FP load as a fault" in {
    simulate(new FpuMemoryUnit(GpuConfig(warps = 2))) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      dut.io.in.valid.poke(false.B)
      dut.io.in.bits.poke(0.U.asTypeOf(dut.io.in.bits))
      dut.io.cacheResponse.valid.poke(false.B)
      dut.io.cacheResponse.bits.poke(
        0.U.asTypeOf(dut.io.cacheResponse.bits))
      dut.io.commit.ready.poke(true.B)
      dut.io.fault.ready.poke(true.B)
      dut.io.cacheRequest.ready.poke(true.B)

      dut.io.in.bits.scalarRs1Data.poke("h1002".U)
      dut.io.in.bits.decode.instruction.poke(
        "b000000000000_00001_010_00100_0000111".U)
      dut.io.in.bits.decode.decoded.memoryRead.poke(true.B)
      dut.io.in.valid.poke(true.B)
      dut.clock.step()
      dut.io.in.valid.poke(false.B)
      dut.clock.step()

      dut.io.fault.valid.expect(true.B)
      dut.io.fault.bits.misaligned.expect(true.B)
      dut.io.fault.bits.isStore.expect(false.B)
    }
  }
}
