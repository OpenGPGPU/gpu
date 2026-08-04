package gpu.core.memory

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import gpu.config.GpuConfig
import org.scalatest.flatspec.AnyFlatSpec

class InstructionCacheSpec extends AnyFlatSpec {
  private val config = GpuConfig(warps = 2)

  private def defaults(dut: InstructionCache): Unit = {
    dut.io.fetch.valid.poke(false.B)
    dut.io.response.ready.poke(true.B)
    dut.io.lowerRequest.ready.poke(true.B)
    dut.io.lowerResponse.valid.poke(false.B)
    dut.io.lowerResponse.bits.readData.poke(0.U)
    dut.io.lowerResponse.bits.fault.poke(false.B)
    dut.io.invalidate.poke(false.B)
  }

  private def request(dut: InstructionCache, address: BigInt, warpId: Int = 0): Unit = {
    dut.io.fetch.valid.poke(true.B)
    dut.io.fetch.bits.warpId.poke(warpId.U)
    dut.io.fetch.bits.pc.poke(address.U)
    while (!dut.io.fetch.ready.peek().litToBoolean) { dut.clock.step() }
    dut.clock.step(); dut.io.fetch.valid.poke(false.B)
  }

  private def refill(dut: InstructionCache, line: BigInt, fault: Boolean = false): Unit = {
    while (!dut.io.lowerRequest.valid.peek().litToBoolean) { dut.clock.step() }
    val requestId = dut.io.lowerRequest.bits.requestId.peek().litValue
    dut.clock.step()
    dut.io.lowerResponse.valid.poke(true.B)
    dut.io.lowerResponse.bits.readData.poke(line.U)
    dut.io.lowerResponse.bits.fault.poke(fault.B)
    dut.io.lowerResponse.bits.requestId.poke(requestId.U)
    dut.clock.step(); dut.io.lowerResponse.valid.poke(false.B)
  }

  behavior of "InstructionCache"

  it should "refill a line and serve another word as a hit" in {
    simulate(new InstructionCache(config, sets = 4, ways = 2, lineBytes = 16)) { dut =>
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B); defaults(dut)
      val line = BigInt("44444444333333332222222211111111", 16)
      request(dut, 0x04)
      dut.io.lowerRequest.valid.expect(true.B)
      dut.io.lowerRequest.bits.lineAddress.expect(0.U)
      refill(dut, line)
      dut.io.response.valid.expect(true.B)
      dut.io.response.bits.instruction.expect("h22222222".U)
      dut.clock.step()

      request(dut, 0x0c)
      dut.io.response.valid.expect(true.B)
      dut.io.response.bits.instruction.expect("h44444444".U)
      dut.io.lowerRequest.valid.expect(false.B)
    }
  }

  it should "invalidate hits and never allocate a faulting refill" in {
    simulate(new InstructionCache(config, sets = 4, ways = 2, lineBytes = 16)) { dut =>
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B); defaults(dut)
      request(dut, 0x20); refill(dut, BigInt("deadbeef", 16))
      dut.clock.step()
      dut.io.invalidate.poke(true.B); dut.clock.step(); dut.io.invalidate.poke(false.B)
      request(dut, 0x20)
      dut.io.lowerRequest.valid.expect(true.B)
      refill(dut, 0, fault = true)
      dut.io.response.bits.accessFault.expect(true.B)
      dut.clock.step()
      request(dut, 0x20)
      dut.io.lowerRequest.valid.expect(true.B)
    }
  }

  it should "report a misaligned PC without accessing lower memory" in {
    simulate(new InstructionCache(config, sets = 4, ways = 2, lineBytes = 16)) { dut =>
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B); defaults(dut)
      request(dut, 0x02)
      dut.io.response.valid.expect(true.B)
      dut.io.response.bits.accessFault.expect(true.B)
      dut.io.lowerRequest.valid.expect(false.B)
    }
  }

  it should "keep multiple misses and accept out-of-order refills" in {
    simulate(new InstructionCache(config, sets = 4, ways = 2, lineBytes = 16,
      missEntries = 2)) { dut =>
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B); defaults(dut)
      dut.io.response.ready.poke(false.B)

      request(dut, 0x00)
      val firstId = dut.io.lowerRequest.bits.requestId.peek().litValue
      dut.clock.step()
      request(dut, 0x40, warpId = 1)
      val secondId = dut.io.lowerRequest.bits.requestId.peek().litValue
      assert(firstId != secondId)
      dut.clock.step()

      dut.io.lowerResponse.valid.poke(true.B)
      dut.io.lowerResponse.bits.requestId.poke(secondId.U)
      dut.io.lowerResponse.bits.readData.poke("hbbbbbbbb".U)
      dut.io.lowerResponse.bits.fault.poke(false.B)
      dut.clock.step()
      dut.io.lowerResponse.bits.requestId.poke(firstId.U)
      dut.io.lowerResponse.bits.readData.poke("haaaaaaaa".U)
      dut.clock.step(); dut.io.lowerResponse.valid.poke(false.B)

      dut.io.response.valid.expect(true.B)
      dut.io.response.bits.warpId.expect(1.U)
      dut.io.response.bits.instruction.expect("hbbbbbbbb".U)
      dut.io.response.ready.poke(true.B); dut.clock.step()
      dut.io.response.valid.expect(true.B)
      dut.io.response.bits.warpId.expect(0.U)
      dut.io.response.bits.instruction.expect("haaaaaaaa".U)
    }
  }
}
