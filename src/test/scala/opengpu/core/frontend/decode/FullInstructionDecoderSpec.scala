package opengpu.core.frontend.decode

import chisel3._
import opengpu.config.GpuConfig
import opengpu.testutil.GpuSim._
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

      // vmerge.vvm v4, v2, v3, v0
      dut.io.instruction.poke("h5c218257".U)
      dut.io.decoded.legal.expect(true.B)
      dut.io.decoded.vector.unit.expect(VectorUnit.alu)
      dut.io.decoded.vector.readsVs1.expect(true.B)
      dut.io.decoded.vector.readsVs2.expect(true.B)
      dut.io.decoded.vector.vm.expect(false.B)
      // vmv.v.x v5, x9
      dut.io.instruction.poke("h5e04c2d7".U)
      dut.io.decoded.legal.expect(true.B)
      dut.io.decoded.vector.readsVs1.expect(false.B)
      dut.io.decoded.vector.readsVs2.expect(false.B)
      dut.io.decoded.vector.readsScalar.expect(true.B)
      dut.io.decoded.vector.vm.expect(true.B)
      // vmv with vs2 other than v0 is reserved.
      dut.io.instruction.poke("h5e218257".U)
      dut.io.decoded.legal.expect(false.B)

      // vmand.mm v0, v4, v5
      dut.io.instruction.poke("h6642a057".U)
      dut.io.decoded.legal.expect(true.B)
      dut.io.decoded.vector.unit.expect(VectorUnit.alu)
      dut.io.decoded.vector.readsVs1.expect(true.B)
      dut.io.decoded.vector.readsVs2.expect(true.B)
      dut.io.decoded.vector.vm.expect(true.B)
      // The masked encoding of a mask-logical op is reserved.
      dut.io.instruction.poke("h6442a057".U)
      dut.io.decoded.legal.expect(false.B)

      // vmv.x.s x10, v2
      dut.io.instruction.poke("h42202557".U)
      dut.io.decoded.legal.expect(true.B)
      dut.io.decoded.vector.unit.expect(VectorUnit.alu)
      dut.io.decoded.vector.readsVs2.expect(true.B)
      dut.io.decoded.vector.readsVs1.expect(false.B)
      dut.io.decoded.vector.readsScalar.expect(false.B)
      dut.io.decoded.vector.writesVd.expect(false.B)
      dut.io.decoded.vector.writesScalar.expect(true.B)
      // vs1 other than 0 is a different unary and stays reserved.
      dut.io.instruction.poke("h4220a557".U)
      dut.io.decoded.legal.expect(false.B)
      // The masked encoding is reserved.
      dut.io.instruction.poke("h40202557".U)
      dut.io.decoded.legal.expect(false.B)

      // vmv.s.x v3, x9
      dut.io.instruction.poke("h4204e1d7".U)
      dut.io.decoded.legal.expect(true.B)
      dut.io.decoded.vector.unit.expect(VectorUnit.alu)
      dut.io.decoded.vector.readsVs2.expect(false.B)
      dut.io.decoded.vector.readsScalar.expect(true.B)
      dut.io.decoded.vector.writesVd.expect(true.B)
      dut.io.decoded.vector.writesScalar.expect(false.B)
      // vs2 other than v0 is reserved.
      dut.io.instruction.poke("h4214e1d7".U)
      dut.io.decoded.legal.expect(false.B)

      // vfmv.f.s f2, v2
      dut.io.instruction.poke("h42201157".U)
      dut.io.decoded.legal.expect(true.B)
      dut.io.decoded.vector.unit.expect(VectorUnit.floatingPoint)
      dut.io.decoded.vector.readsVs2.expect(true.B)
      dut.io.decoded.vector.readsFloat.expect(false.B)
      dut.io.decoded.vector.writesVd.expect(false.B)
      dut.io.decoded.vector.writesFloat.expect(true.B)
      // vs1 other than 0 stays reserved.
      dut.io.instruction.poke("h42209157".U)
      dut.io.decoded.legal.expect(false.B)
      // The masked encoding is reserved.
      dut.io.instruction.poke("h40201157".U)
      dut.io.decoded.legal.expect(false.B)

      // vfmv.s.f v2, f1
      dut.io.instruction.poke("h4200d157".U)
      dut.io.decoded.legal.expect(true.B)
      dut.io.decoded.vector.unit.expect(VectorUnit.floatingPoint)
      dut.io.decoded.vector.readsVs2.expect(false.B)
      dut.io.decoded.vector.readsFloat.expect(true.B)
      dut.io.decoded.vector.writesVd.expect(true.B)
      dut.io.decoded.vector.writesFloat.expect(false.B)
      // vs2 other than v0 is reserved.
      dut.io.instruction.poke("h4210d157".U)
      dut.io.decoded.legal.expect(false.B)

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

      // vfcvt.xu.f.v v3, v2
      dut.io.instruction.poke("h4a2011d7".U)
      dut.io.decoded.legal.expect(true.B)
      dut.io.decoded.executionType.expect(ExecutionType.vector)
      dut.io.decoded.vector.valid.expect(true.B)
      dut.io.decoded.vector.unit.expect(VectorUnit.floatingPoint)
      dut.io.decoded.vector.readsVs1.expect(false.B)
      dut.io.decoded.vector.readsVs2.expect(true.B)

      // vfclass.v v3, v2
      dut.io.instruction.poke("h4e2811d7".U)
      dut.io.decoded.legal.expect(true.B)
      dut.io.decoded.executionType.expect(ExecutionType.vector)
      dut.io.decoded.vector.valid.expect(true.B)
      dut.io.decoded.vector.unit.expect(VectorUnit.floatingPoint)
      dut.io.decoded.vector.readsVs2.expect(true.B)

      // vfsqrt.v v3, v2
      dut.io.instruction.poke("h4e2011d7".U)
      dut.io.decoded.legal.expect(true.B)
      dut.io.decoded.executionType.expect(ExecutionType.vector)
      dut.io.decoded.vector.valid.expect(true.B)
      dut.io.decoded.vector.unit.expect(VectorUnit.floatingPoint)
      dut.io.decoded.vector.readsVs2.expect(true.B)

      // vfrsqrt7.v v3, v2
      dut.io.instruction.poke("h4e2291d7".U)
      dut.io.decoded.legal.expect(true.B)
      dut.io.decoded.executionType.expect(ExecutionType.vector)
      dut.io.decoded.vector.valid.expect(true.B)
      dut.io.decoded.vector.unit.expect(VectorUnit.floatingPoint)
      dut.io.decoded.vector.readsVs2.expect(true.B)

      // vfrec7.v v3, v2
      dut.io.instruction.poke("h4e2211d7".U)
      dut.io.decoded.legal.expect(true.B)
      dut.io.decoded.executionType.expect(ExecutionType.vector)
      dut.io.decoded.vector.valid.expect(true.B)
      dut.io.decoded.vector.unit.expect(VectorUnit.floatingPoint)
      dut.io.decoded.vector.readsVs2.expect(true.B)

      // vtex.sample v3, v1, v2 (unmasked custom-1 texture instruction)
      dut.io.instruction.poke("h062081ab".U)
      dut.io.decoded.legal.expect(true.B)
      dut.io.decoded.executionType.expect(ExecutionType.vector)
      dut.io.decoded.vector.unit.expect(VectorUnit.texture)
      dut.io.decoded.vector.readsVs1.expect(true.B)
      dut.io.decoded.vector.readsVs2.expect(true.B)
      dut.io.decoded.vector.writesVd.expect(true.B)
      dut.io.decoded.vector.vm.expect(true.B)

      // vquad.dfdx v3, v2 (custom-1, unary vs2 source)
      dut.io.instruction.poke("h322001ab".U)
      dut.io.decoded.legal.expect(true.B)
      dut.io.decoded.executionType.expect(ExecutionType.vector)
      dut.io.decoded.vector.unit.expect(VectorUnit.alu)
      dut.io.decoded.vector.readsVs1.expect(false.B)
      dut.io.decoded.vector.readsVs2.expect(true.B)
      dut.io.decoded.vector.writesVd.expect(true.B)

      // vquad.dfdy v3, v2
      dut.io.instruction.poke("h362001ab".U)
      dut.io.decoded.legal.expect(true.B)
      dut.io.decoded.vector.unit.expect(VectorUnit.alu)
      dut.io.decoded.vector.readsVs2.expect(true.B)
    }
  }

  it should "report reserved and unsupported encodings as illegal instructions" in {
    simulate(new FullInstructionDecoder) { dut =>
      // Reserved vsub.vi encoding.
      dut.io.instruction.poke("b000010_1_00001_00010_011_00011_1010111".U)
      dut.io.decoded.legal.expect(false.B)
      dut.io.decoded.illegalInstruction.expect(true.B)
      dut.io.decoded.executionType.expect(ExecutionType.illegal)

      // Reserved VFUNARY0 opcode (vs1 = 01000).
      dut.io.instruction.poke("h4a2411d7".U)
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

      dut.io.instruction.poke("h0000000b".U)
      dut.io.decoded.legal.expect(true.B)
      dut.io.decoded.executionType.expect(ExecutionType.system)
      dut.io.decoded.scalar.barrier.expect(true.B)

      dut.io.instruction.poke("he020a1af".U) // amomaxu.w x3, x2, (x1)
      dut.io.decoded.legal.expect(true.B)
      dut.io.decoded.executionType.expect(ExecutionType.memory)
      dut.io.decoded.scalar.atomic.expect(true.B)
      dut.io.decoded.scalar.atomicOp.expect(8.U)
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

  it should "decode the guest ellipse multiply and convert words" in {
    simulate(new FullInstructionDecoder) { dut =>
      def check(
          word: String,
          unit: VectorUnit.Type,
          vs1: Boolean,
          vs2: Boolean,
          float: Boolean = false
      ): Unit = {
        dut.io.instruction.poke(s"h$word".U)
        dut.io.decoded.legal.expect(true.B)
        dut.io.decoded.executionType.expect(ExecutionType.vector)
        dut.io.decoded.vector.valid.expect(true.B)
        dut.io.decoded.vector.unit.expect(unit)
        dut.io.decoded.vector.readsVs1.expect(vs1.B)
        dut.io.decoded.vector.readsVs2.expect(vs2.B)
        dut.io.decoded.vector.readsFloat.expect(float.B)
        dut.io.decoded.vector.writesVd.expect(true.B)
      }

      // vfadd.vv v6, v5, v1
      check("02509357", VectorUnit.floatingPoint, vs1 = true, vs2 = true)
      // vfmul.vv v2, v5, v5
      check("92529157", VectorUnit.floatingPoint, vs1 = true, vs2 = true)
      // vfcvt.x.f.v v5, v6
      check("4a6092d7", VectorUnit.floatingPoint, vs1 = false, vs2 = true)
      // vfcvt.f.x.v v5, v5
      check("4a5192d7", VectorUnit.floatingPoint, vs1 = false, vs2 = true)
      // vmv.v.v v7, v2 and vmv.v.v v8, v6
      check("5e0103d7", VectorUnit.alu, vs1 = true, vs2 = false)
      check("5e030457", VectorUnit.alu, vs1 = true, vs2 = false)
    }
  }
}
