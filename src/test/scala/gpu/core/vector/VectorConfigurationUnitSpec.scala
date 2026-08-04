package gpu.core.vector

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import gpu.config.GpuConfig
import org.scalatest.flatspec.AnyFlatSpec

class VectorConfigurationUnitSpec extends AnyFlatSpec {
  behavior of "VectorConfigurationUnit"

  private val config = GpuConfig(lanes = 8, warps = 4)

  private def vsetvli(rd: Int, rs1: Int, vtype: Int): BigInt =
    (BigInt(vtype) << 20) | (BigInt(rs1) << 15) |
      (BigInt(7) << 12) | (BigInt(rd) << 7) | 0x57

  private def vsetivli(rd: Int, avl: Int, vtype: Int): BigInt =
    (BigInt(3) << 30) | (BigInt(vtype & 0x3ff) << 20) |
      (BigInt(avl) << 15) | (BigInt(7) << 12) |
      (BigInt(rd) << 7) | 0x57

  private def vsetvl(rd: Int, rs1: Int, rs2: Int): BigInt =
    (BigInt(0x40) << 25) | (BigInt(rs2) << 20) |
      (BigInt(rs1) << 15) | (BigInt(7) << 12) |
      (BigInt(rd) << 7) | 0x57

  private def defaults(dut: VectorConfigurationUnit): Unit = {
    dut.io.in.valid.poke(false.B)
    dut.io.out.ready.poke(true.B)
    dut.io.in.bits.instruction.poke(vsetvli(1, 2, 0x10).U)
    dut.io.in.bits.warpId.poke(0.U)
    dut.io.in.bits.rs1Data.poke(0.U)
    dut.io.in.bits.rs2Data.poke(0.U)
    dut.io.queryWarpId.poke(0.U)
    dut.io.csrWrite.valid.poke(false.B)
    dut.io.csrWrite.bits.warpId.poke(0.U)
    dut.io.csrWrite.bits.address.poke(0.U)
    dut.io.csrWrite.bits.data.poke(0.U)
  }

  private def issue(
    dut: VectorConfigurationUnit,
    instruction: BigInt,
    warp: Int,
    rs1Data: BigInt = 0,
    rs2Data: BigInt = 0
  ): Unit = {
    dut.io.in.valid.poke(true.B)
    dut.io.in.bits.instruction.poke(instruction.U)
    dut.io.in.bits.warpId.poke(warp.U)
    dut.io.in.bits.rs1Data.poke(rs1Data.U)
    dut.io.in.bits.rs2Data.poke(rs2Data.U)
    dut.io.in.ready.expect(true.B)
    dut.clock.step()
    dut.io.in.valid.poke(false.B)
  }

  it should "configure vl and vtype independently for each warp" in {
    simulate(new VectorConfigurationUnit(config)) { dut =>
      dut.reset.poke(true.B)
      defaults(dut)
      dut.clock.step()
      dut.reset.poke(false.B)

      dut.io.state.vl.expect(0.U)
      dut.io.state.vtype.expect("h80000000".U)

      issue(dut, vsetvli(3, 2, 0x10), warp = 1, rs1Data = 5)
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.data.expect(5.U)
      dut.io.out.bits.rd.expect(3.U)
      dut.io.out.bits.vill.expect(false.B)
      dut.io.queryWarpId.poke(1.U)
      dut.io.state.vl.expect(5.U)
      dut.io.state.vtype.expect(0x10.U)

      dut.clock.step()
      issue(dut, vsetivli(4, 31, 0x10), warp = 2)
      dut.io.out.bits.data.expect(8.U)
      dut.io.queryWarpId.poke(2.U)
      dut.io.state.vl.expect(8.U)
      dut.io.queryWarpId.poke(1.U)
      dut.io.state.vl.expect(5.U)
    }
  }

  it should "set vill and vl zero for unsupported vector types" in {
    simulate(new VectorConfigurationUnit(config)) { dut =>
      dut.reset.poke(true.B)
      defaults(dut)
      dut.clock.step()
      dut.reset.poke(false.B)

      // e16 is unsupported by the fixed SEW=32 datapath.
      issue(dut, vsetvli(1, 2, 0x08), warp = 0, rs1Data = 4)
      dut.io.out.bits.vill.expect(true.B)
      dut.io.out.bits.data.expect(0.U)
      dut.io.state.vl.expect(0.U)
      dut.io.state.vtype.expect("h80000000".U)

      dut.clock.step()
      // vta=1 is legal; retaining old tail values is an allowed agnostic
      // implementation choice.
      issue(dut, vsetvli(1, 2, 0x50), warp = 0, rs1Data = 4)
      dut.io.out.bits.vill.expect(false.B)
      dut.io.state.vtype.expect("h00000050".U)
    }
  }

  it should "load vtype from rs2 and maintain vector rounding CSRs" in {
    simulate(new VectorConfigurationUnit(config)) { dut =>
      dut.reset.poke(true.B)
      defaults(dut)
      dut.clock.step()
      dut.reset.poke(false.B)

      issue(dut, vsetvl(5, 2, 3), warp = 3, rs1Data = 7, rs2Data = 0x10)
      dut.io.out.bits.data.expect(7.U)
      dut.io.queryWarpId.poke(3.U)
      dut.io.state.vtype.expect(0x10.U)

      dut.clock.step()
      dut.io.csrWrite.valid.poke(true.B)
      dut.io.csrWrite.bits.warpId.poke(3.U)
      dut.io.csrWrite.bits.address.poke("h00f".U)
      dut.io.csrWrite.bits.data.poke("b101".U)
      dut.clock.step()
      dut.io.csrWrite.valid.poke(false.B)
      dut.io.state.vxrm.expect(2.U)
      dut.io.state.vxsat.expect(true.B)
    }
  }

  it should "hold its result under output backpressure" in {
    simulate(new VectorConfigurationUnit(config)) { dut =>
      dut.reset.poke(true.B)
      defaults(dut)
      dut.clock.step()
      dut.reset.poke(false.B)

      dut.io.out.ready.poke(false.B)
      issue(dut, vsetvli(6, 2, 0x10), warp = 0, rs1Data = 3)
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.data.expect(3.U)
      dut.io.in.ready.expect(false.B)
      dut.clock.step(3)
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.data.expect(3.U)
      dut.io.state.vl.expect(3.U)
    }
  }
}
