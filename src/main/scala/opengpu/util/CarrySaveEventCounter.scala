package opengpu.util

import chisel3._

/** Exact 64-bit event counter with carry-save state.
  *
  * A plain `counter + 1.U` commonly becomes a 64-bit ripple chain in the
  * open-source ASAP7 flow. Keeping the count as `sum + carry` makes the
  * every-cycle feedback update bitwise; the carry-propagate addition exists
  * only on the observation output and cannot become a register-to-register
  * critical path. The architectural value and one-event-per-cycle throughput
  * remain unchanged.
  */
class CarrySaveEventCounter(
  width: Int = 64
) extends Module {
  require(width > 0)

  val io = IO(new Bundle {
    val clear = Input(Bool())
    val increment = Input(Bool())
    val value = Output(UInt(width.W))
  })

  private val accumulator = Module(new CarrySaveAccumulator(width))
  accumulator.io.clear := io.clear
  accumulator.io.addend := io.increment.asUInt
  io.value := accumulator.io.value
}

/** Exact wide accumulator with carry-save state.
  *
  * Same rationale as [[CarrySaveEventCounter]], but accepts an arbitrary
  * addend each cycle (use 0.U when idle). `sum + carry` is unchanged when the
  * addend is zero, so observation stays bit-exact.
  */
class CarrySaveAccumulator(
  width: Int = 64
) extends Module {
  require(width > 0)

  val io = IO(new Bundle {
    val clear = Input(Bool())
    val addend = Input(UInt(width.W))
    val value = Output(UInt(width.W))
  })

  private val sum = RegInit(0.U(width.W))
  private val carry = RegInit(0.U(width.W))
  private val addend = io.addend
  private val nextSum = sum ^ carry ^ addend
  private val carryGenerate = (sum & carry) | (sum & addend) | (carry & addend)
  private val nextCarry = (carryGenerate << 1)(width - 1, 0)

  when(io.clear) {
    sum := 0.U
    carry := 0.U
  }.otherwise {
    sum := nextSum
    carry := nextCarry
  }

  io.value := (sum +& carry)(width - 1, 0)
}
