package opengpu.core.vector

import chisel3._
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
import org.scalatest.flatspec.AnyFlatSpec

class VectorFReduceAluSpec extends AnyFlatSpec {
  behavior of "VectorFReduceAlu"

  /** Drive one reduction and return its data plus the flags it reports.
    *
    * The fold issues one add per participating element through a five-stage
    * lane, so the wait budget scales with the lane count rather than being the
    * single-add budget the lane-local FP specs use.
    */
  private def run(
    config: GpuConfig,
    request: VectorFpuRequest => Unit
  ): (Vector[BigInt], BigInt, BigInt) = {
    var data = Vector.fill(config.lanes)(BigInt(0))
    var flags = BigInt(0)
    var writeFlags = BigInt(0)
    simulate(new VectorFReduceAlu(config)) { dut =>
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
      val budget = 8 * (config.lanes + 4)
      while (!dut.io.out.valid.peek().litToBoolean && cycles < budget) {
        dut.clock.step(); cycles += 1
      }
      assert(dut.io.out.valid.peek().litToBoolean, s"no result after $cycles")
      data = dut.io.out.bits.data.map(_.peek().litValue).toVector
      flags = dut.io.out.bits.flags.peek().litValue
      writeFlags = dut.io.out.bits.writesFlags.peek().litValue
      dut.io.out.ready.poke(true.B)
      dut.clock.step()
    }
    (data, flags, writeFlags)
  }

  private def configure(
    bits: VectorFpuRequest,
    config: GpuConfig,
    seed: String,
    vs2: Seq[String],
    activeMask: BigInt = -1,
    predicateMask: BigInt = -1,
    vm: Boolean = true
  ): Unit = {
    val allLanes = (BigInt(1) << config.lanes) - 1
    bits.warpId.poke(0.U)
    bits.pc.poke("h1000".U)
    bits.vd.poke(3.U)
    bits.activeMask.poke((if (activeMask < 0) allLanes else activeMask).U)
    bits.rawActiveMask.poke((if (activeMask < 0) allLanes else activeMask).U)
    bits.predicateMask.poke((if (predicateMask < 0) allLanes else predicateMask).U)
    bits.vm.poke(vm.B)
    bits.funct6.poke("h01".U)
    bits.operandType.poke("b001".U)
    bits.roundingMode.poke("b000".U)
    for (lane <- 0 until config.lanes) {
      bits.vs1(lane).poke(
        BigInt(if (lane == 0) seed.drop(1) else "00000000", 16).U)
      bits.vs2(lane).poke(BigInt(vs2(lane).drop(1), 16).U)
      bits.oldVd(lane).poke("h3f800000".U) // 1.0
    }
  }

  it should "fold the seed and every active element into element zero" in {
    val config = GpuConfig(lanes = 4, warps = 2)
    val (data, _, writeFlags) = run(config, { bits =>
      // 1 + 2 + 3 + 4 + 0.5
      configure(bits, config, "h3f800000",
        Seq("h40000000", "h40400000", "h40800000", "h3f000000"))
    })
    assert(data(0) == BigInt("41280000", 16)) // 10.5
    assert(data(1) == BigInt("3f800000", 16), "elements past 0 keep old vd")
    assert(data(2) == BigInt("3f800000", 16))
    assert(data(3) == BigInt("3f800000", 16))
    assert(writeFlags == 1)
  }

  it should "fold only the elements v0 selects in the masked form" in {
    val config = GpuConfig(lanes = 4, warps = 2)
    val (data, _, _) = run(config, { bits =>
      // 1 + 3 + 0.5: lanes 0 and 2 are masked off.
      configure(bits, config, "h3f800000",
        Seq("h40000000", "h40400000", "h40800000", "h3f000000"),
        predicateMask = 0b1010, vm = false)
    })
    assert(data(0) == BigInt("40900000", 16)) // 4.5
  }

  it should "fold in element order rather than through a tree" in {
    val config = GpuConfig(lanes = 4, warps = 2)
    val (data, _, _) = run(config, { bits =>
      // ((0.1 + 1e10) - 1e10) + 1 is 1.0: the 0.1 is lost to rounding on the
      // first add. A tree that paired 1e10 with -1e10 first would report 1.1.
      configure(bits, config, "h3dcccccd",
        Seq("h501502f9", "hd01502f9", "h3f800000", "h00000000"))
    })
    assert(data(0) == BigInt("3f800000", 16), s"got 0x${data(0).toString(16)}")
  }

  it should "leave element zero alone when no element participates" in {
    val config = GpuConfig(lanes = 4, warps = 2)
    val (data, flags, writeFlags) = run(config, { bits =>
      configure(bits, config, "h3f800000",
        Seq("h40000000", "h40400000", "h40800000", "h3f000000"),
        predicateMask = 0, vm = false)
    })
    assert(data == Vector.fill(config.lanes)(BigInt("3f800000", 16)))
    assert(flags == 0)
    assert(writeFlags == 0, "an empty reduction must not touch the FP flags")
  }

  it should "report the flags of every add it folds" in {
    val config = GpuConfig(lanes = 4, warps = 2)
    // 1 + 0.5 + 0.1 + 0.1 + 0.1 rounds at every step, so the sum is one ulp
    // off the correctly rounded 1.8 and the adds report inexact.
    val (data, flags, writeFlags) = run(config, { bits =>
      configure(bits, config, "h3f800000",
        Seq("h3f000000", "h3dcccccd", "h3dcccccd", "h3dcccccd"))
    })
    assert(data(0) == BigInt("3fe66667", 16))
    assert(writeFlags == 1)
    assert((flags & BigInt("1", 16)) != 0, s"flags were $flags")
  }

  it should "skip inactive warp lanes" in {
    val config = GpuConfig(lanes = 4, warps = 2)
    val (data, _, _) = run(config, { bits =>
      // Only lane 2 is active: 1 + 4.
      configure(bits, config, "h3f800000",
        Seq("h40000000", "h40400000", "h40800000", "h3f000000"),
        activeMask = 0b0100)
    })
    assert(data(0) == BigInt("40a00000", 16)) // 5.0
  }
}
