package opengpu.core.frontend.decode

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec

class ExtensionDecoderSpec extends AnyFlatSpec {
  behavior of "extension decoders"

  it should "classify FPU operations independently" in {
    simulate(new FpuDecoder) { dut =>
      dut.io.instruction.poke("b0000000_00000_00001_000_00010_1010011".U)
      dut.io.decoded.valid.expect(true.B)
      dut.io.decoded.unit.expect(FpuUnit.fast)
      dut.io.decoded.writesFp.expect(true.B)

      // D, Zfh, and their memory widths are deliberately absent.
      dut.io.instruction.poke("b0000001_00000_00001_000_00010_1010011".U) // fadd.d
      dut.io.decoded.recognized.expect(true.B)
      dut.io.decoded.valid.expect(false.B)
      dut.io.instruction.poke("b0000010_00000_00001_000_00010_1010011".U) // fadd.h
      dut.io.decoded.valid.expect(false.B)
      dut.io.instruction.poke("b0000000_00000_00001_011_00010_0000111".U) // fld
      dut.io.decoded.valid.expect(false.B)

      // DIV/SQRT are intentionally deferred to a separate shared unit.
      dut.io.instruction.poke("b0001100_00010_00001_000_00011_1010011".U) // fdiv.s
      dut.io.decoded.valid.expect(false.B)
      dut.io.instruction.poke("b0101100_00000_00001_000_00011_1010011".U) // fsqrt.s
      dut.io.decoded.valid.expect(false.B)

      dut.io.instruction.poke("h02008157".U)
      dut.io.decoded.valid.expect(false.B)

      // Rounding modes 101 and 110 are reserved in the scalar FP ISA.
      dut.io.instruction.poke("b0000000_00010_00001_101_00011_1010011".U)
      dut.io.decoded.recognized.expect(true.B)
      dut.io.decoded.valid.expect(false.B)

      // fsqrt requires rs2=0; the old funct5/format Cartesian table accepted it.
      dut.io.instruction.poke("b0101100_00001_00010_000_00011_1010011".U)
      dut.io.decoded.recognized.expect(true.B)
      dut.io.decoded.valid.expect(false.B)

      // fsgnj only defines funct3 000/001/010.
      dut.io.instruction.poke("b0010000_00010_00001_011_00011_1010011".U)
      dut.io.decoded.recognized.expect(true.B)
      dut.io.decoded.valid.expect(false.B)

      // OP-FP major opcode with an unsupported funct5/format combination.
      dut.io.instruction.poke("b0111111_00000_00001_000_00010_1010011".U)
      dut.io.decoded.recognized.expect(true.B)
      dut.io.decoded.valid.expect(false.B)
    }
  }

  it should "classify RVV operations independently" in {
    simulate(new VectorDecoder) { dut =>
      dut.io.instruction.poke("h02008157".U)
      dut.io.decoded.valid.expect(true.B)
      dut.io.decoded.unit.expect(VectorUnit.alu)

      dut.io.instruction.poke("h00008153".U)
      dut.io.decoded.valid.expect(false.B)

      // vmul.vv is implemented by the lane-parallel vector multiplier.
      dut.io.instruction.poke("b100101_1_00001_00010_010_00011_1010111".U)
      dut.io.decoded.valid.expect(true.B)
      dut.io.decoded.unit.expect(VectorUnit.multiply)

      // vrgather.vi is a cross-lane integer ALU operation.
      dut.io.instruction.poke("b001100_1_00010_00001_011_00011_1010111".U)
      dut.io.decoded.valid.expect(true.B)
      dut.io.decoded.unit.expect(VectorUnit.alu)

      // vslideup.vx and vslidedown.vi use scalar/immediate offsets.
      dut.io.instruction.poke("b001110_1_00001_00010_100_00011_1010111".U)
      dut.io.decoded.valid.expect(true.B)
      dut.io.decoded.unit.expect(VectorUnit.alu)
      dut.io.instruction.poke("b001111_1_00001_00010_011_00011_1010111".U)
      dut.io.decoded.valid.expect(true.B)
      dut.io.decoded.unit.expect(VectorUnit.alu)

      // There is no vector-vector encoding for the slide family.
      dut.io.instruction.poke("b001110_1_00001_00010_000_00011_1010111".U)
      dut.io.decoded.recognized.expect(true.B)
      dut.io.decoded.valid.expect(false.B)

      // vslideup cannot overlap its destination and vector source groups.
      dut.io.instruction.poke("b001110_1_00011_00010_100_00011_1010111".U)
      dut.io.decoded.recognized.expect(true.B)
      dut.io.decoded.valid.expect(false.B)

      // vredsum.vs is a vector reduction routed through the integer ALU.
      dut.io.instruction.poke("b000000_1_00001_00010_010_00011_1010111".U)
      dut.io.decoded.valid.expect(true.B)
      dut.io.decoded.unit.expect(VectorUnit.alu)

      // Reduction funct6 values above vredmax are currently unsupported.
      dut.io.instruction.poke("b001000_1_00001_00010_010_00011_1010111".U)
      dut.io.decoded.recognized.expect(true.B)
      dut.io.decoded.valid.expect(false.B)

      // vsmul is enabled once vxrm is supplied by vector configuration state.
      dut.io.instruction.poke("b100111_1_00001_00010_000_00011_1010111".U)
      dut.io.decoded.recognized.expect(true.B)
      dut.io.decoded.valid.expect(true.B)
      dut.io.decoded.unit.expect(VectorUnit.multiply)

      // vsub.vi is not an RVV instruction. The old funct6 x funct3
      // Cartesian table incorrectly accepted this reserved combination.
      dut.io.instruction.poke("b000010_1_00001_00010_011_00011_1010111".U)
      dut.io.decoded.recognized.expect(true.B)
      dut.io.decoded.valid.expect(false.B)

      // Scalar-stride loads route to the vector LSU.
      dut.io.instruction.poke("b0000_0_10_1_00001_00010_110_00011_0000111".U)
      dut.io.decoded.valid.expect(true.B)
      dut.io.decoded.unit.expect(VectorUnit.loadStore)
      dut.io.decoded.mop.expect("b10".U)

      // Ordered and unordered 32-bit indexed loads consume vs2 offsets.
      dut.io.instruction.poke("b0000_0_01_1_00100_00010_110_00011_0000111".U)
      dut.io.decoded.valid.expect(true.B)
      dut.io.decoded.unit.expect(VectorUnit.loadStore)
      dut.io.decoded.mop.expect("b01".U)
      dut.io.decoded.readsVs2.expect(true.B)

      dut.io.instruction.poke("b0000_0_11_1_00100_00010_110_00011_0100111".U)
      dut.io.decoded.valid.expect(true.B)
      dut.io.decoded.mop.expect("b11".U)
      dut.io.decoded.readsVs2.expect(true.B)

      // The fixed SEW=32 profile does not yet implement narrower indices.
      dut.io.instruction.poke("b0000_0_01_1_00100_00010_101_00011_0000111".U)
      dut.io.decoded.recognized.expect(true.B)
      dut.io.decoded.valid.expect(false.B)

      // Fixed-profile integer widening consumes the low 16 bits of vs2.
      dut.io.instruction.poke("b0100101_00100_00111_010_00011_1010111".U)
      dut.io.decoded.valid.expect(true.B)
      dut.io.decoded.unit.expect(VectorUnit.alu)
      dut.io.decoded.readsVs2.expect(true.B)
      dut.io.instruction.poke("b0100101_00100_00110_010_00011_1010111".U)
      dut.io.decoded.valid.expect(true.B)
      dut.io.decoded.unit.expect(VectorUnit.alu)

      // Masked unit-stride word loads are implemented by the vector LSU.
      dut.io.instruction.poke("b0000_0_00_0_00000_00010_110_00011_0000111".U)
      dut.io.decoded.valid.expect(true.B)
      dut.io.decoded.vm.expect(false.B)

      // OP-V major opcode with a reserved/unsupported funct6.
      dut.io.instruction.poke("b111111_1_00000_00000_000_00000_1010111".U)
      dut.io.decoded.recognized.expect(true.B)
      dut.io.decoded.valid.expect(false.B)
    }
  }
}
