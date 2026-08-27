package opengpu.core.execute.fpu

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.frontend.decode.FpuDecodeResponse

class FpuFastMappedRequest(config: GpuConfig, tagWidth: Int) extends Bundle {
  val request = new Fp32Request(tagWidth)
  val supported = Bool()
}

/** Maps RV32F fast arithmetic encodings onto fpnew_fma operand conventions. */
class FpuFastMapper(config: GpuConfig = GpuConfig(), tagWidth: Int = 16)
    extends Module {
  val io = IO(new Bundle {
    val in = Input(new FpuDecodeResponse(config))
    val rs1Data = Input(UInt(32.W))
    val rs2Data = Input(UInt(32.W))
    val rs3Data = Input(UInt(32.W))
    val scalarRs1Data = Input(UInt(32.W))
    val frm = Input(Vec(config.warps, UInt(3.W)))
    val out = Output(new FpuFastMappedRequest(config, tagWidth))
  })

  private val opcode = io.in.instruction(6, 0)
  private val funct5 = io.in.instruction(31, 27)
  private val isFmadd = opcode === "b1000011".U
  private val isFmsub = opcode === "b1000111".U
  private val isFnmsub = opcode === "b1001011".U
  private val isFnmadd = opcode === "b1001111".U
  private val isOpFp = opcode === "b1010011".U
  private val isAdd = isOpFp && funct5 === "b00000".U
  private val isSub = isOpFp && funct5 === "b00001".U
  private val isMul = isOpFp && funct5 === "b00010".U
  private val isSgnj = isOpFp && funct5 === "b00100".U
  private val isMinMax = isOpFp && funct5 === "b00101".U &&
    (io.in.instruction(14, 12) === "b000".U ||
      io.in.instruction(14, 12) === "b001".U)
  private val isCompare = isOpFp && funct5 === "b10100".U &&
    (io.in.instruction(14, 12) === "b000".U ||
      io.in.instruction(14, 12) === "b001".U ||
      io.in.instruction(14, 12) === "b010".U)
  private val isClass = isOpFp && funct5 === "b11100".U &&
    io.in.instruction(14, 12) === "b001".U &&
    io.in.instruction(24, 20) === 0.U
  private val isFmvX = isOpFp && funct5 === "b11100".U &&
    io.in.instruction(14, 12) === "b000".U
  private val isFmvFromX = isOpFp && funct5 === "b11110".U &&
    io.in.instruction(14, 12) === "b000".U &&
    io.in.instruction(24, 20) === 0.U
  private val isFpToInt = isOpFp && funct5 === "b11000".U &&
    (io.in.instruction(24, 20) === "b00000".U ||
      io.in.instruction(24, 20) === "b00001".U)
  private val isIntToFp = isOpFp && funct5 === "b11010".U &&
    (io.in.instruction(24, 20) === "b00000".U ||
      io.in.instruction(24, 20) === "b00001".U)
  private val isExact =
    isSgnj || isMinMax || isCompare || isClass || isFmvX || isFmvFromX ||
      isFpToInt || isIntToFp
  private val selectedFrm =
    if (config.warps == 1) io.frm(0) else io.frm(io.in.warpId)

  io.out := 0.U.asTypeOf(io.out)
  io.out.supported := isFmadd || isFmsub || isFnmsub || isFnmadd ||
    isAdd || isSub || isMul || isExact
  io.out.request.roundingMode := Mux(
    io.in.decoded.rm === "b111".U,
    selectedFrm,
    io.in.decoded.rm
  )
  io.out.request.exactFunction := Mux(
    isFpToInt || isIntToFp,
    io.in.instruction(24, 20)(0),
    io.in.instruction(14, 12)
  )
  io.out.request.tag := Cat(io.in.warpId, io.in.instruction(11, 7))

  when(isFmadd || isFmsub || isFnmsub || isFnmadd) {
    io.out.request.operandA := io.rs1Data
    io.out.request.operandB := io.rs2Data
    io.out.request.operandC := io.rs3Data
    io.out.request.operation := Mux(isFnmsub || isFnmadd,
      Fp32Operation.fnmsub, Fp32Operation.fmadd)
    io.out.request.operationModifier := isFmsub || isFnmadd
  }.elsewhen(isAdd || isSub) {
    // fpnew_fma implements ADD as 1.0 * operandB + operandC.
    io.out.request.operandB := io.rs1Data
    io.out.request.operandC := io.rs2Data
    io.out.request.operation := Fp32Operation.add
    io.out.request.operationModifier := isSub
  }.elsewhen(isMul) {
    io.out.request.operandA := io.rs1Data
    io.out.request.operandB := io.rs2Data
    io.out.request.operation := Fp32Operation.mul
  }.elsewhen(isExact) {
    io.out.request.operandA := Mux(
      isFmvFromX || isIntToFp, io.scalarRs1Data, io.rs1Data)
    io.out.request.operandB := io.rs2Data
    io.out.request.operation := Mux(isSgnj, Fp32Operation.sgnj,
      Mux(isMinMax, Fp32Operation.minmax,
        Mux(isCompare, Fp32Operation.compare,
          Mux(isClass, Fp32Operation.classify,
            Mux(isFmvFromX, Fp32Operation.fmvFromX,
              Mux(isFpToInt, Fp32Operation.fpToInt,
                Mux(isIntToFp, Fp32Operation.intToFp,
                  Fp32Operation.fmvX)))))))
  }
}
