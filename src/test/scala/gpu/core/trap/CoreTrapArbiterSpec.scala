package gpu.core.trap

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import gpu.config.GpuConfig
import org.scalatest.flatspec.AnyFlatSpec

class CoreTrapArbiterSpec extends AnyFlatSpec {
  behavior of "CoreTrapArbiter"

  it should "report an illegal scalar instruction with instruction tval" in {
    val config = GpuConfig(lanes = 4, warps = 2)
    simulate(new CoreTrapArbiter(config)) { dut =>
      dut.reset.poke(true.B)
      dut.io.scalar.valid.poke(false.B)
      dut.io.vector.valid.poke(false.B)
      dut.io.scalarMemory.valid.poke(false.B)
      dut.io.out.ready.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)

      dut.io.scalar.valid.poke(true.B)
      dut.io.scalar.bits.decode.warpId.poke(1.U)
      dut.io.scalar.bits.decode.pc.poke(0x1000.U)
      dut.io.scalar.bits.decode.activeMask.poke("b1111".U)
      dut.io.scalar.bits.decode.instruction.poke("hffffffff".U)
      dut.io.scalar.bits.decode.instructionAccessFault.poke(false.B)
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.cause.expect(TrapCause.illegalInstruction.U)
      dut.io.out.bits.tval.expect("hffffffff".U)
      dut.clock.step()
    }
  }

  it should "select the first faulting lane address and preserve page cause" in {
    val config = GpuConfig(lanes = 4, warps = 1)
    simulate(new CoreTrapArbiter(config)) { dut =>
      dut.reset.poke(true.B)
      dut.io.scalar.valid.poke(false.B)
      dut.io.vector.valid.poke(false.B)
      dut.io.scalarMemory.valid.poke(false.B)
      dut.io.out.ready.poke(false.B)
      dut.clock.step()
      dut.reset.poke(false.B)

      dut.io.vector.valid.poke(true.B)
      dut.io.vector.bits.warpId.poke(0.U)
      dut.io.vector.bits.pc.poke(0x2000.U)
      dut.io.vector.bits.warpActiveMask.poke("b1111".U)
      dut.io.vector.bits.faultMask.poke("b1010".U)
      dut.io.vector.bits.pageFault.poke(true.B)
      dut.io.vector.bits.isStore.poke(true.B)
      for (lane <- 0 until config.lanes) {
        dut.io.vector.bits.addresses(lane).poke((0x4000 + lane * 4).U)
      }
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.cause.expect(TrapCause.storePageFault.U)
      dut.io.out.bits.tval.expect(0x4004.U)
      dut.io.out.bits.laneFaultMask.expect("b1010".U)
      dut.clock.step(2)
      dut.io.out.valid.expect(true.B)
      dut.io.out.ready.poke(true.B)
      dut.clock.step()
    }
  }
}
