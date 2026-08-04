package gpu.core.backend.issue

import chisel3._
import chisel3.util._
import chisel3.simulator.EphemeralSimulator._
import gpu.config.GpuConfig
import gpu.core.backend.register.ScalarRegisterWrite
import gpu.core.frontend.decode.{DecodePipe, DecodeRequest}
import org.scalatest.flatspec.AnyFlatSpec

private class ScalarIssueHarness(config: GpuConfig) extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new DecodeRequest(config)))
    val out = Decoupled(new ScalarIssuedInstruction(config))
    val writeback = Flipped(Valid(new ScalarRegisterWrite(config)))
    val rawHazard = Output(Bool())
  })

  private val decode = Module(new DecodePipe(config))
  private val issue = Module(new ScalarIssueStage(config))
  issue.io.cancel.valid := false.B
  issue.io.cancel.bits := 0.U.asTypeOf(issue.io.cancel.bits)
  decode.io.in <> io.in
  issue.io.in <> decode.io.scalarOut
  decode.io.fpuOut.ready := true.B
  decode.io.vectorOut.ready := true.B
  io.out <> issue.io.out
  issue.io.writeback <> io.writeback
  io.rawHazard := issue.io.rawHazard
}

class ScalarIssueStageSpec extends AnyFlatSpec {
  behavior of "ScalarIssueStage"

  private def writeRegister(
    dut: ScalarIssueHarness,
    warpId: Int,
    rd: Int,
    data: Int
  ): Unit = {
    dut.io.writeback.valid.poke(true.B)
    dut.io.writeback.bits.warpId.poke(warpId.U)
    dut.io.writeback.bits.rd.poke(rd.U)
    dut.io.writeback.bits.data.poke(data.U)
    dut.clock.step()
    dut.io.writeback.valid.poke(false.B)
    dut.clock.step()
  }

  private def send(
    dut: ScalarIssueHarness,
    instruction: BigInt,
    pc: Int,
    mask: Int
  ): Unit = {
    dut.io.in.valid.poke(true.B)
    dut.io.in.bits.instruction.poke(instruction.U)
    dut.io.in.bits.pc.poke(pc.U)
    dut.io.in.bits.warpId.poke(0.U)
    dut.io.in.bits.activeMask.poke(mask.U)
    dut.io.in.bits.instructionAccessFault.poke(false.B)
    while (!dut.io.in.ready.peek().litToBoolean) {
      dut.clock.step()
    }
    dut.clock.step()
    dut.io.in.valid.poke(false.B)
  }

  private def waitForOutput(dut: ScalarIssueHarness): Unit = {
    var cycles = 0
    while (!dut.io.out.valid.peek().litToBoolean && cycles < 12) {
      dut.clock.step()
      cycles += 1
    }
    assert(dut.io.out.valid.peek().litToBoolean)
  }

  it should "align decoded metadata and operands across dependency stalls" in {
    val config = GpuConfig(lanes = 4, warps = 2)
    simulate(new ScalarIssueHarness(config)) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      dut.io.in.valid.poke(false.B)
      dut.io.out.ready.poke(false.B)
      dut.io.writeback.valid.poke(false.B)

      writeRegister(dut, warpId = 0, rd = 1, data = 7)
      writeRegister(dut, warpId = 0, rd = 2, data = 9)

      // add x3, x1, x2
      send(dut, BigInt("002081b3", 16), pc = 0x100, mask = 0xb)
      waitForOutput(dut)
      dut.io.out.bits.decode.pc.expect(0x100.U)
      dut.io.out.bits.decode.activeMask.expect(0xb.U)
      dut.io.out.bits.decode.decoded.useRs1.expect(true.B)
      dut.io.out.bits.decode.decoded.useRs2.expect(true.B)
      dut.io.out.bits.rs1Data.expect(7.U)
      dut.io.out.bits.rs2Data.expect(9.U)
      dut.io.out.ready.poke(true.B)
      dut.clock.step()
      dut.io.out.ready.poke(false.B)

      // addi x4, x3, 1 must wait for x3 writeback.
      send(dut, BigInt("00118213", 16), pc = 0x104, mask = 0xb)
      dut.clock.step(3)
      dut.io.out.valid.expect(false.B)
      dut.io.rawHazard.expect(true.B)

      writeRegister(dut, warpId = 0, rd = 3, data = 16)
      waitForOutput(dut)
      dut.io.out.bits.decode.pc.expect(0x104.U)
      dut.io.out.bits.decode.decoded.useRs1.expect(true.B)
      dut.io.out.bits.decode.decoded.useRs2.expect(false.B)
      dut.io.out.bits.rs1Data.expect(16.U)
    }
  }
}
