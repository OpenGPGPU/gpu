package opengpu.core.frontend.decode

import chisel3._
import chisel3.util._
import chisel3.util.experimental.decode.{
  BoolDecodeField,
  DecodeField,
  DecodePattern,
  DecodeTable
}

private case class FpuPattern(
  name: String,
  encoding: String,
  unit: Int,
  readsRs1: Boolean = false,
  readsRs2: Boolean = false,
  readsRs3: Boolean = false,
  writesFp: Boolean = false,
  writesInteger: Boolean = false,
  memoryRead: Boolean = false,
  memoryWrite: Boolean = false,
  setsFlags: Boolean = false,
  usesRoundingMode: Boolean = false
) extends DecodePattern {
  override def bitPat: BitPat = BitPat("b" + encoding)
}

private abstract class FpuBoolField(override val name: String)
    extends BoolDecodeField[FpuPattern] {
  override def default: BitPat = n
  protected def value(pattern: FpuPattern): Boolean
  override def genTable(pattern: FpuPattern): BitPat = if (value(pattern)) y else n
}

private object FpuDecodeTable {
  object Legal extends FpuBoolField("legal") {
    override protected def value(pattern: FpuPattern): Boolean = true
  }
  object Unit extends DecodeField[FpuPattern, UInt] {
    override def name: String = "unit"
    override def chiselType: UInt = UInt(3.W)
    override def default: BitPat = BitPat(0.U(3.W))
    override def genTable(pattern: FpuPattern): BitPat = BitPat(pattern.unit.U(3.W))
  }
  object ReadsRs1 extends FpuBoolField("readsRs1") {
    override protected def value(pattern: FpuPattern): Boolean = pattern.readsRs1
  }
  object ReadsRs2 extends FpuBoolField("readsRs2") {
    override protected def value(pattern: FpuPattern): Boolean = pattern.readsRs2
  }
  object ReadsRs3 extends FpuBoolField("readsRs3") {
    override protected def value(pattern: FpuPattern): Boolean = pattern.readsRs3
  }
  object WritesFp extends FpuBoolField("writesFp") {
    override protected def value(pattern: FpuPattern): Boolean = pattern.writesFp
  }
  object WritesInteger extends FpuBoolField("writesInteger") {
    override protected def value(pattern: FpuPattern): Boolean = pattern.writesInteger
  }
  object MemoryRead extends FpuBoolField("memoryRead") {
    override protected def value(pattern: FpuPattern): Boolean = pattern.memoryRead
  }
  object MemoryWrite extends FpuBoolField("memoryWrite") {
    override protected def value(pattern: FpuPattern): Boolean = pattern.memoryWrite
  }
  object SetsFlags extends FpuBoolField("setsFlags") {
    override protected def value(pattern: FpuPattern): Boolean = pattern.setsFlags
  }
  object UsesRoundingMode extends FpuBoolField("usesRoundingMode") {
    override protected def value(pattern: FpuPattern): Boolean = pattern.usesRoundingMode
  }

  // This GPU intentionally implements RV32F only.  Do not create a Cartesian
  // product with D/Zfh formats: accepting them here would advertise hardware
  // that the execution backend and 32-bit lane register files do not contain.
  private val format = "00"
  private val fmaOpcodes = Seq("1000011", "1000111", "1001011", "1001111")

  private val memoryPatterns = Seq(
    FpuPattern("flw", "?????????????????010?????0000111", 1,
      writesFp = true, memoryRead = true),
    FpuPattern("fsw", "?????????????????010?????0100111", 1,
      readsRs2 = true, memoryWrite = true)
  )

  private val fmaPatterns = for {
    (opcode, opcodeIndex) <- fmaOpcodes.zipWithIndex
  } yield FpuPattern(
    s"fma_${opcodeIndex}_s",
    s"?????$format??????????????????$opcode",
    unit = 2,
    readsRs1 = true,
    readsRs2 = true,
    readsRs3 = true,
      writesFp = true,
      setsFlags = true,
      usesRoundingMode = true
  )

  private def binary(
    name: String,
    funct5: String,
    unit: Int = 1,
    setsFlags: Boolean = true,
    usesRoundingMode: Boolean = true
  ): Seq[FpuPattern] = Seq(
    FpuPattern(
      s"${name}_s",
      s"$funct5$format??????????????????1010011",
      unit,
      readsRs1 = true,
      readsRs2 = true,
      writesFp = true,
      setsFlags = setsFlags,
      usesRoundingMode = usesRoundingMode
    )
  )

  private def exactRm(
    name: String,
    funct5: String,
    rm: String,
    unit: Int = 1,
    readsRs2: Boolean = true,
    writesFp: Boolean = true,
    writesInteger: Boolean = false,
    setsFlags: Boolean = false,
    rs2: String = "?????"
  ): Seq[FpuPattern] = Seq(
    FpuPattern(
      s"${name}_s_$rm",
      s"$funct5$format$rs2?????$rm?????1010011",
      unit,
      readsRs1 = true,
      readsRs2 = readsRs2,
      writesFp = writesFp,
      writesInteger = writesInteger,
      setsFlags = setsFlags
    )
  )

  private def unaryRs2(
    name: String,
    funct5: String,
    rs2: String,
    unit: Int,
    writesFp: Boolean,
    writesInteger: Boolean,
    usesRoundingMode: Boolean,
    setsFlags: Boolean
  ): Seq[FpuPattern] = Seq(
    FpuPattern(
      s"${name}_s_$rs2",
      s"$funct5$format$rs2?????????????1010011",
      unit,
      readsRs1 = true,
      writesFp = writesFp,
      writesInteger = writesInteger,
      setsFlags = setsFlags,
      usesRoundingMode = usesRoundingMode
    )
  )

  private val roundedBinaryPatterns =
    binary("fadd", "00000") ++
      binary("fsub", "00001") ++
      binary("fmul", "00010")

  private val exactFunctionPatterns =
    exactRm("fsgnj",  "00100", "000", setsFlags = false) ++
      exactRm("fsgnjn", "00100", "001", setsFlags = false) ++
      exactRm("fsgnjx", "00100", "010", setsFlags = false) ++
      exactRm("fmin",   "00101", "000", setsFlags = true) ++
      exactRm("fmax",   "00101", "001", setsFlags = true) ++
      exactRm("fle",    "10100", "000", writesFp = false,
        writesInteger = true, setsFlags = true) ++
      exactRm("flt",    "10100", "001", writesFp = false,
        writesInteger = true, setsFlags = true) ++
      exactRm("feq",    "10100", "010", writesFp = false,
        writesInteger = true, setsFlags = true)

  private val unaryPatterns =
    unaryRs2("fcvt_w", "11000", "00000", 5, writesFp = false,
        writesInteger = true, usesRoundingMode = true, setsFlags = true) ++
      unaryRs2("fcvt_wu", "11000", "00001", 5, writesFp = false,
        writesInteger = true, usesRoundingMode = true, setsFlags = true) ++
      unaryRs2("fcvt_from_w", "11010", "00000", 5, writesFp = true,
        writesInteger = false, usesRoundingMode = true, setsFlags = true) ++
      unaryRs2("fcvt_from_wu", "11010", "00001", 5, writesFp = true,
        writesInteger = false, usesRoundingMode = true, setsFlags = true)

  private val moveAndClassPatterns =
    Seq(FpuPattern("fmv_to_x_s", s"11100${format}00000?????000?????1010011", 1,
      readsRs1 = true, writesInteger = true)) ++
      exactRm("fclass", "11100", "001", readsRs2 = false,
        writesFp = false, writesInteger = true, rs2 = "00000") ++
      Seq(FpuPattern("fmv_from_x_s", s"11110${format}00000?????000?????1010011", 1,
        readsRs1 = true, writesFp = true))

  private val arithmeticPatterns =
    roundedBinaryPatterns ++ exactFunctionPatterns ++ unaryPatterns ++
      moveAndClassPatterns

  val patterns: Seq[FpuPattern] = memoryPatterns ++ fmaPatterns ++ arithmeticPatterns
  val fields: Seq[DecodeField[FpuPattern, _ <: Data]] = Seq(
    Legal,
    Unit,
    ReadsRs1,
    ReadsRs2,
    ReadsRs3,
    WritesFp,
    WritesInteger,
    MemoryRead,
    MemoryWrite,
    SetsFlags,
    UsesRoundingMode
  )
  val table = new DecodeTable(patterns, fields)
}

/** Table-driven decoder for the GPU's FP32-only RV32F profile. */
class FpuDecoder extends Module {
  val io = IO(new Bundle {
    val instruction = Input(UInt(32.W))
    val decoded = Output(new FpuDecodeSignals)
  })

  val opcode = io.instruction(6, 0)
  val recognized = opcode === "b0000111".U || opcode === "b0100111".U ||
    opcode === "b1000011".U || opcode === "b1000111".U ||
    opcode === "b1001011".U || opcode === "b1001111".U ||
    opcode === "b1010011".U
  val result = FpuDecodeTable.table.decode(io.instruction)
  val (decodedUnit, _) = FpuUnit.safe(result(FpuDecodeTable.Unit))
  val roundingMode = io.instruction(14, 12)
  val legalRoundingMode = roundingMode <= 4.U || roundingMode === 7.U
  val legal = result(FpuDecodeTable.Legal) &&
    (!result(FpuDecodeTable.UsesRoundingMode) || legalRoundingMode)

  io.decoded := 0.U.asTypeOf(new FpuDecodeSignals)
  io.decoded.recognized := recognized
  io.decoded.valid := legal
  io.decoded.unit := decodedUnit
  io.decoded.format := io.instruction(26, 25)
  io.decoded.funct5 := io.instruction(31, 27)
  io.decoded.rm := io.instruction(14, 12)
  io.decoded.readsRs1 := result(FpuDecodeTable.ReadsRs1)
  io.decoded.readsRs2 := result(FpuDecodeTable.ReadsRs2)
  io.decoded.readsRs3 := result(FpuDecodeTable.ReadsRs3)
  io.decoded.writesFp := result(FpuDecodeTable.WritesFp)
  io.decoded.writesInteger := result(FpuDecodeTable.WritesInteger)
  io.decoded.memoryRead := result(FpuDecodeTable.MemoryRead)
  io.decoded.memoryWrite := result(FpuDecodeTable.MemoryWrite)
  io.decoded.setsFlags := result(FpuDecodeTable.SetsFlags)
}
