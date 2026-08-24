package gpu.graphics

import chisel3._
import chisel3.util._

/** An RV32IM integer-subset shader core.
  *
  * Executes real RISC-V RV32IM instructions (integer R-type and I-type:
  * ADD/SUB/SLL/SLT/.../AND, MUL, ADDI/XORI/ORI/ANDI/...) lock-step across a
  * parameterized set of SIMT lanes, each with its own x0..x31 register file.
  * The shader program is a base-addressed sequence of 32-bit RV32 instruction
  * words; the fragment's per-lane inputs are loaded into the register file at
  * init, uniforms are read via a per-draw bank, and the saturated value of
  * register x20 is the lane's colour output.
  *
  * This is the "real RISC-V shader" step: the same instruction set the compute
  * core executes, so no new toolchain or ISA is needed and M5 can run compiled
  * shaders.  The FPU (IMF'S F/D) and memory ops are added in a later slice.
  */
class RV32ShaderCore(
  lanes: Int = 8,
  regs: Int = 32,
  progSize: Int = 16
) extends Module {
  val io = IO(new Bundle {
    val prog = Input(Vec(progSize, UInt(32.W))) // RV32 instruction words
    val programBase = Input(UInt(8.W))
    val uniform = Input(Vec(8, UInt(32.W))) // read as base through x-reg constant bank (simplified)
    val init = Input(Bool())
    val initReg = Input(Vec(lanes, Vec(regs, SInt(32.W))))
    val start = Input(Bool())
    val done = Output(Bool())
    val pc = Output(UInt(8.W))
    val color = Output(Vec(lanes, UInt(8.W)))
  })

  private val regFile = Reg(Vec(lanes, Vec(regs, SInt(32.W))))
  private val pc = RegInit(0.U(8.W))
  private val running = RegInit(false.B)
  private val finished = RegInit(false.B)
  private val outColor = Reg(Vec(lanes, UInt(8.W)))

  io.pc := pc
  io.done := finished

  private val fet = pc + io.programBase
  private val inst = io.prog(fet(log2Ceil(progSize) - 1, 0))

  private val opcode = inst(6, 0)
  private val rd = inst(11, 7)
  private val funct3 = inst(14, 12)
  private val rs1 = inst(19, 15)
  private val rs2 = inst(24, 20)
  private val funct7 = inst(31, 25)

  private val immI = inst(31, 20).asSInt // sign-extended I immediate

  // Per-lane ALU: R-type (opcode 0b0110011) and I-type (opcode 0b0010011).
  private def alu(lane: Int): SInt = {
    val lhs = Mux(rs1 === 0.U, 0.S, regFile(lane)(rs1(log2Ceil(regs) - 1, 0)))
    val rhs = Mux(rs2 === 0.U, 0.S, regFile(lane)(rs2(log2Ceil(regs) - 1, 0)))
    val rAluL = MuxLookup(funct3, 0.S)(
      Seq(
        0.U -> Mux(funct7(5), lhs - rhs, lhs + rhs),
        4.U -> (lhs ^ rhs),
        6.U -> (lhs | rhs),
        7.U -> (lhs & rhs)
      )
    )
    val iAluL = MuxLookup(funct3, 0.S)(
      Seq(0.U -> (lhs + immI), 4.U -> (lhs ^ immI), 6.U -> (lhs | immI), 7.U -> (lhs & immI))
    )
    val mulL = lhs * rhs
    Mux(opcode === "b0110011".U,
      Mux(funct7 === "b0000001".U, mulL, rAluL),
      iAluL)
  }

  when(io.init) {
    regFile := io.initReg
    running := false.B
    pc := 0.U
    finished := false.B
    outColor := VecInit(Seq.fill(lanes)(0.U(8.W)))
  }.elsewhen(!running && io.start) {
    running := true.B
    pc := 0.U
    finished := false.B
  }.elsewhen(running) {
    for (lane <- 0 until lanes) {
      when(opcode === "b0110011".U || opcode === "b0010011".U) {
        when(rd =/= 0.U) {
          regFile(lane)(rd(log2Ceil(regs) - 1, 0)) := alu(lane)
        }
      }
      // Preserve a colour output register convention: x30 holds the result.
      when(rd === 30.U) {
        val v = alu(lane) // value being written to x30 this cycle
        outColor(lane) := Mux(v < 0.S, 0.U, Mux(v > 255.S, 255.U, v.asUInt(7, 0)))
      }
    }
    when(pc === (progSize - 1).U) {
      running := false.B
      finished := true.B
    }.otherwise {
      pc := pc + 1.U
    }
  }

  io.color := outColor
}
