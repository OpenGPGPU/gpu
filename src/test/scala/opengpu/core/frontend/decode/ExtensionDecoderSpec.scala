package opengpu.core.frontend.decode

import chisel3._
import opengpu.testutil.GpuSim._
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

      dut.io.instruction.poke("b0001100_00010_00001_000_00011_1010011".U) // fdiv.s
      dut.io.decoded.valid.expect(true.B)
      dut.io.decoded.unit.expect(FpuUnit.divide)
      dut.io.decoded.readsRs1.expect(true.B)
      dut.io.decoded.readsRs2.expect(true.B)
      dut.io.decoded.writesFp.expect(true.B)
      dut.io.decoded.setsFlags.expect(true.B)
      dut.io.instruction.poke("b0101100_00000_00001_000_00011_1010011".U) // fsqrt.s
      dut.io.decoded.valid.expect(true.B)
      dut.io.decoded.unit.expect(FpuUnit.squareRoot)
      dut.io.decoded.readsRs2.expect(false.B)
      dut.io.decoded.writesFp.expect(true.B)

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

      // vslideup.vv and vslidedown.vv read a vector of per-element offsets.
      dut.io.instruction.poke("b001110_1_00001_00010_000_00011_1010111".U)
      dut.io.decoded.recognized.expect(true.B)
      dut.io.decoded.valid.expect(true.B)
      dut.io.decoded.unit.expect(VectorUnit.alu)
      dut.io.decoded.readsVs1.expect(true.B)
      dut.io.decoded.readsScalar.expect(false.B)
      dut.io.decoded.readsVs2.expect(true.B)
      dut.io.decoded.writesVd.expect(true.B)
      dut.io.instruction.poke("b001111_1_00001_00010_000_00011_1010111".U)
      dut.io.decoded.recognized.expect(true.B)
      dut.io.decoded.valid.expect(true.B)
      dut.io.decoded.readsVs1.expect(true.B)
      // The masked forms are equally legal.
      dut.io.instruction.poke("b001110_0_00001_00010_000_00011_1010111".U)
      dut.io.decoded.valid.expect(true.B)
      dut.io.decoded.vm.expect(false.B)

      // The vslideup overlap rule covers the vector-vector form too.
      dut.io.instruction.poke("b001110_1_00001_00010_000_00001_1010111".U)
      dut.io.decoded.recognized.expect(true.B)
      dut.io.decoded.valid.expect(false.B)

      // vslide1up.vx / vslide1down.vx insert an integer and shift by one.
      dut.io.instruction.poke("h3a2561d7".U) // vslide1up.vx v3, v2, x10
      dut.io.decoded.valid.expect(true.B)
      dut.io.decoded.unit.expect(VectorUnit.alu)
      dut.io.decoded.readsScalar.expect(true.B)
      dut.io.decoded.readsVs2.expect(true.B)
      dut.io.decoded.writesVd.expect(true.B)
      dut.io.instruction.poke("h3e256257".U) // vslide1down.vx v4, v2, x10
      dut.io.decoded.valid.expect(true.B)
      dut.io.decoded.unit.expect(VectorUnit.alu)
      // vslide1up keeps the destination/source overlap rule. vslide1down does not.
      dut.io.instruction.poke("b001110_1_00011_00010_110_00011_1010111".U)
      dut.io.decoded.recognized.expect(true.B)
      dut.io.decoded.valid.expect(false.B)
      dut.io.instruction.poke("b001111_1_00011_00010_110_00011_1010111".U)
      dut.io.decoded.valid.expect(true.B)

      // vslideup cannot overlap its destination and vector source groups.
      dut.io.instruction.poke("b001110_1_00011_00010_100_00011_1010111".U)
      dut.io.decoded.recognized.expect(true.B)
      dut.io.decoded.valid.expect(false.B)

      // vid.v is funct6 010100 in OPMVV with vs2 fixed to v0 and vs1 holding
      // the EEW/EMUL selector 10001 rather than a register number.
      def elementIndex(selector: Int, masked: Boolean, vs2: Int): BigInt =
        (BigInt(0x14) << 26) | (BigInt(if (masked) 0 else 1) << 25) |
          (BigInt(vs2) << 20) | (BigInt(selector) << 15) |
          (BigInt(2) << 12) | (BigInt(4) << 7) | 0x57

      dut.io.instruction.poke("h5208a257".U) // vid.v v4, from the assembler
      dut.io.instruction.poke(elementIndex(17, masked = false, vs2 = 0).U)
      dut.io.decoded.recognized.expect(true.B)
      dut.io.decoded.valid.expect(true.B)
      dut.io.decoded.unit.expect(VectorUnit.alu)
      dut.io.decoded.readsVs1.expect(false.B)
      dut.io.decoded.readsVs2.expect(false.B)
      dut.io.decoded.writesVd.expect(true.B)
      dut.io.instruction.poke(elementIndex(17, masked = true, vs2 = 0).U)
      dut.io.decoded.valid.expect(true.B)
      dut.io.decoded.vm.expect(false.B)
      dut.io.instruction.poke(elementIndex(17, masked = false, vs2 = 1).U)
      dut.io.decoded.valid.expect(false.B)

      // viota.m is the same funct6 with vs1 = 10000, and unlike vid.v its
      // vs2 names the mask register it accumulates.
      dut.io.instruction.poke("h522822d7".U) // viota.m v5, v2
      dut.io.decoded.recognized.expect(true.B)
      dut.io.decoded.valid.expect(true.B)
      dut.io.decoded.unit.expect(VectorUnit.alu)
      dut.io.decoded.readsVs1.expect(false.B)
      dut.io.decoded.readsVs2.expect(true.B)
      dut.io.decoded.writesVd.expect(true.B)
      dut.io.instruction.poke(elementIndex(16, masked = true, vs2 = 2).U)
      dut.io.decoded.valid.expect(true.B)
      dut.io.decoded.vm.expect(false.B)
      dut.io.instruction.poke(elementIndex(1, masked = false, vs2 = 2).U)
      dut.io.decoded.valid.expect(false.B) // vmsbf.m is not implemented

      // vcompress.vm is funct6 010111 in OPMVV. The masked form of the same
      // encoding is reserved, and the destination must be disjoint from both
      // sources.
      dut.io.instruction.poke("h5e2021d7".U) // vcompress.vm v3, v2, v0
      dut.io.decoded.recognized.expect(true.B)
      dut.io.decoded.valid.expect(true.B)
      dut.io.decoded.unit.expect(VectorUnit.alu)
      dut.io.decoded.readsVs1.expect(true.B)
      dut.io.decoded.readsVs2.expect(true.B)
      dut.io.decoded.writesVd.expect(true.B)
      dut.io.instruction.poke("h5c2021d7".U) // masked: reserved
      dut.io.decoded.valid.expect(false.B)
      dut.io.instruction.poke(
        (BigInt("5e2021d7", 16) & ~(BigInt(31) << 7) | (BigInt(2) << 7)).U)
      dut.io.decoded.valid.expect(false.B) // vd == vs2
      dut.io.instruction.poke(
        (BigInt("5e2021d7", 16) & ~(BigInt(31) << 7)).U)
      dut.io.decoded.valid.expect(false.B) // vd == mask

      // vredsum.vs is a vector reduction routed through the integer ALU.
      dut.io.instruction.poke("b000000_1_00001_00010_010_00011_1010111".U)
      dut.io.decoded.valid.expect(true.B)
      dut.io.decoded.unit.expect(VectorUnit.alu)

      // Single-width multiply-accumulate. Both operand forms are admitted and
      // both keep vs1/vs2 as vector sources; the destination is an operand too,
      // which the unit reads from the old vd.
      for (funct6 <- Seq(0x29, 0x2b, 0x2d, 0x2f); form <- Seq(2, 6);
           vm <- Seq(0, 1)) {
        dut.io.instruction.poke(
          ((BigInt(funct6) << 26) | (BigInt(vm) << 25) | (BigInt(2) << 20) |
            (BigInt(3) << 15) | (BigInt(form) << 12) | (BigInt(7) << 7) |
            0x57).U)
        dut.io.decoded.recognized.expect(true.B)
        dut.io.decoded.valid.expect(true.B)
        dut.io.decoded.unit.expect(VectorUnit.multiply)
        dut.io.decoded.readsVs2.expect(true.B)
        dut.io.decoded.writesVd.expect(true.B)
        dut.io.decoded.vm.expect((vm == 1).B)
        dut.io.decoded.readsVs1.expect((form == 2).B)
        dut.io.decoded.readsScalar.expect((form == 6).B)
        // The operand form is what keeps these apart from the immediates that
        // share the funct6: 0x29/0x2b are vsra.vi/vssra.vi and 0x2d/0x2f are
        // the narrowing clip immediates, all routed to the integer ALU.
        dut.io.instruction.poke(
          ((BigInt(funct6) << 26) | (BigInt(vm) << 25) | (BigInt(2) << 20) |
            (BigInt(3) << 15) | (BigInt(3) << 12) | (BigInt(7) << 7) |
            0x57).U)
        dut.io.decoded.recognized.expect(true.B)
        dut.io.decoded.valid.expect(true.B)
        dut.io.decoded.unit.expect(VectorUnit.alu)
      }
      // vmadd.vv at funct6 0x29 keeps the plain multiply funct6 range clear:
      // 0x28 is reserved, so a shift-by-funct6 lookup cannot reach it.
      dut.io.instruction.poke(
        ((BigInt(0x28) << 26) | (BigInt(1) << 25) | (BigInt(2) << 20) |
          (BigInt(3) << 15) | (BigInt(2) << 12) | (BigInt(7) << 7) |
          0x57).U)
      dut.io.decoded.recognized.expect(true.B)
      dut.io.decoded.valid.expect(false.B)

      // vfredusum.vs and vfredosum.vs are the OPFRED sums. vs1 is the seed
      // register, not a selector, and only OPFVV admits the funct6.
      for (funct6 <- Seq(0x01, 0x03); vm <- Seq(0, 1)) {
        dut.io.instruction.poke(
          ((BigInt(funct6) << 26) | (BigInt(vm) << 25) | (BigInt(2) << 20) |
            (BigInt(4) << 15) | (BigInt(1) << 12) | (BigInt(3) << 7) |
            0x57).U)
        dut.io.decoded.recognized.expect(true.B)
        dut.io.decoded.valid.expect(true.B)
        dut.io.decoded.unit.expect(VectorUnit.floatingPoint)
        dut.io.decoded.readsVs1.expect(true.B)
        dut.io.decoded.readsVs2.expect(true.B)
        dut.io.decoded.writesVd.expect(true.B)
        dut.io.decoded.vm.expect((vm == 1).B)
        // funct6 000001 in OPMVV is the integer vredand and 000011 in OPIVX is
        // the integer vrsub.vx, so the operand form is what keeps the
        // reductions apart. OPMVX has no row for either funct6.
        dut.io.instruction.poke(
          ((BigInt(funct6) << 26) | (BigInt(vm) << 25) | (BigInt(2) << 20) |
            (BigInt(4) << 15) | (BigInt(6) << 12) | (BigInt(3) << 7) |
            0x57).U)
        dut.io.decoded.valid.expect(false.B)
      }
      // vfredmin.vs is funct6 001010, which this core spends on vfsgnjx.vv.
      dut.io.instruction.poke("h162213d7".U) // vfredmin.vs v7, v2, v4
      dut.io.decoded.recognized.expect(true.B)
      dut.io.decoded.valid.expect(false.B)

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
      dut.io.instruction.poke("b0100101_00100_00101_010_00011_1010111".U)
      dut.io.decoded.valid.expect(true.B)
      dut.io.instruction.poke("b0100101_00100_00010_010_00011_1010111".U)
      dut.io.decoded.valid.expect(true.B)

      // Both mask forms of every extension scale have unary source metadata.
      for (selector <- 2 to 7; vm <- 0 to 1) {
        val instruction = (BigInt(0x12) << 26) | (BigInt(vm) << 25) |
          (BigInt(4) << 20) | (BigInt(selector) << 15) |
          (BigInt(2) << 12) | (BigInt(3) << 7) | 0x57
        dut.io.instruction.poke(instruction.U)
        dut.io.decoded.valid.expect(true.B)
        dut.io.decoded.vm.expect((vm == 1).B)
        dut.io.decoded.unit.expect(VectorUnit.alu)
        dut.io.decoded.readsVs1.expect(false.B)
        dut.io.decoded.readsVs2.expect(true.B)
        dut.io.decoded.writesVd.expect(true.B)
        // A fractional-width source cannot overlap the destination.
        dut.io.instruction.poke(((instruction & ~(BigInt(31) << 7)) |
          (BigInt(4) << 7)).U)
        dut.io.decoded.valid.expect(false.B)
        dut.io.instruction.poke((instruction & ~(BigInt(31) << 7)).U)
        dut.io.decoded.valid.expect((vm == 1).B)
      }
      for (selector <- Seq(0, 1, 8, 31)) {
        val instruction = (BigInt(0x12) << 26) | (BigInt(4) << 20) |
          (BigInt(selector) << 15) | (BigInt(2) << 12) |
          (BigInt(3) << 7) | 0x57
        dut.io.instruction.poke(instruction.U)
        dut.io.decoded.valid.expect(false.B)
      }

      // The rtz integer-to-FP conversions are vfrm selectors 4 and 5 in OPFVV,
      // masked or not. vs1 is the conversion, not a VGPR.
      for (selector <- Seq(4, 5); vm <- Seq(0, 1)) {
        val instruction = (BigInt(0x12) << 26) | (BigInt(vm) << 25) |
          (BigInt(4) << 20) | (BigInt(selector) << 15) |
          (BigInt(1) << 12) | (BigInt(3) << 7) | 0x57
        dut.io.instruction.poke(instruction.U)
        dut.io.decoded.valid.expect(true.B)
        dut.io.decoded.unit.expect(VectorUnit.floatingPoint)
        dut.io.decoded.readsVs1.expect(false.B)
        dut.io.decoded.readsVs2.expect(true.B)
        dut.io.decoded.writesVd.expect(true.B)
      }
      // Selectors 8 and 9 are the unimplemented float-to-float conversions.
      for (selector <- Seq(8, 9, 10, 17)) {
        val instruction = (BigInt(0x12) << 26) | (BigInt(4) << 20) |
          (BigInt(selector) << 15) | (BigInt(1) << 12) |
          (BigInt(3) << 7) | 0x57
        dut.io.instruction.poke(instruction.U)
        dut.io.decoded.recognized.expect(true.B)
        dut.io.decoded.valid.expect(false.B)
      }

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

  it should "decode single-width scaling shifts without source-pair restrictions" in {
    simulate(new VectorDecoder) { dut =>
      for (funct6 <- Seq(0x2a, 0x2b); form <- Seq(0, 3, 4);
           vm <- Seq(0, 1); vd <- Seq(3, 31)) {
        // Odd v31 is legal, and vd may overlap either input register.
        val instruction = (BigInt(funct6) << 26) | (BigInt(vm) << 25) |
          (BigInt(31) << 20) | (BigInt(3) << 15) |
          (BigInt(form) << 12) | (BigInt(vd) << 7) | 0x57
        dut.io.instruction.poke(instruction.U)
        dut.io.decoded.recognized.expect(true.B)
        dut.io.decoded.valid.expect(true.B)
        dut.io.decoded.unit.expect(VectorUnit.alu)
        dut.io.decoded.readsVs2.expect(true.B)
        dut.io.decoded.readsVs2Pair.expect(false.B)
        dut.io.decoded.readsVs1.expect((form == 0).B)
        dut.io.decoded.readsScalar.expect((form == 4).B)
        dut.io.decoded.vm.expect((vm == 1).B)
        dut.io.decoded.writesVd.expect(true.B)
      }
    }
  }

  it should "decode narrowing shifts and clips in wv, wx, and wi forms" in {
    simulate(new VectorDecoder) { dut =>
      def narrowing(vd: Int, vs2: Int, operand: Int, form: Int,
                    funct6: Int): BigInt =
        (BigInt(funct6) << 26) | (BigInt(1) << 25) |
          (BigInt(vs2) << 20) | (BigInt(operand) << 15) |
          (BigInt(form) << 12) | (BigInt(vd) << 7) | 0x57

      for (funct6 <- Seq(0x2c, 0x2d, 0x2e, 0x2f); form <- Seq(0, 4, 3)) {
        dut.io.instruction.poke(narrowing(6, 4, 1, form, funct6).U)
        dut.io.decoded.recognized.expect(true.B)
        dut.io.decoded.valid.expect(true.B)
        dut.io.decoded.unit.expect(VectorUnit.alu)
        dut.io.decoded.readsVs2.expect(true.B)
        dut.io.decoded.readsVs2Pair.expect(true.B)
        dut.io.decoded.readsVs1.expect((form == 0).B)
        dut.io.decoded.readsScalar.expect((form == 4).B)
        dut.io.decoded.writesVd.expect(true.B)

        dut.io.instruction.poke(
          (narrowing(6, 4, 1, form, funct6) & ~(BigInt(1) << 25)).U)
        dut.io.decoded.valid.expect(true.B)
        dut.io.decoded.vm.expect(false.B)
        dut.io.decoded.readsVs2Pair.expect(true.B)
        dut.io.instruction.poke(narrowing(6, 30, 1, form, funct6).U)
        dut.io.decoded.valid.expect(true.B)
        dut.io.instruction.poke(narrowing(6, 31, 1, form, funct6).U)
        dut.io.decoded.valid.expect(false.B)

        // vs2 must be even: the 64-bit source spans the vs2/vs2+1 pair.
        dut.io.instruction.poke(narrowing(6, 3, 1, form, funct6).U)
        dut.io.decoded.recognized.expect(true.B)
        dut.io.decoded.valid.expect(false.B)

        // vd must not overlap either half of the source pair.
        dut.io.instruction.poke(narrowing(4, 4, 1, form, funct6).U)
        dut.io.decoded.valid.expect(false.B)
        dut.io.instruction.poke(narrowing(5, 4, 1, form, funct6).U)
        dut.io.decoded.valid.expect(false.B)
        dut.io.instruction.poke(narrowing(6, 4, 1, form, funct6).U)
        dut.io.decoded.valid.expect(true.B)

        // A masked narrowing shift cannot target v0.
        dut.io.instruction.poke(
          (narrowing(0, 4, 1, form, funct6) & ~(BigInt(1) << 25)).U
        )
        dut.io.decoded.recognized.expect(true.B)
        dut.io.decoded.valid.expect(false.B)
        // An unmasked write to v0 with a legal pair stays valid.
        dut.io.instruction.poke(narrowing(0, 4, 1, form, funct6).U)
        dut.io.decoded.valid.expect(true.B)
      }

      // Other instructions keep the pair read flag clear.
      dut.io.instruction.poke(narrowing(6, 4, 1, 4, 0x28).U)
      dut.io.decoded.valid.expect(true.B)
      dut.io.decoded.readsVs2Pair.expect(false.B)
    }
  }
}
