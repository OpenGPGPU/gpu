package gpu.core.execute.fpu

import chisel3._
import chisel3.util._
import chisel3.util.HasBlackBoxResource

/** Operation encodings are intentionally identical to fpnew_pkg::operation_e. */
object Fp32Operation extends ChiselEnum {
  val fmadd, fnmsub, add, mul, div, sqrt, sgnj, minmax, compare, classify,
      fpToFp, fpToInt, intToFp, packAB, packCD = Value
}

class Fp32Request(tagWidth: Int) extends Bundle {
  val operandA = UInt(32.W)
  val operandB = UInt(32.W)
  val operandC = UInt(32.W)
  val roundingMode = UInt(3.W)
  val operation = Fp32Operation()
  val operationModifier = Bool()
  val tag = UInt(tagWidth.W)
}

class Fp32Response(tagWidth: Int) extends Bundle {
  val result = UInt(32.W)
  /** RISC-V fflags order: NV, DZ, OF, UF, NX. */
  val status = UInt(5.W)
  val tag = UInt(tagWidth.W)
}

private class Fp32CvFpuBlackBox(tagWidth: Int)
    extends BlackBox(Map("TAG_WIDTH" -> tagWidth))
    with HasBlackBoxResource {
  val io = IO(new Bundle {
    val clk_i = Input(Clock())
    val rst_ni = Input(Bool())
    val operand_a_i = Input(UInt(32.W))
    val operand_b_i = Input(UInt(32.W))
    val operand_c_i = Input(UInt(32.W))
    val rnd_mode_i = Input(UInt(3.W))
    val op_i = Input(UInt(4.W))
    val op_mod_i = Input(Bool())
    val tag_i = Input(UInt(tagWidth.W))
    val in_valid_i = Input(Bool())
    val in_ready_o = Output(Bool())
    val flush_i = Input(Bool())
    val result_o = Output(UInt(32.W))
    val status_o = Output(UInt(5.W))
    val tag_o = Output(UInt(tagWidth.W))
    val out_valid_o = Output(Bool())
    val out_ready_i = Input(Bool())
    val busy_o = Output(Bool())
  })
  override def desiredName: String = "fp32_cvfpu_wrapper"
  addResource("/fpu/fp32_cvfpu_wrapper.sv")
}

/** Correct ready/valid wrapper around the FP32-only CVFPU instance. */
class Fp32CvFpu(tagWidth: Int = 16) extends Module {
  require(tagWidth > 0)
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new Fp32Request(tagWidth)))
    val out = Decoupled(new Fp32Response(tagWidth))
    val flush = Input(Bool())
    val busy = Output(Bool())
  })

  private val fpu = Module(new Fp32CvFpuBlackBox(tagWidth))
  fpu.io.clk_i := clock
  fpu.io.rst_ni := !reset.asBool
  fpu.io.operand_a_i := io.in.bits.operandA
  fpu.io.operand_b_i := io.in.bits.operandB
  fpu.io.operand_c_i := io.in.bits.operandC
  fpu.io.rnd_mode_i := io.in.bits.roundingMode
  fpu.io.op_i := io.in.bits.operation.asUInt
  fpu.io.op_mod_i := io.in.bits.operationModifier
  fpu.io.tag_i := io.in.bits.tag
  fpu.io.in_valid_i := io.in.valid
  io.in.ready := fpu.io.in_ready_o
  fpu.io.flush_i := io.flush

  io.out.valid := fpu.io.out_valid_o
  io.out.bits.result := fpu.io.result_o
  io.out.bits.status := fpu.io.status_o
  io.out.bits.tag := fpu.io.tag_o
  fpu.io.out_ready_i := io.out.ready
  io.busy := fpu.io.busy_o
}
