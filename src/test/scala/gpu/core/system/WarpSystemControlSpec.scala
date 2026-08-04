package gpu.core.system

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import gpu.config.GpuConfig
import org.scalatest.flatspec.AnyFlatSpec

class WarpSystemControlSpec extends AnyFlatSpec {
  behavior of "WarpSystemControl"

  it should "finish cease and backpressure join atomically" in {
    val config = GpuConfig(lanes = 4, warps = 2)
    simulate(new WarpSystemControl(config)) { dut =>
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      dut.io.in.valid.poke(true.B)
      dut.io.in.bits.poke(0.U.asTypeOf(dut.io.in.bits))
      dut.io.in.bits.decode.warpId.poke(1.U)
      dut.io.in.bits.decode.decoded.cease.poke(true.B)
      dut.io.restore.ready.poke(false.B)
      dut.io.resume.ready.poke(false.B)
      dut.io.unsupported.ready.poke(false.B)
      dut.io.in.ready.expect(true.B)
      dut.io.finish.valid.expect(true.B)
      dut.io.finish.bits.expect(1.U)
      dut.clock.step()

      dut.io.in.bits.decode.decoded.cease.poke(false.B)
      dut.io.in.bits.decode.decoded.join.poke(true.B)
      dut.io.in.ready.expect(false.B)
      dut.io.finish.valid.expect(false.B)
      dut.io.restore.valid.expect(true.B)
      dut.clock.step(2)
      dut.io.restore.ready.poke(true.B)
      dut.io.in.ready.expect(true.B)
    }
  }

  it should "resume after a fence and expose unsupported CSR operations" in {
    val config = GpuConfig(lanes = 4, warps = 2)
    simulate(new WarpSystemControl(config)) { dut =>
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      dut.io.in.valid.poke(true.B)
      dut.io.in.bits.poke(0.U.asTypeOf(dut.io.in.bits))
      dut.io.in.bits.decode.pc.poke(0x100.U)
      dut.io.in.bits.decode.warpId.poke(1.U)
      dut.io.in.bits.decode.activeMask.poke(0x5.U)
      dut.io.in.bits.decode.decoded.fence.poke(true.B)
      dut.io.restore.ready.poke(false.B)
      dut.io.resume.ready.poke(true.B)
      dut.io.unsupported.ready.poke(false.B)
      dut.io.resume.valid.expect(true.B)
      dut.io.resume.bits.pc.expect(0x104.U)
      dut.io.resume.bits.activeMask.expect(0x5.U)
      dut.clock.step()

      dut.io.in.bits.decode.decoded.fence.poke(false.B)
      dut.io.in.bits.decode.decoded.csr.poke(true.B)
      dut.io.unsupported.valid.expect(true.B)
      dut.io.in.ready.expect(false.B)
    }
  }
}
