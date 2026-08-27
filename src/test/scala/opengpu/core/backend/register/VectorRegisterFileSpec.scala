package opengpu.core.backend.register

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import circt.stage.ChiselStage
import opengpu.config.GpuConfig
import org.scalatest.flatspec.AnyFlatSpec
import java.nio.file.{Files, Path}

class VectorRegisterFileSpec extends AnyFlatSpec {
  behavior of "VectorRegisterFile"

  private def runBankIsolationTest(
    dut: VectorRegisterFile,
    config: GpuConfig
  ): Unit = {
    dut.io.read.warpId.poke(0.U)
    dut.io.read.vs1.poke(0.U)
    dut.io.read.vs2.poke(3.U)
    dut.io.read.vd.poke(0.U)
    dut.io.write.valid.poke(false.B)
    dut.clock.step()

    dut.io.write.valid.poke(true.B)
    dut.io.write.bits.warpId.poke(0.U)
    dut.io.write.bits.vd.poke(0.U)
    for (lane <- 0 until config.lanes) {
      dut.io.write.bits.data(lane).poke((0x10 + lane).U)
    }
    for (lane <- 0 until config.lanes) {
      dut.io.vs1Data(lane).expect((0x10 + lane).U)
      dut.io.oldVdData(lane).expect((0x10 + lane).U)
    }
    dut.io.predicateMask.expect(0.U)
    dut.clock.step()

    dut.io.write.bits.warpId.poke(1.U)
    for (lane <- 0 until config.lanes) {
      dut.io.write.bits.data(lane).poke((0x20 + lane).U)
    }
    dut.clock.step()
    dut.io.write.valid.poke(false.B)

    dut.io.read.warpId.poke(0.U)
    for (lane <- 0 until config.lanes) {
      dut.io.vs1Data(lane).expect((0x10 + lane).U)
    }
    dut.io.read.warpId.poke(1.U)
    for (lane <- 0 until config.lanes) {
      dut.io.vs1Data(lane).expect((0x20 + lane).U)
    }
    dut.io.predicateMask.expect(0.U)
  }

  it should "isolate warps, keep v0 writable, and bypass same-cycle writes" in {
    val config = GpuConfig(lanes = 4, warps = 2)
    simulate(new VectorRegisterFile(config)) { dut =>
      runBankIsolationTest(dut, config)
    }
  }

  it should "take the predicate mask from v0 regardless of the vs1 field" in {
    // The predicate mask is the architectural v0 mask (the low `lanes` bits of
    // v0's lane-0 word, matching the packed layout mask-producing instructions
    // write back).  It must not follow the vs1 read port: for loads/stores
    // instruction(19,15) encodes the scalar base register rs1, not a vector
    // operand.
    val config = GpuConfig(lanes = 4, warps = 2)
    simulate(new VectorRegisterFile(config)) { dut =>
      dut.io.read.warpId.poke(0.U)
      dut.io.read.vs1.poke(3.U)
      dut.io.read.vs2.poke(0.U)
      dut.io.read.vd.poke(0.U)
      dut.io.write.valid.poke(false.B)
      dut.clock.step()

      // v0 lane 0 word = 0b0101; v3 lane 0 word = 0xff (must be ignored).
      dut.io.write.valid.poke(true.B)
      dut.io.write.bits.warpId.poke(0.U)
      dut.io.write.bits.vd.poke(0.U)
      for (lane <- 0 until config.lanes) {
        dut.io.write.bits.data(lane).poke((if (lane == 0) 0x5 else 0).U)
      }
      dut.clock.step()
      dut.io.write.bits.vd.poke(3.U)
      for (lane <- 0 until config.lanes) {
        dut.io.write.bits.data(lane).poke(0xff.U)
      }
      dut.clock.step()
      dut.io.write.valid.poke(false.B)

      dut.io.vs1Data(0).expect(0xff.U)
      dut.io.predicateMask.expect("b0101".U)

      // The mask is warp-local: warp 1's v0 was never written.
      dut.io.read.warpId.poke(1.U)
      dut.io.predicateMask.expect(0.U)
    }
  }

  it should "emit the physical RF as ASAP7 SRAM macro instances" in {
    val target = Files.createTempDirectory("vrf-macro")
    try {
      ChiselStage.emitSystemVerilogFile(
        new VectorRegisterFile(GpuConfig(lanes = 4, warps = 2), useBlackBox = true),
        Array("--target-dir", target.toString)
      )
      val rtl = Files.list(Path.of(target.toString))
        .filter(_.toString.endsWith(".sv"))
        .map(Files.readString(_))
        .toArray
        .mkString("\n")
      val instanceCount = "srambank_64x4x64_6t122".r
        .findAllMatchIn(rtl).length
      assert(instanceCount > 0,
        "expected ASAP7 SRAM macro instances in physical vector RF RTL")
    } finally {
      // Leave the emitted RTL in place for manual inspection on failure.
    }
  }
}
