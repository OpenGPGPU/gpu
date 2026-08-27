package opengpu.core.memory

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import opengpu.config.GpuConfig
import org.scalatest.flatspec.AnyFlatSpec

class VectorMemoryUnitSpec extends AnyFlatSpec {
  behavior of "VectorMemoryUnit"

  it should "generate unit-stride lane addresses and wait for a load response" in {
    val config = GpuConfig(lanes = 4, warps = 2)
    simulate(new VectorMemoryUnit(config)) { dut =>
      dut.reset.poke(true.B)
      dut.io.in.valid.poke(false.B)
      dut.io.memoryRequest.ready.poke(false.B)
      dut.io.memoryResponse.valid.poke(false.B)
      dut.io.out.ready.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)

      dut.io.in.valid.poke(true.B)
      dut.io.in.bits.warpId.poke(1.U)
      dut.io.in.bits.pc.poke(0x100.U)
      dut.io.in.bits.warpActiveMask.poke("b1111".U)
      dut.io.in.bits.activeMask.poke("b0101".U)
      dut.io.in.bits.vd.poke(7.U)
      dut.io.in.bits.baseAddress.poke(0x200.U)
      dut.io.in.bits.elementSize.poke(1.U)
      dut.io.in.bits.isStore.poke(false.B)
      for (lane <- 0 until config.lanes) {
        dut.io.in.bits.storeData(lane).poke(0.U)
        dut.io.in.bits.oldVd(lane).poke((0x900 + lane).U)
      }
      dut.clock.step()
      dut.io.in.valid.poke(false.B)

      dut.io.memoryRequest.valid.expect(true.B)
      dut.io.memoryRequest.bits.laneMask.expect("b0101".U)
      for (lane <- 0 until config.lanes) {
        dut.io.memoryRequest.bits.addresses(lane)
          .expect((0x200 + lane * 2).U)
      }
      dut.io.memoryRequest.ready.poke(true.B)
      dut.clock.step()
      dut.io.memoryRequest.valid.expect(false.B)
      dut.io.out.valid.expect(false.B)

      dut.io.memoryResponse.valid.poke(true.B)
      dut.io.memoryResponse.bits.faultMask.poke("b1000".U)
      dut.io.memoryResponse.bits.pageFault.poke(false.B)
      for (lane <- 0 until config.lanes) {
        dut.io.memoryResponse.bits.readData(lane)
          .poke((0xab0000 + lane * 0x10001).U)
      }
      dut.clock.step()
      dut.io.memoryResponse.valid.poke(false.B)
      dut.clock.step()
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.writesVd.expect(true.B)
      dut.io.out.bits.faultMask.expect(0.U)
      for (lane <- 0 until config.lanes) {
        val expected =
          if ((lane & 1) == 0) lane * 0x10001 & 0xffff else 0x900 + lane
        dut.io.out.bits.data(lane).expect(expected.U)
      }
      dut.clock.step()
      dut.io.out.valid.expect(false.B)
    }
  }

  it should "send vs3 data for a masked word store and complete on its ack" in {
    val config = GpuConfig(lanes = 4, warps = 1)
    simulate(new VectorMemoryUnit(config)) { dut =>
      dut.reset.poke(true.B)
      dut.io.in.valid.poke(false.B)
      dut.io.memoryRequest.ready.poke(true.B)
      dut.io.memoryResponse.valid.poke(false.B)
      dut.io.out.ready.poke(false.B)
      dut.clock.step()
      dut.reset.poke(false.B)

      dut.io.in.valid.poke(true.B)
      dut.io.in.bits.warpId.poke(0.U)
      dut.io.in.bits.pc.poke(0x300.U)
      dut.io.in.bits.warpActiveMask.poke("b1111".U)
      dut.io.in.bits.activeMask.poke("b1011".U)
      dut.io.in.bits.vd.poke(9.U)
      dut.io.in.bits.baseAddress.poke(0x400.U)
      dut.io.in.bits.elementSize.poke(2.U)
      dut.io.in.bits.isStore.poke(true.B)
      for (lane <- 0 until config.lanes) {
        dut.io.in.bits.storeData(lane).poke((0x40 + lane).U)
        dut.io.in.bits.oldVd(lane).poke(0.U)
      }
      dut.clock.step()
      dut.io.in.valid.poke(false.B)
      for (lane <- 0 until config.lanes) {
        dut.io.memoryRequest.bits.addresses(lane)
          .expect((0x400 + lane * 4).U)
        dut.io.memoryRequest.bits.writeData(lane).expect((0x40 + lane).U)
      }
      dut.io.memoryRequest.valid.expect(true.B)
      dut.clock.step()

      dut.io.memoryResponse.valid.poke(true.B)
      dut.io.memoryResponse.bits.faultMask.poke(0.U)
      dut.io.memoryResponse.bits.pageFault.poke(false.B)
      for (lane <- 0 until config.lanes) {
        dut.io.memoryResponse.bits.readData(lane).poke(0.U)
      }
      dut.clock.step()
      dut.io.memoryResponse.valid.poke(false.B)
      dut.clock.step()
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.writesVd.expect(false.B)
      dut.clock.step(2)
      dut.io.out.valid.expect(true.B)
      dut.io.out.ready.poke(true.B)
      dut.clock.step()
    }
  }
}
