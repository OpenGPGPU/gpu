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
  readsScalar: Boolean = false,
  readsFloat: Boolean = false,
  writesVd: Boolean = false,
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
    override def chiselType: UInt = UInt(3.W)
    override def default: BitPat = BitPat(0.U(3.W))
    override def genTable(pattern: VectorPattern): BitPat = BitPat(pattern.unit.U(3.W))
  }
  object ReadsVs1 extends VectorBoolField("readsVs1") {
    override protected def value(pattern: VectorPattern): Boolean = pattern.readsVs1
  }
  object ReadsVs2 extends VectorBoolField("readsVs2") {
    override protected def value(pattern: VectorPattern): Boolean = pattern.readsVs2
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
    unit: Int = 1
  )

  /*
   * This is an allow-list, not funct6 x funct3.  Each row below corresponds
   * to an actual RVV 1.0 encoding in riscv-opcodes extensions/rv_v.  Legal
   * RVV operations that require cross-lane, widening, narrowing, reduction,
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
    VectorInstruction("vsra",   0x29, Seq(IVV, IVX, IVI))
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
            readsScalar = form.readsScalar,
            readsFloat = form.readsFloat,
            writesVd = true
          )
        }
      }

  // ELEN=32 implementation: exact, non-segmented unit-stride e8/e16/e32.
  private val memoryPatterns = Seq("000", "101", "110").flatMap { width =>
    Seq(
      VectorPattern(s"vload_$width", s"000000?00000?????$width?????0000111", 5,
        readsScalar = true, writesVd = true, memoryRead = true),
      VectorPattern(s"vstore_$width", s"000000?00000?????$width?????0100111", 5,
        readsScalar = true, memoryWrite = true)
    )
  }

  private val configPatterns = Seq(
    VectorPattern("vsetvli",  "0????????????????111?????1010111", 6, configure = true),
    VectorPattern("vsetivli", "11???????????????111?????1010111", 6, configure = true),
    VectorPattern("vsetvl",   "1000000??????????111?????1010111", 6, configure = true)
  )

  val patterns: Seq[VectorPattern] =
    memoryPatterns ++ configPatterns ++ arithmeticPatterns ++
      unary0Patterns ++ unary1Patterns
  val fields: Seq[DecodeField[VectorPattern, _ <: Data]] = Seq(
    Legal,
    Unit,
    ReadsVs1,
    ReadsVs2,
    ReadsScalar,
    ReadsFloat,
    WritesVd,
    MemoryRead,
    MemoryWrite,
    Configure
  )
  val table = new DecodeTable(patterns, fields)
}

/** Table-driven decoder for the implemented lane-local RVV subset. */
class VectorDecoder extends Module {
  val io = IO(new Bundle {
    val instruction = Input(UInt(32.W))
    val decoded = Output(new VectorDecodeSignals)
  })

  val opcode = io.instruction(6, 0)
  val recognized = opcode === "b1010111".U || opcode === "b0000111".U ||
    opcode === "b0100111".U
  val result = VectorDecodeTable.table.decode(io.instruction)
  val (decodedUnit, _) = VectorUnit.safe(result(VectorDecodeTable.Unit))

  io.decoded := 0.U.asTypeOf(new VectorDecodeSignals)
  io.decoded.recognized := recognized
  io.decoded.valid := result(VectorDecodeTable.Legal)
  io.decoded.unit := decodedUnit
  io.decoded.funct6 := io.instruction(31, 26)
  io.decoded.operandType := io.instruction(14, 12)
  io.decoded.vm := io.instruction(25)
  io.decoded.nf := io.instruction(31, 29)
  io.decoded.mop := io.instruction(27, 26)
  io.decoded.elementWidth := io.instruction(14, 12)
  io.decoded.readsVs1 := result(VectorDecodeTable.ReadsVs1)
  io.decoded.readsVs2 := result(VectorDecodeTable.ReadsVs2)
  io.decoded.readsScalar := result(VectorDecodeTable.ReadsScalar)
  io.decoded.readsFloat := result(VectorDecodeTable.ReadsFloat)
  io.decoded.writesVd := result(VectorDecodeTable.WritesVd)
  io.decoded.memoryRead := result(VectorDecodeTable.MemoryRead)
  io.decoded.memoryWrite := result(VectorDecodeTable.MemoryWrite)
  io.decoded.configure := result(VectorDecodeTable.Configure)
}
