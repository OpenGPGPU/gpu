package opengpu.core.vector

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.execute.fpu.{Fp32FmaLane, Fp32Operation}

/** RVV OPFRED FP32 sum reductions for the fixed SEW=32 profile.
  *
  * `vfredusum.vs` and `vfredosum.vs` fold `vs1[0]` and the participating `vs2`
  * elements into element 0 of `vd`; every other element keeps the old
  * destination. The fold runs one element at a time through a single elastic
  * `Fp32FmaLane`, which is why both forms share one datapath: FP addition is
  * not associative, so a `lanes`-1 adder tree would silently break the ordered
  * `vfredosum.vs` while looking correct for the unordered one. A sequential
  * fold satisfies both and costs one lane of area instead of `lanes`-1. The
  * price is latency: one add per element, five pipeline stages each.
  *
  * A masked-off or inactive element issues no operation instead of adding an
  * identity, so a -0.0 accumulator cannot be turned into +0.0 the way an
  * added +0.0 identity would. Each add reports through the normal FP flag
  * path, so `frm` and the accumulated NV/NX flags match `vfadd.vv`.
  */
class VectorFReduceAlu(config: GpuConfig = GpuConfig()) extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new VectorFpuRequest(config)))
    val out = Decoupled(new VectorFpuResult(config))
  })

  private val tagWidth = config.warpIdWidth + 5
  private val adder = Module(new Fp32FmaLane(tagWidth))
  // Element counter plus one, so "every element dispatched" is representable.
  private val indexWidth = math.max(1, log2Ceil(config.lanes + 1))
  private val folding = RegInit(false.B)
  private val index = RegInit(0.U(indexWidth.W))
  // The counter runs one past the last element; fold it back into range so the
  // dynamic selects below never see an out-of-range index.
  private val foldedIndex = index(math.max(1, log2Ceil(config.lanes)) - 1, 0)
  // One add is in flight at a time: the accumulator is the next add's left
  // operand, so issuing two at once would fold a stale value.
  private val waiting = RegInit(false.B)
  private val accumulator = Reg(UInt(32.W))
  private val flags = Reg(UInt(5.W))
  private val inputBits = Reg(new VectorFpuRequest(config))
  private val outputValid = RegInit(false.B)
  private val outputBits = Reg(new VectorFpuResult(config))

  // v0 masks the participating elements for the masked form, and the warp's
  // active mask always applies. Element 0 of the result is written whenever
  // any element participates.
  private val sourceEnabled =
    inputBits.activeMask &
      Mux(inputBits.vm, Fill(config.lanes, 1.U), inputBits.predicateMask)
  private val anyParticipating = sourceEnabled.orR
  private val allDispatched = index >= config.lanes.U
  private val pendingElement =
    folding && !waiting && anyParticipating && !allDispatched
  private val finish =
    folding && (!anyParticipating || (allDispatched && !waiting))

  adder.io.flush := false.B
  adder.io.in.valid := pendingElement && sourceEnabled(foldedIndex)
  adder.io.in.bits.operation := Fp32Operation.add
  adder.io.in.bits.operationModifier := false.B
  adder.io.in.bits.exactFunction := 0.U
  adder.io.in.bits.roundingMode := inputBits.roundingMode
  // Fp32FmaLane computes Fp32Operation.add as operandC + operandB: it forces
  // the multiplier to 1.0 and emits the FMA, so the accumulator is the addend
  // in C and the new element is the one in B.
  adder.io.in.bits.operandA := accumulator
  adder.io.in.bits.operandB := inputBits.vs2(foldedIndex)
  adder.io.in.bits.operandC := accumulator
  adder.io.in.bits.tag := Cat(inputBits.warpId, inputBits.vd)
  adder.io.out.ready := folding
  // A non-participating element costs no pipeline slot: it only advances the
  // counter, so the fold stays in element order either way.
  when(pendingElement && !sourceEnabled(foldedIndex)) {
    index := index + 1.U
  }
  when(adder.io.in.fire) {
    index := index + 1.U
    waiting := true.B
  }
  when(adder.io.out.fire) {
    accumulator := adder.io.out.bits.result
    flags := flags | adder.io.out.bits.status
    waiting := false.B
  }

  private val outputReady = !outputValid || io.out.ready
  io.out.valid := outputValid
  io.out.bits := outputBits
  when(outputReady) {
    outputValid := finish
    folding := folding && !finish
    when(finish) {
      outputBits.warpId := inputBits.warpId
      outputBits.pc := inputBits.pc
      outputBits.warpActiveMask := inputBits.warpActiveMask
      outputBits.vd := inputBits.vd
      outputBits.data := inputBits.oldVd
      when(anyParticipating) {
        outputBits.data(0) := accumulator
      }
      outputBits.mask := 0.U
      outputBits.writesMask := false.B
      outputBits.saturated := false.B
      outputBits.flags := flags
      outputBits.writesFlags := anyParticipating
      outputBits.writesFloat := false.B
      outputBits.floatData := 0.U
    }
  }

  // One reduction at a time: the accumulator is a single register, so a second
  // request would need a second fold over the same state.
  io.in.ready := !folding && !outputValid
  when(io.in.fire) {
    folding := true.B
    inputBits := io.in.bits
    accumulator := io.in.bits.vs1(0)
    flags := 0.U
    index := 0.U
    waiting := false.B
  }

  when(io.in.valid) {
    assert(
      io.in.bits.operandType === "b001".U,
      "VectorFReduceAlu supports the OPFVV reduction forms only"
    )
  }
}
