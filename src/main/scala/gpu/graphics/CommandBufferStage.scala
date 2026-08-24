package gpu.graphics

import chisel3._
import chisel3.util._

/** Draw-call command-buffer reader.
  *
  * Reads `count` sequential draw-call records from software-allocated host
  * memory starting at `base` and emits each as a SceneTriangle on `draw`.
  * A record is a fixed 24-word layout (little-endian word index):
  *
  *   [0..11]  v0/v1/v2 clip-space (x,y,z,w) as Q16.16   (12 words)
  *   [12..20] v0/v1/v2 colour (r,g,b) as 8-bit          (9 words)
  *   [21..23] v0/v1/v2 depth as signed 32-bit           (3 words)
  *
  * This is the hardware side of "the driver writes a command list, the GPU
  * executes it", the prerequisite for a host-driven (M6) Linux device.
  */
class CommandBufferStage(config: GraphicsConfig) extends Module {
  private val wordsPerRecord = 24

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
  private val word = RegInit(0.U(6.W)) // 0..23
  private val waiting = RegInit(false.B) // a read is in flight
  private val words = Reg(Vec(wordsPerRecord, UInt(32.W)))
  private val running = RegInit(false.B)
  private val presenting = RegInit(false.B)

  private val wordAddr = io.base + ((record * wordsPerRecord.U) + word) * 4.U

  io.done := !running
  io.mem.req.valid := running && !presenting && !waiting
  io.mem.req.bits.addr := wordAddr
  io.mem.req.bits.write := false.B
  io.mem.req.bits.data := 0.U
  io.mem.resp.ready := running && waiting

  io.draw.valid := presenting
  io.draw.bits.clip(0).x := words(0).asSInt
  io.draw.bits.clip(0).y := words(1).asSInt
  io.draw.bits.clip(0).z := words(2).asSInt
  io.draw.bits.clip(0).w := words(3).asSInt
  io.draw.bits.clip(1).x := words(4).asSInt
  io.draw.bits.clip(1).y := words(5).asSInt
  io.draw.bits.clip(1).z := words(6).asSInt
  io.draw.bits.clip(1).w := words(7).asSInt
  io.draw.bits.clip(2).x := words(8).asSInt
  io.draw.bits.clip(2).y := words(9).asSInt
  io.draw.bits.clip(2).z := words(10).asSInt
  io.draw.bits.clip(2).w := words(11).asSInt
  io.draw.bits.color(0).r := words(12)(7, 0)
  io.draw.bits.color(0).g := words(13)(7, 0)
  io.draw.bits.color(0).b := words(14)(7, 0)
  io.draw.bits.color(1).r := words(15)(7, 0)
  io.draw.bits.color(1).g := words(16)(7, 0)
  io.draw.bits.color(1).b := words(17)(7, 0)
  io.draw.bits.color(2).r := words(18)(7, 0)
  io.draw.bits.color(2).g := words(19)(7, 0)
  io.draw.bits.color(2).b := words(20)(7, 0)
  io.draw.bits.depth(0) := words(21).asSInt
  io.draw.bits.depth(1) := words(22).asSInt
  io.draw.bits.depth(2) := words(23).asSInt

  private val lastRecord = record === io.count - 1.U

  when(!running && io.start) {
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
    when(io.draw.fire) {
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
