package opengpu.core.vector

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig

private class VectorMultiplyMetadata(config: GpuConfig) extends Bundle {
  val warpId = UInt(config.warpIdWidth.W)
  val pc = UInt(config.xLen.W)
  val warpActiveMask = UInt(config.lanes.W)
  val vd = UInt(5.W)
  val oldVd = Vec(config.lanes, UInt(config.xLen.W))
  val enabled = UInt(config.lanes.W)
  val highHalf = Bool()
  val saturating = Bool()
  val vxrm = UInt(2.W)
}

private class VectorBoothStageOne(config: GpuConfig) extends Bundle {
  val metadata = new VectorMultiplyMetadata(config)
  val terms = Vec(config.lanes, Vec(12, UInt(68.W)))
}

private class VectorBoothEncoded(config: GpuConfig) extends Bundle {
  val metadata = new VectorMultiplyMetadata(config)
  val terms = Vec(config.lanes, Vec(17, UInt(68.W)))
}

private class VectorBoothStageTwo(config: GpuConfig) extends Bundle {
  val metadata = new VectorMultiplyMetadata(config)
  val terms = Vec(config.lanes, Vec(4, UInt(68.W)))
}

private class VectorBoothStageThree(config: GpuConfig) extends Bundle {
  val metadata = new VectorMultiplyMetadata(config)
  val terms = Vec(config.lanes, Vec(2, UInt(68.W)))
}

private class VectorProductStage(config: GpuConfig) extends Bundle {
  val metadata = new VectorMultiplyMetadata(config)
  val products = Vec(config.lanes, UInt(68.W))
}

private class VectorRoundingStage(config: GpuConfig) extends Bundle {
  val metadata = new VectorMultiplyMetadata(config)
  val multiplyResult = Vec(config.lanes, UInt(config.xLen.W))
  val fractionalBase = Vec(config.lanes, UInt(config.xLen.W))
  val roundIncrement = UInt(config.lanes.W)
  val saturation = UInt(config.lanes.W)
}

/** Lane-parallel RVV integer multiplier for the fixed SEW=32 profile.
  *
  * Implements vmul, vmulh, vmulhu, and vmulhsu in vv and vx forms. Each lane
  * contains a four-stage elastic radix-4 Booth datapath. Once full, the unit
  * accepts one complete vector operation per cycle and preserves inactive
  * destination elements.
  */
class VectorMultiplyAlu(config: GpuConfig = GpuConfig()) extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new VectorMultiplyRequest(config)))
    val out = Decoupled(new VectorMultiplyResult(config))
  })

  private def csa(a: UInt, b: UInt, c: UInt): (UInt, UInt) = {
    val sum = a ^ b ^ c
    val carry = ((a & b) | (a & c) | (b & c)) << 1
    (sum, carry(67, 0))
  }

  private def reduceOnce(terms: Seq[UInt]): Seq[UInt] =
    terms.grouped(3).toSeq.flatMap {
      case Seq(a, b, c) =>
        val (sum, carry) = csa(a, b, c)
        Seq(sum, carry)
      case remainder => remainder
    }

  private def reduceTo(terms: Seq[UInt], size: Int): Seq[UInt] = {
    var current = terms
    while (current.size > size) {
      current = reduceOnce(current)
    }
    require(current.size == size)
    current
  }

  private def boothTerms(lhs: UInt, rhs: UInt): Seq[UInt] = {
    val lhsWide = Cat(Fill(35, lhs(32)), lhs).asSInt
    val boothBits = Cat(Fill(2, rhs(32)), rhs, 0.U(1.W))
    (0 until 17).map { index =>
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
  }

  private val stageOneValid = RegInit(false.B)
  private val stageOneBits = Reg(new VectorBoothStageOne(config))
  private val inputValid = RegInit(false.B)
  private val inputBits = Reg(new VectorMultiplyRequest(config))
  private val boothValid = RegInit(false.B)
  private val boothBits = Reg(new VectorBoothEncoded(config))
  private val stageTwoValid = RegInit(false.B)
  private val stageTwoBits = Reg(new VectorBoothStageTwo(config))
  private val stageThreeValid = RegInit(false.B)
  private val stageThreeBits = Reg(new VectorBoothStageThree(config))
  private val productValid = RegInit(false.B)
  private val productBits = Reg(new VectorProductStage(config))
  private val roundingValid = RegInit(false.B)
  private val roundingBits = Reg(new VectorRoundingStage(config))
  private val outputValid = RegInit(false.B)
  private val outputBits = Reg(new VectorMultiplyResult(config))

  private val outputReady = !outputValid || io.out.ready
  private val roundingReady = !roundingValid || outputReady
  private val productReady = !productValid || roundingReady
  private val stageThreeReady = !stageThreeValid || productReady
  private val stageTwoReady = !stageTwoValid || stageThreeReady
  private val stageOneReady = !stageOneValid || stageTwoReady
  private val boothReady = !boothValid || stageOneReady
  private val inputReady = !inputValid || boothReady

  private val isMultiplyVv = io.in.bits.operandType === "b010".U
  private val isMultiplyVx = io.in.bits.operandType === "b110".U
  private val isSaturatingVv = io.in.bits.operandType === "b000".U
  private val isSaturatingVx = io.in.bits.operandType === "b100".U
  private val supportedMultiplyFunct6 =
    io.in.bits.funct6 >= "h24".U && io.in.bits.funct6 <= "h27".U
  private val saturatingOperation =
    io.in.bits.funct6 === "h27".U &&
      (isSaturatingVv || isSaturatingVx)
  private val supported =
    ((isMultiplyVv || isMultiplyVx) && supportedMultiplyFunct6) ||
      saturatingOperation
  private val lhsSigned =
    saturatingOperation ||
      io.in.bits.funct6 === "h26".U || io.in.bits.funct6 === "h27".U
  private val rhsSigned =
    saturatingOperation || io.in.bits.funct6 === "h27".U
  private val inputIsMultiplyVv = inputBits.operandType === "b010".U
  private val inputIsSaturatingVv = inputBits.operandType === "b000".U
  private val inputSaturatingOperation =
    inputBits.funct6 === "h27".U &&
      (inputIsSaturatingVv || inputBits.operandType === "b100".U)
  private val inputLhsSigned =
    inputSaturatingOperation ||
      inputBits.funct6 === "h26".U || inputBits.funct6 === "h27".U
  private val inputRhsSigned =
    inputSaturatingOperation || inputBits.funct6 === "h27".U

  private val firstTerms = Wire(Vec(config.lanes, Vec(12, UInt(68.W))))
  for (lane <- 0 until config.lanes) {
    val vectorRhs = inputIsMultiplyVv || inputIsSaturatingVv
    val rhsData = Mux(vectorRhs, inputBits.vs1(lane), inputBits.scalar)
    val lhs = Cat(inputLhsSigned && inputBits.vs2(lane)(31), inputBits.vs2(lane))
    val rhs = Cat(inputRhsSigned && rhsData(31), rhsData)
    firstTerms(lane) :=
      VecInit(reduceTo(boothBits.terms(lane).toSeq, 12))
  }

  private val secondTerms = Wire(Vec(config.lanes, Vec(4, UInt(68.W))))
  private val thirdTerms = Wire(Vec(config.lanes, Vec(2, UInt(68.W))))
  private val products = Wire(Vec(config.lanes, UInt(68.W)))
  for (lane <- 0 until config.lanes) {
    secondTerms(lane) :=
      VecInit(reduceTo(stageOneBits.terms(lane).toSeq, 4))
    thirdTerms(lane) :=
      VecInit(reduceTo(stageTwoBits.terms(lane).toSeq, 2))
    products(lane) :=
      stageThreeBits.terms(lane)(0) + stageThreeBits.terms(lane)(1)
  }

  io.in.ready := inputReady && supported
  io.out.valid := outputValid
  io.out.bits := outputBits

  private val preparedMultiply =
    Wire(Vec(config.lanes, UInt(config.xLen.W)))
  private val preparedFractional =
    Wire(Vec(config.lanes, UInt(config.xLen.W)))
  private val preparedIncrement = Wire(Vec(config.lanes, Bool()))
  private val preparedSaturation = Wire(Vec(config.lanes, Bool()))
  for (lane <- 0 until config.lanes) {
    val product = productBits.products(lane)
    preparedIncrement(lane) := MuxLookup(
      productBits.metadata.vxrm,
      false.B
    )(Seq(
      "b00".U -> product(30),
      "b01".U -> (product(30) && (product(29, 0).orR || product(31))),
      "b10".U -> false.B,
      "b11".U -> (!product(31) && product(30, 0).orR)
    ))
    preparedFractional(lane) := (product.asSInt >> 31).asUInt
    preparedSaturation(lane) :=
      productBits.metadata.saturating &&
        product(63, 0) === "h4000000000000000".U
    preparedMultiply(lane) := Mux(
      productBits.metadata.highHalf,
      product(63, 32),
      product(31, 0)
    )
  }

  when(outputReady) {
    outputValid := roundingValid
    when(roundingValid) {
      outputBits.warpId := roundingBits.metadata.warpId
      outputBits.pc := roundingBits.metadata.pc
      outputBits.warpActiveMask := roundingBits.metadata.warpActiveMask
      outputBits.vd := roundingBits.metadata.vd
      val saturatedLanes = Wire(Vec(config.lanes, Bool()))
      for (lane <- 0 until config.lanes) {
        val fractionalResult =
          roundingBits.fractionalBase(lane) +
            roundingBits.roundIncrement(lane)
        val selected = Mux(
          roundingBits.metadata.saturating,
          Mux(
            roundingBits.saturation(lane),
            "h7fffffff".U,
            fractionalResult
          ),
          roundingBits.multiplyResult(lane)
        )
        outputBits.data(lane) := Mux(
          roundingBits.metadata.enabled(lane),
          selected,
          roundingBits.metadata.oldVd(lane)
        )
        saturatedLanes(lane) :=
          roundingBits.saturation(lane) &&
            roundingBits.metadata.enabled(lane)
      }
      outputBits.saturated := saturatedLanes.asUInt.orR
    }
  }

  when(roundingReady) {
    roundingValid := productValid
    when(productValid) {
      roundingBits.metadata := productBits.metadata
      roundingBits.multiplyResult := preparedMultiply
      roundingBits.fractionalBase := preparedFractional
      roundingBits.roundIncrement := preparedIncrement.asUInt
      roundingBits.saturation := preparedSaturation.asUInt
    }
  }

  when(productReady) {
    productValid := stageThreeValid
    when(stageThreeValid) {
      productBits.metadata := stageThreeBits.metadata
      productBits.products := products
    }
  }

  when(stageThreeReady) {
    stageThreeValid := stageTwoValid
    when(stageTwoValid) {
      stageThreeBits.metadata := stageTwoBits.metadata
      stageThreeBits.terms := thirdTerms
    }
  }

  when(stageTwoReady) {
    stageTwoValid := stageOneValid
    when(stageOneValid) {
      stageTwoBits.metadata := stageOneBits.metadata
      stageTwoBits.terms := secondTerms
    }
  }

  when(stageOneReady) {
    stageOneValid := boothValid
    when(boothValid) {
      stageOneBits.metadata := boothBits.metadata
      stageOneBits.terms := firstTerms
    }
  }

  when(boothReady) {
    boothValid := inputValid
    when(inputValid) {
      boothBits.metadata.warpId := inputBits.warpId
      boothBits.metadata.pc := inputBits.pc
      boothBits.metadata.warpActiveMask := inputBits.warpActiveMask
      boothBits.metadata.vd := inputBits.vd
      boothBits.metadata.oldVd := inputBits.oldVd
      boothBits.metadata.enabled :=
        inputBits.activeMask &
          Mux(
            inputBits.vm,
            Fill(config.lanes, 1.U),
            inputBits.predicateMask
          )
      boothBits.metadata.highHalf := inputBits.funct6 =/= "h25".U
      boothBits.metadata.saturating := inputSaturatingOperation
      boothBits.metadata.vxrm := inputBits.vxrm
      for (lane <- 0 until config.lanes) {
        val vectorRhs = inputIsMultiplyVv || inputIsSaturatingVv
        val rhsData = Mux(vectorRhs, inputBits.vs1(lane), inputBits.scalar)
        val lhs = Cat(
          inputLhsSigned && inputBits.vs2(lane)(31),
          inputBits.vs2(lane)
        )
        val rhs = Cat(inputRhsSigned && rhsData(31), rhsData)
        boothBits.terms(lane) := VecInit(boothTerms(lhs, rhs))
      }
    }
  }

  when(inputReady) {
    inputValid := io.in.valid
    when(io.in.valid) {
      inputBits := io.in.bits
    }
  }

  when(io.in.valid) {
    assert(supported, "VectorMultiplyAlu received an unsupported RVV operation")
  }
}
