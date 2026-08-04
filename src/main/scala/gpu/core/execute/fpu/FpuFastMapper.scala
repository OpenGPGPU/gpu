package gpu.core.execute.fpu

import chisel3._
import chisel3.util._
import gpu.config.GpuConfig
import gpu.core.frontend.decode.FpuDecodeResponse

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

  io.out := 0.U.asTypeOf(io.out)
  io.out.supported := isFmadd || isFmsub || isFnmsub || isFnmadd ||
    isAdd || isSub || isMul
  io.out.request.roundingMode := io.in.decoded.rm
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
  }
}
