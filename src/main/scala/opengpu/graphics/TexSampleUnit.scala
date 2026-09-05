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
    /** Signed integer bias applied after gradient LOD selection. */
    val lodBias = Input(SInt(5.W))
    /** Inclusive lower clamp; texMaxLevel is the inclusive upper clamp. */
    val minLevel = Input(UInt(4.W))
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

  // Vector LOD selection is deliberately pipelined.  The gradient subtracts,
  // extent multiplies, max reduction, mip walk, and log-fraction LUT each sit
  // in their own stage; even the gradient cone alone is far deeper than one
  // 1 GHz cycle between the issued vector request and mipFracReg.
  private val sIdle :: sGrad :: sMul :: sMaxPair :: sMax :: sMax2 :: sLod :: sFrac :: sSample :: sCommit :: Nil =
    Enum(10)
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
  private val mipFracReg = RegInit(0.U(8.W))
  private val rhoReg = RegInit(0.U(46.W))
  private val rhoNormalReg = RegInit(0.U(46.W))
  private val lodIsClampedReg = RegInit(false.B)
  private val lodBiasReg = RegInit(0.S(5.W))
  private val minLevelReg = RegInit(0.U(4.W))
  private val maxLevelReg = RegInit(0.U(4.W))
  private val texWidthReg = RegInit(0.U(14.W))
  private val texHeightReg = RegInit(0.U(14.W))

  /** One nearest mip per quad. UV differences remain Q16.16; multiplying by
    * the base extent converts them to texels-per-pixel in Q16.16. */
  private def absDiff(a: UInt, b: UInt): UInt = Mux(a >= b, a - b, b - a)
  private val diffReg = Reg(Vec(8, UInt(32.W)))
  private val gradReg = Reg(Vec(8, UInt(46.W)))
  private val rhoPairReg = Reg(Vec(4, UInt(46.W)))
  private val rhoLowReg = RegInit(0.U(46.W))
  private val rhoHighReg = RegInit(0.U(46.W))
  if (config.lanes >= 4) {
    val u = uVectorReg
    val v = vVectorReg
    diffReg(0) := absDiff(u(1), u(0))
    diffReg(1) := absDiff(u(3), u(2))
    diffReg(2) := absDiff(u(2), u(0))
    diffReg(3) := absDiff(u(3), u(1))
    diffReg(4) := absDiff(v(1), v(0))
    diffReg(5) := absDiff(v(3), v(2))
    diffReg(6) := absDiff(v(2), v(0))
    diffReg(7) := absDiff(v(3), v(1))
    val gradients = Seq(
      diffReg(0) * texWidthReg,
      diffReg(1) * texWidthReg,
      diffReg(2) * texWidthReg,
      diffReg(3) * texWidthReg,
      diffReg(4) * texHeightReg,
      diffReg(5) * texHeightReg,
      diffReg(6) * texHeightReg,
      diffReg(7) * texHeightReg)
    gradReg := VecInit(gradients)
  }
  private val automaticMip = WireDefault(0.U(4.W))
  if (config.lanes >= 4) {
    for (level <- 1 until 16) {
      when(rhoReg >= (BigInt(1) << (16 + level)).U) {
        automaticMip := level.U
      }
    }
  }
  private val biasedMip = automaticMip.zext + lodBiasReg
  private val clampedMip = Mux(biasedMip < minLevelReg.zext,
    minLevelReg, Mux(biasedMip > maxLevelReg.zext,
      maxLevelReg, biasedMip.asUInt(3, 0)))
  // The sampler consumes an 8-bit trilinear weight.  Normalise rho into
  // [1,2), then look up frac(log2(rho)) from the top eight fractional bits.
  // This is a 256-entry constant mux (not a divider or iterative logarithm),
  // and keeps the LOD error below one Q0.8 step plus input quantisation.
  // Clamp endpoints intentionally have no fractional blend, matching a
  // saturated LOD.
  private val rhoNormal = rhoNormalReg
  private val log2FracLut = VecInit(Seq.tabulate(256) { i =>
    math.floor(math.log(1.0 + i.toDouble / 256.0) / math.log(2.0) * 256.0)
      .toInt.U(8.W)
  })
  private val automaticFrac = Mux(rhoNormal > (1 << 16).U,
    log2FracLut(rhoNormal(15, 8)), 0.U)
  private val lodIsClamped = lodIsClampedReg
  private val clampedFrac = Mux(lodIsClamped, 0.U, automaticFrac)

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
  sampler.io.sample.bits.lodFrac := mipFracReg
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
        texWidthReg := io.texWidth
        texHeightReg := io.texHeight
        lodBiasReg := io.lodBias
        minLevelReg := io.minLevel
        maxLevelReg := io.texMaxLevel
        laneReg := 0.U
        preferVector := false.B
        state := sGrad
      }.elsewhen(io.in.fire) {
        vectorModeReg := false.B
        warpIdReg := io.in.bits.decode.warpId
        rdReg := io.in.bits.decode.instruction(11, 7)
        pcReg := io.in.bits.decode.pc
        activeMaskReg := io.in.bits.decode.activeMask
        uReg := io.in.bits.rs1Data
        vReg := io.in.bits.rs2Data
        val scalarBiased = io.lodBias
        mipLevelReg := Mux(scalarBiased < io.minLevel.zext,
          io.minLevel, Mux(scalarBiased > io.texMaxLevel.zext,
            io.texMaxLevel, scalarBiased.asUInt(3, 0)))
        // Scalar tex.sample has no derivatives, hence it is an exact selected
        // level sample even when a positive LOD bias chooses a higher mip.
        mipFracReg := 0.U
        preferVector := true.B
        state := sSample
      }
    }
    is(sGrad) {
      // Stage 2: UV differences only; the request-cycle logic stops at the
      // subtract so the multiply cone never sees the input port.
      state := sMul
    }
    is(sMul) {
      // Stage 3: extent multiplies, one 32x14 product per gradient entry.
      state := sMaxPair
    }
    is(sMaxPair) {
      // Stage 4: compare adjacent gradients in parallel.  Keep this explicit
      // rather than using reduce, which builds a serial comparator chain.
      for (i <- 0 until 4) {
        rhoPairReg(i) := Mux(
          gradReg(2 * i) >= gradReg(2 * i + 1),
          gradReg(2 * i),
          gradReg(2 * i + 1))
      }
      state := sMax
    }
    is(sMax) {
      // Stage 5: reduce the four pair maxima to two in parallel.
      rhoLowReg := Mux(rhoPairReg(0) >= rhoPairReg(1), rhoPairReg(0), rhoPairReg(1))
      rhoHighReg := Mux(rhoPairReg(2) >= rhoPairReg(3), rhoPairReg(2), rhoPairReg(3))
      state := sMax2
    }
    is(sMax2) {
      // Stage 6: final reduction into rhoReg.
      rhoReg := Mux(rhoLowReg >= rhoHighReg, rhoLowReg, rhoHighReg)
      state := sLod
    }
    is(sLod) {
      // Stage 7: select the nearest mip and register the normalized rho used
      // by the fractional-LOD lookup.
      rhoNormalReg := rhoReg >> automaticMip
      lodIsClampedReg := biasedMip < minLevelReg.zext ||
        biasedMip > maxLevelReg.zext
      mipLevelReg := clampedMip
      state := sFrac
    }
    is(sFrac) {
      // Stage 8: keep the LUT and its output mux off the request boundary.
      mipFracReg := clampedFrac
      state := sSample
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
