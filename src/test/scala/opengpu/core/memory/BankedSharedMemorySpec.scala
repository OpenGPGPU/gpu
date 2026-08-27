package opengpu.core.memory

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import opengpu.config.GpuConfig
import org.scalatest.flatspec.AnyFlatSpec

class BankedSharedMemorySpec extends AnyFlatSpec {
  behavior of "BankedSharedMemory"

  it should "store and load lane words through byte-interleaved banks" in {
    val config = GpuConfig(lanes = 4, warps = 2,
      sharedMemoryBytes = 1024, sharedMemoryBanks = 8)
    simulate(new BankedSharedMemory(config)) { dut =>
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      dut.io.in.valid.poke(false.B)
      dut.io.out.ready.poke(true.B)
      dut.io.atomicIn.valid.poke(false.B)
      dut.io.atomicOut.ready.poke(true.B)

      def request(store: Boolean): Unit = {
        dut.io.in.valid.poke(true.B)
        dut.io.in.bits.warpId.poke(0.U)
        dut.io.in.bits.laneMask.poke("b1111".U)
        dut.io.in.bits.elementSize.poke(2.U)
        dut.io.in.bits.isStore.poke(store.B)
        (0 until 4).foreach { lane =>
          dut.io.in.bits.addresses(lane)
            .poke((config.sharedMemoryBase + lane * 4).U)
          dut.io.in.bits.writeData(lane).poke((0x11223340L + lane).U)
        }
        dut.io.in.ready.expect(true.B)
        dut.clock.step()
        dut.io.in.valid.poke(false.B)
      }

      request(store = true)
      var cycles = 0
      while (!dut.io.out.valid.peek().litToBoolean && cycles < 16) {
        dut.clock.step(); cycles += 1
      }
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.faultMask.expect(0.U)
      dut.clock.step()

      request(store = false)
      cycles = 0
      while (!dut.io.out.valid.peek().litToBoolean && cycles < 16) {
        dut.clock.step(); cycles += 1
      }
      dut.io.out.valid.expect(true.B)
      (0 until 4).foreach { lane =>
        dut.io.out.bits.readData(lane).expect((0x11223340L + lane).U)
      }
      dut.io.out.bits.faultMask.expect(0.U)
    }
  }

  it should "perform an indivisible word AMO on the same bank storage" in {
    val config = GpuConfig(lanes = 1, warps = 1,
      sharedMemoryBytes = 256, sharedMemoryBanks = 4)
    simulate(new BankedSharedMemory(config)) { dut =>
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      dut.io.out.ready.poke(true.B)
      dut.io.atomicOut.ready.poke(true.B)
      dut.io.atomicIn.valid.poke(false.B)

      // Initialize the target word through the normal vector-memory port.
      dut.io.in.valid.poke(true.B)
      dut.io.in.bits.poke(0.U.asTypeOf(dut.io.in.bits))
      dut.io.in.bits.laneMask.poke(1.U)
      dut.io.in.bits.elementSize.poke(2.U)
      dut.io.in.bits.isStore.poke(true.B)
      dut.io.in.bits.addresses(0).poke(config.sharedMemoryBase.U)
      dut.io.in.bits.writeData(0).poke(10.U)
      dut.clock.step(); dut.io.in.valid.poke(false.B)
      while (!dut.io.out.valid.peek().litToBoolean) { dut.clock.step() }
      dut.clock.step()

      dut.io.atomicIn.valid.poke(true.B)
      dut.io.atomicIn.bits.warpId.poke(0.U)
      dut.io.atomicIn.bits.address.poke(config.sharedMemoryBase.U)
      dut.io.atomicIn.bits.operand.poke(7.U)
      dut.io.atomicIn.bits.operation.poke(AtomicMemoryOp.add)
      dut.io.atomicIn.ready.expect(true.B)
      dut.clock.step(); dut.io.atomicIn.valid.poke(false.B)
      var cycles = 0
      while (!dut.io.atomicOut.valid.peek().litToBoolean && cycles < 12) {
        dut.clock.step(); cycles += 1
      }
      dut.io.atomicOut.valid.expect(true.B)
      dut.io.atomicOut.bits.oldValue.expect(10.U)
      dut.io.atomicOut.bits.fault.expect(false.B)
      dut.clock.step()

      dut.io.in.valid.poke(true.B)
      dut.io.in.bits.isStore.poke(false.B)
      dut.clock.step(); dut.io.in.valid.poke(false.B)
      while (!dut.io.out.valid.peek().litToBoolean) { dut.clock.step() }
      dut.io.out.bits.readData(0).expect(17.U)
    }
  }

  it should "serialize conflicts and report a lane crossing the local window" in {
    val config = GpuConfig(lanes = 2, warps = 2,
      sharedMemoryBytes = 256, sharedMemoryBanks = 4)
    simulate(new BankedSharedMemory(config)) { dut =>
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      dut.io.out.ready.poke(false.B)
      dut.io.atomicIn.valid.poke(false.B)
      dut.io.atomicOut.ready.poke(true.B)
      dut.io.in.valid.poke(true.B)
      dut.io.in.bits.poke(0.U.asTypeOf(dut.io.in.bits))
      dut.io.in.bits.laneMask.poke("b11".U)
      dut.io.in.bits.elementSize.poke(2.U)
      dut.io.in.bits.addresses(0).poke(config.sharedMemoryBase.U)
      dut.io.in.bits.addresses(1)
        .poke((config.sharedMemoryBase + config.sharedMemoryBytes - 2).U)
      dut.clock.step()
      dut.io.in.valid.poke(false.B)
      var cycles = 0
      while (!dut.io.out.valid.peek().litToBoolean && cycles < 16) {
        dut.clock.step(); cycles += 1
      }
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.faultMask.expect("b10".U)
      dut.io.idle.expect(false.B)
    }
  }
}
