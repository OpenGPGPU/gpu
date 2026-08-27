package opengpu.core.frontend.simt

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import opengpu.config.GpuConfig
import org.scalatest.flatspec.AnyFlatSpec

class SimtStackSpec extends AnyFlatSpec {
  behavior of "SimtStack"

  private def driveEntry(
    dut: SimtStack,
    pc: Int,
    activeMask: Int,
    originalMask: Int,
    divergent: Boolean
  ): Unit = {
    dut.io.push.bits.pc.poke(pc.U)
    dut.io.push.bits.activeMask.poke(activeMask.U)
    dut.io.push.bits.originalMask.poke(originalMask.U)
    dut.io.push.bits.divergent.poke(divergent.B)
  }

  it should "push and pop control-flow entries in LIFO order" in {
    val config = GpuConfig(lanes = 4, simtStackDepth = 2)
    simulate(new SimtStack(config)) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      dut.io.clear.poke(false.B)
      dut.io.pop.ready.poke(false.B)
      dut.io.push.valid.poke(true.B)

      driveEntry(dut, 0x100, 0x3, 0xf, divergent = false)
      dut.clock.step()
      driveEntry(dut, 0x200, 0xc, 0xf, divergent = true)
      dut.clock.step()

      dut.io.push.valid.poke(false.B)
      dut.io.full.expect(true.B)
      dut.io.count.expect(2.U)
      dut.io.pop.valid.expect(true.B)
      dut.io.pop.bits.pc.expect(0x200.U)
      dut.io.pop.bits.activeMask.expect(0xc.U)
      dut.io.pop.bits.originalMask.expect(0xf.U)
      dut.io.pop.bits.divergent.expect(true.B)

      dut.io.pop.ready.poke(true.B)
      dut.clock.step()
      dut.io.pop.bits.pc.expect(0x100.U)
      dut.io.pop.bits.divergent.expect(false.B)
      dut.clock.step()
      dut.io.empty.expect(true.B)
      dut.io.pop.valid.expect(false.B)
    }
  }

  it should "replace a full-stack top with simultaneous pop and push" in {
    val config = GpuConfig(lanes = 4, simtStackDepth = 2)
    simulate(new SimtStack(config)) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      dut.io.clear.poke(false.B)
      dut.io.pop.ready.poke(false.B)
      dut.io.push.valid.poke(true.B)

      driveEntry(dut, 0x10, 1, 0xf, divergent = false)
      dut.clock.step()
      driveEntry(dut, 0x20, 2, 0xf, divergent = true)
      dut.clock.step()

      dut.io.pop.ready.poke(true.B)
      driveEntry(dut, 0x30, 4, 0xf, divergent = true)
      dut.io.push.ready.expect(true.B)
      dut.clock.step()

      dut.io.push.valid.poke(false.B)
      dut.io.pop.ready.poke(false.B)
      dut.io.count.expect(2.U)
      dut.io.pop.bits.pc.expect(0x30.U)

      dut.io.clear.poke(true.B)
      dut.clock.step()
      dut.io.empty.expect(true.B)
      dut.io.count.expect(0.U)
    }
  }
}
