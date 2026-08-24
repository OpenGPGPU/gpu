package gpu.graphics

import chisel3._
import chisel3.util._

/** A shader instruction for the SIMT shader core. */
class ShaderOp extends Bundle {
  val op = UInt(4.W)   // 0 NOP,1 LOADI,2 LDU,3 ADD,4 MUL,5 SUB,6 SAT,7 OUT
  val dst = UInt(4.W)  // destination register (or OUT's source)
  val a = UInt(4.W)    // source a (or uniform index for LDU / imm for LOADI)
  val b = UInt(4.W)    // source b (unused for most)
  val imm = SInt(16.W) // immediate for LOADI
}

/** A lock-step SIMT shader core.
  *
  * Executes a fixed program against a per-lane register file and a per-draw
  * uniform bank, one instruction per cycle in lock-step across all lanes —
  * the SIMT model that shades every fragment of a quad/warp together.  This is
  * the compute side of unified shading; the fixed-function FragmentShader is a
  * stand-in until a real RV32IMF(+V) shader program executes here.
  */
class ShaderCore(
  lanes: Int = 8,
  regs: Int = 8,
  progSize: Int = 16
) extends Module {
  val io = IO(new Bundle {
    val prog = Input(Vec(progSize, new ShaderOp))
    val uniform = Input(Vec(16, SInt(32.W)))
    val start = Input(Bool())
    val done = Output(Bool())
    val pc = Output(UInt(8.W))
    val color = Output(Vec(lanes, UInt(8.W)))
  })

  private val regFile = Reg(Vec(lanes, Vec(regs, SInt(32.W))))
  private val pc = RegInit(0.U(8.W))
  private val running = RegInit(false.B)
  private val outColor = Reg(Vec(lanes, UInt(8.W)))

  io.pc := pc
  io.done := !running

  private val inst = io.prog(pc)

  private def reg(idx: UInt, lane: Int): SInt = regFile(lane)(idx(log2Ceil(regs) - 1, 0))

  when(!running && io.start) {
    running := true.B
    pc := 0.U
  }.elsewhen(running) {
    // execute one instruction across all lanes (lock-step SIMT)
    for (lane <- 0 until lanes) {
      switch(inst.op) {
        is("h0".U) {} // NOP
        is("h1".U) { regFile(lane)(inst.dst(2, 0)) := inst.imm }
        is("h2".U) { regFile(lane)(inst.dst(2, 0)) := io.uniform(inst.a(3, 0)) }
        is("h3".U) { regFile(lane)(inst.dst(2, 0)) := reg(inst.a, lane) + reg(inst.b, lane) }
        is("h4".U) { regFile(lane)(inst.dst(2, 0)) := reg(inst.a, lane) * reg(inst.b, lane) }
        is("h5".U) { regFile(lane)(inst.dst(2, 0)) := reg(inst.a, lane) - reg(inst.b, lane) }
        is("h6".U) {
          val v = reg(inst.a, lane)
          regFile(lane)(inst.dst(2, 0)) := Mux(v < 0.S, 0.S, Mux(v > 255.S, 255.S, v))
        }
        is("h7".U) {
          val v = reg(inst.a, lane)
          outColor(lane) := Mux(v < 0.S, 0.U, Mux(v > 255.S, 255.U, v.asUInt(7, 0)))
        }
      }
    }
    when(pc === (progSize - 1).U) {
      running := false.B
    }.otherwise {
      pc := pc + 1.U
    }
  }

  // idle defaults
  io.color := outColor
}
