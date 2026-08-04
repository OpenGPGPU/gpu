package gpu.core.execute.fpu

import chisel3._
import chisel3.util._
import chisel3.util.HasBlackBoxResource

/** FP32-specialized YunSuan FloatFMA generated from the upstream Chisel RTL. */
private class YunSuanFp32FmaBlackBox extends BlackBox
    with HasBlackBoxResource {
  val io = IO(new Bundle {
    val clk_i = Input(Clock())
    val rst_ni = Input(Bool())
    val operand_a_i = Input(UInt(32.W))
    val operand_b_i = Input(UInt(32.W))
    val operand_c_i = Input(UInt(32.W))
    val rnd_mode_i = Input(UInt(3.W))
    val op_i = Input(UInt(4.W))
    val in_valid_i = Input(Bool())
    val result_o = Output(UInt(32.W))
    val status_o = Output(UInt(5.W))
  })

  override def desiredName: String = "yunsuan_float_fma_fp32_wrapper"
  addResource("/fpu/YunSuanFloatFmaFp32Ppa.v")
}

/** Elastic GPU wrapper around YunSuan's three-cycle FP32 FMA pipeline.
  *
  * YunSuan accepts one operation per cycle but has no downstream backpressure.
  * Credits cover both the three in-flight pipeline slots and the result queue,
  * so accepting a request always reserves storage for its eventual result.
  */
class Fp32FmaLane(tagWidth: Int = 16, pipeRegs: Int = 3) extends Module {
  require(tagWidth > 0)
  require(pipeRegs == 3, "YunSuan FloatFMA has three fixed arithmetic stages")

  private val resultEntries = 4
  private val latency = 3

  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new Fp32Request(tagWidth)))
    val out = Decoupled(new Fp32Response(tagWidth))
    val flush = Input(Bool())
    val busy = Output(Bool())
  })

  private val core = Module(new YunSuanFp32FmaBlackBox)
  private val resetOrFlush = reset.asBool || io.flush
  core.io.clk_i := clock
  core.io.rst_ni := !resetOrFlush

  private val isFmadd = io.in.bits.operation === Fp32Operation.fmadd
  private val isFnmsub = io.in.bits.operation === Fp32Operation.fnmsub
  private val isAdd = io.in.bits.operation === Fp32Operation.add
  private val isMul = io.in.bits.operation === Fp32Operation.mul

  // YunSuan FmaOpCode: fmul=0, fmacc=1, fnmacc=2, fmsac=3,
  // fnmsac=4.  The existing modifier distinguishes the paired RISC-V signs.
  private val yunSuanOp = WireDefault(0.U(4.W))
  when(isFmadd || isAdd) {
    yunSuanOp := Mux(io.in.bits.operationModifier, 3.U, 1.U)
  }.elsewhen(isFnmsub) {
    yunSuanOp := Mux(io.in.bits.operationModifier, 2.U, 4.U)
  }

  core.io.operand_a_i := Mux(isAdd, "h3f800000".U, io.in.bits.operandA)
  core.io.operand_b_i := io.in.bits.operandB
  core.io.operand_c_i := Mux(isMul, 0.U, io.in.bits.operandC)
  core.io.rnd_mode_i := io.in.bits.roundingMode
  core.io.op_i := yunSuanOp

  private val responseQueue = withReset(resetOrFlush) {
    Module(new Queue(new Fp32Response(tagWidth), resultEntries))
  }
  private val outstanding = RegInit(0.U(log2Ceil(resultEntries + 1).W))
  private val canRecycleCredit = io.out.valid && io.out.ready
  io.in.ready := !io.flush &&
    (outstanding =/= resultEntries.U || canRecycleCredit)
  private val accept = io.in.valid && io.in.ready
  core.io.in_valid_i := accept

  private val validPipe = RegInit(VecInit(Seq.fill(latency)(false.B)))
  private val tagPipe = Reg(Vec(latency, UInt(tagWidth.W)))
  when(resetOrFlush) {
    validPipe.foreach(_ := false.B)
  }.otherwise {
    validPipe(0) := accept
    for (i <- 1 until latency) {
      validPipe(i) := validPipe(i - 1)
    }
    when(accept) { tagPipe(0) := io.in.bits.tag }
    for (i <- 1 until latency) {
      when(validPipe(i - 1)) { tagPipe(i) := tagPipe(i - 1) }
    }
  }

  responseQueue.io.enq.valid := validPipe(latency - 1)
  responseQueue.io.enq.bits.result := core.io.result_o
  responseQueue.io.enq.bits.status := core.io.status_o
  responseQueue.io.enq.bits.tag := tagPipe(latency - 1)
  assert(!responseQueue.io.enq.valid || responseQueue.io.enq.ready,
    "reserved YunSuan FMA result queue entry was unavailable")

  io.out <> responseQueue.io.deq

  when(resetOrFlush) {
    outstanding := 0.U
  }.otherwise {
    switch(Cat(accept, io.out.fire)) {
      is("b10".U) { outstanding := outstanding + 1.U }
      is("b01".U) { outstanding := outstanding - 1.U }
    }
  }
  io.busy := outstanding =/= 0.U

  when(io.in.valid) {
    assert(isFmadd || isFnmsub || isAdd || isMul,
      "Fp32FmaLane received an operation outside FMADD/FNMSUB/ADD/MUL")
  }
}
