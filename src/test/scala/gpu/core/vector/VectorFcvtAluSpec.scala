package gpu.core.vector

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import gpu.config.GpuConfig
import org.scalatest.flatspec.AnyFlatSpec

class VectorFcvtAluSpec extends AnyFlatSpec {
  behavior of "VectorFcvtAlu"

  private def run(
    config: GpuConfig,
    request: VectorFpuRequest => Unit
  ): (Vector[BigInt], BigInt) = {
    var data = Vector.fill(config.lanes)(BigInt(0))
    var flags = BigInt(0)
    simulate(new VectorFcvtAlu(config)) { dut =>
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
      while (!dut.io.out.valid.peek().litToBoolean && cycles < 12) {
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
    vs2: Seq[String],
    funct6: Int = 0x12
  ): Unit = {
    bits.warpId.poke(0.U)
    bits.vd.poke(3.U)
    bits.activeMask.poke(((BigInt(1) << config.lanes) - 1).U)
    bits.vm.poke(true.B)
    bits.funct6.poke(funct6.U)
    bits.operandType.poke("b001".U)
    bits.vs1Field.poke(vs1Field.U)
    for (lane <- 0 until config.lanes) {
      bits.vs2(lane).poke(BigInt(vs2(lane).drop(1), 16).U)
      bits.oldVd(lane).poke("h11111111".U)
    }
  }

  it should "convert FP to unsigned integers" in {
    val config = GpuConfig(lanes = 2)
    val (data, flags) = run(config, { bits =>
      configure(bits, config, 0, Seq("h3f800000", "h40000000"))
    })
    assert(data(0) == 1)
    assert(data(1) == 2)
    assert(flags == 0)
  }

  it should "convert FP to signed integers" in {
    val config = GpuConfig(lanes = 2)
    val (data, flags) = run(config, { bits =>
      configure(bits, config, 1, Seq("h3f800000", "hbf800000"))
    })
    assert(data(0) == 1)
    assert(data(1) == BigInt("ffffffff", 16))
    assert(flags == 0)
  }

  it should "apply RTZ to the rtz float-to-integer forms" in {
    val config = GpuConfig(lanes = 2)
    val (data, flags) = run(config, { bits =>
      configure(bits, config, 7, Seq("h3fc00000", "h40200000"))
    })
    assert(data(0) == 1) // 1.5 truncates to 1
    assert(data(1) == 2) // 2.5 truncates to 2
    assert((flags & BigInt("1", 16)) != 0)
  }

  it should "saturate and raise NV for negative unsigned conversions" in {
    val config = GpuConfig(lanes = 2)
    val (data, flags) = run(config, { bits =>
      configure(bits, config, 6, Seq("hbf800000", "h3fc00000"))
    })
    assert(data(0) == 0)
    assert(data(1) == 1)
    assert((flags & BigInt("11", 16)) == BigInt("11", 16))
  }

  it should "convert unsigned integers to FP32" in {
    val config = GpuConfig(lanes = 2)
    val (data, flags) = run(config, { bits =>
      configure(bits, config, 2, Seq("h00000005", "h80000001"))
    })
    assert(data(0) == BigInt("40a00000", 16))
    assert(data(1) == BigInt("4f000000", 16))
    assert((flags & BigInt("1", 16)) != 0)
  }

  it should "convert signed integers to FP32" in {
    val config = GpuConfig(lanes = 2)
    val (data, flags) = run(config, { bits =>
      configure(bits, config, 3, Seq("hfffffffb", "h80000000"))
    })
    assert(data(0) == BigInt("c0a00000", 16))
    assert(data(1) == BigInt("cf000000", 16))
    assert(flags == 0)
  }

  it should "classify FP values into the RVV vfclass bitmask" in {
    val config = GpuConfig(lanes = 4)
    val (data, flags) = run(config, { bits =>
      configure(
        bits,
        config,
        16,
        Seq(
          "h80000000", // -0.0 -> bit 3
          "h00000000", // +0.0 -> bit 4
          "h3f800000", // 1.0 -> bit 6
          "h7f800000"  // +inf -> bit 7
        ),
        funct6 = 0x13
      )
    })
    assert(data(0) == BigInt("8", 16))
    assert(data(1) == BigInt("10", 16))
    assert(data(2) == BigInt("40", 16))
    assert(data(3) == BigInt("80", 16))
    assert(flags == 0)
  }

  it should "classify quiet and signaling NaNs" in {
    val config = GpuConfig(lanes = 2)
    val (data, flags) = run(config, { bits =>
      configure(
        bits,
        config,
        16,
        Seq("h7fc00000", "h7f800001"),
        funct6 = 0x13
      )
    })
    assert(data(0) == BigInt("200", 16))
    assert(data(1) == BigInt("100", 16))
    assert(flags == 0)
  }

  it should "honor the routed dynamic rounding mode" in {
    val config = GpuConfig(lanes = 2)
    val (data, _) = run(config, { bits =>
      configure(bits, config, 1, Seq("h3fc00000", "hbfc00000"))
      bits.roundingMode.poke("b011".U) // RUP
    })
    assert(data(0) == 2) // 1.5 rounds up
    assert(data(1) == BigInt("ffffffff", 16)) // -1.5 rounds toward +inf
  }

  it should "preserve masked-off lanes and skip their flags" in {
    val config = GpuConfig(lanes = 2)
    val (data, flags) = run(config, { bits =>
      configure(bits, config, 1, Seq("h3fc00000", "h7fc00000"))
      bits.activeMask.poke("b01".U)
      bits.oldVd(1).poke("h22222222".U)
    })
    assert(data(0) == 2)
    assert(data(1) == BigInt("22222222", 16))
    assert((flags & BigInt("1", 16)) != 0)
    assert((flags & BigInt("10", 16)) == 0)
  }
}
