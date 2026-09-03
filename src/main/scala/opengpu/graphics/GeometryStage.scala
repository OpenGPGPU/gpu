package opengpu.graphics

import chisel3._
import chisel3.util._

/** A clip-space vertex: x,y,z,w as signed fixed-point (Q16.16). */
class ClipVertex extends Bundle {
  val x = SInt(32.W)
  val y = SInt(32.W)
  val z = SInt(32.W)
  val w = SInt(32.W)
}

/** A screen-space vertex for the rasterizer: fixed-point (subPixel) position,
  * twice-the-1/w precision, and interpolated per-vertex attributes.
  */
class ScreenVertex(config: GraphicsConfig) extends Bundle {
  val sx = SInt(config.edgeWidth.W)
  val sy = SInt(config.edgeWidth.W)
  val invW = SInt(32.W)
  val color = new Varyings
}

/** Perspective divide + viewport mapping.
  *
  * Takes a clip-space triangle (Q16.16) and yields a screen-space triangle in
  * the rasterizer's fixed-point convention (subPixel bits) plus a per-vertex
  * 1/w for perspective-correct interpolation:
  *
  *   sx = ((x + w) * screenW * 2^subPixel) / (2*w)
  *   sy = ((y + w) * screenH * 2^subPixel) / (2*w)
  *   invW = 2^32 / |w|            (Q16.16 reciprocal)
  *
  * The per-vertex varyings pass through unchanged. `RenderPipeline` clips the
  * complete homogeneous view volume before presenting vertices here, so every
  * accepted vertex has a finite positive w.
  *
  * This is the durable, non-throwaway part of the geometry front-end — the
  * programmable 4x4 MVP transform is a separate, temporary block that M5
  * replaces with SIMT vertex shading.
  */
class GeometryStage(config: GraphicsConfig) extends Module {
  private val invWScale = BigInt(1) << 32

  val io = IO(new Bundle {
    val clip = Input(Vec(3, new ClipVertex))
    val color = Input(Vec(3, new Varyings))
    val screenW = Input(UInt(16.W))
    val screenH = Input(UInt(16.W))
    val out = Output(Vec(3, new ScreenVertex(config)))
  })

  private def mapCoord(cl: SInt, w: SInt, dim: UInt): SInt = {
    val num = (cl + w).pad(64) * dim.pad(64) * (1 << config.subPixelBits).S
    val den = (w.pad(64) << 1)
    (num / den)(config.edgeWidth - 1, 0).asSInt
  }

  private def invW(w: SInt): SInt = {
    val a = w.asUInt
    ((invWScale.U(64.W) / a)(31, 0)).asSInt
  }

  val outs = Wire(Vec(3, new ScreenVertex(config)))
  for (i <- 0 until 3) {
    outs(i).sx := mapCoord(io.clip(i).x, io.clip(i).w, io.screenW)
    outs(i).sy := mapCoord(io.clip(i).y, io.clip(i).w, io.screenH)
    outs(i).invW := invW(io.clip(i).w)
    outs(i).color := io.color(i)
  }
  io.out := outs
}
