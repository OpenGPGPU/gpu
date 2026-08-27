package opengpu.core.backend.issue

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import opengpu.config.GpuConfig
import opengpu.core.frontend.decode.ExecutionType
import org.scalatest.flatspec.AnyFlatSpec

class ScalarExecutionDispatchSpec extends AnyFlatSpec {
  behavior of "ScalarExecutionDispatch"

  private def defaults(dut: ScalarExecutionDispatch): Unit = {
    dut.io.integer.ready.poke(true.B)
    dut.io.multiply.ready.poke(true.B)
    dut.io.divide.ready.poke(true.B)
    dut.io.branch.ready.poke(true.B)
    dut.io.memory.ready.poke(true.B)
    dut.io.atomic.ready.poke(true.B)
    dut.io.system.ready.poke(true.B)
    dut.io.trap.ready.poke(true.B)
    dut.io.in.valid.poke(true.B)
    dut.io.in.bits.decode.instructionAccessFault.poke(false.B)
    dut.io.in.bits.decode.illegalInstruction.poke(false.B)
    dut.io.in.bits.decode.decoded.multiply.poke(false.B)
    dut.io.in.bits.decode.decoded.divide.poke(false.B)
    dut.io.in.bits.decode.decoded.atomic.poke(false.B)
  }

  it should "route each scalar class exclusively and honor selected backpressure" in {
    simulate(new ScalarExecutionDispatch(GpuConfig())) { dut =>
      defaults(dut)
      dut.io.in.bits.decode.executionType.poke(ExecutionType.integer)
      dut.io.integer.valid.expect(true.B)
      dut.io.multiply.valid.expect(false.B)
      dut.io.in.ready.expect(true.B)

      dut.io.in.bits.decode.decoded.multiply.poke(true.B)
      dut.io.integer.valid.expect(false.B)
      dut.io.multiply.valid.expect(true.B)
      dut.io.multiply.ready.poke(false.B)
      dut.io.in.ready.expect(false.B)

      dut.io.in.bits.decode.decoded.multiply.poke(false.B)
      dut.io.in.bits.decode.decoded.divide.poke(true.B)
      dut.io.divide.valid.expect(true.B)

      dut.io.in.bits.decode.decoded.divide.poke(false.B)
      dut.io.in.bits.decode.executionType.poke(ExecutionType.branch)
      dut.io.branch.valid.expect(true.B)

      dut.io.in.bits.decode.executionType.poke(ExecutionType.memory)
      dut.io.memory.valid.expect(true.B)

      dut.io.in.bits.decode.decoded.atomic.poke(true.B)
      dut.io.memory.valid.expect(false.B)
      dut.io.atomic.valid.expect(true.B)

      dut.io.in.bits.decode.executionType.poke(ExecutionType.system)
      dut.io.system.valid.expect(true.B)
    }
  }

  it should "send fetch and illegal faults only to the trap path" in {
    simulate(new ScalarExecutionDispatch(GpuConfig())) { dut =>
      defaults(dut)
      dut.io.in.bits.decode.executionType.poke(ExecutionType.integer)
      dut.io.in.bits.decode.decoded.multiply.poke(true.B)
      dut.io.in.bits.decode.instructionAccessFault.poke(true.B)
      dut.io.trap.valid.expect(true.B)
      dut.io.multiply.valid.expect(false.B)
      dut.io.integer.valid.expect(false.B)

      dut.io.in.bits.decode.instructionAccessFault.poke(false.B)
      dut.io.in.bits.decode.illegalInstruction.poke(true.B)
      dut.io.trap.valid.expect(true.B)
      dut.io.multiply.valid.expect(false.B)
    }
  }
}
