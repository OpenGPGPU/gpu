package opengpu.core.vector

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import opengpu.config.GpuConfig
import org.scalatest.flatspec.AnyFlatSpec

class VectorFpuAluSpec extends AnyFlatSpec {
  behavior of "VectorFpuAlu"

  private def run(
    config: GpuConfig,
    request: VectorFpuRequest => Unit
  ): (BigInt, BigInt, BigInt) = {
    var data = BigInt(0)
    var mask = BigInt(0)
    var flags = BigInt(0)
    simulate(new VectorFpuAlu(config)) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      dut.io.in.valid.poke(false.B)
      dut.io.in.bits.poke(0.U.asTypeOf(dut.io.in.bits))
      dut.io.out.ready.poke(true.B)

      request(dut.io.in.bits)
      dut.io.in.valid.poke(true.B)
      dut.io.in.ready.expect(true.B)
      dut.clock.step()
      dut.io.in.valid.poke(false.B)
      dut.clock.step()
      dut.clock.step()
      dut.io.out.valid.expect(true.B)
      data = dut.io.out.bits.data
        .map(_.peek().litValue)
        .foldLeft(BigInt(0))((value, lane) => (value << 32) | lane)
      mask = dut.io.out.bits.mask.peek().litValue
      flags = dut.io.out.bits.flags.peek().litValue
    }
    (data, mask, flags)
  }

  it should "execute sign injection per lane" in {
    val config = GpuConfig(lanes = 2)
    val (data, _, _) = run(config, { bits =>
      bits.warpId.poke(0.U)
      bits.vd.poke(3.U)
      bits.activeMask.poke("b11".U)
      bits.vm.poke(true.B)
      bits.funct6.poke("h08".U)
      bits.operandType.poke("b001".U)
      bits.vs2(0).poke("h3f800000".U)
      bits.vs2(1).poke("h3f800000".U)
      bits.vs1(0).poke("h80000000".U)
      bits.vs1(1).poke("h00000000".U)
    })
    assert((data >> 32) == BigInt("bf800000", 16))
    assert((data & 0xffffffffL) == BigInt("3f800000", 16))
  }

  it should "select per-lane FP minimum" in {
    val config = GpuConfig(lanes = 2)
    val (data, _, _) = run(config, { bits =>
      bits.warpId.poke(0.U)
      bits.vd.poke(3.U)
      bits.activeMask.poke("b11".U)
      bits.vm.poke(true.B)
      bits.funct6.poke("h04".U)
      bits.operandType.poke("b001".U)
      bits.vs2(0).poke("hbf800000".U)
      bits.vs2(1).poke("h3f800000".U)
      bits.vs1(0).poke("h3f800000".U)
      bits.vs1(1).poke("h40000000".U)
    })
    assert((data >> 32) == BigInt("bf800000", 16))
    assert((data & 0xffffffffL) == BigInt("3f800000", 16))
  }

  it should "write an FP compare mask" in {
    val config = GpuConfig(lanes = 4)
    val (_, mask, _) = run(config, { bits =>
      bits.warpId.poke(0.U)
      bits.vd.poke(3.U)
      bits.activeMask.poke("b1111".U)
      bits.vm.poke(true.B)
      bits.funct6.poke("h19".U) // vmfle
      bits.operandType.poke("b001".U)
      for (lane <- 0 until config.lanes) {
        bits.vs2(lane).poke("h3f800000".U)
        bits.vs1(lane).poke("h3f800000".U)
      }
    })
    assert(mask == BigInt("f", 16))
  }

  it should "use the scalar FP operand for FVF sign injection" in {
    val config = GpuConfig(lanes = 2)
    val (data, _, _) = run(config, { bits =>
      bits.warpId.poke(0.U)
      bits.vd.poke(3.U)
      bits.activeMask.poke("b11".U)
      bits.vm.poke(true.B)
      bits.funct6.poke("h08".U)
      bits.operandType.poke("b101".U)
      bits.scalarFpData.poke("h80000000".U)
      bits.vs2(0).poke("h3f800000".U)
      bits.vs2(1).poke("h3f800000".U)
    })
    assert((data >> 32) == BigInt("bf800000", 16))
    assert((data & 0xffffffffL) == BigInt("bf800000", 16))
  }

  it should "set NV for an enabled NaN min while skipping disabled lanes" in {
    val config = GpuConfig(lanes = 2)
    val (data, _, flags) = run(config, { bits =>
      bits.warpId.poke(0.U)
      bits.vd.poke(3.U)
      bits.activeMask.poke("b01".U)
      bits.vm.poke(true.B)
      bits.funct6.poke("h04".U)
      bits.operandType.poke("b001".U)
      bits.vs2(0).poke("h7fc00000".U)
      bits.vs2(1).poke("h7fc00000".U)
      bits.vs1(0).poke("h3f800000".U)
      bits.vs1(1).poke("h3f800000".U)
      bits.oldVd(0).poke(0.U)
      bits.oldVd(1).poke("h12345678".U)
    })
    assert((data >> 32) == BigInt("7fc00000", 16))
    assert((data & 0xffffffffL) == BigInt("12345678", 16))
    assert((flags & BigInt("10", 16)) != 0)
  }

  it should "broadcast a scalar FP register with vfmv.v.f" in {
    val config = GpuConfig(lanes = 2)
    val (data, _, flags) = run(config, { bits =>
      bits.warpId.poke(0.U)
      bits.vd.poke(3.U)
      bits.activeMask.poke("b11".U)
      bits.vm.poke(true.B)
      bits.funct6.poke("h17".U)
      bits.operandType.poke("b101".U)
      bits.scalarFpData.poke("h40000000".U) // 2.0
      bits.oldVd(0).poke("h11111111".U)
      bits.oldVd(1).poke("h22222222".U)
    })
    assert((data >> 32) == BigInt("40000000", 16))
    assert((data & 0xffffffffL) == BigInt("40000000", 16))
    assert(flags == 0)
  }

  it should "keep masked-off vfmv.v.f lanes undisturbed" in {
    val config = GpuConfig(lanes = 2)
    val (data, _, _) = run(config, { bits =>
      bits.warpId.poke(0.U)
      bits.vd.poke(3.U)
      bits.activeMask.poke("b01".U)
      bits.vm.poke(true.B)
      bits.funct6.poke("h17".U)
      bits.operandType.poke("b101".U)
      bits.scalarFpData.poke("h40000000".U)
      bits.oldVd(0).poke("h11111111".U)
      bits.oldVd(1).poke("h22222222".U)
    })
    assert((data >> 32) == BigInt("40000000", 16))
    assert((data & 0xffffffffL) == BigInt("22222222", 16))
  }

  it should "merge from vs2 where the mask is clear" in {
    val config = GpuConfig(lanes = 2)
    val (data, _, _) = run(config, { bits =>
      bits.warpId.poke(0.U)
      bits.vd.poke(3.U)
      bits.activeMask.poke("b11".U)
      bits.rawActiveMask.poke("b11".U)
      bits.predicateMask.poke("b10".U)
      bits.vm.poke(false.B)
      bits.funct6.poke("h17".U)
      bits.operandType.poke("b101".U)
      bits.scalarFpData.poke("h40000000".U) // 2.0
      bits.vs2(0).poke("h40800000".U) // 4.0
      bits.vs2(1).poke("h40400000".U) // 3.0
      bits.oldVd(0).poke("h11111111".U)
      bits.oldVd(1).poke("h22222222".U)
    })
    assert((data >> 32) == BigInt("40800000", 16))
    assert((data & 0xffffffffL) == BigInt("40000000", 16))
  }

  it should "preserve inactive vfmerge lanes" in {
    val config = GpuConfig(lanes = 2)
    val (data, _, _) = run(config, { bits =>
      bits.warpId.poke(0.U)
      bits.vd.poke(3.U)
      bits.activeMask.poke("b01".U)
      bits.rawActiveMask.poke("b01".U)
      bits.predicateMask.poke("b01".U)
      bits.vm.poke(false.B)
      bits.funct6.poke("h17".U)
      bits.operandType.poke("b101".U)
      bits.scalarFpData.poke("h40000000".U)
      bits.vs2(0).poke("h40800000".U)
      bits.vs2(1).poke("h40400000".U)
      bits.oldVd(0).poke("h11111111".U)
      bits.oldVd(1).poke("h22222222".U)
    })
    assert((data >> 32) == BigInt("40000000", 16))
    assert((data & 0xffffffffL) == BigInt("22222222", 16))
  }
}
