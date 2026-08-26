package gpu.graphics

import chisel3._
import chisel3.util._

/** One-word memory request/response used by the Output Merger.
  *
  * The OM issues a single size-4 byte read (depth) or write (color + depth)
  * per RMW.  In the integrated SoC this sits on the shared memory hierarchy
  * (SharedL2Slice); the standalone test drives a memory model that holds the
  * software-allocated color/depth buffers.
  */
class OmMemoryRequest extends Bundle {
  val write = Bool()
  val addr = UInt(32.W)
  val data = UInt(32.W)
}

class OmMemoryResponse extends Bundle {
  val data = UInt(32.W)
  // Echoes the request's write bit.  Responses may arrive out of order (a
  // shared L2 arbitrates several clients/banks), so a write acknowledgement
  // can overtake a later read; recipients waiting on read data must check
  // this tag instead of assuming the next response is their read.
  val write = Bool()
}

/** A fragment presented to the Output Merger (integer pixel position). */
class OmFragment extends Bundle {
  val x = UInt(16.W)
  val y = UInt(16.W)
  val color = UInt(32.W) // RGBA8888
  val depth = UInt(30.W) // D24 in the low 24 bits of a 32-bit word
}

/** Depth-test / output-merge read-modify-write (ROB).
  *
  * Serially processes one fragment at a time: read the depth word at the
  * pixel, compare against the fragment depth using the programmable depth
  * func, and if it passes, write the color and (optionally) the new depth.
  * Serialization trivially preserves per-pixel submission order — the
  * in-flight pixel table is a later throughput optimization, not required
  * for correctness.  Writes complete fire-and-forget at request accept;
  * their acknowledgements are tagged (`OmMemoryResponse.write`) and popped
  * wherever they arrive, so an out-of-order memory system cannot let a stale
  * write ack impersonate a later fragment's depth-read data.
  *
  * Registers (driver-writable; inputs here): color/depth base addresses, row
  * stride in bytes, depth-test enable, depth func (0=less, 1=less-eq,
  * 2=greater, 3=greater-eq, 4=eq, 5=ne, 6=always, 7=never), depth-write
  * enable, and a blending-enable hook (blending out of scope for M3c).
  */
class OutputMerger(
  config: GraphicsConfig,
  colorBytesPerPixel: Int = 4,
  depthBytesPerPixel: Int = 4
) extends Module {
  private val bppColor = colorBytesPerPixel
  private val bppDepth = depthBytesPerPixel

  val io = IO(new Bundle {
    val fragIn = Flipped(Decoupled(new OmFragment))
    val mem = new Bundle {
      val req = Decoupled(new OmMemoryRequest)
      val resp = Flipped(Decoupled(new OmMemoryResponse))
    }
    val colorBase = Input(UInt(32.W))
    val depthBase = Input(UInt(32.W))
    val stride = Input(UInt(32.W)) // bytes per row
    val depthTestEnable = Input(Bool())
    val depthFunc = Input(UInt(3.W))
    val depthWriteEnable = Input(Bool())
    val blendEnable = Input(Bool()) // hook; blending not implemented
    val accepted = Output(Bool())
    val wroteDepth = Output(Bool())
    val wroteColor = Output(Bool())
  })

  private val idle :: sReadDepth :: sWaitDepth :: sWriteColor :: sWriteDepth :: Nil =
    Enum(5)
  private val state = RegInit(idle)

  private val fragX = Reg(UInt(16.W))
  private val fragY = Reg(UInt(16.W))
  private val fragColor = Reg(UInt(32.W))
  private val fragDepth = Reg(UInt(32.W))
  private val writeDepth = RegInit(false.B)

  private def wordAddr(ba: UInt, x: UInt, y: UInt, bpp: Int): UInt =
    ba + (y * io.stride) + (x * bpp.U)

  private val colorAddr = wordAddr(io.colorBase, fragX, fragY, bppColor)
  private val depthAddr = wordAddr(io.depthBase, fragX, fragY, bppDepth)

  io.fragIn.ready := state === idle
  io.accepted := io.fragIn.fire
  io.mem.resp.ready := true.B

  io.mem.req.valid := state === sReadDepth || state === sWriteColor || state === sWriteDepth
  io.mem.req.bits.addr := Mux(state === sReadDepth, depthAddr, Mux(state === sWriteColor, colorAddr, depthAddr))
  io.mem.req.bits.write := state === sWriteColor || state === sWriteDepth
  io.mem.req.bits.data := Mux(state === sWriteColor, fragColor, fragDepth)

  io.wroteColor := state === sWriteColor && io.mem.req.fire
  io.wroteDepth := state === sWriteDepth && io.mem.req.fire

  def depthPass(newDepth: UInt, stored: UInt): Bool =
    MuxLookup(io.depthFunc, true.B)(
      Seq(
        0.U -> (newDepth < stored),
        1.U -> (newDepth <= stored),
        2.U -> (newDepth > stored),
        3.U -> (newDepth >= stored),
        4.U -> (newDepth === stored),
        5.U -> (newDepth =/= stored),
        6.U -> true.B,
        7.U -> false.B
      )
    )

  switch(state) {
    is(idle) {
      when(io.fragIn.fire) {
        fragX := io.fragIn.bits.x
        fragY := io.fragIn.bits.y
        fragColor := io.fragIn.bits.color
        fragDepth := io.fragIn.bits.depth
        writeDepth := io.depthWriteEnable
        state := sReadDepth
      }
    }
    is(sReadDepth) {
      when(io.mem.req.fire) { state := sWaitDepth }
    }
    is(sWaitDepth) {
      // Only a read response carries this fragment's depth word.  Writes are
      // fire-and-forget (the OM advances at request accept), so write
      // acknowledgements from earlier fragments may still be in flight and —
      // through an out-of-order shared L2 — can arrive while this read is
      // awaited.  They are popped and ignored; the single outstanding read is
      // identified by write=false.  Read-after-write data ordering to the
      // same line is preserved by the memory system (the L2 holds reads while
      // a store transaction is active).
      when(io.mem.resp.fire && !io.mem.resp.bits.write) {
        val pass = Mux(!io.depthTestEnable, true.B, depthPass(fragDepth, io.mem.resp.bits.data))
        state := Mux(pass, sWriteColor, idle)
      }
    }
    is(sWriteColor) {
      when(io.mem.req.fire) {
        state := Mux(writeDepth, sWriteDepth, idle)
      }
    }
    is(sWriteDepth) {
      when(io.mem.req.fire) { state := idle }
    }
  }
}
