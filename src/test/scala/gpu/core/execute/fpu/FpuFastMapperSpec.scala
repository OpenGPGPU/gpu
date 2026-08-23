package gpu.core.execute.fpu

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import gpu.config.GpuConfig
import org.scalatest.flatspec.AnyFlatSpec

class FpuFastMapperSpec extends AnyFlatSpec {
  behavior of "FpuFastMapper"

  it should "map RISC-V fast FP operations to fpnew_fma conventions" in {
    simulate(new FpuFastMapper(GpuConfig(warps = 4))) { dut =>
      dut.io.in.poke(0.U.asTypeOf(dut.io.in))
      dut.io.rs1Data.poke("h3f800000".U)
      dut.io.rs2Data.poke("h40000000".U)
      dut.io.rs3Data.poke("h3f000000".U)
      dut.io.scalarRs1Data.poke("h12345678".U)
      for (warp <- 0 until 4) {
        dut.io.frm(warp).poke(0.U)
      }
      dut.io.in.warpId.poke(2.U)
      dut.io.in.decoded.rm.poke(3.U)

      // fsub.s f4, f1, f2 maps to ADD with an inverted addend.
      dut.io.in.instruction.poke("b0000100_00010_00001_000_00100_1010011".U)
      dut.io.out.supported.expect(true.B)
      dut.io.out.request.operation.expect(Fp32Operation.add)
      dut.io.out.request.operationModifier.expect(true.B)
      dut.io.out.request.operandB.expect("h3f800000".U)
      dut.io.out.request.operandC.expect("h40000000".U)
      dut.io.out.request.roundingMode.expect(3.U)

      // fnmadd.s selects FNMSUB plus addend inversion.
      dut.io.in.instruction.poke("b0000000_00011_00010_000_00100_1001111".U)
      dut.io.out.request.operation.expect(Fp32Operation.fnmsub)
      dut.io.out.request.operationModifier.expect(true.B)
      dut.io.out.request.operandA.expect("h3f800000".U)
      dut.io.out.request.operandB.expect("h40000000".U)
      dut.io.out.request.operandC.expect("h3f000000".U)

      // Compare now maps to the exact unit.
      dut.io.in.instruction.poke("b1010000_00010_00001_010_00100_1010011".U)
      dut.io.out.supported.expect(true.B)
      dut.io.out.request.operation.expect(Fp32Operation.compare)
      dut.io.out.request.exactFunction.expect(2.U)

      // fclass.s f4, f1 maps to the exact classification unit.
      dut.io.in.instruction.poke(
        "b1110000_00000_00001_001_00100_1010011".U)
      dut.io.out.supported.expect(true.B)
      dut.io.out.request.operation.expect(Fp32Operation.classify)

      // fmv.x.w f4, f1 maps to the raw bit move.
      dut.io.in.instruction.poke(
        "b1110000_00000_00001_000_00100_1010011".U)
      dut.io.out.supported.expect(true.B)
      dut.io.out.request.operation.expect(Fp32Operation.fmvX)

      // fmv.w.x f4, x1 maps to a raw scalar-to-FP bit move.
      dut.io.in.instruction.poke(
        "b1111000_00000_00001_000_00100_1010011".U)
      dut.io.out.supported.expect(true.B)
      dut.io.out.request.operation.expect(Fp32Operation.fmvFromX)
      dut.io.out.request.operandA.expect("h12345678".U)

      // fcvt.w.s x5, f1 maps to signed FP-to-int.
      dut.io.in.instruction.poke(
        "b1100000_00000_00001_000_00101_1010011".U)
      dut.io.out.supported.expect(true.B)
      dut.io.out.request.operation.expect(Fp32Operation.fpToInt)
      dut.io.out.request.exactFunction.expect(0.U)

      // fcvt.s.w f4, x1 maps to signed int-to-FP.
      dut.io.in.instruction.poke(
        "b1101000_00000_00001_000_00100_1010011".U)
      dut.io.out.supported.expect(true.B)
      dut.io.out.request.operation.expect(Fp32Operation.intToFp)
      dut.io.out.request.exactFunction.expect(0.U)
      dut.io.out.request.operandA.expect("h12345678".U)

      // fsgnj.s f4, f1, f2 maps to sign injection with funct3=0.
      dut.io.in.instruction.poke(
        "b0010000_00010_00001_000_00100_1010011".U)
      dut.io.out.supported.expect(true.B)
      dut.io.out.request.operation.expect(Fp32Operation.sgnj)
      dut.io.out.request.exactFunction.expect(0.U)
      dut.io.out.request.operandA.expect("h3f800000".U)
      dut.io.out.request.operandB.expect("h40000000".U)

      // fmin.s f4, f1, f2 maps to the exact min/max unit.
      dut.io.in.instruction.poke(
        "b0010100_00010_00001_000_00100_1010011".U)
      dut.io.out.supported.expect(true.B)
      dut.io.out.request.operation.expect(Fp32Operation.minmax)
      dut.io.out.request.exactFunction.expect(0.U)
    }
  }

  it should "resolve dynamic rounding from the per-warp frm" in {
    simulate(new FpuFastMapper(GpuConfig(warps = 4))) { dut =>
      dut.io.in.poke(0.U.asTypeOf(dut.io.in))
      dut.io.rs1Data.poke("h3f800000".U)
      dut.io.rs2Data.poke(0.U)
      dut.io.rs3Data.poke(0.U)
      dut.io.scalarRs1Data.poke(0.U)
      for (warp <- 0 until 4) {
        dut.io.frm(warp).poke(0.U)
      }
      dut.io.frm(2).poke("b011".U) // RUP
      dut.io.in.warpId.poke(2.U)
      dut.io.in.decoded.rm.poke("b111".U)
      dut.io.in.instruction.poke("b0000000_00000_00001_000_00100_1010011".U)
      dut.io.out.request.roundingMode.expect("b011".U)

      dut.io.in.warpId.poke(3.U)
      dut.io.in.decoded.rm.poke("b000".U)
      dut.io.out.request.roundingMode.expect("b000".U)
    }
  }
}
