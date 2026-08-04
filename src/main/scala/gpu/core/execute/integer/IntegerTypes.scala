package gpu.core.execute.integer

import chisel3._
import chisel3.util._

/** Integer execution operation shared by decode and the lane ALUs. */
object AluOp {
  val width = 4
  val add   = 0.U(width.W)
  val sub   = 1.U(width.W)
  val sll   = 2.U(width.W)
  val slt   = 3.U(width.W)
  val sltu  = 4.U(width.W)
  val xor   = 5.U(width.W)
  val srl   = 6.U(width.W)
  val sra   = 7.U(width.W)
  val or    = 8.U(width.W)
  val and   = 9.U(width.W)
  val copyB = 10.U(width.W)
}

/** Parallel-prefix addition for the fixed RV32 datapath.
  *
  * Yosys maps a generic add to a linear carry chain in the ASAP7 flow. This
  * explicit prefix network computes all carries in log2(32) combine levels.
  * The historical name is retained because this helper is already shared by
  * integer execution, branch targets, and commit PC updates.
  */
object Rv32CarrySelect {
  def apply(lhs: UInt, rhs: UInt, subtract: Bool = false.B): UInt = {
    require(lhs.getWidth == 32 && rhs.getWidth == 32)
    val adjustedRhs = rhs ^ Fill(32, subtract)
    val bitPropagate = lhs ^ adjustedRhs
    var groupPropagate = bitPropagate
    var groupGenerate = lhs & adjustedRhs

    for (distance <- Seq(1, 2, 4, 8, 16)) {
      val previousPropagate = groupPropagate
      val previousGenerate = groupGenerate
      val shiftedPropagate =
        Cat(previousPropagate(31 - distance, 0), 0.U(distance.W))
      val shiftedGenerate =
        Cat(previousGenerate(31 - distance, 0), 0.U(distance.W))
      val combineMask = ((BigInt(1) << 32) - (BigInt(1) << distance)).U(32.W)

      groupGenerate =
        previousGenerate |
          (previousPropagate & shiftedGenerate & combineMask)
      groupPropagate =
        previousPropagate &
          (shiftedPropagate | ~combineMask)
    }

    val lowerCarry =
      groupGenerate(30, 0) |
        (groupPropagate(30, 0) & Fill(31, subtract))
    val carryInto = Cat(lowerCarry, subtract)
    bitPropagate ^ carryInto
  }
}
