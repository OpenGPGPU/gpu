package opengpu.core.frontend.decode

import chisel3._
import chisel3.util._
import chisel3.util.experimental.decode.{
  BoolDecodeField,
  DecodeField,
  DecodePattern,
  DecodeTable
}

private case class VectorPattern(
  name: String,
  encoding: String,
  unit: Int,
  readsVs1: Boolean = false,
  readsVs2: Boolean = false,
  readsVs2Pair: Boolean = false,
  readsScalar: Boolean = false,
  readsFloat: Boolean = false,
  writesVd: Boolean = false,
  writesScalar: Boolean = false,
  writesFloat: Boolean = false,
  memoryRead: Boolean = false,
  memoryWrite: Boolean = false,
  configure: Boolean = false
) extends DecodePattern {
  override def bitPat: BitPat = BitPat("b" + encoding)
}

private abstract class VectorBoolField(override val name: String)
    extends BoolDecodeField[VectorPattern] {
  override def default: BitPat = n
  protected def value(pattern: VectorPattern): Boolean
  override def genTable(pattern: VectorPattern): BitPat = if (value(pattern)) y else n
}

private object VectorDecodeTable {
  object Legal extends VectorBoolField("legal") {
    override protected def value(pattern: VectorPattern): Boolean = true
  }
  object Unit extends DecodeField[VectorPattern, UInt] {
    override def name: String = "unit"
    override def chiselType: UInt = UInt(4.W)
    override def default: BitPat = BitPat(0.U(4.W))
    override def genTable(pattern: VectorPattern): BitPat = BitPat(pattern.unit.U(4.W))
  }
  object ReadsVs1 extends VectorBoolField("readsVs1") {
    override protected def value(pattern: VectorPattern): Boolean = pattern.readsVs1
  }
  object ReadsVs2 extends VectorBoolField("readsVs2") {
    override protected def value(pattern: VectorPattern): Boolean = pattern.readsVs2
  }
  object ReadsVs2Pair extends VectorBoolField("readsVs2Pair") {
    override protected def value(pattern: VectorPattern): Boolean =
      pattern.readsVs2Pair
  }
  object ReadsScalar extends VectorBoolField("readsScalar") {
    override protected def value(pattern: VectorPattern): Boolean = pattern.readsScalar
  }
  object ReadsFloat extends VectorBoolField("readsFloat") {
    override protected def value(pattern: VectorPattern): Boolean = pattern.readsFloat
  }
  object WritesVd extends VectorBoolField("writesVd") {
    override protected def value(pattern: VectorPattern): Boolean = pattern.writesVd
  }
  object WritesScalar extends VectorBoolField("writesScalar") {
    override protected def value(pattern: VectorPattern): Boolean =
      pattern.writesScalar
  }
  object WritesFloat extends VectorBoolField("writesFloat") {
    override protected def value(pattern: VectorPattern): Boolean =
      pattern.writesFloat
  }
  object MemoryRead extends VectorBoolField("memoryRead") {
    override protected def value(pattern: VectorPattern): Boolean = pattern.memoryRead
  }
  object MemoryWrite extends VectorBoolField("memoryWrite") {
    override protected def value(pattern: VectorPattern): Boolean = pattern.memoryWrite
  }
  object Configure extends VectorBoolField("configure") {
    override protected def value(pattern: VectorPattern): Boolean = pattern.configure
  }

  private sealed trait OperandForm {
    def funct3: String
    def readsVs1: Boolean = false
    def readsScalar: Boolean = false
    def readsFloat: Boolean = false
  }
  private case object IVV extends OperandForm {
    val funct3 = "000"
    override val readsVs1 = true
  }
  private case object FVV extends OperandForm {
    val funct3 = "001"
    override val readsVs1 = true
  }
  private case object MVV extends OperandForm {
    val funct3 = "010"
    override val readsVs1 = true
  }
  private case object IVI extends OperandForm { val funct3 = "011" }
  private case object IVX extends OperandForm {
    val funct3 = "100"
    override val readsScalar = true
  }
  private case object FVF extends OperandForm {
    val funct3 = "101"
    override val readsFloat = true
  }
  private case object MVX extends OperandForm {
    val funct3 = "110"
    override val readsScalar = true
  }

  private case class VectorInstruction(
    name: String,
    funct6: Int,
    forms: Seq[OperandForm],
    unit: Int = 1,
    readsVs2Pair: Boolean = false
  )

  /*
   * This is an allow-list, not funct6 x funct3.  Each row below corresponds
   * to an actual RVV 1.0 encoding in riscv-opcodes extensions/rv_v.  Legal
   * RVV operations that require unsupported cross-lane, widening, narrowing,
   * or special unary handling are deliberately absent until implemented.
   */
  private val integerAluInstructions = Seq(
    VectorInstruction("vadd",   0x00, Seq(IVV, IVX, IVI)),
    VectorInstruction("vsub",   0x02, Seq(IVV, IVX)),
    VectorInstruction("vrsub",  0x03, Seq(IVX, IVI)),
    VectorInstruction("vminu",  0x04, Seq(IVV, IVX)),
    VectorInstruction("vmin",   0x05, Seq(IVV, IVX)),
    VectorInstruction("vmaxu",  0x06, Seq(IVV, IVX)),
    VectorInstruction("vmax",   0x07, Seq(IVV, IVX)),
    VectorInstruction("vand",   0x09, Seq(IVV, IVX, IVI)),
    VectorInstruction("vor",    0x0a, Seq(IVV, IVX, IVI)),
    VectorInstruction("vxor",   0x0b, Seq(IVV, IVX, IVI)),
    VectorInstruction("vrgather", 0x0c, Seq(IVV, IVX, IVI)),
    VectorInstruction("vslideup", 0x0e, Seq(IVV, IVX, IVI)),
    VectorInstruction("vslidedown", 0x0f, Seq(IVV, IVX, IVI)),
    // OPMVX inserts the integer scalar and shifts by one element.
    VectorInstruction("vslide1up", 0x0e, Seq(MVX)),
    VectorInstruction("vslide1down", 0x0f, Seq(MVX)),
    VectorInstruction("vredsum",  0x00, Seq(MVV)),
    VectorInstruction("vredand",  0x01, Seq(MVV)),
    VectorInstruction("vredor",   0x02, Seq(MVV)),
    VectorInstruction("vredxor",  0x03, Seq(MVV)),
    VectorInstruction("vredminu", 0x04, Seq(MVV)),
    VectorInstruction("vredmin",  0x05, Seq(MVV)),
    VectorInstruction("vredmaxu", 0x06, Seq(MVV)),
    VectorInstruction("vredmax",  0x07, Seq(MVV)),
    VectorInstruction("vmseq",  0x18, Seq(IVV, IVX, IVI), unit = 7),
    VectorInstruction("vmsne",  0x19, Seq(IVV, IVX, IVI), unit = 7),
    VectorInstruction("vmsltu", 0x1a, Seq(IVV, IVX), unit = 7),
    VectorInstruction("vmslt",  0x1b, Seq(IVV, IVX), unit = 7),
    VectorInstruction("vmsleu", 0x1c, Seq(IVV, IVX, IVI), unit = 7),
    VectorInstruction("vmsle",  0x1d, Seq(IVV, IVX, IVI), unit = 7),
    VectorInstruction("vmsgtu", 0x1e, Seq(IVX, IVI), unit = 7),
    VectorInstruction("vmsgt",  0x1f, Seq(IVX, IVI), unit = 7),
    VectorInstruction("vsaddu", 0x20, Seq(IVV, IVX, IVI)),
    VectorInstruction("vsadd",  0x21, Seq(IVV, IVX, IVI)),
    VectorInstruction("vssubu", 0x22, Seq(IVV, IVX)),
    VectorInstruction("vssub",  0x23, Seq(IVV, IVX)),
    VectorInstruction("vsll",   0x25, Seq(IVV, IVX, IVI)),
    VectorInstruction("vsmul",  0x27, Seq(IVV, IVX), unit = 2),
    VectorInstruction("vsrl",   0x28, Seq(IVV, IVX, IVI)),
    VectorInstruction("vsra",   0x29, Seq(IVV, IVX, IVI)),
    VectorInstruction("vssrl",  0x2a, Seq(IVV, IVX, IVI)),
    VectorInstruction("vssra",  0x2b, Seq(IVV, IVX, IVI)),
    // Fixed-profile narrowing shifts consume the even/odd pair vs2/vs2+1
    // as one 64-bit source per lane.
    VectorInstruction("vnsrl",  0x2c, Seq(IVV, IVX, IVI), readsVs2Pair = true),
    VectorInstruction("vnsra",  0x2d, Seq(IVV, IVX, IVI), readsVs2Pair = true),
    VectorInstruction("vnclipu", 0x2e, Seq(IVV, IVX, IVI), readsVs2Pair = true),
    VectorInstruction("vnclip",  0x2f, Seq(IVV, IVX, IVI), readsVs2Pair = true),
    // Widening integer operations: take the lower 16 bits of each 32-bit lane,
    // sign/zero-extend to 32 bits, and perform the operation.
    VectorInstruction("vwadd",  0x30, Seq(IVV, IVX, IVI)),
    VectorInstruction("vwsub",  0x31, Seq(IVV, IVX)),
    VectorInstruction("vwmul",  0x32, Seq(IVV, IVX)),
    VectorInstruction("vwmulu", 0x33, Seq(IVV, IVX)),
    VectorInstruction("vwmulsu",0x34, Seq(IVV))
  )

  private val integerMultiplyDivideInstructions = Seq(
    VectorInstruction("vdivu",   0x20, Seq(MVV, MVX), unit = 3),
    VectorInstruction("vdiv",    0x21, Seq(MVV, MVX), unit = 3),
    VectorInstruction("vremu",   0x22, Seq(MVV, MVX), unit = 3),
    VectorInstruction("vrem",    0x23, Seq(MVV, MVX), unit = 3),
    VectorInstruction("vmulhu",  0x24, Seq(MVV, MVX), unit = 2),
    VectorInstruction("vmul",    0x25, Seq(MVV, MVX), unit = 2),
    VectorInstruction("vmulhsu", 0x26, Seq(MVV, MVX), unit = 2),
    VectorInstruction("vmulh",   0x27, Seq(MVV, MVX), unit = 2)
  )

  private val floatingPointInstructions = Seq(
    VectorInstruction("vfadd",   0x00, Seq(FVV, FVF), unit = 4),
    VectorInstruction("vfsub",   0x02, Seq(FVV, FVF), unit = 4),
    VectorInstruction("vfmin",   0x04, Seq(FVV, FVF), unit = 4),
    VectorInstruction("vfmax",   0x06, Seq(FVV, FVF), unit = 4),
    VectorInstruction("vfsgnj",  0x08, Seq(FVV, FVF), unit = 4),
    VectorInstruction("vfsgnjn", 0x09, Seq(FVV, FVF), unit = 4),
    VectorInstruction("vfsgnjx", 0x0a, Seq(FVV, FVF), unit = 4),
    VectorInstruction("vfmerge", 0x17, Seq(FVF), unit = 4),
    VectorInstruction("vmfeq",   0x18, Seq(FVV, FVF), unit = 4),
    VectorInstruction("vmfle",   0x19, Seq(FVV, FVF), unit = 4),
    VectorInstruction("vmflt",   0x1b, Seq(FVV, FVF), unit = 4),
    VectorInstruction("vmfne",   0x1c, Seq(FVV, FVF), unit = 4),
    VectorInstruction("vmfgt",   0x1d, Seq(FVF), unit = 4),
    VectorInstruction("vmfge",   0x1f, Seq(FVF), unit = 4),
    VectorInstruction("vfdiv",   0x20, Seq(FVV, FVF), unit = 4),
    VectorInstruction("vfrdiv",  0x21, Seq(FVF), unit = 4),
    VectorInstruction("vfmul",   0x24, Seq(FVV, FVF), unit = 4),
    VectorInstruction("vfrsub",  0x27, Seq(FVF), unit = 4),
    VectorInstruction("vfmadd",  0x28, Seq(FVV, FVF), unit = 4),
    VectorInstruction("vfnmadd", 0x29, Seq(FVV, FVF), unit = 4),
    VectorInstruction("vfmsub",  0x2a, Seq(FVV, FVF), unit = 4),
    VectorInstruction("vfnmsub", 0x2b, Seq(FVV, FVF), unit = 4),
    VectorInstruction("vfmacc",  0x2c, Seq(FVV, FVF), unit = 4),
    VectorInstruction("vfnmacc", 0x2d, Seq(FVV, FVF), unit = 4),
    VectorInstruction("vfmsac",  0x2e, Seq(FVV, FVF), unit = 4),
    VectorInstruction("vfnmsac", 0x2f, Seq(FVV, FVF), unit = 4)
  )

  // VFUNARY0: funct6 010010 carries the conversion opcode in the vs1 field.
  // Only the implemented SEW=32 conversions are allow-listed.
  private val unary0Patterns = Seq(
    // Fixed-profile integer widening from low 16/8/4 bits to SEW=32.
    VectorPattern("vsext_vf2", "010010??????00111010?????1010111", 1,
      readsVs2 = true, writesVd = true),
    VectorPattern("vzext_vf2", "010010??????00110010?????1010111", 1,
      readsVs2 = true, writesVd = true),
    VectorPattern("vsext_vf4", "010010??????00101010?????1010111", 1,
      readsVs2 = true, writesVd = true),
    VectorPattern("vzext_vf4", "010010??????00100010?????1010111", 1,
      readsVs2 = true, writesVd = true),
    VectorPattern("vsext_vf8", "010010??????00011010?????1010111", 1,
      readsVs2 = true, writesVd = true),
    VectorPattern("vzext_vf8", "010010??????00010010?????1010111", 1,
      readsVs2 = true, writesVd = true),
    VectorPattern("vfcvt_xu_f_v", "010010??????00000001?????1010111", 4,
      readsVs2 = true, writesVd = true),
    VectorPattern("vfcvt_x_f_v", "010010??????00001001?????1010111", 4,
      readsVs2 = true, writesVd = true),
    VectorPattern("vfcvt_f_xu_v", "010010??????00010001?????1010111", 4,
      readsVs2 = true, writesVd = true),
    VectorPattern("vfcvt_f_x_v", "010010??????00011001?????1010111", 4,
      readsVs2 = true, writesVd = true),
    VectorPattern("vfcvt_rtz_xu_f_v", "010010??????00110001?????1010111", 4,
      readsVs2 = true, writesVd = true),
    VectorPattern("vfcvt_rtz_x_f_v", "010010??????00111001?????1010111", 4,
      readsVs2 = true, writesVd = true),
    VectorPattern("vfcvt_rtz_f_xu_v", "010010??????00100001?????1010111", 4,
      readsVs2 = true, writesVd = true),
    VectorPattern("vfcvt_rtz_f_x_v", "010010??????00101001?????1010111", 4,
      readsVs2 = true, writesVd = true)
  )

  // VFUNARY1: funct6 010011 carries the unary FP opcode in the vs1 field.
  private val unary1Patterns = Seq(
    VectorPattern("vfsqrt_v", "010011??????00000001?????1010111", 4,
      readsVs2 = true, writesVd = true),
    VectorPattern("vfrsqrt7_v", "010011??????00101001?????1010111", 4,
      readsVs2 = true, writesVd = true),
    VectorPattern("vfrec7_v", "010011??????00100001?????1010111", 4,
      readsVs2 = true, writesVd = true),
    VectorPattern("vfclass_v", "010011??????10000001?????1010111", 4,
      readsVs2 = true, writesVd = true)
  )

  // vmerge is masked (vm=0). vmv is the same funct6 with vm=1 and vs2=v0;
  // any other vs2 is reserved.
  private val mergePatterns = Seq(
    VectorPattern("vmerge_vvm", "0101110??????????000?????1010111", 1,
      readsVs1 = true, readsVs2 = true, writesVd = true),
    VectorPattern("vmv_v_v", "010111100000?????000?????1010111", 1,
      readsVs1 = true, writesVd = true),
    VectorPattern("vmerge_vxm", "0101110??????????100?????1010111", 1,
      readsVs2 = true, readsScalar = true, writesVd = true),
    VectorPattern("vmv_v_x", "010111100000?????100?????1010111", 1,
      readsScalar = true, writesVd = true),
    VectorPattern("vmerge_vim", "0101110??????????011?????1010111", 1,
      readsVs2 = true, writesVd = true),
    VectorPattern("vmv_v_i", "010111100000?????011?????1010111", 1,
      writesVd = true)
  )

  // vmv.x.s is VWXUNARY0 (OPMVV, vs1 = 0). vmv.s.x is VRXUNARY0 (OPMVX, vs2 = v0).
  // Other unary opcodes in that space, and the masked forms, stay reserved.
  private val scalarMovePatterns = Seq(
    VectorPattern("vmv_x_s", "0100001?????00000010?????1010111", 1,
      readsVs2 = true, writesScalar = true),
    VectorPattern("vmv_s_x", "010000100000?????110?????1010111", 1,
      readsScalar = true, writesVd = true)
  )

  // vfmv.f.s is OPFVV with vs1 = 0. vfmv.s.f is OPFVF with vs2 = v0.
  // Other unary opcodes in that space, and the masked forms, stay reserved.
  private val floatMovePatterns = Seq(
    VectorPattern("vfmv_f_s", "0100001?????00000001?????1010111", 4,
      readsVs2 = true, writesFloat = true),
    VectorPattern("vfmv_s_f", "010000100000?????101?????1010111", 4,
      readsFloat = true, writesVd = true)
  )

  // OPMVV mask logical. The masked encoding (vm=0) is reserved.
  private val maskLogicalPatterns = Seq(
    "vmandn" -> "011000",
    "vmand" -> "011001",
    "vmor" -> "011010",
    "vmxor" -> "011011",
    "vmorn" -> "011100",
    "vmnand" -> "011101",
    "vmnor" -> "011110",
    "vmxnor" -> "011111"
  ).map { case (name, funct6) =>
    VectorPattern(
      name,
      s"${funct6}1??????????010?????1010111",
      1,
      readsVs1 = true,
      readsVs2 = true,
      writesVd = true
    )
  }

  private val arithmeticPatterns =
    (integerAluInstructions ++ integerMultiplyDivideInstructions ++ floatingPointInstructions)
      .flatMap { instruction =>
        instruction.forms.map { form =>
          val funct6 = instruction.funct6.toBinaryString.reverse.padTo(6, '0').reverse
          VectorPattern(
            s"${instruction.name}_${form.funct3}",
            s"${funct6}???????????${form.funct3}?????1010111",
            unit = instruction.unit,
            readsVs1 = form.readsVs1,
            readsVs2 = true,
            readsVs2Pair = instruction.readsVs2Pair,
            readsScalar = form.readsScalar,
            readsFloat = form.readsFloat,
            writesVd = true
          )
        }
      }

  // ELEN=32 implementation: exact, non-segmented unit/strided e8/e16/e32,
  // plus ordered and unordered 32-bit index operations for the fixed SEW=32
  // execution profile.
  private val memoryPatterns = Seq("000", "101", "110").flatMap { width =>
    Seq(
      VectorPattern(s"vload_$width", s"000000?00000?????$width?????0000111", 5,
        readsScalar = true, writesVd = true, memoryRead = true),
      VectorPattern(s"vstore_$width", s"000000?00000?????$width?????0100111", 5,
        readsScalar = true, memoryWrite = true),
      VectorPattern(s"vstrided_load_$width", s"000010???????????$width?????0000111", 5,
        readsScalar = true, writesVd = true, memoryRead = true),
      VectorPattern(s"vstrided_store_$width", s"000010???????????$width?????0100111", 5,
        readsScalar = true, memoryWrite = true)
    )
  } ++ Seq("000001", "000011").flatMap { prefix =>
    Seq(
      VectorPattern(s"vindexed_load_$prefix",
        s"$prefix???????????110?????0000111", 5,
        readsVs2 = true, readsScalar = true, writesVd = true,
        memoryRead = true),
      VectorPattern(s"vindexed_store_$prefix",
        s"$prefix???????????110?????0100111", 5,
        readsVs2 = true, readsScalar = true, memoryWrite = true)
    )
  }

  private val configPatterns = Seq(
    VectorPattern("vsetvli",  "0????????????????111?????1010111", 6, configure = true),
    VectorPattern("vsetivli", "11???????????????111?????1010111", 6, configure = true),
    VectorPattern("vsetvl",   "1000000??????????111?????1010111", 6, configure = true)
  )

  // vtex.sample vd, vs1, vs2: lane-local Q16.16 coordinates to packed
  // RGBA8888. custom-1 opcode, funct6=000001, funct3=000; bit 25 retains
  // RVV's vm meaning so masked sampling has ordinary vector semantics.
  private val texturePatterns = Seq(
    VectorPattern("vtex_sample",
      "000001???????????000?????0101011", 8,
      readsVs1 = true, readsVs2 = true, writesVd = true)
  )

  // Fragment-quad derivatives. custom-1 keeps the operation outside standard
  // RVV encodings; vs2 is the varying and lane groups are ordered TL,TR,BL,BR.
  // Driver profile v8 admits these after true quad dispatch is established.
  private val quadPatterns = Seq(
    VectorPattern("vquad_dfdx",
      "001100???????????000?????0101011", 1,
      readsVs2 = true, writesVd = true),
    VectorPattern("vquad_dfdy",
      "001101???????????000?????0101011", 1,
      readsVs2 = true, writesVd = true)
  )

  val patterns: Seq[VectorPattern] =
    memoryPatterns ++ configPatterns ++ arithmeticPatterns ++
      mergePatterns ++ maskLogicalPatterns ++ scalarMovePatterns ++
      floatMovePatterns ++
      unary0Patterns ++
      unary1Patterns ++
      texturePatterns ++ quadPatterns
  val fields: Seq[DecodeField[VectorPattern, _ <: Data]] = Seq(
    Legal,
    Unit,
    ReadsVs1,
    ReadsVs2,
    ReadsVs2Pair,
    ReadsScalar,
    ReadsFloat,
    WritesVd,
    WritesScalar,
    WritesFloat,
    MemoryRead,
    MemoryWrite,
    Configure
  )
  val table = new DecodeTable(patterns, fields)
}

/** Table-driven decoder for the implemented RVV subset. */
class VectorDecoder extends Module {
  val io = IO(new Bundle {
    val instruction = Input(UInt(32.W))
    val decoded = Output(new VectorDecodeSignals)
  })

  val opcode = io.instruction(6, 0)
  val recognized = opcode === "b1010111".U || opcode === "b0000111".U ||
    opcode === "b0100111".U || opcode === "b0101011".U
  val result = VectorDecodeTable.table.decode(io.instruction)
  val (decodedUnit, _) = VectorUnit.safe(result(VectorDecodeTable.Unit))
  val slideUpOverlap = io.instruction(31, 26) === "b001110".U &&
    io.instruction(11, 7) === io.instruction(24, 20)

  val integerExtension = opcode === "b1010111".U &&
    io.instruction(31, 26) === "b010010".U &&
    io.instruction(14, 12) === "b010".U
  val extensionOverlap = integerExtension && (
    io.instruction(11, 7) === io.instruction(24, 20) ||
      (!io.instruction(25) && io.instruction(11, 7) === 0.U)
  )

  // Narrowing shifts and clips treat vs2/vs2+1 as one 64-bit source.
  // vs2 must be even and vd disjoint from the pair; masked vd cannot be v0.
  val integerNarrowing = opcode === "b1010111".U &&
    (io.instruction(31, 26) >= "h2c".U &&
      io.instruction(31, 26) <= "h2f".U) &&
    (io.instruction(14, 12) === "b000".U ||
      io.instruction(14, 12) === "b011".U ||
      io.instruction(14, 12) === "b100".U)
  val narrowingOverlap = integerNarrowing && (
    io.instruction(20) ||
      io.instruction(11, 7) === io.instruction(24, 20) ||
      io.instruction(11, 7) === (io.instruction(24, 20) + 1.U)(4, 0) ||
      (!io.instruction(25) && io.instruction(11, 7) === 0.U)
  )

  io.decoded := 0.U.asTypeOf(new VectorDecodeSignals)
  io.decoded.recognized := recognized
  io.decoded.valid := result(VectorDecodeTable.Legal) &&
    !slideUpOverlap && !extensionOverlap && !narrowingOverlap
  io.decoded.unit := decodedUnit
  io.decoded.funct6 := io.instruction(31, 26)
  io.decoded.operandType := io.instruction(14, 12)
  io.decoded.vm := io.instruction(25)
  io.decoded.nf := io.instruction(31, 29)
  io.decoded.mop := io.instruction(27, 26)
  io.decoded.elementWidth := io.instruction(14, 12)
  io.decoded.readsVs1 := result(VectorDecodeTable.ReadsVs1)
  io.decoded.readsVs2 := result(VectorDecodeTable.ReadsVs2)
  io.decoded.readsVs2Pair := result(VectorDecodeTable.ReadsVs2Pair)
  io.decoded.readsScalar := result(VectorDecodeTable.ReadsScalar)
  io.decoded.readsFloat := result(VectorDecodeTable.ReadsFloat)
  io.decoded.writesVd := result(VectorDecodeTable.WritesVd)
  io.decoded.writesScalar := result(VectorDecodeTable.WritesScalar)
  io.decoded.writesFloat := result(VectorDecodeTable.WritesFloat)
  io.decoded.memoryRead := result(VectorDecodeTable.MemoryRead)
  io.decoded.memoryWrite := result(VectorDecodeTable.MemoryWrite)
  io.decoded.configure := result(VectorDecodeTable.Configure)
}
