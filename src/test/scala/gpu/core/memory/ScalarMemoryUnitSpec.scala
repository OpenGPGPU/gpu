package gpu.core.memory

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import gpu.config.GpuConfig
import org.scalatest.flatspec.AnyFlatSpec

class ScalarMemoryUnitSpec extends AnyFlatSpec {
  behavior of "ScalarMemoryUnit"

  private def defaults(dut: ScalarMemoryUnit): Unit = {
    dut.io.in.valid.poke(false.B)
    dut.io.cacheRequest.ready.poke(false.B)
    dut.io.cacheResponse.valid.poke(false.B)
    dut.io.commit.ready.poke(true.B)
    dut.io.fault.ready.poke(true.B)
  }

  it should "sign extend a byte load and commit through the scalar path" in {
    val config = GpuConfig(lanes = 4, warps = 1)
    simulate(new ScalarMemoryUnit(config)) { dut =>
      dut.reset.poke(true.B); defaults(dut); dut.clock.step(); dut.reset.poke(false.B)
      dut.io.in.valid.poke(true.B)
      dut.io.in.bits.decode.warpId.poke(0.U)
      dut.io.in.bits.decode.pc.poke(0x100.U)
      dut.io.in.bits.decode.activeMask.poke(0xf.U)
      dut.io.in.bits.decode.instruction.poke("h00000283".U) // lb x5, 0(x0)
      dut.io.in.bits.decode.decoded.immediate.poke(3.U)
      dut.io.in.bits.decode.decoded.rd.poke(5.U)
      dut.io.in.bits.decode.decoded.memoryRead.poke(true.B)
      dut.io.in.bits.decode.decoded.memoryWrite.poke(false.B)
      dut.io.in.bits.rs1Data.poke(0x40.U)
      dut.io.in.bits.rs2Data.poke(0.U)
      dut.clock.step(); dut.io.in.valid.poke(false.B)
      dut.io.cacheRequest.valid.expect(true.B)
      dut.io.cacheRequest.bits.lineAddress.expect(0x40.U)
      dut.io.cacheRequest.bits.byteMask.expect(8.U)
      dut.io.cacheRequest.ready.poke(true.B); dut.clock.step()
      dut.io.cacheResponse.valid.poke(true.B)
      dut.io.cacheResponse.bits.readData.poke((BigInt(0x80) << 24).U)
      dut.io.cacheResponse.bits.fault.poke(false.B)
      dut.io.cacheResponse.bits.pageFault.poke(false.B)
      dut.clock.step(); dut.io.cacheResponse.valid.poke(false.B)
      dut.io.commit.valid.expect(true.B)
      dut.io.commit.bits.rd.expect(5.U)
      dut.io.commit.bits.data.expect("hffffff80".U)
      dut.io.commit.bits.nextPc.expect(0x104.U)
    }
  }

  it should "place a halfword store and reject a misaligned word" in {
    val config = GpuConfig(lanes = 4, warps = 1)
    simulate(new ScalarMemoryUnit(config)) { dut =>
      dut.reset.poke(true.B); defaults(dut); dut.clock.step(); dut.reset.poke(false.B)
      dut.io.in.valid.poke(true.B)
      dut.io.in.bits.decode.warpId.poke(0.U)
      dut.io.in.bits.decode.pc.poke(0x200.U)
      dut.io.in.bits.decode.activeMask.poke(0xf.U)
      dut.io.in.bits.decode.instruction.poke("h00001023".U) // sh
      dut.io.in.bits.decode.decoded.immediate.poke(2.U)
      dut.io.in.bits.decode.decoded.memoryRead.poke(false.B)
      dut.io.in.bits.decode.decoded.memoryWrite.poke(true.B)
      dut.io.in.bits.rs1Data.poke(0.U)
      dut.io.in.bits.rs2Data.poke("habcd".U)
      dut.clock.step(); dut.io.in.valid.poke(false.B)
      dut.io.cacheRequest.bits.byteMask.expect("h000000000000000c".U)
      assert(((dut.io.cacheRequest.bits.writeData.peek().litValue >> 16) & 0xffff) == 0xabcd)

      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B); defaults(dut)
      dut.io.in.valid.poke(true.B)
      dut.io.in.bits.decode.warpId.poke(0.U)
      dut.io.in.bits.decode.pc.poke(0x204.U)
      dut.io.in.bits.decode.activeMask.poke(0xf.U)
      dut.io.in.bits.decode.instruction.poke("h00002083".U) // lw
      dut.io.in.bits.decode.decoded.immediate.poke(2.U)
      dut.io.in.bits.decode.decoded.memoryRead.poke(true.B)
      dut.io.in.bits.decode.decoded.memoryWrite.poke(false.B)
      dut.io.in.bits.rs1Data.poke(0.U)
      dut.io.in.bits.rs2Data.poke(0.U)
      dut.clock.step(); dut.io.in.valid.poke(false.B); dut.clock.step()
      dut.io.cacheRequest.valid.expect(false.B)
      dut.io.fault.valid.expect(true.B)
      dut.io.fault.bits.misaligned.expect(true.B)
      dut.io.fault.bits.address.expect(2.U)
    }
  }
}

class SharedCacheLinePortSpec extends AnyFlatSpec {
  behavior of "SharedCacheLinePort"
  it should "route a response back to the locked request source" in {
    val config = GpuConfig(lanes = 2, warps = 1)
    simulate(new SharedCacheLinePort(config)) { dut =>
      dut.reset.poke(true.B)
      dut.io.scalarRequest.valid.poke(false.B)
      dut.io.vectorRequest.valid.poke(false.B)
      dut.io.sharedRequest.ready.poke(true.B)
      dut.io.sharedResponse.valid.poke(false.B)
      dut.io.scalarResponse.ready.poke(false.B)
      dut.io.vectorResponse.ready.poke(true.B)
      dut.clock.step(); dut.reset.poke(false.B)
      dut.io.vectorRequest.valid.poke(true.B)
      dut.io.vectorRequest.bits.warpId.poke(0.U)
      dut.io.vectorRequest.bits.lineAddress.poke(0x80.U)
      dut.io.vectorRequest.bits.writeData.poke(0.U)
      dut.io.vectorRequest.bits.byteMask.poke(1.U)
      dut.io.vectorRequest.bits.isStore.poke(false.B)
      dut.clock.step(); dut.io.vectorRequest.valid.poke(false.B)
      dut.io.scalarRequest.valid.poke(true.B)
      dut.io.sharedRequest.valid.expect(false.B)
      dut.io.sharedResponse.valid.poke(true.B)
      dut.io.sharedResponse.bits.readData.poke("h1234".U)
      dut.io.sharedResponse.bits.fault.poke(false.B)
      dut.io.sharedResponse.bits.pageFault.poke(false.B)
      dut.io.vectorResponse.valid.expect(true.B)
      dut.io.scalarResponse.valid.expect(false.B)
      dut.clock.step()
    }
  }
}
