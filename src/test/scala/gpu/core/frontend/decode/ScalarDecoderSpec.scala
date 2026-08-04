package gpu.core.frontend.decode

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import gpu.core.execute.integer.AluOp
import org.scalatest.flatspec.AnyFlatSpec

class DecoderSpec extends AnyFlatSpec {
  behavior of "RiscVDecoder"

  it should "decode RV32I register and immediate ALU instructions" in {
    simulate(new RiscVDecoder) { dut =>
      // add x3, x2, x1
      dut.io.instruction.poke("b0000000_00001_00010_000_00011_0110011".U)
      dut.io.decoded.legal.expect(true.B)
      dut.io.decoded.rs1.expect(2.U)
      dut.io.decoded.rs2.expect(1.U)
      dut.io.decoded.rd.expect(3.U)
      dut.io.decoded.aluOp.expect(AluOp.add)
      dut.io.decoded.writeRd.expect(true.B)

      // addi x5, x4, -16
      dut.io.instruction.poke("hff020293".U)
      dut.io.decoded.legal.expect(true.B)
      dut.io.decoded.rs1.expect(4.U)
      dut.io.decoded.rd.expect(5.U)
      dut.io.decoded.immediate.expect("hfffffff0".U)
      dut.io.decoded.useImmediate.expect(true.B)
    }
  }

  it should "decode control flow and RV32M while rejecting unsupported encodings" in {
    simulate(new RiscVDecoder) { dut =>
      // beq x1, x2, +8
      dut.io.instruction.poke("h00208463".U)
      dut.io.decoded.legal.expect(true.B)
      dut.io.decoded.branchOp.expect(BranchOp.eq)
      dut.io.decoded.immediate.expect(8.U)

      // RV32M mul is routed to the multiplier issue path.
      dut.io.instruction.poke("h022081b3".U)
      dut.io.decoded.legal.expect(true.B)
      dut.io.decoded.multiply.expect(true.B)
      dut.io.decoded.divide.expect(false.B)

      // A reserved OP funct7 remains illegal.
      dut.io.instruction.poke("b0000010_00001_00010_000_00011_0110011".U)
      dut.io.decoded.legal.expect(false.B)
    }
  }

  it should "decode CSR, system, and GPU warp-control instructions" in {
    simulate(new RiscVDecoder) { dut =>
      // csrrwi x1, mstatus, 3
      dut.io.instruction.poke("b001100000000_00011_101_00001_1110011".U)
      dut.io.decoded.legal.expect(true.B)
      dut.io.decoded.csr.expect(true.B)
      dut.io.decoded.system.expect(true.B)
      dut.io.decoded.immediate.expect(3.U)

      dut.io.instruction.poke("h00100073".U) // ebreak
      dut.io.decoded.legal.expect(true.B)
      dut.io.decoded.system.expect(true.B)

      dut.io.instruction.poke("h30500073".U) // opengpu cease
      dut.io.decoded.legal.expect(true.B)
      dut.io.decoded.cease.expect(true.B)

      // SYSTEM/funct3=000 is not generally legal: only explicit rows match.
      dut.io.instruction.poke("h00200073".U)
      dut.io.decoded.legal.expect(false.B)
    }
  }
}
