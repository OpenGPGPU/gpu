package gpu.core.vector

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import gpu.config.GpuConfig
import org.scalatest.flatspec.AnyFlatSpec

class VectorFEstimateAluSpec extends AnyFlatSpec {
  behavior of "VectorFEstimateAlu"

  private def run(
    config: GpuConfig,
    request: VectorFpuRequest => Unit
  ): (Vector[BigInt], BigInt) = {
    var data = Vector.fill(config.lanes)(BigInt(0))
    var flags = BigInt(0)
    simulate(new VectorFEstimateAlu(config)) { dut =>
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
      while (!dut.io.out.valid.peek().litToBoolean && cycles < 8) {
        dut.clock.step(); cycles += 1
      }
      assert(dut.io.out.valid.peek().litToBoolean)
      data = dut.io.out.bits.data.map(_.peek().litValue).toVector
      flags = dut.io.out.bits.flags.peek().litValue
      dut.io.out.ready.poke(true.B)
      dut.clock.step()
    }
    (data, flags)
  }

  private def configure(
    bits: VectorFpuRequest,
    config: GpuConfig,
    vs1Field: Int,
    vs2: Seq[String]
  ): Unit = {
    bits.warpId.poke(0.U)
    bits.vd.poke(3.U)
    bits.activeMask.poke(((BigInt(1) << config.lanes) - 1).U)
    bits.vm.poke(true.B)
    bits.funct6.poke("h13".U)
    bits.operandType.poke("b001".U)
    bits.vs1Field.poke(vs1Field.U)
    for (lane <- 0 until config.lanes) {
      bits.vs2(lane).poke(BigInt(vs2(lane).drop(1), 16).U)
      bits.oldVd(lane).poke("h11111111".U)
    }
  }

  it should "execute vfrec7 per lane" in {
    val config = GpuConfig(lanes = 2)
    val (data, flags) = run(config, { bits =>
      configure(bits, config, 4, Seq("h3f800000", "h40400000"))
    })
    assert(data(0) == BigInt("3f7f0000", 16))
    assert(data(1) == BigInt("3eaa0000", 16))
    assert(flags == 0)
  }

  it should "execute vfrsqrt7 per lane" in {
    val config = GpuConfig(lanes = 2)
    val (data, flags) = run(config, { bits =>
      configure(bits, config, 5, Seq("h40800000", "h3f800000"))
    })
    assert(data(0) == BigInt("3eff0000", 16))
    assert(data(1) == BigInt("3f7f0000", 16))
    assert(flags == 0)
  }

  it should "report DZ for reciprocal zero lanes and preserve masked lanes" in {
    val config = GpuConfig(lanes = 2)
    val (data, flags) = run(config, { bits =>
      configure(bits, config, 4, Seq("h00000000", "h7f800000"))
      bits.activeMask.poke("b01".U)
      bits.oldVd(1).poke("h22222222".U)
    })
    assert(data(0) == BigInt("7f800000", 16))
    assert(data(1) == BigInt("22222222", 16))
    assert((flags & BigInt("08", 16)) != 0)
  }

  it should "raise NV for negative vfrsqrt7 lanes" in {
    val config = GpuConfig(lanes = 2)
    val (data, flags) = run(config, { bits =>
      configure(bits, config, 5, Seq("hbf800000", "h3f800000"))
    })
    assert(data(0) == BigInt("7fc00000", 16))
    assert(data(1) == BigInt("3f7f0000", 16))
    assert((flags & BigInt("10", 16)) != 0)
  }
}
