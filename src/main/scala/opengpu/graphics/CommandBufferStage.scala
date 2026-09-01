package opengpu.graphics

import chisel3._
import chisel3.util._

/** Draw-call command-buffer reader.
  *
  * Reads `count` sequential draw-call records from software-allocated host
  * memory starting at `base` and emits each as a SceneTriangle on `draw`.
  * A record is a fixed 40-word layout (little-endian word index):
  *
  *   [0..11]  v0/v1/v2 clip-space (x,y,z,w) as Q16.16   (12 words)
  *   [12..20] v0/v1/v2 colour (r,g,b) as 8-bit          (9 words)
  *   [21..23] v0/v1/v2 depth as signed 32-bit           (3 words)
  *   [24]     shader entry PC (the draw's shader descriptor)
  *   [25]     kernarg buffer address
  *   [26..31] v0/v1/v2 texture u,v as unsigned Q16.16
  *   [32]     optional depth/cull/texture state override
  *   [33]     signed integer LOD bias and minimum mip clamp
  *   [34..39] reserved
  *
  * This is the hardware side of "the driver writes a command list, the GPU
  * executes it", the prerequisite for a host-driven (M6) Linux device.  The
  * phase-D shader descriptor lets a draw select a compiled RV32 shader program
  * and point at it kernarg buffer.
  */
class CommandBufferStage(config: GraphicsConfig) extends Module {
  private val wordsPerRecord = 40

  val io = IO(new Bundle {
    val base = Input(UInt(32.W))
    val count = Input(UInt(16.W))
    val start = Input(Bool())
    val mem = new Bundle {
      val req = Decoupled(new OmMemoryRequest)
      val resp = Flipped(Decoupled(new OmMemoryResponse))
    }
    val draw = Decoupled(new SceneTriangle(config))
    val done = Output(Bool())
  })

  private val record = RegInit(0.U(16.W))
  private val word = RegInit(0.U(log2Ceil(wordsPerRecord).W)) // 0..39
  private val waiting = RegInit(false.B) // a read is in flight
  private val words = Reg(Vec(wordsPerRecord, UInt(32.W)))
  private val running = RegInit(false.B)
  private val presenting = RegInit(false.B)
  /**
    * Decouple command-buffer memory latency from rasterization.  The parser
    * may fill these entries while the pipeline is still rendering the
    * previous draw, but the FIFO remains the ordering point for draw records.
    */
  private val drawFifo = Module(new Queue(
    new SceneTriangle(config), config.drawFifoDepth))

  private val wordAddr = io.base + ((record * wordsPerRecord.U) + word) * 4.U

  private val decodedDraw = Wire(new SceneTriangle(config))
  decodedDraw := 0.U.asTypeOf(new SceneTriangle(config))
  decodedDraw.clip(0).x := words(0).asSInt
  decodedDraw.clip(0).y := words(1).asSInt
  decodedDraw.clip(0).z := words(2).asSInt
  decodedDraw.clip(0).w := words(3).asSInt
  decodedDraw.clip(1).x := words(4).asSInt
  decodedDraw.clip(1).y := words(5).asSInt
  decodedDraw.clip(1).z := words(6).asSInt
  decodedDraw.clip(1).w := words(7).asSInt
  decodedDraw.clip(2).x := words(8).asSInt
  decodedDraw.clip(2).y := words(9).asSInt
  decodedDraw.clip(2).z := words(10).asSInt
  decodedDraw.clip(2).w := words(11).asSInt
  decodedDraw.color(0).r := words(12)(7, 0)
  decodedDraw.color(0).g := words(13)(7, 0)
  decodedDraw.color(0).b := words(14)(7, 0)
  decodedDraw.color(1).r := words(15)(7, 0)
  decodedDraw.color(1).g := words(16)(7, 0)
  decodedDraw.color(1).b := words(17)(7, 0)
  decodedDraw.color(2).r := words(18)(7, 0)
  decodedDraw.color(2).g := words(19)(7, 0)
  decodedDraw.color(2).b := words(20)(7, 0)
  decodedDraw.depth(0) := words(21).asSInt
  decodedDraw.depth(1) := words(22).asSInt
  decodedDraw.depth(2) := words(23).asSInt
  decodedDraw.shaderPc := words(24)
  decodedDraw.shaderKernarg := words(25)
  decodedDraw.uv(0).u := words(26)
  decodedDraw.uv(0).v := words(27)
  decodedDraw.uv(1).u := words(28)
  decodedDraw.uv(1).v := words(29)
  decodedDraw.uv(2).u := words(30)
  decodedDraw.uv(2).v := words(31)
  decodedDraw.stateOverride := words(32)(0)
  decodedDraw.depthTestEnable := words(32)(1)
  decodedDraw.depthFunc := words(32)(6, 4)
  decodedDraw.depthWriteEnable := words(32)(7)
  decodedDraw.cullMode := words(32)(9, 8)
  decodedDraw.texEnable := words(32)(10)
  decodedDraw.texWrapClamp := words(32)(11)
  decodedDraw.texMaxLevel := words(32)(15, 12)
  decodedDraw.texLodBias := words(33)(4, 0).asSInt
  decodedDraw.texMinLevel := words(33)(11, 8)

  drawFifo.io.enq.valid := presenting
  drawFifo.io.enq.bits := decodedDraw
  io.draw <> drawFifo.io.deq

  // A start pulse is not itself a completed job.  This also makes the
  // contract robust for users that sample done in the launch cycle rather
  // than using the RenderHost sawBusy guard.
  io.done := !io.start && !running && !presenting && !waiting &&
    !drawFifo.io.deq.valid
  io.mem.req.valid := running && !presenting && !waiting
  io.mem.req.bits.addr := wordAddr
  io.mem.req.bits.write := false.B
  io.mem.req.bits.data := 0.U
  io.mem.resp.ready := running && waiting

  private val lastRecord = record === io.count - 1.U

  when(!running && io.start && io.count.orR && !drawFifo.io.deq.valid) {
    running := true.B
    record := 0.U
    word := 0.U
    waiting := false.B
    presenting := false.B
  }.elsewhen(waiting) {
    when(io.mem.resp.fire) {
      words(word) := io.mem.resp.bits.data
      waiting := false.B
      when(word === (wordsPerRecord - 1).U) {
        presenting := true.B
      }.otherwise {
        word := word + 1.U
      }
    }
  }.elsewhen(presenting) {
    when(drawFifo.io.enq.fire) {
      presenting := false.B
      when(lastRecord) {
        running := false.B
      }.otherwise {
        record := record + 1.U
        word := 0.U
      }
    }
  }.otherwise {
    // issue the next word read
    when(io.mem.req.fire) { waiting := true.B }
  }

}
