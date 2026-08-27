package opengpu.core.vector

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import opengpu.config.GpuConfig
import org.scalatest.flatspec.AnyFlatSpec

class VectorFsqrtAluSpec extends AnyFlatSpec {
  behavior of "VectorFsqrtAlu"

  private def run(
    config: GpuConfig,
    request: VectorFpuRequest => Unit
  ): (Vector[BigInt], BigInt) = {
    var data = Vector.fill(config.lanes)(BigInt(0))
    var flags = BigInt(0)
    simulate(new VectorFsqrtAlu(config)) { dut =>
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
      while (!dut.io.out.valid.peek().litToBoolean && cycles < 80) {
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
    vs2: Seq[String]
  ): Unit = {
    bits.warpId.poke(0.U)
    bits.vd.poke(3.U)
    bits.activeMask.poke(((BigInt(1) << config.lanes) - 1).U)
    bits.vm.poke(true.B)
    bits.funct6.poke("h13".U)
    bits.operandType.poke("b001".U)
    bits.vs1Field.poke("b00000".U)
    for (lane <- 0 until config.lanes) {
      bits.vs2(lane).poke(BigInt(vs2(lane).drop(1), 16).U)
      bits.oldVd(lane).poke("h11111111".U)
    }
  }

  it should "execute vfsqrt per lane" in {
    val config = GpuConfig(lanes = 2)
    val (data, flags) = run(config, { bits =>
      configure(bits, config, Seq("h40800000", "h3f800000"))
    })
    assert(data(0) == BigInt("40000000", 16)) // sqrt(4) = 2
    assert(data(1) == BigInt("3f800000", 16)) // sqrt(1) = 1
    assert(flags == 0)
  }

  it should "round inexact roots to nearest even and report NX" in {
    val config = GpuConfig(lanes = 2)
    val (data, flags) = run(config, { bits =>
      configure(bits, config, Seq("h40000000", "h3fc00000"))
    })
    assert(data(0) == BigInt("3fb504f3", 16)) // sqrt(2)
    assert(data(1) == BigInt("3f9cc471", 16)) // sqrt(1.5)
    assert((flags & BigInt("1", 16)) != 0)
  }

  it should "honor the routed dynamic rounding mode" in {
    val config = GpuConfig(lanes = 2)
    val (data, _) = run(config, { bits =>
      configure(bits, config, Seq("h40000000", "h40000000"))
      bits.roundingMode.poke("b011".U) // RUP
    })
    assert(data(0) == BigInt("3fb504f4", 16))
    assert(data(1) == BigInt("3fb504f4", 16))
  }

  it should "preserve masked-off lanes and skip their flags" in {
    val config = GpuConfig(lanes = 2)
    val (data, flags) = run(config, { bits =>
      configure(bits, config, Seq("h40000000", "hc0000000"))
      bits.activeMask.poke("b01".U)
      bits.oldVd(1).poke("h22222222".U)
    })
    assert(data(0) == BigInt("3fb504f3", 16))
    assert(data(1) == BigInt("22222222", 16))
    assert((flags & BigInt("1", 16)) != 0)
    assert((flags & BigInt("10", 16)) == 0)
  }
}
