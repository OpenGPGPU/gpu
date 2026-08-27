package opengpu.core.memory

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import opengpu.config.GpuConfig
import org.scalatest.flatspec.AnyFlatSpec

class VectorMemoryCoalescerSpec extends AnyFlatSpec {
  behavior of "VectorMemoryCoalescer"

  it should "merge lanes, split a crossing vector, and reassemble load data" in {
    val config = GpuConfig(lanes = 4, warps = 2)
    val lineBytes = 16
    simulate(new VectorMemoryCoalescer(config, lineBytes)) { dut =>
      dut.reset.poke(true.B)
      dut.io.in.valid.poke(false.B)
      dut.io.cacheRequest.ready.poke(true.B)
      dut.io.cacheResponse.valid.poke(false.B)
      dut.io.out.ready.poke(false.B)
      dut.clock.step()
      dut.reset.poke(false.B)

      // Four words beginning at 0x0c occupy one word in line 0 and three in 1.
      dut.io.in.valid.poke(true.B)
      dut.io.in.bits.warpId.poke(1.U)
      dut.io.in.bits.laneMask.poke("b1111".U)
      dut.io.in.bits.elementSize.poke(2.U)
      dut.io.in.bits.isStore.poke(false.B)
      for (lane <- 0 until config.lanes) {
        dut.io.in.bits.addresses(lane).poke((0x0c + lane * 4).U)
        dut.io.in.bits.writeData(lane).poke(0.U)
      }
      dut.clock.step()
      dut.io.in.valid.poke(false.B)

      dut.io.cacheRequest.valid.expect(true.B)
      dut.io.cacheRequest.bits.lineAddress.expect(0x00.U)
      dut.io.cacheRequest.bits.byteMask.expect("hf000".U)
      dut.clock.step()
      dut.io.cacheResponse.valid.poke(true.B)
      dut.io.cacheResponse.bits.fault.poke(false.B)
      dut.io.cacheResponse.bits.pageFault.poke(false.B)
      val firstLine = (0 until lineBytes).foldLeft(BigInt(0)) {
        case (value, byte) => value | (BigInt(byte) << (byte * 8))
      }
      dut.io.cacheResponse.bits.readData.poke(firstLine.U)
      dut.clock.step()
      dut.io.cacheResponse.valid.poke(false.B)

      dut.io.cacheRequest.valid.expect(true.B)
      dut.io.cacheRequest.bits.lineAddress.expect(0x10.U)
      dut.io.cacheRequest.bits.byteMask.expect("h0fff".U)
      dut.clock.step()
      dut.io.cacheResponse.valid.poke(true.B)
      dut.io.cacheResponse.bits.fault.poke(true.B)
      dut.io.cacheResponse.bits.pageFault.poke(true.B)
      val secondLine = (0 until lineBytes).foldLeft(BigInt(0)) {
        case (value, byte) =>
          value | (BigInt(0x10 + byte) << (byte * 8))
      }
      dut.io.cacheResponse.bits.readData.poke(secondLine.U)
      dut.clock.step()
      dut.io.cacheResponse.valid.poke(false.B)

      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.readData(0).expect("h0f0e0d0c".U)
      dut.io.out.bits.readData(1).expect("h13121110".U)
      dut.io.out.bits.readData(2).expect("h17161514".U)
      dut.io.out.bits.readData(3).expect("h1b1a1918".U)
      dut.io.out.bits.faultMask.expect("b1110".U)
      dut.io.out.bits.pageFault.expect(true.B)
      dut.clock.step(2)
      dut.io.out.valid.expect(true.B)
      dut.io.out.ready.poke(true.B)
      dut.clock.step()
    }
  }

  it should "place masked store bytes into the correct cache lines" in {
    val config = GpuConfig(lanes = 4, warps = 1)
    val lineBytes = 16
    simulate(new VectorMemoryCoalescer(config, lineBytes)) { dut =>
      dut.reset.poke(true.B)
      dut.io.in.valid.poke(false.B)
      dut.io.cacheRequest.ready.poke(false.B)
      dut.io.cacheResponse.valid.poke(false.B)
      dut.io.out.ready.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)

      dut.io.in.valid.poke(true.B)
      dut.io.in.bits.warpId.poke(0.U)
      dut.io.in.bits.laneMask.poke("b1101".U)
      dut.io.in.bits.elementSize.poke(1.U)
      dut.io.in.bits.isStore.poke(true.B)
      for (lane <- 0 until config.lanes) {
        dut.io.in.bits.addresses(lane).poke((0x0e + lane * 2).U)
        dut.io.in.bits.writeData(lane).poke((0x1100 + lane).U)
      }
      dut.clock.step()
      dut.io.in.valid.poke(false.B)

      dut.io.cacheRequest.bits.lineAddress.expect(0.U)
      dut.io.cacheRequest.bits.byteMask.expect("hc000".U)
      assert(
        ((dut.io.cacheRequest.bits.writeData.peek().litValue >> 112) &
          0xffff) == 0x1100
      )
      dut.io.cacheRequest.ready.poke(true.B)
      dut.clock.step()
      dut.io.cacheResponse.valid.poke(true.B)
      dut.io.cacheResponse.bits.readData.poke(0.U)
      dut.io.cacheResponse.bits.fault.poke(false.B)
      dut.io.cacheResponse.bits.pageFault.poke(false.B)
      dut.clock.step()
      dut.io.cacheResponse.valid.poke(false.B)

      dut.io.cacheRequest.bits.lineAddress.expect(0x10.U)
      // lane 1 is masked; lanes 2 and 3 occupy byte offsets 2..5.
      dut.io.cacheRequest.bits.byteMask.expect("h003c".U)
      val writeData = dut.io.cacheRequest.bits.writeData.peek().litValue
      assert(((writeData >> 16) & 0xffff) == 0x1102)
      assert(((writeData >> 32) & 0xffff) == 0x1103)
    }
  }
}
