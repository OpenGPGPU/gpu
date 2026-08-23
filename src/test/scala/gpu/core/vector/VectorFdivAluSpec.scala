package gpu.core.vector

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import gpu.config.GpuConfig
import org.scalatest.flatspec.AnyFlatSpec

class VectorFdivAluSpec extends AnyFlatSpec {
  behavior of "VectorFdivAlu"

  private def run(
    config: GpuConfig,
    request: VectorFpuRequest => Unit
  ): (Vector[BigInt], BigInt) = {
    var data = Vector.fill(config.lanes)(BigInt(0))
    var flags = BigInt(0)
    simulate(new VectorFdivAlu(config)) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      dut.io.in.valid.poke(false.B)
      dut.io.in.bits.poke(0.U.asTypeOf(dut.io.in.bits))
      dut.io.out.ready.poke(false.B)

      request(dut.io.in.bits)
      dut.io.in.valid.poke(true.B)
      dut.io.in.ready.expect(true.B)
      dut.clock.step()
      dut.io.in.valid.poke(false.B)
      var cycles = 0
      while (!dut.io.out.valid.peek().litToBoolean && cycles < 64) {
        dut.clock.step()
        cycles += 1
      }
      assert(dut.io.out.valid.peek().litToBoolean)
      data = dut.io.out.bits.data.map(_.peek().litValue).toVector
      flags = dut.io.out.bits.flags.peek().litValue
      dut.io.out.ready.poke(true.B)
      dut.clock.step()
    }
    (data, flags)
  }

  it should "execute vfdiv per lane" in {
    val config = GpuConfig(lanes = 2)
    val (data, flags) = run(config, { bits =>
      bits.warpId.poke(0.U)
      bits.vd.poke(3.U)
      bits.activeMask.poke("b11".U)
      bits.vm.poke(true.B)
      bits.funct6.poke("h20".U)
      bits.operandType.poke("b001".U)
      bits.vs2(0).poke("h40c00000".U) // 6.0
      bits.vs2(1).poke("h40800000".U) // 4.0
      bits.vs1(0).poke("h40400000".U) // 3.0
      bits.vs1(1).poke("h40000000".U) // 2.0
    })
    assert(data(0) == BigInt("40000000", 16)) // 2.0
    assert(data(1) == BigInt("40000000", 16)) // 2.0
    assert(flags == 0)
  }

  it should "execute vfrdiv as scalar divided by vector" in {
    val config = GpuConfig(lanes = 2)
    val (data, _) = run(config, { bits =>
      bits.warpId.poke(0.U)
      bits.vd.poke(3.U)
      bits.activeMask.poke("b11".U)
      bits.vm.poke(true.B)
      bits.funct6.poke("h21".U)
      bits.operandType.poke("b101".U)
      bits.scalarFpData.poke("h40a00000".U) // 5.0
      bits.vs2(0).poke("h40000000".U) // 2.0
      bits.vs2(1).poke("h3f800000".U) // 1.0
    })
    assert(data(0) == BigInt("40200000", 16)) // 2.5
    assert(data(1) == BigInt("40a00000", 16)) // 5.0
  }

  it should "preserve masked-off lanes and report flags" in {
    val config = GpuConfig(lanes = 2)
    val (data, flags) = run(config, { bits =>
      bits.warpId.poke(0.U)
      bits.vd.poke(3.U)
      bits.activeMask.poke("b01".U)
      bits.vm.poke(true.B)
      bits.funct6.poke("h20".U)
      bits.operandType.poke("b001".U)
      bits.vs2(0).poke("h3f800000".U)
      bits.vs2(1).poke("h00000000".U)
      bits.vs1(0).poke("h00000000".U)
      bits.vs1(1).poke("h00000000".U)
      bits.oldVd(0).poke("h11111111".U)
      bits.oldVd(1).poke("h22222222".U)
    })
    assert(data(0) == BigInt("7f800000", 16))
    assert(data(1) == BigInt("22222222", 16))
    assert((flags & BigInt("8", 16)) != 0) // DZ from lane 0
  }
}
