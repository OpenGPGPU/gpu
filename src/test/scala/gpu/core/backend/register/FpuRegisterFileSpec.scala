package gpu.core.backend.register

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import gpu.config.GpuConfig
import org.scalatest.flatspec.AnyFlatSpec

class FpuRegisterFileSpec extends AnyFlatSpec {
  behavior of "FpuRegisterFile"

  it should "keep f0 writable, isolate warps, and bypass all three ports" in {
    val config = GpuConfig(warps = 2)
    simulate(new FpuRegisterFile(config)) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      dut.io.write.valid.poke(true.B)
      dut.io.write.bits.warpId.poke(0.U)
      dut.io.write.bits.rd.poke(0.U)
      dut.io.write.bits.data.poke("h3f800000".U)
      dut.io.read.warpId.poke(0.U)
      dut.io.read.rs1.poke(0.U)
      dut.io.read.rs2.poke(0.U)
      dut.io.read.rs3.poke(0.U)
      dut.io.rs1Data.expect("h3f800000".U)
      dut.io.rs2Data.expect("h3f800000".U)
      dut.io.rs3Data.expect("h3f800000".U)
      dut.clock.step()

      dut.io.write.valid.poke(false.B)
      dut.io.read.warpId.poke(1.U)
      dut.io.rs1Data.expect(0.U)
      dut.io.read.warpId.poke(0.U)
      dut.io.rs1Data.expect("h3f800000".U)
    }
  }

  it should "expose a dedicated FVF read port with write bypass" in {
    val config = GpuConfig(warps = 2)
    simulate(new FpuRegisterFile(config)) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      dut.io.write.valid.poke(true.B)
      dut.io.write.bits.warpId.poke(0.U)
      dut.io.write.bits.rd.poke(7.U)
      dut.io.write.bits.data.poke("h40400000".U)
      dut.io.fvfRead.warpId.poke(0.U)
      dut.io.fvfRead.rs1.poke(7.U)
      dut.io.fvfData.expect("h40400000".U)
      dut.clock.step()

      dut.io.write.valid.poke(false.B)
      dut.io.fvfRead.warpId.poke(1.U)
      dut.io.fvfData.expect(0.U)
      dut.io.fvfRead.warpId.poke(0.U)
      dut.io.fvfData.expect("h40400000".U)
    }
  }
}
