package opengpu.core.execute.integer

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.backend.issue.ScalarIssuedInstruction
import opengpu.core.frontend.decode.ExecutionType

private class MultiplyMetadata(config: GpuConfig) extends Bundle {
  val warpId = UInt(config.warpIdWidth.W)
  val pc = UInt(config.xLen.W)
  val activeMask = UInt(config.lanes.W)
  val rd = UInt(5.W)
  val writeRd = Bool()
  val highHalf = Bool()
}

private class BoothStageOne(config: GpuConfig) extends Bundle {
  val metadata = new MultiplyMetadata(config)
  val terms = Vec(12, UInt(68.W))
}

private class BoothStageTwo(config: GpuConfig) extends Bundle {
  val metadata = new MultiplyMetadata(config)
  val terms = Vec(4, UInt(68.W))
}

private class BoothStageThree(config: GpuConfig) extends Bundle {
  val metadata = new MultiplyMetadata(config)
  val terms = Vec(2, UInt(68.W))
}

private class BoothStageFour(config: GpuConfig) extends Bundle {
  val metadata = new MultiplyMetadata(config)
  // Carry-select partials of the 68-bit completion add (same cut as
  // VectorMultiplyAlu): assemble on the output stage so stageThree→CPA
  // cannot bind the integrated top.
  val lowSum = UInt(35.W)
  val highSum0 = UInt(35.W)
  val highSum1 = UInt(35.W)
}

/** Five-stage elastic radix-4 Booth RV32M multiplier.
  *
  * Seventeen Booth partial products are reduced by carry-save compressors
  * without propagating carry. The first boundary is deliberately close to
  * Booth generation, following the timing structure used by XiangShan's
  * multiplier: it prevents partial-product generation and a deep compressor
  * tree from occupying one cycle. The tree is split 17→12, 12→4, and 4→2;
  * stage four registers carry-select halves of the final add; the output
  * stage mux-assembles and selects the high/low half. Once full, the unit
  * accepts and completes one multiply per cycle.
  */
class MultiplyExecuteStage(config: GpuConfig = GpuConfig()) extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new ScalarIssuedInstruction(config)))
    val out = Decoupled(new IntegerExecutionResult(config))
  })

  private def csa(
    a: UInt,
    b: UInt,
    c: UInt
  ): (UInt, UInt) = {
    val sum = a ^ b ^ c
    val carry = ((a & b) | (a & c) | (b & c)) << 1
    (sum, carry(67, 0))
  }

  private def reduceOnce(terms: Seq[UInt]): Seq[UInt] = {
    val groups = terms.grouped(3).toSeq
    groups.flatMap {
      case Seq(a, b, c) =>
        val (sum, carry) = csa(a, b, c)
        Seq(sum, carry)
      case remainder => remainder
    }
  }

  private def reduceTo(terms: Seq[UInt], size: Int): Seq[UInt] = {
    var current = terms
    while (current.size > size) {
      current = reduceOnce(current)
    }
    require(current.size == size)
    current
  }

  private val stageOneValid = RegInit(false.B)
  private val stageOneBits = Reg(new BoothStageOne(config))
  private val stageTwoValid = RegInit(false.B)
  private val stageTwoBits = Reg(new BoothStageTwo(config))
  private val stageThreeValid = RegInit(false.B)
  private val stageThreeBits = Reg(new BoothStageThree(config))
  private val stageFourValid = RegInit(false.B)
  private val stageFourBits = Reg(new BoothStageFour(config))
  private val outputValid = RegInit(false.B)
  private val outputBits = Reg(new IntegerExecutionResult(config))

  private val outputReady = !outputValid || io.out.ready
  private val stageFourReady = !stageFourValid || outputReady
  private val stageThreeReady = !stageThreeValid || stageFourReady
  private val stageTwoReady = !stageTwoValid || stageThreeReady
  private val stageOneReady = !stageOneValid || stageTwoReady

  private val decoded = io.in.bits.decode.decoded
  private val funct3 = io.in.bits.decode.instruction(14, 12)
  private val supported =
    io.in.bits.decode.executionType === ExecutionType.integer &&
      decoded.multiply && !decoded.divide &&
      !io.in.bits.decode.illegalInstruction &&
      !io.in.bits.decode.instructionAccessFault &&
      funct3 <= 3.U

  private val lhsSigned = funct3 === 1.U || funct3 === 2.U
  private val rhsSigned = funct3 === 1.U
  private val lhs = Cat(
    lhsSigned && io.in.bits.rs1Data(31),
    io.in.bits.rs1Data
  )
  private val rhs = Cat(
    rhsSigned && io.in.bits.rs2Data(31),
    io.in.bits.rs2Data
  )
  private val lhsWide = Cat(Fill(35, lhs(32)), lhs).asSInt
  private val boothBits = Cat(Fill(2, rhs(32)), rhs, 0.U(1.W))

  private val partialProducts = (0 until 17).map { index =>
    val code = boothBits(2 * index + 2, 2 * index)
    val multiple = MuxLookup(code, 0.S(68.W))(Seq(
      "b001".U -> lhsWide,
      "b010".U -> lhsWide,
      "b011".U -> (lhsWide << 1).asSInt,
      "b100".U -> (-(lhsWide << 1)).asSInt,
      "b101".U -> (-lhsWide).asSInt,
      "b110".U -> (-lhsWide).asSInt
    ))
    (multiple.asUInt << (2 * index))(67, 0)
  }
  private val firstReduction = reduceTo(partialProducts, 12)
  private val secondReduction =
    reduceTo(stageOneBits.terms.toSeq, 4)
  private val thirdReduction =
    reduceTo(stageTwoBits.terms.toSeq, 2)
  private val productSplit = 34
  private val productLow =
    stageThreeBits.terms(0)(productSplit - 1, 0) +&
      stageThreeBits.terms(1)(productSplit - 1, 0)
  private val productHigh0 =
    stageThreeBits.terms(0)(67, productSplit) +&
      stageThreeBits.terms(1)(67, productSplit)
  private val productHigh1 = (productHigh0 +& 1.U)(34, 0)
  private val product = {
    val highSum = Mux(
      stageFourBits.lowSum(productSplit),
      stageFourBits.highSum1,
      stageFourBits.highSum0)
    Cat(highSum(67 - productSplit, 0), stageFourBits.lowSum(productSplit - 1, 0))
  }

  io.in.ready := stageOneReady && supported
  io.out.valid := outputValid
  io.out.bits := outputBits

  when(outputReady) {
    outputValid := stageFourValid
    when(stageFourValid) {
      outputBits.warpId := stageFourBits.metadata.warpId
      outputBits.pc := stageFourBits.metadata.pc
      outputBits.activeMask := stageFourBits.metadata.activeMask
      outputBits.rd := stageFourBits.metadata.rd
      outputBits.writeRd := stageFourBits.metadata.writeRd
      outputBits.data := Mux(
        stageFourBits.metadata.highHalf,
        product(63, 32),
        product(31, 0)
      )
    }
  }

  when(stageFourReady) {
    stageFourValid := stageThreeValid
    when(stageThreeValid) {
      stageFourBits.metadata := stageThreeBits.metadata
      stageFourBits.lowSum := productLow
      stageFourBits.highSum0 := productHigh0
      stageFourBits.highSum1 := productHigh1
    }
  }

  when(stageThreeReady) {
    stageThreeValid := stageTwoValid
    when(stageTwoValid) {
      stageThreeBits.metadata := stageTwoBits.metadata
      stageThreeBits.terms := VecInit(thirdReduction)
    }
  }

  when(stageTwoReady) {
    stageTwoValid := stageOneValid
    when(stageOneValid) {
      stageTwoBits.metadata := stageOneBits.metadata
      stageTwoBits.terms := VecInit(secondReduction)
    }
  }

  when(stageOneReady) {
    stageOneValid := io.in.valid && supported
    when(io.in.valid && supported) {
      stageOneBits.metadata.warpId := io.in.bits.decode.warpId
      stageOneBits.metadata.pc := io.in.bits.decode.pc
      stageOneBits.metadata.activeMask := io.in.bits.decode.activeMask
      stageOneBits.metadata.rd := decoded.rd
      stageOneBits.metadata.writeRd := decoded.writeRd
      stageOneBits.metadata.highHalf := funct3 =/= 0.U
      stageOneBits.terms := VecInit(firstReduction)
    }
  }

  when(io.in.valid) {
    assert(
      supported,
      "MultiplyExecuteStage received a non-multiply instruction"
    )
  }
}
