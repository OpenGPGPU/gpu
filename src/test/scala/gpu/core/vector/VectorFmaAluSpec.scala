package gpu.core.vector

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import gpu.config.GpuConfig
import org.scalatest.flatspec.AnyFlatSpec

class VectorFmaAluSpec extends AnyFlatSpec {
  behavior of "VectorFmaAlu"

  private def run(
    config: GpuConfig,
    request: VectorFpuRequest => Unit
  ): (Vector[BigInt], BigInt) = {
    var data = Vector.fill(config.lanes)(BigInt(0))
    var flags = BigInt(0)
    simulate(new VectorFmaAlu(config)) { dut =>
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

  private def configureFvv(
    bits: VectorFpuRequest,
    config: GpuConfig,
    funct6: String,
    vs2: Seq[String],
    vs1: Seq[String]
  ): Unit = {
    bits.warpId.poke(0.U)
    bits.vd.poke(3.U)
    bits.activeMask.poke(((BigInt(1) << config.lanes) - 1).U)
    bits.vm.poke(true.B)
    bits.funct6.poke(BigInt(funct6.drop(1), 16).U)
    bits.operandType.poke("b001".U)
    for (lane <- 0 until config.lanes) {
      bits.vs2(lane).poke(BigInt(vs2(lane).drop(1), 16).U)
      bits.vs1(lane).poke(BigInt(vs1(lane).drop(1), 16).U)
      bits.oldVd(lane).poke("h3f800000".U) // 1.0
    }
  }

  it should "execute vfadd per lane" in {
    val config = GpuConfig(lanes = 2)
    val (data, _) = run(config, { bits =>
      configureFvv(bits, config, "h00", Seq("h3f800000", "h40000000"),
        Seq("h40000000", "h40400000"))
    })
    assert(data(0) == BigInt("40400000", 16)) // 3.0
    assert(data(1) == BigInt("40a00000", 16)) // 5.0
  }

  it should "round addends to nearest even by default" in {
    val config = GpuConfig(lanes = 2)
    val (data, _) = run(config, { bits =>
      configureFvv(bits, config, "h00", Seq("h3f800000", "h3f800000"),
        Seq("h33800000", "h33800000"))
    })
    assert(data(0) == BigInt("3f800000", 16)) // 1.0 + 2^-24 ties to 1.0
    assert(data(1) == BigInt("3f800000", 16))
  }

  it should "honor a routed upward rounding mode" in {
    val config = GpuConfig(lanes = 2)
    val (data, _) = run(config, { bits =>
      configureFvv(bits, config, "h00", Seq("h3f800000", "h3f800000"),
        Seq("h33800000", "h33800000"))
      bits.roundingMode.poke(3.U) // RUP
    })
    assert(data(0) == BigInt("3f800001", 16))
    assert(data(1) == BigInt("3f800001", 16))
  }

  it should "execute vfsub per lane" in {
    val config = GpuConfig(lanes = 2)
    val (data, _) = run(config, { bits =>
      configureFvv(bits, config, "h02", Seq("h40a00000", "h40000000"),
        Seq("h3f800000", "h3f800000"))
    })
    assert(data(0) == BigInt("40800000", 16)) // 4.0
    assert(data(1) == BigInt("3f800000", 16)) // 1.0
  }

  it should "execute vfmul per lane" in {
    val config = GpuConfig(lanes = 2)
    val (data, _) = run(config, { bits =>
      configureFvv(bits, config, "h24", Seq("h3fc00000", "h40000000"),
        Seq("h40000000", "h40400000"))
    })
    assert(data(0) == BigInt("40400000", 16)) // 3.0
    assert(data(1) == BigInt("40c00000", 16)) // 6.0
  }

  it should "execute all eight fused FMA variants" in {
    val config = GpuConfig(lanes = 2)
    val cases = Seq(
      ("h28", BigInt("40e00000", 16)), // vfmadd: 3*2+1
      ("h29", BigInt("c0a00000", 16)), // vfnmadd: -(3*2)+1
      ("h2a", BigInt("40a00000", 16)), // vfmsub: 3*2-1
      ("h2b", BigInt("c0e00000", 16)), // vfnmsub: -(3*2)-1
      ("h2c", BigInt("40e00000", 16)), // vfmacc: 2*3+1
      ("h2d", BigInt("c0a00000", 16)), // vfnmacc: -(2*3)+1
      ("h2e", BigInt("40a00000", 16)), // vfmsac: 2*3-1
      ("h2f", BigInt("c0e00000", 16))  // vfnmsac: -(2*3)-1
    )
    for ((funct6, expected) <- cases) {
      val (data, _) = run(config, { bits =>
        configureFvv(bits, config, funct6, Seq("h40400000", "h40400000"),
          Seq("h40000000", "h40000000"))
      })
      assert(data(0) == expected, s"funct6 $funct6 lane 0")
      assert(data(1) == expected, s"funct6 $funct6 lane 1")
    }
  }

  it should "use the scalar FP operand for FVF arithmetic" in {
    val config = GpuConfig(lanes = 2)
    val (data, _) = run(config, { bits =>
      bits.warpId.poke(0.U)
      bits.vd.poke(3.U)
      bits.activeMask.poke("b11".U)
      bits.vm.poke(true.B)
      bits.funct6.poke("h00".U)
      bits.operandType.poke("b101".U)
      bits.scalarFpData.poke("h40000000".U) // 2.0
      bits.vs2(0).poke("h3f800000".U) // 1.0
      bits.vs2(1).poke("h40400000".U) // 3.0
      bits.vs1(0).poke("h00000000".U)
      bits.vs1(1).poke("h00000000".U)
    })
    assert(data(0) == BigInt("40400000", 16)) // 3.0
    assert(data(1) == BigInt("40a00000", 16)) // 5.0
  }

  it should "execute vfrsub as scalar minus vector" in {
    val config = GpuConfig(lanes = 2)
    val (data, _) = run(config, { bits =>
      bits.warpId.poke(0.U)
      bits.vd.poke(3.U)
      bits.activeMask.poke("b11".U)
      bits.vm.poke(true.B)
      bits.funct6.poke("h27".U)
      bits.operandType.poke("b101".U)
      bits.scalarFpData.poke("h40a00000".U) // 5.0
      bits.vs2(0).poke("h40400000".U) // 3.0
      bits.vs2(1).poke("h40000000".U) // 2.0
    })
    assert(data(0) == BigInt("40000000", 16)) // 2.0
    assert(data(1) == BigInt("40400000", 16)) // 3.0
  }

  it should "preserve masked-off lanes and emit a flag summary" in {
    val config = GpuConfig(lanes = 2)
    val (data, flags) = run(config, { bits =>
      bits.warpId.poke(0.U)
      bits.vd.poke(3.U)
      bits.activeMask.poke("b01".U)
      bits.vm.poke(true.B)
      bits.funct6.poke("h00".U)
      bits.operandType.poke("b001".U)
      bits.vs2(0).poke("h3f800000".U) // 1.0
      bits.vs2(1).poke("h7fc00000".U) // NaN
      bits.vs1(0).poke("h3f800000".U) // 1.0
      bits.vs1(1).poke("h3f800000".U)
      bits.oldVd(0).poke("h11111111".U)
      bits.oldVd(1).poke("h22222222".U)
    })
    assert(data(0) == BigInt("40000000", 16)) // 2.0
    assert(data(1) == BigInt("22222222", 16)) // inactive lane preserved
    assert(flags == 0) // disabled NaN lane must not raise NV
  }

  it should "raise NV for an enabled invalid multiply" in {
    val config = GpuConfig(lanes = 2)
    val (data, flags) = run(config, { bits =>
      bits.warpId.poke(0.U)
      bits.vd.poke(3.U)
      bits.activeMask.poke("b11".U)
      bits.vm.poke(true.B)
      bits.funct6.poke("h24".U)
      bits.operandType.poke("b001".U)
      bits.vs2(0).poke("h00000000".U)
      bits.vs2(1).poke("h00000000".U)
      bits.vs1(0).poke("h7f800000".U)
      bits.vs1(1).poke("h7f800000".U)
      bits.oldVd(0).poke("h11111111".U)
      bits.oldVd(1).poke("h11111111".U)
    })
    assert(data(0) == BigInt("7fc00000", 16))
    assert(data(1) == BigInt("7fc00000", 16))
    assert((flags & BigInt("10", 16)) != 0)
  }
}
