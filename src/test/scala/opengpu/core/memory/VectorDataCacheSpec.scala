package opengpu.core.memory

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import opengpu.config.GpuConfig
import org.scalatest.flatspec.AnyFlatSpec

class VectorDataCacheSpec extends AnyFlatSpec {
  behavior of "VectorDataCache"

  it should "refill a load miss and serve the next access as a hit" in {
    val config = GpuConfig(lanes = 4, warps = 2)
    val lineBytes = 16
    simulate(new VectorDataCache(config, sets = 4, lineBytes)) { dut =>
      dut.reset.poke(true.B)
      dut.io.in.valid.poke(false.B)
      dut.io.out.ready.poke(true.B)
      dut.io.lowerRequest.ready.poke(true.B)
      dut.io.lowerResponse.valid.poke(false.B)
      dut.io.invalidate.valid.poke(false.B)
      dut.io.invalidateDone.ready.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)

      def requestLoad(address: Int): Unit = {
        dut.io.in.valid.poke(true.B)
        dut.io.in.bits.warpId.poke(1.U)
        dut.io.in.bits.lineAddress.poke(address.U)
        dut.io.in.bits.writeData.poke(0.U)
        dut.io.in.bits.byteMask.poke("hffff".U)
        dut.io.in.bits.isStore.poke(false.B)
        dut.io.in.ready.expect(true.B)
        dut.clock.step()
        dut.io.in.valid.poke(false.B)
      }

      requestLoad(0x40)
      dut.clock.step()
      dut.io.lowerRequest.valid.expect(true.B)
      dut.io.lowerRequest.bits.lineAddress.expect(0x40.U)
      dut.io.lowerRequest.bits.isWrite.expect(false.B)
      dut.clock.step()

      val refill = BigInt("ffeeddccbbaa99887766554433221100", 16)
      dut.io.lowerResponse.valid.poke(true.B)
      dut.io.lowerResponse.bits.readData.poke(refill.U)
      dut.io.lowerResponse.bits.fault.poke(false.B)
      dut.clock.step()
      dut.io.lowerResponse.valid.poke(false.B)
      dut.io.invalidate.valid.poke(false.B)
      dut.io.invalidateDone.ready.poke(true.B)
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.readData.expect(refill.U)
      dut.clock.step()

      requestLoad(0x40)
      dut.clock.step()
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.readData.expect(refill.U)
      dut.io.lowerRequest.valid.expect(false.B)
    }
  }

  it should "write through stores and update a hit only after a good ack" in {
    val config = GpuConfig(lanes = 4, warps = 1)
    val lineBytes = 16
    simulate(new VectorDataCache(config, sets = 4, lineBytes)) { dut =>
      dut.reset.poke(true.B)
      dut.io.in.valid.poke(false.B)
      dut.io.out.ready.poke(true.B)
      dut.io.lowerRequest.ready.poke(true.B)
      dut.io.lowerResponse.valid.poke(false.B)
      dut.io.invalidate.valid.poke(false.B)
      dut.io.invalidateDone.ready.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)

      def send(
        isStore: Boolean,
        writeData: BigInt = 0,
        byteMask: Int = 0xffff
      ): Unit = {
        dut.io.in.valid.poke(true.B)
        dut.io.in.bits.warpId.poke(0.U)
        dut.io.in.bits.lineAddress.poke(0x80.U)
        dut.io.in.bits.writeData.poke(writeData.U)
        dut.io.in.bits.byteMask.poke(byteMask.U)
        dut.io.in.bits.isStore.poke(isStore.B)
        dut.clock.step()
        dut.io.in.valid.poke(false.B)
      }

      // Allocate the line with a load miss.
      send(isStore = false)
      dut.clock.step(2)
      dut.io.lowerResponse.valid.poke(true.B)
      dut.io.lowerResponse.bits.readData.poke(0.U)
      dut.io.lowerResponse.bits.fault.poke(false.B)
      dut.clock.step()
      dut.io.lowerResponse.valid.poke(false.B)
      dut.clock.step()

      send(isStore = true, writeData = BigInt("00000000aabbccdd", 16), byteMask = 0xf)
      dut.clock.step()
      dut.io.lowerRequest.valid.expect(true.B)
      dut.io.lowerRequest.bits.isWrite.expect(true.B)
      dut.io.lowerRequest.bits.byteMask.expect("h000f".U)
      dut.clock.step()
      dut.io.lowerResponse.valid.poke(true.B)
      dut.io.lowerResponse.bits.readData.poke(0.U)
      dut.io.lowerResponse.bits.fault.poke(false.B)
      dut.clock.step()
      dut.io.lowerResponse.valid.poke(false.B)
      dut.clock.step()

      send(isStore = false)
      dut.clock.step()
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.readData.expect("haabbccdd".U)
      dut.io.lowerRequest.valid.expect(false.B)
    }
  }

  it should "propagate lower-memory faults without allocating the line" in {
    val config = GpuConfig(lanes = 2, warps = 1)
    simulate(new VectorDataCache(config, sets = 2, lineBytes = 16)) { dut =>
      dut.reset.poke(true.B)
      dut.io.in.valid.poke(false.B)
      dut.io.out.ready.poke(true.B)
      dut.io.lowerRequest.ready.poke(true.B)
      dut.io.lowerResponse.valid.poke(false.B)
      dut.clock.step()
      dut.reset.poke(false.B)

      dut.io.in.valid.poke(true.B)
      dut.io.in.bits.warpId.poke(0.U)
      dut.io.in.bits.lineAddress.poke(0xc0.U)
      dut.io.in.bits.writeData.poke(0.U)
      dut.io.in.bits.byteMask.poke("hffff".U)
      dut.io.in.bits.isStore.poke(false.B)
      dut.clock.step()
      dut.io.in.valid.poke(false.B)
      dut.clock.step(2)
      dut.io.lowerResponse.valid.poke(true.B)
      dut.io.lowerResponse.bits.readData.poke("h1234".U)
      dut.io.lowerResponse.bits.fault.poke(true.B)
      dut.clock.step()
      dut.io.lowerResponse.valid.poke(false.B)
      dut.io.out.bits.fault.expect(true.B)
      dut.clock.step()

      // The same line must miss again because a faulting refill is not valid.
      dut.io.in.valid.poke(true.B)
      dut.clock.step()
      dut.io.in.valid.poke(false.B)
      dut.clock.step()
      dut.io.lowerRequest.valid.expect(true.B)
    }
  }

  it should "retain two conflicting tags and replace only one way" in {
    val config = GpuConfig(lanes = 2, warps = 1)
    simulate(new VectorDataCache(config, sets = 2, ways = 2, lineBytes = 16)) {
      dut =>
        dut.reset.poke(true.B)
        dut.io.in.valid.poke(false.B)
        dut.io.out.ready.poke(true.B)
        dut.io.lowerRequest.ready.poke(true.B)
        dut.io.lowerResponse.valid.poke(false.B)
        dut.io.invalidate.valid.poke(false.B)
        dut.io.invalidateDone.ready.poke(true.B)
        dut.clock.step()
        dut.reset.poke(false.B)

        def startLoad(address: Int): Unit = {
          dut.io.in.valid.poke(true.B)
          dut.io.in.bits.warpId.poke(0.U)
          dut.io.in.bits.lineAddress.poke(address.U)
          dut.io.in.bits.writeData.poke(0.U)
          dut.io.in.bits.byteMask.poke("hffff".U)
          dut.io.in.bits.isStore.poke(false.B)
          dut.io.in.ready.expect(true.B)
          dut.clock.step()
          dut.io.in.valid.poke(false.B)
          dut.clock.step()
        }

        def refill(address: Int, value: Int): Unit = {
          startLoad(address)
          dut.io.lowerRequest.valid.expect(true.B)
          dut.clock.step()
          dut.io.lowerResponse.valid.poke(true.B)
          dut.io.lowerResponse.bits.readData.poke(value.U)
          dut.io.lowerResponse.bits.fault.poke(false.B)
          dut.clock.step()
          dut.io.lowerResponse.valid.poke(false.B)
          dut.io.out.bits.readData.expect(value.U)
          dut.clock.step()
        }

        def expectHit(address: Int, value: Int): Unit = {
          startLoad(address)
          dut.io.out.valid.expect(true.B)
          dut.io.out.bits.readData.expect(value.U)
          dut.io.lowerRequest.valid.expect(false.B)
          dut.clock.step()
        }

        // With two sets and 16-byte lines, these tags all map to set zero.
        refill(0x00, 0x11)
        refill(0x20, 0x22)
        expectHit(0x00, 0x11)
        expectHit(0x20, 0x22)

        refill(0x40, 0x33)
        expectHit(0x20, 0x22)
        startLoad(0x00)
        dut.io.lowerRequest.valid.expect(true.B)
      }
  }


  it should "acknowledge an L2 probe only after invalidating the matching line" in {
    val config = GpuConfig(lanes = 2, warps = 1)
    simulate(new VectorDataCache(config, sets = 2, ways = 1, lineBytes = 16)) { dut =>
      dut.reset.poke(true.B)
      dut.io.in.valid.poke(false.B)
      dut.io.out.ready.poke(true.B)
      dut.io.lowerRequest.ready.poke(true.B)
      dut.io.lowerResponse.valid.poke(false.B)
      dut.io.invalidate.valid.poke(false.B)
      dut.io.invalidateDone.ready.poke(true.B)
      dut.clock.step(); dut.reset.poke(false.B)

      dut.io.in.valid.poke(true.B)
      dut.io.in.bits.poke(0.U.asTypeOf(dut.io.in.bits))
      dut.io.in.bits.lineAddress.poke(0x40.U)
      dut.clock.step(); dut.io.in.valid.poke(false.B)
      dut.clock.step(2)
      dut.io.lowerResponse.valid.poke(true.B)
      dut.io.lowerResponse.bits.readData.poke(0x77.U)
      dut.io.lowerResponse.bits.fault.poke(false.B)
      dut.clock.step(); dut.io.lowerResponse.valid.poke(false.B)
      dut.clock.step()

      dut.io.invalidate.valid.poke(true.B)
      dut.io.invalidate.bits.lineAddress.poke(0x40.U)
      dut.clock.step(); dut.io.invalidate.valid.poke(false.B)
      dut.io.invalidateDone.valid.expect(true.B)
      dut.io.invalidateDone.bits.lineAddress.expect(0x40.U)
      dut.clock.step()

      dut.io.in.valid.poke(true.B)
      dut.io.in.bits.lineAddress.poke(0x40.U)
      dut.clock.step(); dut.io.in.valid.poke(false.B)
      dut.clock.step()
      dut.io.lowerRequest.valid.expect(true.B)
    }
  }
}
