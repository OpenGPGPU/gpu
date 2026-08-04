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

      // Compare is handled by a separate non-computational unit.
      dut.io.in.instruction.poke("b1010000_00010_00001_010_00100_1010011".U)
      dut.io.out.supported.expect(false.B)
    }
  }
}
