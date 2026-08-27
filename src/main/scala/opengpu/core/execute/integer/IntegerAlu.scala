package opengpu.core.execute.integer

import chisel3._
import chisel3.util._

class IntegerAlu(width: Int = 32) extends Module {
  val io = IO(new Bundle {
    val lhs = Input(UInt(width.W))
    val rhs = Input(UInt(width.W))
    val operation = Input(UInt(AluOp.width.W))
    val result = Output(UInt(width.W))
  })

  val shiftAmount = io.rhs(log2Ceil(width) - 1, 0)
  io.result := MuxLookup(io.operation, 0.U)(Seq(
    AluOp.add   -> Rv32CarrySelect(io.lhs, io.rhs),
    AluOp.sub   -> Rv32CarrySelect(io.lhs, io.rhs, true.B),
    AluOp.sll   -> (io.lhs << shiftAmount),
    AluOp.slt   -> (io.lhs.asSInt < io.rhs.asSInt),
    AluOp.sltu  -> (io.lhs < io.rhs),
    AluOp.xor   -> (io.lhs ^ io.rhs),
    AluOp.srl   -> (io.lhs >> shiftAmount),
    AluOp.sra   -> (io.lhs.asSInt >> shiftAmount).asUInt,
    AluOp.or    -> (io.lhs | io.rhs),
    AluOp.and   -> (io.lhs & io.rhs),
    AluOp.copyB -> io.rhs
  ))
}
