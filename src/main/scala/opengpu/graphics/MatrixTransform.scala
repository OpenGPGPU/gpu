package opengpu.graphics

import chisel3._
import chisel3.util._

/** A 4x4 transform matrix in fixed-point Q16.16 (row-major). */
class Matrix4x4 extends Bundle {
  val m = Vec(4, Vec(4, SInt(32.W)))
}

/** Transform a clip/object-space 4-vector by a 4x4 matrix (fixed-point).
  *
  * clip = M * v, where each component is the dot product of a row of M with
  * the vertex.  This is the programmable model/view/projection transform of
  * the geometry front-end; it runs on the FPU/SIMT lanes in M5 and this block
  * is then retired.
  */
class MatrixTransform extends Module {
  val io = IO(new Bundle {
    val m = Input(new Matrix4x4)
    val v = Input(Vec(4, SInt(32.W)))
    val out = Output(Vec(4, SInt(32.W)))
  })

  for (i <- 0 until 4) {
    val acc =
      (io.m.m(i)(0) * io.v(0) + io.m.m(i)(1) * io.v(1) +
        io.m.m(i)(2) * io.v(2) + io.m.m(i)(3) * io.v(3))
    // Q16.16 * Q16.16 = Q32; shift 16 back to Q16.16.
    io.out(i) := (acc >> 16)(31, 0).asSInt
  }
}
