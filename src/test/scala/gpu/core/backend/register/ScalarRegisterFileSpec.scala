package gpu.core.backend.register

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import gpu.config.GpuConfig
import org.scalatest.flatspec.AnyFlatSpec

class ScalarRegisterFileSpec extends AnyFlatSpec {
  private val config = GpuConfig(warps = 4)

  behavior of "ScalarRegisterFile"

  it should "isolate warp namespaces and hard-wire x0 to zero" in {
    simulate(new ScalarRegisterFile(config)) { dut =>
      dut.io.write.valid.poke(false.B)
      dut.io.write.bits.warpId.poke(0.U)
      dut.io.write.bits.rd.poke(0.U)
      dut.io.write.bits.data.poke(0.U)
      dut.io.read.warpId.poke(0.U)
      dut.io.read.rs1.poke(0.U)
      dut.io.read.rs2.poke(0.U)
      dut.io.rs1Data.expect(0.U)

      dut.io.write.valid.poke(true.B)
      dut.io.write.bits.warpId.poke(0.U)
      dut.io.write.bits.rd.poke(5.U)
      dut.io.write.bits.data.poke("h12345678".U)
      dut.clock.step()

      dut.io.write.bits.warpId.poke(1.U)
      dut.io.write.bits.rd.poke(5.U)
      dut.io.write.bits.data.poke("hdeadbeef".U)
      dut.clock.step()
      dut.io.write.valid.poke(false.B)

      dut.io.read.warpId.poke(0.U)
      dut.io.read.rs1.poke(5.U)
      dut.io.rs1Data.expect("h12345678".U)
      dut.io.read.warpId.poke(1.U)
      dut.io.rs1Data.expect("hdeadbeef".U)

      dut.io.write.valid.poke(true.B)
      dut.io.write.bits.warpId.poke(1.U)
      dut.io.write.bits.rd.poke(0.U)
      dut.io.write.bits.data.poke("hffffffff".U)
      dut.clock.step()
      dut.io.read.rs1.poke(0.U)
      dut.io.rs1Data.expect(0.U)
    }
  }

  it should "provide deterministic write-first bypass" in {
    simulate(new ScalarRegisterFile(config)) { dut =>
      dut.io.read.warpId.poke(2.U)
      dut.io.read.rs1.poke(7.U)
      dut.io.read.rs2.poke(8.U)
      dut.io.write.valid.poke(true.B)
      dut.io.write.bits.warpId.poke(2.U)
      dut.io.write.bits.rd.poke(7.U)
      dut.io.write.bits.data.poke("hcafebabe".U)

      dut.io.rs1Data.expect("hcafebabe".U)
      dut.clock.step()
      dut.io.write.valid.poke(false.B)
      dut.io.rs1Data.expect("hcafebabe".U)
    }
  }
}
