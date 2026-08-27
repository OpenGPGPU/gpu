package opengpu.core.frontend

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import opengpu.config.GpuConfig
import org.scalatest.flatspec.AnyFlatSpec

class GpuFrontendSpec extends AnyFlatSpec {
  behavior of "GpuFrontend"

  private def launch(
    dut: GpuFrontend,
    warpId: Int,
    pc: Int,
    activeMask: Int
  ): Unit = {
    dut.io.launch.valid.poke(true.B)
    dut.io.launch.bits.warpId.poke(warpId.U)
    dut.io.launch.bits.startPc.poke(pc.U)
    dut.io.launch.bits.activeMask.poke(activeMask.U)
    dut.io.launch.ready.expect(true.B)
    dut.clock.step()
    dut.io.launch.valid.poke(false.B)
  }

  it should "retain warp metadata and replace a completed fetch without a bubble" in {
    val config = GpuConfig(lanes = 4, warps = 2)
    simulate(new GpuFrontend(config)) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)

      dut.io.launch.valid.poke(false.B)
      dut.io.fetchRequest.ready.poke(false.B)
      dut.io.fetchResponse.valid.poke(false.B)
      dut.io.fetchResponse.bits.warpId.poke(0.U)
      dut.io.fetchResponse.bits.instruction.poke(0.U)
      dut.io.fetchResponse.bits.accessFault.poke(false.B)
      dut.io.scalarOut.ready.poke(false.B)
      dut.io.fpuOut.ready.poke(true.B)
      dut.io.vectorOut.ready.poke(true.B)
      dut.io.branch.valid.poke(false.B)
      dut.io.restore.valid.poke(false.B)
      dut.io.restore.bits.poke(0.U)
      dut.io.scalarRedirect.valid.poke(false.B)
      dut.io.finish.valid.poke(false.B)
      dut.io.finish.bits.poke(0.U)

      launch(dut, warpId = 0, pc = 0x100, activeMask = 0xf)
      launch(dut, warpId = 1, pc = 0x200, activeMask = 0x3)

      dut.io.fetchRequest.ready.poke(true.B)
      while (!dut.io.fetchRequest.valid.peek().litToBoolean) {
        dut.clock.step()
      }
      dut.io.fetchRequest.bits.warpId.expect(0.U)
      dut.io.fetchRequest.bits.pc.expect(0x100.U)
      dut.clock.step()

      // Retire warp 0's response while launching warp 1's fetch.
      dut.io.fetchResponse.valid.poke(true.B)
      dut.io.fetchResponse.bits.instruction.poke("h00100093".U)
      dut.io.fetchResponse.bits.accessFault.poke(false.B)
      dut.io.fetchResponse.ready.expect(true.B)
      dut.io.fetchRequest.valid.expect(true.B)
      dut.io.fetchRequest.bits.warpId.expect(1.U)
      dut.io.fetchRequest.bits.pc.expect(0x200.U)
      dut.clock.step()

      dut.io.fetchResponse.valid.poke(false.B)
      dut.io.scalarOut.valid.expect(true.B)
      dut.io.scalarOut.bits.warpId.expect(0.U)
      dut.io.scalarOut.bits.pc.expect(0x100.U)
      dut.io.scalarOut.bits.activeMask.expect(0xf.U)
      dut.io.scalarOut.bits.instructionAccessFault.expect(false.B)

      // The occupied decode output backpressures the next memory response.
      dut.io.fetchResponse.valid.poke(true.B)
      dut.io.fetchResponse.bits.warpId.poke(1.U)
      dut.io.fetchResponse.bits.instruction.poke(
        "b0000000_00000_00001_000_00010_1010011".U
      )
      dut.io.fetchResponse.bits.accessFault.poke(true.B)
      dut.io.fetchResponse.ready.expect(false.B)

      dut.io.scalarOut.ready.poke(true.B)
      dut.io.fetchResponse.ready.expect(true.B)
      dut.clock.step()
      dut.io.fetchResponse.valid.poke(false.B)

      // A fetch fault is a scalar trap event, not an illegal instruction or
      // an FPU operation selected from meaningless response data.
      dut.io.scalarOut.valid.expect(true.B)
      dut.io.scalarOut.bits.warpId.expect(1.U)
      dut.io.scalarOut.bits.pc.expect(0x200.U)
      dut.io.scalarOut.bits.activeMask.expect(0x3.U)
      dut.io.scalarOut.bits.instructionAccessFault.expect(true.B)
      dut.io.scalarOut.bits.illegalInstruction.expect(false.B)
      dut.io.fpuOut.valid.expect(false.B)
      dut.io.vectorOut.valid.expect(false.B)
    }
  }
}
