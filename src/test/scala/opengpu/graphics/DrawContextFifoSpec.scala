package opengpu.graphics

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec

class DrawContextFifoSpec extends AnyFlatSpec {
  behavior of "DrawContextFifo"

  private def driveContext(dut: DrawContextFifo, tag: Int): Unit = {
    // These are simulator drives, deliberately using poke rather than Chisel
    // connection syntax (:=).  The latter only builds hardware during module
    // elaboration and does not drive a DUT in a test.
    dut.io.enq.bits.shaderPc.poke((0x1000 + tag).U)
    dut.io.enq.bits.kernargBase.poke((0x2000 + tag).U)
    dut.io.enq.bits.kernargBankStride.poke((0x3000 + tag).U)
    dut.io.enq.bits.texBase.poke((0x4000 + tag).U)
    dut.io.enq.bits.texWidth.poke((16 + tag).U)
    dut.io.enq.bits.texHeight.poke((32 + tag).U)
    dut.io.enq.bits.texWrapClamp.poke((tag & 1).B)
    dut.io.enq.bits.texMaxLevel.poke((tag & 0xf).U)
    dut.io.enq.bits.texLodBias.poke((tag - 2).S)
    dut.io.enq.bits.texMinLevel.poke((tag & 0xf).U)
    dut.io.enq.bits.colorBase.poke((0x8000 + tag).U)
    dut.io.enq.bits.depthBase.poke((0x9000 + tag).U)
    dut.io.enq.bits.stride.poke((0x100 + tag).U)
    dut.io.enq.bits.depthTestEnable.poke((tag & 1).B)
    dut.io.enq.bits.depthFunc.poke((tag & 7).U)
    dut.io.enq.bits.depthWriteEnable.poke((tag & 1).B)
  }

  it should "preserve the complete context at head and tail" in {
    simulate(new DrawContextFifo(2)) { dut =>
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      dut.io.retire.poke(false.B)
      dut.io.enq.valid.poke(true.B)
      driveContext(dut, 1)
      dut.io.enq.ready.expect(true.B)
      dut.clock.step()
      dut.io.enq.valid.poke(false.B)
      dut.io.headValid.expect(true.B)
      dut.io.tailValid.expect(true.B)
      dut.io.head.shaderPc.expect(0x1001.U)
      dut.io.head.kernargBase.expect(0x2001.U)
      dut.io.head.texBase.expect(0x4001.U)
      dut.io.head.colorBase.expect(0x8001.U)
      dut.io.head.depthBase.expect(0x9001.U)
      dut.io.head.texLodBias.expect((-1).S)
      dut.io.tail.shaderPc.expect(0x1001.U)
    }
  }

  it should "retain FIFO order while the tail follows the latest admitted draw" in {
    simulate(new DrawContextFifo(2)) { dut =>
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      dut.io.retire.poke(false.B)
      dut.io.enq.valid.poke(true.B); driveContext(dut, 1); dut.clock.step()
      driveContext(dut, 2); dut.clock.step()
      dut.io.enq.valid.poke(false.B)
      dut.io.enq.ready.expect(false.B)
      dut.io.head.shaderPc.expect(0x1001.U)
      dut.io.tail.shaderPc.expect(0x1002.U)
      dut.io.retire.poke(true.B); dut.clock.step()
      dut.io.retire.poke(false.B)
      dut.io.headValid.expect(true.B)
      dut.io.head.shaderPc.expect(0x1002.U)
      dut.io.tail.shaderPc.expect(0x1002.U)
    }
  }

  it should "admit a replacement on the same edge that retires its head" in {
    simulate(new DrawContextFifo(2)) { dut =>
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      dut.io.retire.poke(false.B)
      dut.io.enq.valid.poke(true.B); driveContext(dut, 1); dut.clock.step()
      driveContext(dut, 2); dut.clock.step()
      dut.io.enq.valid.poke(true.B); dut.io.retire.poke(true.B); driveContext(dut, 3)
      dut.io.enq.ready.expect(true.B)
      dut.clock.step()
      dut.io.enq.valid.poke(false.B); dut.io.retire.poke(false.B)
      dut.io.head.shaderPc.expect(0x1002.U)
      dut.io.tail.shaderPc.expect(0x1003.U)
      dut.io.retire.poke(true.B); dut.clock.step(); dut.io.retire.poke(false.B)
      dut.io.head.shaderPc.expect(0x1003.U)
    }
  }
}
