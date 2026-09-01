package opengpu.graphics

import chisel3._
import chisel3.util._

/** Draw-scoped state held until the last shaded fragment has left the OM. */
class DrawContext extends Bundle {
  val shaderPc = UInt(32.W)
  val kernargBase = UInt(32.W)
  val kernargBankStride = UInt(32.W)
  val texBase = UInt(32.W)
  val texWidth = UInt(14.W)
  val texHeight = UInt(14.W)
  val texWrapClamp = Bool()
  val texMaxLevel = UInt(4.W)
  val texLodBias = SInt(5.W)
  val texMinLevel = UInt(4.W)
  val colorBase = UInt(32.W)
  val depthBase = UInt(32.W)
  val stride = UInt(32.W)
  val depthTestEnable = Bool()
  val depthFunc = UInt(3.W)
  val depthWriteEnable = Bool()
}

/**
  * Small draw-context FIFO with independently visible producer (tail) and
  * retirement (head) contexts.  `tail` is the most recently admitted draw;
  * it remains stable until the next enqueue, which is exactly what a batch
  * snapshot needs.  `head` remains stable through the output merger's final
  * read/modify/write and is removed only by `retire`.
  */
class DrawContextFifo(depth: Int = 2) extends Module {
  require(depth >= 2, "draw-context FIFO needs at least two entries")
  private val ptrWidth = math.max(1, log2Ceil(depth))
  private val countWidth = log2Ceil(depth + 1)

  val io = IO(new Bundle {
    val enq = Flipped(Decoupled(new DrawContext))
    val retire = Input(Bool())
    val head = Output(new DrawContext)
    val headValid = Output(Bool())
    val tail = Output(new DrawContext)
    val tailValid = Output(Bool())
  })

  private val entries = Reg(Vec(depth, new DrawContext))
  private val readPtr = RegInit(0.U(ptrWidth.W))
  private val writePtr = RegInit(0.U(ptrWidth.W))
  private val count = RegInit(0.U(countWidth.W))
  private val tailReg = RegInit(0.U.asTypeOf(new DrawContext))

  private def advance(ptr: UInt): UInt =
    Mux(ptr === (depth - 1).U, 0.U, ptr + 1.U)

  io.headValid := count =/= 0.U
  io.tailValid := count =/= 0.U
  io.head := Mux(io.headValid, entries(readPtr), 0.U.asTypeOf(new DrawContext))
  io.tail := tailReg

  // A retiring head frees a slot on the same edge for a newly admitted draw.
  io.enq.ready := count =/= depth.U || (io.retire && io.headValid)
  private val enqFire = io.enq.fire
  private val retireFire = io.retire && io.headValid

  when(enqFire) {
    entries(writePtr) := io.enq.bits
    writePtr := advance(writePtr)
    tailReg := io.enq.bits
  }
  when(retireFire) { readPtr := advance(readPtr) }
  when(enqFire && !retireFire) { count := count + 1.U }
  when(!enqFire && retireFire) { count := count - 1.U }
}
