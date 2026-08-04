package gpu.core.frontend.decode

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import gpu.config.GpuConfig
import org.scalatest.flatspec.AnyFlatSpec

class ExtendedDecoderSpec extends AnyFlatSpec {
  behavior of "FullInstructionDecoder"

  it should "route scalar floating-point instructions to the FPU" in {
    simulate(new FullInstructionDecoder) { dut =>
      // fadd.s f2, f1, f0
      dut.io.instruction.poke("b0000000_00000_00001_000_00010_1010011".U)
      dut.io.decoded.legal.expect(true.B)
      dut.io.decoded.executionType.expect(ExecutionType.fpu)
      dut.io.decoded.fpu.valid.expect(true.B)
      dut.io.decoded.fpu.format.expect(0.U)
      dut.io.decoded.fpu.writesFp.expect(true.B)

      // fmadd.s f4, f3, f2, f1
      dut.io.instruction.poke("b0000000_00001_00010_000_00100_1000011".U)
      dut.io.decoded.executionType.expect(ExecutionType.fpu)
      dut.io.decoded.fpu.unit.expect(FpuUnit.fma)
      dut.io.decoded.fpu.readsRs3.expect(true.B)

      // The FP32-only GPU must reject the corresponding FP64 instruction.
      dut.io.instruction.poke("b0000010_00001_00010_000_00100_1000011".U)
      dut.io.decoded.legal.expect(false.B)
      dut.io.decoded.illegalInstruction.expect(true.B)
      dut.io.decoded.executionType.expect(ExecutionType.illegal)
    }
  }

  it should "route RVV arithmetic, configuration, and memory instructions" in {
    simulate(new FullInstructionDecoder) { dut =>
      // vadd.vv v2, v1, v0
      dut.io.instruction.poke("h02008157".U)
      dut.io.decoded.legal.expect(true.B)
      dut.io.decoded.executionType.expect(ExecutionType.vector)
      dut.io.decoded.vector.valid.expect(true.B)
      dut.io.decoded.vector.unit.expect(VectorUnit.alu)
      dut.io.decoded.vector.readsVs1.expect(true.B)
      dut.io.decoded.vector.readsVs2.expect(true.B)

      // vsetvli x1, x2, e32,m1
      dut.io.instruction.poke("h010170d7".U)
      dut.io.decoded.executionType.expect(ExecutionType.vector)
      dut.io.decoded.vector.configure.expect(true.B)
      dut.io.decoded.vector.unit.expect(VectorUnit.configuration)

      // vle32.v v1, (x2)
      dut.io.instruction.poke("h02016087".U)
      dut.io.decoded.executionType.expect(ExecutionType.vector)
      dut.io.decoded.vector.memoryRead.expect(true.B)
      dut.io.decoded.vector.unit.expect(VectorUnit.loadStore)
    }
  }

  it should "report reserved and unsupported encodings as illegal instructions" in {
    simulate(new FullInstructionDecoder) { dut =>
      // Reserved vsub.vi encoding.
      dut.io.instruction.poke("b000010_1_00001_00010_011_00011_1010111".U)
      dut.io.decoded.legal.expect(false.B)
      dut.io.decoded.illegalInstruction.expect(true.B)
      dut.io.decoded.executionType.expect(ExecutionType.illegal)

      // A normal RV32I addi clears the exception indication.
      dut.io.instruction.poke("h00100093".U)
      dut.io.decoded.legal.expect(true.B)
      dut.io.decoded.illegalInstruction.expect(false.B)
    }
  }

  it should "classify CSR and warp-control instructions as system operations" in {
    simulate(new FullInstructionDecoder) { dut =>
      dut.io.instruction.poke("b001100000000_00011_101_00001_1110011".U)
      dut.io.decoded.legal.expect(true.B)
      dut.io.decoded.executionType.expect(ExecutionType.system)
      dut.io.decoded.scalar.csr.expect(true.B)

      dut.io.instruction.poke("h30500073".U)
      dut.io.decoded.legal.expect(true.B)
      dut.io.decoded.executionType.expect(ExecutionType.system)
      dut.io.decoded.scalar.cease.expect(true.B)
    }
  }

  it should "carry illegal-instruction reporting through the decode pipe" in {
    simulate(new DecodePipe(GpuConfig(warps = 4))) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)

      dut.io.scalarOut.ready.poke(true.B)
      dut.io.fpuOut.ready.poke(true.B)
      dut.io.vectorOut.ready.poke(true.B)
      dut.io.in.valid.poke(true.B)
      dut.io.in.bits.instruction.poke("hffffffff".U)
      dut.io.in.bits.pc.poke("h2000".U)
      dut.io.in.bits.warpId.poke(1.U)
      dut.io.in.bits.activeMask.poke("h5a".U)
      dut.io.in.bits.instructionAccessFault.poke(false.B)
      dut.clock.step()

      dut.io.in.valid.poke(false.B)
      dut.io.scalarOut.valid.expect(true.B)
      dut.io.scalarOut.bits.executionType.expect(ExecutionType.illegal)
      dut.io.scalarOut.bits.illegalInstruction.expect(true.B)
      dut.io.scalarOut.bits.instruction.expect("hffffffff".U)
      dut.io.scalarOut.bits.pc.expect("h2000".U)
      dut.io.scalarOut.bits.activeMask.expect("h5a".U)
    }
  }

  it should "preserve decode output while the pipe is backpressured" in {
    simulate(new DecodePipe(GpuConfig(warps = 4))) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)

      dut.io.scalarOut.ready.poke(true.B)
      dut.io.fpuOut.ready.poke(true.B)
      dut.io.vectorOut.ready.poke(false.B)
      dut.io.in.valid.poke(true.B)
      dut.io.in.bits.instruction.poke("h02008157".U)
      dut.io.in.bits.pc.poke("h1000".U)
      dut.io.in.bits.warpId.poke(2.U)
      dut.io.in.bits.activeMask.poke("ha5".U)
      dut.io.in.bits.instructionAccessFault.poke(false.B)
      dut.clock.step()

      dut.io.in.valid.poke(false.B)
      dut.io.vectorOut.valid.expect(true.B)
      dut.io.vectorOut.bits.pc.expect("h1000".U)
      dut.io.vectorOut.bits.warpId.expect(2.U)
      dut.io.vectorOut.bits.activeMask.expect("ha5".U)
      dut.io.vectorOut.bits.decoded.valid.expect(true.B)
      dut.clock.step(2)
      dut.io.vectorOut.valid.expect(true.B)

      dut.io.vectorOut.ready.poke(true.B)
      dut.clock.step()
      dut.io.vectorOut.valid.expect(false.B)
    }
  }
}
