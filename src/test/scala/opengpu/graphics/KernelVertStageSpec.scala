package opengpu.graphics

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import opengpu.config.GpuConfig
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable.LongMap

class KernelVertStageSpec extends AnyFlatSpec {
  behavior of "KernelVertStage"

  private val LineMask = (BigInt(1) << 512) - 1

  private class MemModel {
    val lines = LongMap.empty[BigInt]
    def putWord(lineAddr: Long, wordIdx: Int, value: BigInt): Unit = {
      val base = lines.getOrElse(lineAddr, BigInt(0))
      val mask = (BigInt(0xffffffffL) << (wordIdx * 32))
      lines(lineAddr) = (base & ~mask) | (value << (wordIdx * 32))
    }
    def getWord(addr: Long): BigInt = {
      val line = lines.getOrElse(addr & ~63L, BigInt(0))
      (line >> ((addr & 63L).toInt * 8)) & BigInt("ffffffff", 16)
    }
    def readLine(addr: Long): BigInt = lines.getOrElse(addr & ~63L, BigInt(0))
    def applyWrite(addr: Long, writeData: BigInt, byteMask: BigInt): Unit = {
      var line = lines.getOrElse(addr, BigInt(0))
      for (b <- 0 until 64) {
        if (((byteMask >> b) & 1) != 0) {
          line = (line & ~(BigInt(0xff) << (b * 8))) |
            (((writeData >> (b * 8)) & 0xff) << (b * 8))
        }
      }
      lines(addr) = line
    }
  }

  // RV32I + V instruction encoders (matching KernelFragStageSpec patterns)
  private def lw(rd: Int, rs1: Int, imm: Int): BigInt =
    ((BigInt(imm & 0xfff)) << 20) | ((BigInt(rs1 & 0x1f)) << 15) |
      (BigInt(2) << 12) | ((BigInt(rd & 0x1f)) << 7) | 0x03
  private def sw(rs2: Int, rs1: Int, imm: Int): BigInt = {
    val imm12 = imm & 0xfff
    val imm11_5 = (imm12 >> 5) & 0x7f
    val imm4_0 = imm12 & 0x1f
    ((BigInt(imm11_5)) << 25) | ((BigInt(rs2 & 0x1f)) << 20) |
      ((BigInt(rs1 & 0x1f)) << 15) | (BigInt(2) << 12) |
      ((BigInt(imm4_0)) << 7) | 0x23
  }
  private def addi(rd: Int, rs1: Int, imm: Int): BigInt =
    ((BigInt(imm & 0xfff)) << 20) | ((BigInt(rs1 & 0x1f)) << 15) |
      ((BigInt(rd & 0x1f)) << 7) | 0x13
  private def slli(rd: Int, rs1: Int, shamt: Int): BigInt =
    ((BigInt(shamt & 0x1f)) << 20) | ((BigInt(rs1 & 0x1f)) << 15) |
      (BigInt(1) << 12) | ((BigInt(rd & 0x1f)) << 7) | 0x13
  private def add(rd: Int, rs1: Int, rs2: Int): BigInt =
    ((BigInt(rs2 & 0x1f)) << 20) | ((BigInt(rs1 & 0x1f)) << 15) |
      ((BigInt(rd & 0x1f)) << 7) | 0x33
  private def vsetivli(uimm: Int): BigInt =
    (BigInt(0x3) << 30) | (BigInt(0x10) << 20) | (BigInt(uimm & 0x1f) << 15) |
      (BigInt(0x7) << 12) | 0x57
  private def vle32(rs1: Int, vd: Int): BigInt =
    (BigInt(1) << 25) | (BigInt(0x6) << 12) | (BigInt(vd & 0x1f) << 7) |
      (BigInt(rs1 & 0x1f) << 15) | 0x07
  private def vse32(rs1: Int, vs3: Int): BigInt =
    (BigInt(1) << 25) | (BigInt(0x6) << 12) | (BigInt(vs3 & 0x1f) << 7) |
      (BigInt(rs1 & 0x1f) << 15) | 0x27
  private val cease = BigInt("30500073", 16)

  private val smallConfig = GpuConfig(lanes = 4, warps = 2)
  private val batchCap = smallConfig.warps * smallConfig.lanes // 8
  private val batchEff = (batchCap / 3) * 3 // 6
  private val arrayStride = 4 * batchCap // 32 bytes

  private val shaderPc = 0x1000L
  private val vbBase = 0x2000L
  private val vertStride = 32
  private val kernargBase = 0x8000L

  private def putInstr(mem: MemModel, offset: Int, instr: BigInt): Unit = {
    val addr = shaderPc + offset * 4
    val lineAddr = addr & ~63L
    val wordIdx = ((addr & 63L) >> 2).toInt
    mem.putWord(lineAddr, wordIdx, instr)
  }

  private def loadVertex(mem: MemModel, vertIdx: Int, posX: Int, posY: Int,
      posZ: Int, posW: Int, color: Long, depth: Int, texU: Int, texV: Int): Unit = {
    val base = vbBase + vertIdx * vertStride
    val lineAddr = base & ~63L
    val wordBase = ((base & 63L) >> 2).toInt
    mem.putWord(lineAddr, wordBase + 0, BigInt(posX))
    mem.putWord(lineAddr, wordBase + 1, BigInt(posY))
    mem.putWord(lineAddr, wordBase + 2, BigInt(posZ))
    mem.putWord(lineAddr, wordBase + 3, BigInt(posW))
    mem.putWord(lineAddr, wordBase + 4, BigInt(color))
    mem.putWord(lineAddr, wordBase + 5, BigInt(depth))
    mem.putWord(lineAddr, wordBase + 6, BigInt(texU & 0xffffffffL))
    mem.putWord(lineAddr, wordBase + 7, BigInt(texV & 0xffffffffL))
  }

  private def pump(dut: KernelVertStage, mem: MemModel, maxCycles: Int = 20000): Unit = {
    var kResp = false; var kId = BigInt(0); var kData = BigInt(0)
    var wResp = false; var wId = BigInt(0); var wData = BigInt(0)
    var cycles = 0
    while (cycles < maxCycles && !dut.io.done.peek().litToBoolean) {
      dut.io.memResp.valid.poke(kResp)
      if (kResp) {
        dut.io.memResp.bits.transactionId.poke(kId.U)
        dut.io.memResp.bits.readData.poke((kData & LineMask).U)
        dut.io.memResp.bits.fault.poke(false.B)
      }
      dut.io.wordMemResp.valid.poke(wResp)
      if (wResp) {
        dut.io.wordMemResp.bits.transactionId.poke(wId.U)
        dut.io.wordMemResp.bits.readData.poke((wData & LineMask).U)
        dut.io.wordMemResp.bits.fault.poke(false.B)
      }
      val kFired = dut.io.memReq.valid.peek().litToBoolean &&
        dut.io.memReq.ready.peek().litToBoolean
      if (kFired) {
        val addr = dut.io.memReq.bits.address.peek().litValue.toLong
        val id = dut.io.memReq.bits.transactionId.peek().litValue
        if (dut.io.memReq.bits.isWrite.peek().litToBoolean) {
          val wd = dut.io.memReq.bits.writeData.peek().litValue
          val bm = dut.io.memReq.bits.byteMask.peek().litValue
          mem.applyWrite(addr, wd, bm); kData = 0
        } else kData = mem.readLine(addr)
        kId = id; kResp = true
      } else kResp = false
      val wFired = dut.io.wordMemReq.valid.peek().litToBoolean &&
        dut.io.wordMemReq.ready.peek().litToBoolean
      if (wFired) {
        val addr = dut.io.wordMemReq.bits.address.peek().litValue.toLong
        val id = dut.io.wordMemReq.bits.transactionId.peek().litValue
        if (dut.io.wordMemReq.bits.isWrite.peek().litToBoolean) {
          val wd = dut.io.wordMemReq.bits.writeData.peek().litValue
          val bm = dut.io.wordMemReq.bits.byteMask.peek().litValue
          mem.applyWrite(addr, wd, bm); wData = 0
        } else wData = mem.readLine(addr)
        wId = id; wResp = true
      } else wResp = false
      dut.io.memReq.ready.poke(true.B)
      dut.io.wordMemReq.ready.poke(true.B)
      dut.clock.step()
      cycles += 1
    }
    dut.io.memResp.valid.poke(false.B)
    dut.io.wordMemResp.valid.poke(false.B)
  }

  private def pokeDefaults(dut: KernelVertStage): Unit = {
    dut.io.start.poke(false.B)
    dut.io.vertBufferBase.poke(0.U)
    dut.io.vertCount.poke(0.U)
    dut.io.vertStride.poke(0.U)
    dut.io.shaderPc.poke(0.U)
    dut.io.kernargBase.poke(0.U)
    dut.io.kernargBankStride.poke(0.U)
    dut.io.fragShaderPc.poke(0.U)
    dut.io.fragKernarg.poke(0.U)
    dut.io.fragKernargBankStride.poke(0.U)
    dut.io.stateOverride.poke(false.B)
    dut.io.depthTestEnable.poke(false.B)
    dut.io.depthFunc.poke(0.U)
    dut.io.depthWriteEnable.poke(false.B)
    dut.io.blendEnable.poke(false.B)
    dut.io.cullMode.poke(0.U)
    dut.io.texEnable.poke(false.B)
    dut.io.texWrapClamp.poke(false.B)
    dut.io.texMaxLevel.poke(0.U)
    dut.io.texLodBias.poke(0.S)
    dut.io.texMinLevel.poke(0.U)
    dut.io.vertOut.ready.poke(true.B)
    dut.io.memReq.ready.poke(true.B)
    dut.io.memResp.valid.poke(false.B)
    dut.io.memResp.bits.readData.poke(0.U)
    dut.io.memResp.bits.fault.poke(false.B)
    dut.io.memResp.bits.transactionId.poke(0.U)
    dut.io.wordMemReq.ready.poke(true.B)
    dut.io.wordMemResp.valid.poke(false.B)
    dut.io.wordMemResp.bits.readData.poke(0.U)
    dut.io.wordMemResp.bits.fault.poke(false.B)
    dut.io.wordMemResp.bits.transactionId.poke(0.U)
    dut.io.l1Invalidate.valid.poke(false.B)
    dut.io.l1Invalidate.bits.lineAddress.poke(0.U)
    dut.io.l1InvalidateDone.ready.poke(true.B)
    dut.io.globalAtomicRequest.ready.poke(true.B)
    dut.io.globalAtomicResponse.valid.poke(false.B)
    dut.io.globalAtomicResponse.bits.warpId.poke(0.U)
    dut.io.globalAtomicResponse.bits.oldValue.poke(0.U)
    dut.io.globalAtomicResponse.bits.fault.poke(false.B)
  }

  it should "pass through position data for a single vertex" in {
    val mem = new MemModel
    // Simple passthrough shader: copy pos_xyzw (fields 0-3) to outputs (fields 8-11)
    // Uses fixed offsets from x1 (kernarg base) since we're testing with 1 vertex.
    // x1 = kernarg base (set by hardware)
    var pc = 0
    for (f <- 0 until 4) {
      // Load pos_f from kernarg + f*stride
      putInstr(mem, pc, addi(10, 1, f * arrayStride)); pc += 1  // x10 = kernarg + f*stride
      putInstr(mem, pc, lw(11, 10, 0)); pc += 1  // x11 = pos_f[0]
      // Store to kernarg + (8+f)*stride
      putInstr(mem, pc, addi(12, 1, (8 + f) * arrayStride)); pc += 1  // x12 = kernarg + (8+f)*stride
      putInstr(mem, pc, sw(11, 12, 0)); pc += 1  // clip_f[0] = x11
    }
    putInstr(mem, pc, cease); pc += 1

    // Vertex buffer: 1 vertex (but vertCount must be >= 3 for the FSM to start)
    // We'll use 3 vertices but only check the first one
    val verts = Seq(
      (0x00010000, 0x00020000, 0x00030000, 0x00010000, 0xff0000ffL, 100, 0x8000, 0x8000),
      (0x00040000, 0x00050000, 0x00060000, 0x00010000, 0x00ff00ffL, 200, 0xC000, 0xC000),
      (0x00070000, 0x00080000, 0x00090000, 0x00010000, 0x0000ffffL, 300, 0x4000, 0x4000)
    )
    for ((v, i) <- verts.zipWithIndex)
      loadVertex(mem, i, v._1, v._2, v._3, v._4, v._5, v._6, v._7, v._8)

    simulate(new KernelVertStage(smallConfig)) { dut =>
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      pokeDefaults(dut)
      dut.io.shaderPc.poke(shaderPc.U)
      dut.io.kernargBase.poke(kernargBase.U)
      dut.io.kernargBankStride.poke(0.U)
      dut.io.vertBufferBase.poke(vbBase.U)
      dut.io.vertCount.poke(3.U)
      dut.io.vertStride.poke(vertStride.U)
      dut.io.fragShaderPc.poke(0x5000.U)
      dut.io.fragKernarg.poke(0x6000.U)
      dut.io.start.poke(true.B)
      dut.clock.step()
      dut.io.start.poke(false.B)

      pump(dut, mem)

      assert(dut.io.done.peek().litToBoolean, "stage must return to idle")
      // Check that vertex 0's outputs were written correctly
      // The shader uses fixed offsets, so it only processes vertex 0 (x8=0)
      val v = verts(0)
      assert(mem.getWord(kernargBase + 8 * arrayStride) == BigInt(v._1),
        s"vertex 0 clip_x mismatch")
      assert(mem.getWord(kernargBase + 9 * arrayStride) == BigInt(v._2),
        s"vertex 0 clip_y mismatch")
      assert(mem.getWord(kernargBase + 10 * arrayStride) == BigInt(v._3),
        s"vertex 0 clip_z mismatch")
      assert(mem.getWord(kernargBase + 11 * arrayStride) == BigInt(v._4),
        s"vertex 0 clip_w mismatch")
    }
  }

  it should "return to idle immediately for a draw with fewer than 3 vertices" in {
    val mem = new MemModel
    putInstr(mem, 0, cease)

    simulate(new KernelVertStage(smallConfig)) { dut =>
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      pokeDefaults(dut)
      dut.io.shaderPc.poke(shaderPc.U)
      dut.io.kernargBase.poke(kernargBase.U)
      dut.io.vertBufferBase.poke(vbBase.U)
      dut.io.vertCount.poke(2.U)
      dut.io.vertStride.poke(vertStride.U)
      dut.io.start.poke(true.B)
      dut.clock.step()
      dut.io.start.poke(false.B)

      dut.clock.step()
      assert(dut.io.done.peek().litToBoolean, "must be idle for < 3 vertices")
    }
  }

  it should "alternate complete kernarg banks between vertex batches" in {
    val mem = new MemModel
    putInstr(mem, 0, cease)
    val bankStride = 0x1000
    for (i <- 0 until 9)
      loadVertex(mem, i, 0x10000 + i, 0, 0, 0x10000,
        0xff0000ffL, i, 0, 0)

    simulate(new KernelVertStage(smallConfig)) { dut =>
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      pokeDefaults(dut)
      dut.io.shaderPc.poke(shaderPc.U)
      dut.io.kernargBase.poke(kernargBase.U)
      dut.io.kernargBankStride.poke(bankStride.U)
      dut.io.vertBufferBase.poke(vbBase.U)
      dut.io.vertCount.poke(9.U) // batchEff=6, followed by a 3-vertex batch
      dut.io.vertStride.poke(vertStride.U)
      dut.io.start.poke(true.B)
      dut.clock.step()
      dut.io.start.poke(false.B)

      pump(dut, mem)

      assert(dut.io.done.peek().litToBoolean, "stage must return to idle")
      assert(mem.getWord(kernargBase) == BigInt(0x10000),
        "first batch must use kernarg bank zero")
      assert(mem.getWord(kernargBase + bankStride) == BigInt(0x10006),
        "second batch must use kernarg bank one")
    }
  }
}
