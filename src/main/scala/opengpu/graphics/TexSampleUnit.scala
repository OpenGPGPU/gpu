package opengpu.graphics

import chisel3._
import chisel3.util._
import opengpu.config.GpuConfig
import opengpu.core.backend.{VectorCommitRequest, VectorTextureRequest}
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
  * The scalar instruction is warp-uniform. `vtex.sample` supplies one Q16.16
  * coordinate pair per vector lane; this unit serializes the active lanes
  * through the one physical sampler and preserves inactive destination lanes.
  */
class TexSampleUnit(
  config: GpuConfig = GpuConfig(),
  gfxConfig: GraphicsConfig = GraphicsConfig()
) extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new ScalarIssuedInstruction(config)))
    val commit = Decoupled(new ScalarCommitRequest(config))
    val vectorIn = Flipped(Decoupled(new VectorTextureRequest(config)))
    val vectorCommit = Decoupled(new VectorCommitRequest(config))
    val texBase = Input(UInt(32.W))
    val texWidth = Input(UInt(14.W))
    val texHeight = Input(UInt(14.W))
    val wrapClamp = Input(Bool())
    val texMaxLevel = Input(UInt(4.W))
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
  sampler.io.texMaxLevel := io.texMaxLevel
  io.mem.req <> sampler.io.mem.req
  sampler.io.mem.resp <> io.mem.resp

  private val sIdle :: sSample :: sCommit :: Nil = Enum(3)
  private val state = RegInit(sIdle)

  private val warpIdReg = RegInit(0.U(config.warpIdWidth.W))
  private val rdReg = RegInit(0.U(5.W))
  private val pcReg = RegInit(0.U(config.xLen.W))
  private val activeMaskReg = RegInit(0.U(config.lanes.W))
  private val dataReg = RegInit(0.U(32.W))
  private val vectorModeReg = RegInit(false.B)
  private val executionMaskReg = RegInit(0.U(config.lanes.W))
  private val vectorDataReg =
    Reg(Vec(config.lanes, UInt(config.xLen.W)))
  private val uVectorReg =
    Reg(Vec(config.lanes, UInt(config.xLen.W)))
  private val vVectorReg =
    Reg(Vec(config.lanes, UInt(config.xLen.W)))
  private val laneWidth = math.max(1, log2Ceil(config.lanes))
  private val laneReg = RegInit(0.U(laneWidth.W))
  // Latch the coordinates at accept: the upstream issued-instruction bits are
  // only stable through the handshake cycle (same race class as M4b/M5b).
  private val uReg = RegInit(0.U(32.W))
  private val vReg = RegInit(0.U(32.W))
  private val mipLevelReg = RegInit(0.U(4.W))

  /** One nearest mip per quad. UV differences remain Q16.16; multiplying by
    * the base extent converts them to texels-per-pixel in Q16.16. */
  private def absDiff(a: UInt, b: UInt): UInt = Mux(a >= b, a - b, b - a)
  private val automaticMip = WireDefault(0.U(4.W))
  if (config.lanes >= 4) {
    val u = io.vectorIn.bits.issued.vs1Data
    val v = io.vectorIn.bits.issued.vs2Data
    val gradients = Seq(
      absDiff(u(1), u(0)) * io.texWidth,
      absDiff(u(3), u(2)) * io.texWidth,
      absDiff(u(2), u(0)) * io.texWidth,
      absDiff(u(3), u(1)) * io.texWidth,
      absDiff(v(1), v(0)) * io.texHeight,
      absDiff(v(3), v(2)) * io.texHeight,
      absDiff(v(2), v(0)) * io.texHeight,
      absDiff(v(3), v(1)) * io.texHeight)
    val rho = gradients.reduce((a, b) => Mux(a >= b, a, b))
    for (level <- 1 until 16) {
      when(rho >= (BigInt(1) << (16 + level)).U) {
        automaticMip := level.U
      }
    }
  }

  // Alternate priority after every accepted instruction so independent scalar
  // and vector warps cannot starve one another at the shared physical sampler.
  private val preferVector = RegInit(true.B)
  private val selectVector =
    io.vectorIn.valid && (!io.in.valid || preferVector)
  io.vectorIn.ready := state === sIdle && selectVector
  io.in.ready := state === sIdle && !selectVector

  private val vectorLaneActive = executionMaskReg(laneReg)
  sampler.io.sample.valid :=
    state === sSample && (!vectorModeReg || vectorLaneActive)
  sampler.io.sample.bits.u :=
    Mux(vectorModeReg, uVectorReg(laneReg), uReg)
  sampler.io.sample.bits.v :=
    Mux(vectorModeReg, vVectorReg(laneReg), vReg)
  sampler.io.sample.bits.mipLevel := mipLevelReg
  sampler.io.result.ready :=
    state === sSample && (!vectorModeReg || vectorLaneActive)

  io.commit.valid := state === sCommit && !vectorModeReg
  io.commit.bits.warpId := warpIdReg
  io.commit.bits.nextPc := pcReg + 4.U
  io.commit.bits.activeMask := activeMaskReg
  io.commit.bits.writeRd := true.B
  io.commit.bits.rd := rdReg
  io.commit.bits.data := dataReg

  io.vectorCommit.valid := state === sCommit && vectorModeReg
  io.vectorCommit.bits.writeback.warpId := warpIdReg
  io.vectorCommit.bits.writeback.vd := rdReg
  io.vectorCommit.bits.writeback.data := vectorDataReg
  io.vectorCommit.bits.saturated := false.B
  io.vectorCommit.bits.writesVd := true.B
  io.vectorCommit.bits.flags := 0.U
  io.vectorCommit.bits.writesFlags := false.B
  io.vectorCommit.bits.pc := pcReg
  io.vectorCommit.bits.warpActiveMask := activeMaskReg

  switch(state) {
    is(sIdle) {
      when(io.vectorIn.fire) {
        vectorModeReg := true.B
        warpIdReg := io.vectorIn.bits.issued.decode.warpId
        rdReg := io.vectorIn.bits.issued.decode.instruction(11, 7)
        pcReg := io.vectorIn.bits.issued.decode.pc
        activeMaskReg := io.vectorIn.bits.issued.decode.activeMask
        executionMaskReg := io.vectorIn.bits.executionMask
        vectorDataReg := io.vectorIn.bits.issued.oldVdData
        uVectorReg := io.vectorIn.bits.issued.vs1Data
        vVectorReg := io.vectorIn.bits.issued.vs2Data
        mipLevelReg := automaticMip
        laneReg := 0.U
        preferVector := false.B
        state := sSample
      }.elsewhen(io.in.fire) {
        vectorModeReg := false.B
        warpIdReg := io.in.bits.decode.warpId
        rdReg := io.in.bits.decode.instruction(11, 7)
        pcReg := io.in.bits.decode.pc
        activeMaskReg := io.in.bits.decode.activeMask
        uReg := io.in.bits.rs1Data
        vReg := io.in.bits.rs2Data
        mipLevelReg := 0.U
        preferVector := true.B
        state := sSample
      }
    }
    is(sSample) {
      when(vectorModeReg) {
        when(vectorLaneActive) {
          when(sampler.io.result.fire) {
            vectorDataReg(laneReg) := sampler.io.result.bits
            when(laneReg === (config.lanes - 1).U) {
              state := sCommit
            }.otherwise {
              laneReg := laneReg + 1.U
            }
          }
        }.otherwise {
          when(laneReg === (config.lanes - 1).U) {
            state := sCommit
          }.otherwise {
            laneReg := laneReg + 1.U
          }
        }
      }.otherwise {
        when(sampler.io.result.fire) {
          dataReg := sampler.io.result.bits
          state := sCommit
        }
      }
    }
    is(sCommit) {
      when((vectorModeReg && io.vectorCommit.fire) ||
        (!vectorModeReg && io.commit.fire)) {
        state := sIdle
      }
    }
  }
}
