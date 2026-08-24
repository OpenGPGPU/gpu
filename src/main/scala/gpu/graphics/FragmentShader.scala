package gpu.graphics

import chisel3._
import chisel3.util._

/** A small fragment shader stage (M5a).
  *
  * Establishes the fragment -> uniform-bank -> program -> colour data flow that
  * the unified SIMT shader core will later execute.  Here the program is a
  * trivial fixed function over a per-draw uniform bank: the colour is
  * tinted (multiply by a per-draw RGB tint) and biased (add a per-draw signed
  * RGB bias), saturated to [0,255].  The SIMT lanes replace this datapath in
  * the full M5 with arbitrary RV32IMF+V shader programs.
  */
class FragmentShader(config: GraphicsConfig) extends Module {
  val io = IO(new Bundle {
    val fragIn = Flipped(Decoupled(new RasterFragment(config)))
    val uniformTint = Input(Vec(3, UInt(8.W)))
    val uniformBias = Input(Vec(3, SInt(8.W)))
    val out = Decoupled(new RasterFragment(config))
  })

  io.out.valid := io.fragIn.valid
  io.out.bits.x := io.fragIn.bits.x
  io.out.bits.y := io.fragIn.bits.y
  io.out.bits.depth := io.fragIn.bits.depth
  io.fragIn.ready := io.out.ready

  private def shade(c: UInt, tint: UInt, bias: SInt): UInt = {
    val base = (c * tint)(15, 8) // c*tint/256, interpreted unsigned
    val v = base.pad(16).asSInt + bias.pad(16)
    Mux(v < 0.S, 0.U, Mux(v > 255.S, 255.U, v.asUInt(7, 0)))
  }

  io.out.bits.color.r := shade(io.fragIn.bits.color.r, io.uniformTint(0), io.uniformBias(0))
  io.out.bits.color.g := shade(io.fragIn.bits.color.g, io.uniformTint(1), io.uniformBias(1))
  io.out.bits.color.b := shade(io.fragIn.bits.color.b, io.uniformTint(2), io.uniformBias(2))
}
