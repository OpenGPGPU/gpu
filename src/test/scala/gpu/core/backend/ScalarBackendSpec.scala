package gpu.core.backend

import chisel3._
import chisel3.util._
import chisel3.simulator.EphemeralSimulator._
import gpu.config.GpuConfig
import gpu.core.frontend.decode.{DecodePipe, DecodeRequest}
import org.scalatest.flatspec.AnyFlatSpec

private class ScalarBackendHarness(config: GpuConfig) extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new DecodeRequest(config)))
    val redirect =
      Decoupled(new gpu.core.execute.control.SimtPath(config))
    val committedWriteback =
      Valid(new gpu.core.backend.register.ScalarRegisterWrite(config))
  })

  private val decode = Module(new DecodePipe(config))
  private val backend = Module(new ScalarBackend(config))
  decode.io.in <> io.in
  backend.io.in <> decode.io.scalarOut
  backend.io.initialize.valid := false.B
  backend.io.initialize.bits := 0.U.asTypeOf(backend.io.initialize.bits)
  decode.io.fpuOut.ready := true.B
  decode.io.vectorOut.ready := true.B
  io.redirect <> backend.io.redirect
  io.committedWriteback := backend.io.committedWriteback
  backend.io.memory.ready := false.B
  backend.io.system.ready := false.B
  backend.io.trap.ready := false.B
  backend.io.cacheRequest.ready := false.B
  backend.io.cacheResponse.valid := false.B
  backend.io.cacheResponse.bits := 0.U.asTypeOf(backend.io.cacheResponse.bits)
  backend.io.memoryFault.ready := false.B
  backend.io.externalWriteback.valid := false.B
  backend.io.externalWriteback.bits := 0.U.asTypeOf(
    backend.io.externalWriteback.bits
  )
  backend.io.externalReserve.valid := false.B
  backend.io.externalReserve.bits := 0.U.asTypeOf(
    backend.io.externalReserve.bits
  )
}

class ScalarBackendSpec extends AnyFlatSpec {
  behavior of "ScalarBackend"

  private def send(
    dut: ScalarBackendHarness,
    instruction: BigInt,
    pc: Int
  ): Unit = {
    dut.io.in.valid.poke(true.B)
    dut.io.in.bits.instruction.poke(instruction.U)
    dut.io.in.bits.pc.poke(pc.U)
    dut.io.in.bits.warpId.poke(0.U)
    dut.io.in.bits.activeMask.poke(0xf.U)
    dut.io.in.bits.instructionAccessFault.poke(false.B)
    while (!dut.io.in.ready.peek().litToBoolean) {
      dut.clock.step()
    }
    dut.clock.step()
    dut.io.in.valid.poke(false.B)
  }

  private def waitForRedirect(dut: ScalarBackendHarness): Unit = {
    var cycles = 0
    while (!dut.io.redirect.valid.peek().litToBoolean && cycles < 16) {
      dut.clock.step()
      cycles += 1
    }
    assert(dut.io.redirect.valid.peek().litToBoolean)
  }

  it should "commit a JAL link before a dependent integer instruction" in {
    simulate(new ScalarBackendHarness(GpuConfig(lanes = 4, warps = 2))) {
      dut =>
        dut.reset.poke(true.B)
        dut.clock.step()
        dut.reset.poke(false.B)
        dut.io.in.valid.poke(false.B)
        dut.io.redirect.ready.poke(true.B)

        // jal x1, +8
        send(dut, BigInt("008000ef", 16), pc = 0x100)
        waitForRedirect(dut)
        dut.io.redirect.bits.pc.expect(0x108.U)
        dut.io.committedWriteback.valid.expect(true.B)
        dut.io.committedWriteback.bits.rd.expect(1.U)
        dut.io.committedWriteback.bits.data.expect(0x104.U)
        dut.clock.step()

        // addi x2, x1, 1; dependency must see the committed link value.
        send(dut, BigInt("00108113", 16), pc = 0x108)
        waitForRedirect(dut)
        dut.io.redirect.bits.pc.expect(0x10c.U)
        dut.io.committedWriteback.valid.expect(true.B)
        dut.io.committedWriteback.bits.rd.expect(2.U)
        dut.io.committedWriteback.bits.data.expect(0x105.U)
    }
  }

  it should "commit MULH before a dependent integer instruction" in {
    simulate(new ScalarBackendHarness(GpuConfig(lanes = 4, warps = 2))) {
      dut =>
        dut.reset.poke(true.B)
        dut.clock.step()
        dut.reset.poke(false.B)
        dut.io.in.valid.poke(false.B)
        dut.io.redirect.ready.poke(true.B)

        // addi x1, x0, -2
        send(dut, BigInt("ffe00093", 16), pc = 0x100)
        waitForRedirect(dut)
        dut.io.committedWriteback.bits.data.expect("hfffffffe".U)
        dut.clock.step()

        // addi x2, x0, 3
        send(dut, BigInt("00300113", 16), pc = 0x104)
        waitForRedirect(dut)
        dut.clock.step()

        // mulh x3, x1, x2 = upper(-2 * 3) = 0xffffffff
        send(dut, BigInt("022091b3", 16), pc = 0x108)
        waitForRedirect(dut)
        dut.io.committedWriteback.valid.expect(true.B)
        dut.io.committedWriteback.bits.rd.expect(3.U)
        dut.io.committedWriteback.bits.data.expect("hffffffff".U)
        dut.clock.step()

        // addi x4, x3, 1; dependency observes the committed MULH result.
        send(dut, BigInt("00118213", 16), pc = 0x10c)
        waitForRedirect(dut)
        dut.io.committedWriteback.bits.rd.expect(4.U)
        dut.io.committedWriteback.bits.data.expect(0.U)
    }
  }

  it should "hold a DIV dependency until the iterative result commits" in {
    simulate(new ScalarBackendHarness(GpuConfig(lanes = 4, warps = 2))) {
      dut =>
        dut.reset.poke(true.B)
        dut.clock.step()
        dut.reset.poke(false.B)
        dut.io.in.valid.poke(false.B)
        dut.io.redirect.ready.poke(true.B)

        // addi x1, x0, -7
        send(dut, BigInt("ff900093", 16), pc = 0x100)
        waitForRedirect(dut)
        dut.clock.step()

        // addi x2, x0, 3
        send(dut, BigInt("00300113", 16), pc = 0x104)
        waitForRedirect(dut)
        dut.clock.step()

        // div x3, x1, x2 = -2
        send(dut, BigInt("0220c1b3", 16), pc = 0x108)
        var cycles = 0
        while (!dut.io.redirect.valid.peek().litToBoolean && cycles < 40) {
          dut.clock.step()
          cycles += 1
        }
        assert(dut.io.redirect.valid.peek().litToBoolean)
        dut.io.committedWriteback.bits.rd.expect(3.U)
        dut.io.committedWriteback.bits.data.expect("hfffffffe".U)
        dut.clock.step()

        // addi x4, x3, 2 = 0
        send(dut, BigInt("00218213", 16), pc = 0x10c)
        waitForRedirect(dut)
        dut.io.committedWriteback.bits.rd.expect(4.U)
        dut.io.committedWriteback.bits.data.expect(0.U)
    }
  }
}
