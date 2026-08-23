package gpu.core.execute.fpu

import chisel3._

object Fp32Operation extends ChiselEnum {
  val fmadd, fnmsub, add, mul, div, sqrt, sgnj, minmax, compare, classify,
      fmvX, fmvFromX, fpToFp, fpToInt, intToFp, recip7, rsqrt7,
      packAB, packCD = Value
}

class Fp32Request(tagWidth: Int) extends Bundle {
  val operandA = UInt(32.W)
  val operandB = UInt(32.W)
  val operandC = UInt(32.W)
  val roundingMode = UInt(3.W)
  val operation = Fp32Operation()
  val operationModifier = Bool()
  val exactFunction = UInt(3.W)
  val tag = UInt(tagWidth.W)
}

class Fp32Response(tagWidth: Int) extends Bundle {
  val result = UInt(32.W)
  /** RISC-V fflags order: NV, DZ, OF, UF, NX. */
  val status = UInt(5.W)
  val tag = UInt(tagWidth.W)
}
