package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.backend.issue.ScalarIssuedInstruction
import opengpu.core.backend.writeback.ScalarCommitRequest

/** Execute-side backend for the `tex.sample rd, rs1, rs2` custom instruction
  * (opengpu graphics ISA extension, custom-0 space).
  *
  * The core decodes the instruction and dispatches it through the system
  * path with the operand values already read (`rs1Data` = u, `rs2Data` = v,
  * both unsigned Q16.16).  This unit drives the fixed-function sampler
  * ([[TextureUnit]]) and returns the packed RGBA8888 texel word through the
  * scalar commit path (writeRd, rd from the instruction, nextPc = pc + 4),
  * so the scoreboard's rd reservation is released by the ordinary commit.
  *
  * Execution is warp-uniform: the scalar register values define the sample
  * position, and every lane of the warp's `rd` receives the same texel word.
  * Per-lane texture coordinates are a follow-up (vector flavour of the same
  * instruction serialised across the active mask).
  */
class TexSampleUnit(
  config: GpuConfig = GpuConfig(),
  gfxConfig: GraphicsConfig = GraphicsConfig()
) extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new ScalarIssuedInstruction(config)))
    val commit = Decoupled(new ScalarCommitRequest(config))
    val texBase = Input(UInt(32.W))
    val texWidth = Input(UInt(14.W))
    val texHeight = Input(UInt(14.W))
    val wrapClamp = Input(Bool())
    val mem = new Bundle {
      val req = Decoupled(new OmMemoryRequest)
      val resp = Flipped(Decoupled(new OmMemoryResponse))
    }
  })

  private val sampler = Module(new TextureUnit(gfxConfig))

  sampler.io.texBase := io.texBase
  sampler.io.texWidth := io.texWidth
  sampler.io.texHeight := io.texHeight
  sampler.io.wrapMode := io.wrapClamp
  io.mem.req <> sampler.io.mem.req
  sampler.io.mem.resp <> io.mem.resp

  private val sIdle :: sSample :: sCommit :: Nil = Enum(3)
  private val state = RegInit(sIdle)

  private val warpIdReg = RegInit(0.U(config.warpIdWidth.W))
  private val rdReg = RegInit(0.U(5.W))
  private val pcReg = RegInit(0.U(config.xLen.W))
  private val activeMaskReg = RegInit(0.U(config.lanes.W))
  private val dataReg = RegInit(0.U(32.W))
  // Latch the coordinates at accept: the upstream issued-instruction bits are
  // only stable through the handshake cycle (same race class as M4b/M5b).
  private val uReg = RegInit(0.U(32.W))
  private val vReg = RegInit(0.U(32.W))

  // The in-flight instruction occupies the sampler for its whole latency, so
  // acceptance holds until the transaction fully completes.
  io.in.ready := state === sIdle
  sampler.io.sample.valid := state === sSample
  sampler.io.sample.bits.u := uReg
  sampler.io.sample.bits.v := vReg
  sampler.io.result.ready := state === sSample

  io.commit.valid := state === sCommit
  io.commit.bits.warpId := warpIdReg
  io.commit.bits.nextPc := pcReg + 4.U
  io.commit.bits.activeMask := activeMaskReg
  io.commit.bits.writeRd := true.B
  io.commit.bits.rd := rdReg
  io.commit.bits.data := dataReg

  switch(state) {
    is(sIdle) {
      when(io.in.fire) {
        warpIdReg := io.in.bits.decode.warpId
        rdReg := io.in.bits.decode.instruction(11, 7)
        pcReg := io.in.bits.decode.pc
        activeMaskReg := io.in.bits.decode.activeMask
        uReg := io.in.bits.rs1Data
        vReg := io.in.bits.rs2Data
        state := sSample
      }
    }
    is(sSample) {
      when(sampler.io.result.fire) {
        dataReg := sampler.io.result.bits
        state := sCommit
      }
    }
    is(sCommit) {
      when(io.commit.fire) { state := sIdle }
    }
  }
}
