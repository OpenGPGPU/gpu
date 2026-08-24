package gpu.graphics

import chisel3._
import chisel3.util._

/** Perspective-correct barycentric interpolation.
  *
  * Screen-space linear interpolation of a varying is wrong after a projective
  * transform.  The correct interpolant weights each vertex's attribute by its
  * 1/w (produced by GeometryStage) and renormalises by the sum of the 1/w
  * terms:
  *
  *   attr_px = (E0*a0*1/w0 + E1*a1*1/w1 + E2*a2*1/w2) / (E0*1/w0 + E1*1/w1 + E2*1/w2)
  *
  * where E_k are the rasterizer's (unnormalised) barycentric edge values and
  * the /area factor cancels.  This is exactly the operation a per-fragment
  * shader consumes on the SIMT lanes for M5.
  */
class PerspectiveInterpolator(config: GraphicsConfig) extends Module {
  val io = IO(new Bundle {
    val e0 = Input(SInt(config.edgeWidth.W))
    val e1 = Input(SInt(config.edgeWidth.W))
    val e2 = Input(SInt(config.edgeWidth.W))
    val invW0 = Input(SInt(32.W))
    val invW1 = Input(SInt(32.W))
    val invW2 = Input(SInt(32.W))
    val c0 = Input(new Varyings)
    val c1 = Input(new Varyings)
    val c2 = Input(new Varyings)
    val color = Output(new Varyings)
  })

  // denom = E0*1/w0 + E1*1/w1 + E2*1/w2  (positive for a front-facing tri).
  private val denom =
    io.e0 * io.invW0 + io.e1 * io.invW1 + io.e2 * io.invW2

  private def interp(a0: UInt, a1: UInt, a2: UInt): UInt = {
    val num =
      io.e0 * io.invW0 * a0 + io.e1 * io.invW1 * a1 + io.e2 * io.invW2 * a2
    (num / denom)(7, 0)
  }

  io.color.r := interp(io.c0.r, io.c1.r, io.c2.r)
  io.color.g := interp(io.c0.g, io.c1.g, io.c2.g)
  io.color.b := interp(io.c0.b, io.c1.b, io.c2.b)
}
