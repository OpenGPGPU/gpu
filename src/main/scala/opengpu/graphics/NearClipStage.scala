package opengpu.graphics

import chisel3._
import chisel3.util._

/** A clip-space vertex with per-vertex attributes (Q16.16 position). */
class ClipVertexColor(config: GraphicsConfig) extends Bundle {
  val x = SInt(32.W)
  val y = SInt(32.W)
  val z = SInt(32.W)
  val w = SInt(32.W)
  val color = new Varyings
}

/** Near-plane (Sutherland-Hodgman) clipping.
  *
  * Clips a clip-space triangle against the plane w >= wNear, emitting 0, 1, or
  * 2 output triangles.  Cases:
  *   - all w >= wNear            -> 1 triangle (pass-through)
  *   - all w <  wNear            -> 0 triangles (rejected)
  *   - crossing                  -> interpolated vertices at w == wNear, up to
  *                                 two triangles (Sutherland-Hodgman fan).
  *
  * Driven by a `start` pulse; the result is held on `out`/`outValid` until the
  * next start.  wNear is a small positive Q16.16 constant so the downstream
  * 1/w divide stays finite.
  */
class NearClipStage(config: GraphicsConfig) extends Module {
  val io = IO(new Bundle {
    val tri = Input(Vec(3, new ClipVertexColor(config)))
    val wNear = Input(UInt(16.W))
    val start = Input(Bool())
    val busy = Output(Bool())
    val out = Output(Vec(6, new ClipVertexColor(config))) // up to 2 triangles x 3
    val outValid = Output(UInt(2.W)) // 0, 1, or 2 output triangles
  })

  private val wNearSInt = io.wNear.pad(32).asSInt
  private def isIn(v: ClipVertexColor): Bool = v.w >= wNearSInt

  // 0 = idle, 1 = latch v0, 2..3 = edge (prev->cur) for k in {0,1}, 4 = closing edge.
  private val tag = RegInit(0.U(3.W))
  private val running = RegInit(false.B)
  private val ready = RegInit(false.B)
  private val poly = Reg(Vec(4, new ClipVertexColor(config)))
  private val count = RegInit(0.U(3.W))

  private def interp(prev: ClipVertexColor, cur: ClipVertexColor): ClipVertexColor = {
    val o = Wire(new ClipVertexColor(config))
    val den = (cur.w - prev.w).pad(64)
    val num = (wNearSInt - prev.w).pad(64)
    def sd(a: SInt, b: SInt): SInt = (a.pad(64) + (b - a) * num / den)(31, 0).asSInt
    def su(a: UInt, b: UInt): UInt = {
      val ai = a.pad(64).asSInt
      val bi = b.pad(64).asSInt
      (ai + (bi - ai) * num / den)(31, 0).asUInt
    }
    o.x := sd(prev.x, cur.x)
    o.y := sd(prev.y, cur.y)
    o.z := sd(prev.z, cur.z)
    o.w := sd(prev.w, cur.w)
    o.color.r := su(prev.color.r, cur.color.r)
    o.color.g := su(prev.color.g, cur.color.g)
    o.color.b := su(prev.color.b, cur.color.b)
    o
  }

  // Process one edge (prev=tri(p), cur=tri(c)), appending into poly at count.
  private def edgeAppend(p: Int, c: Int): Unit = {
    val prev = io.tri(p)
    val cur = io.tri(c)
    val prevIn = isIn(prev)
    val curIn = isIn(cur)
    when(curIn && !prevIn) {
      poly(count) := interp(prev, cur)
      poly(count + 1.U) := cur
      count := count + 2.U
    }.elsewhen(curIn) {
      poly(count) := cur
      count := count + 1.U
    }.elsewhen(prevIn) {
      poly(count) := interp(prev, cur)
      count := count + 1.U
    }
  }

  when(!running && io.start) {
    running := true.B
    tag := 1.U
    count := 0.U
    ready := false.B
  }.elsewhen(running) {
    switch(tag) {
      is(1.U) { edgeAppend(0, 1); tag := 2.U }
      is(2.U) { edgeAppend(1, 2); tag := 3.U }
      is(3.U) { edgeAppend(2, 0); tag := 4.U }
      is(4.U) {
        running := false.B
        ready := true.B
      }
    }
  }

  io.busy := running
  io.outValid := Mux(ready, Mux(count === 4.U, 2.U, Mux(count >= 3.U, 1.U, 0.U)), 0.U)
  // Fan triangulation from poly(0): tri0 = (p0,p1,p2); tri1 = (p0,p2,p3).
  io.out(0) := Mux(ready, poly(0), 0.U.asTypeOf(new ClipVertexColor(config)))
  io.out(1) := Mux(ready, poly(1), 0.U.asTypeOf(new ClipVertexColor(config)))
  io.out(2) := Mux(ready, poly(2), 0.U.asTypeOf(new ClipVertexColor(config)))
  io.out(3) := Mux(ready, poly(0), 0.U.asTypeOf(new ClipVertexColor(config)))
  io.out(4) := Mux(ready, poly(2), 0.U.asTypeOf(new ClipVertexColor(config)))
  io.out(5) := Mux(ready, Mux(count === 4.U, poly(3), poly(2)), 0.U.asTypeOf(new ClipVertexColor(config)))
}
