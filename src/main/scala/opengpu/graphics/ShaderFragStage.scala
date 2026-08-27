package opengpu.graphics

import chisel3._
import chisel3.util._

/** Shades a fragment stream through the SIMT shader core.
  *
  * For each incoming raster fragment, loads its interpolated colour into the
  * per-lane registers (r0/r1/r2 = R,G,B), runs the supplied shader program
  * once, and emits the lane-0 result as the shaded fragment (position and
  * depth pass through unchanged).  This is the bridge between the fixed
  * function raster/interpolate stages and the programmable SIMT shader core —
  * the wiring that M5 needs so shading is a program, not a hard-wired path.
  */
class ShaderFragStage(
  config: GraphicsConfig,
  lanes: Int = 1,
  progSize: Int = 8
) extends Module {
  private val shader = Module(new ShaderCore(lanes, regs = 8, progSize))

  val io = IO(new Bundle {
    val fragIn = Flipped(Decoupled(new RasterFragment(config)))
    val prog = Input(Vec(progSize, new ShaderOp))
    val programBase = Input(UInt(8.W))
    val uniform = Input(Vec(16, SInt(32.W)))
    val out = Decoupled(new RasterFragment(config))
  })

  private val idle :: sInit :: sRun :: sEmit :: Nil = Enum(4)
  private val state = RegInit(idle)
  private val fragX = Reg(SInt(config.coordWidth.W))
  private val fragY = Reg(SInt(config.coordWidth.W))
  private val fragDepth = Reg(SInt(32.W))
  private val fragColor = Reg(new Varyings)
  private val shaded = Reg(UInt(8.W))

  shader.io.uniform := io.uniform
  shader.io.prog := io.prog
  shader.io.programBase := io.programBase
  shader.io.start := state === sRun
  shader.io.init := state === sInit
  // Load the latched interpolated colour into lane0 r0/r1/r2.
  shader.io.initReg(0)(0) := fragColor.r.pad(32).asSInt
  shader.io.initReg(0)(1) := fragColor.g.pad(32).asSInt
  shader.io.initReg(0)(2) := fragColor.b.pad(32).asSInt
  for (r <- 3 until 8) shader.io.initReg(0)(r) := 0.S
  if (lanes > 1) {
    for (l <- 1 until lanes) for (r <- 0 until 8) shader.io.initReg(l)(r) := 0.S
  }

  io.fragIn.ready := state === idle
  io.out.valid := state === sEmit
  io.out.bits.x := fragX
  io.out.bits.y := fragY
  io.out.bits.depth := fragDepth
  io.out.bits.color.r := shaded
  io.out.bits.color.g := shaded
  io.out.bits.color.b := shaded

  switch(state) {
    is(idle) {
      when(io.fragIn.fire) {
        fragX := io.fragIn.bits.x
        fragY := io.fragIn.bits.y
        fragDepth := io.fragIn.bits.depth
        fragColor := io.fragIn.bits.color
        state := sInit
      }
    }
    is(sInit) {
      state := sRun
    }
    is(sRun) {
      when(shader.io.done) {
        shaded := shader.io.color(0)
        state := sEmit
      }
    }
    is(sEmit) {
      when(io.out.fire) { state := idle }
    }
  }
}
