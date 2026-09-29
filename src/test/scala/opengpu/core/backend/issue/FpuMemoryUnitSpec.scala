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
      // S-type: imm[11:5] in 31:25, the source f-register in 24:20, imm[4:0]
      // in 11:7. Here the offset is zero and the source is f2.
      dut.io.in.bits.decode.instruction.poke(
        "b0000000_00010_00001_010_00000_0100111".U)
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

  // The store offset is S-type, split across 31:25 and 11:7 so that 24:20 can
  // hold the source register. The cases below are written against the unit's
  // 64-byte line, not 8: a request carries the line base and a 64-bit byte
  // mask, so a 0x10 offset from 0x1004 stays on line 0x1000 and moves the mask
  // to 0xf << 20. 0x7f0 is the case that crosses a line, and the two negative
  // offsets are the ones that put a sign in 31:25.
  it should "assemble a store offset from both halves of the S-type field" in {
    // offset, encoded instruction, expected line, expected byte mask
    val cases = Seq(
      (0x000, "b0000000_00010_00001_010_00000_0100111", 0x1000, "hf0"),
      (0x010, "b0000000_00010_00001_010_10000_0100111", 0x1000, "hf00000"),
      (0x7f0, "b0111111_00010_00001_010_10000_0100111", 0x17c0,
        "hf0000000000000"),
      (0xffc, "b1111111_00010_00001_010_11100_0100111", 0x1000, "hf"),
      (0xfe0, "b1111111_00010_00001_010_00000_0100111", 0x0fc0,
        "hf000000000"))
    for ((offset, encoded, line, mask) <- cases) {
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
        dut.io.in.bits.decode.decoded.memoryWrite.poke(true.B)
        dut.io.in.bits.decode.instruction.poke(encoded.U)
        dut.io.in.valid.poke(true.B)
        dut.io.in.ready.expect(true.B)
        dut.clock.step()
        dut.io.in.valid.poke(false.B)
        dut.clock.step()

        withClue(s"offset $offset: ") {
          dut.io.cacheRequest.valid.expect(true.B)
          dut.io.cacheRequest.bits.lineAddress.expect(line.U)
          dut.io.cacheRequest.bits.byteMask.expect(mask.U)
        }
      }
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
