package gpu.core.execute.fpu

import chisel3._
import chisel3.util._
import yunsuan.fpu.FloatFMA

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

  private val resetOrFlush = reset.asBool || io.flush
  private val core = withReset(resetOrFlush) { Module(new FloatFMA) }

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

  core.io.fp_a := Mux(isAdd, "h3f800000".U, io.in.bits.operandA)
  core.io.fp_b := io.in.bits.operandB
  core.io.fp_c := Mux(isMul, 0.U, io.in.bits.operandC)
  core.io.round_mode := io.in.bits.roundingMode
  core.io.fp_format := 2.U // FP32 only
  core.io.op_code := yunSuanOp
  core.io.fp_aIsFpCanonicalNAN :=
    core.io.fp_a(30, 0) === "h7fc00000".U
  core.io.fp_bIsFpCanonicalNAN :=
    core.io.fp_b(30, 0) === "h7fc00000".U
  core.io.fp_cIsFpCanonicalNAN :=
    core.io.fp_c(30, 0) === "h7fc00000".U

  private val responseQueue = withReset(resetOrFlush) {
    Module(new Queue(new Fp32Response(tagWidth), resultEntries))
  }
  private val outValid = RegInit(false.B)
  private val outBits = Reg(new Fp32Response(tagWidth))
  private val outCaptureReady = !outValid || io.out.ready
  private val outFire = outValid && io.out.ready
  private val outstanding = RegInit(0.U(log2Ceil(resultEntries + 1).W))
  private val canRecycleCredit = outFire
  io.in.ready := !io.flush &&
    (outstanding =/= resultEntries.U || canRecycleCredit)
  private val accept = io.in.valid && io.in.ready
  core.io.fire := accept

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

  // Register the FMA result one cycle before the credit/response queue so
  // the core-output to queue-pointer path never spans a full clock period.
  private val resultReady = RegInit(false.B)
  private val resultData = Reg(UInt(32.W))
  private val resultStatus = Reg(UInt(5.W))
  private val resultTag = Reg(UInt(tagWidth.W))
  when(resetOrFlush) {
    resultReady := false.B
  }.otherwise {
    resultReady := validPipe(latency - 1)
    when(validPipe(latency - 1)) {
      resultData := core.io.fp_result(31, 0)
      resultStatus := core.io.fflags
      resultTag := tagPipe(latency - 1)
    }
  }

  responseQueue.io.enq.valid := resultReady
  responseQueue.io.enq.bits.result := resultData
  responseQueue.io.enq.bits.status := resultStatus
  responseQueue.io.enq.bits.tag := resultTag
  assert(!responseQueue.io.enq.valid || responseQueue.io.enq.ready,
    "reserved YunSuan FMA result queue entry was unavailable")

  responseQueue.io.deq.ready := outCaptureReady
  io.out.valid := outValid
  io.out.bits := outBits
  when(outCaptureReady) {
    outValid := responseQueue.io.deq.valid
    when(responseQueue.io.deq.valid) {
      outBits := responseQueue.io.deq.bits
    }
  }

  when(resetOrFlush) {
    outstanding := 0.U
  }.otherwise {
    switch(Cat(accept, outFire)) {
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
