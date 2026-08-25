package gpu.graphics

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import gpu.config.GpuConfig
import org.scalatest.flatspec.AnyFlatSpec

class KernelFragStageSpec extends AnyFlatSpec {
  behavior of "KernelFragStage"

  // Pass-through shader: read packed colour at x1+0, write it to x1+4, halt.
  private val lw = BigInt("0000a503", 16)   // lw x10, 0(x1)
  private val sw = BigInt("00a0a223", 16)   // sw x10, 4(x1)
  private val cease = BigInt("30500073", 16)

  // Shared physical line memory model keyed by line address (per-byte writes).
  private class MemModel {
    val lines = scala.collection.mutable.LongMap[BigInt]()
    def putWord(line: Long, wordIdx: Int, value: BigInt): Unit = {
      val base = lines.getOrElse(line, BigInt(0))
      val mask = (BigInt(0xffffffffL) << (wordIdx * 32))
      lines(line) = (base & ~mask) | (value << (wordIdx * 32))
    }
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
    def readLine(addr: Long): BigInt = lines.getOrElse(addr, BigInt(0))
  }

  it should "shade one fragment via a core-backed kernel and read the output back" in {
    val config = GpuConfig(lanes = 4, warps = 2)
    simulate(new KernelFragStage(config)) { dut =>
      val mem = new MemModel
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      dut.io.fragIn.valid.poke(false.B)
      dut.io.fragIn.bits.x.poke(3.S)
      dut.io.fragIn.bits.y.poke(4.S)
      dut.io.fragIn.bits.depth.poke(0x20.S)
      dut.io.fragIn.bits.color.r.poke(0xab.U)
      dut.io.fragIn.bits.color.g.poke(0xcd.U)
      dut.io.fragIn.bits.color.b.poke(0xef.U)
      dut.io.out.ready.poke(true.B)
      dut.io.shaderPc.poke(0x1000.U)
      dut.io.kernargBase.poke(0x8000.U)
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

      // Preload the shader program into the shared line memory.
      mem.putWord(0x1000L, 0, lw)
      mem.putWord(0x1000L, 1, sw)
      mem.putWord(0x1000L, 2, cease)

      // Feed one fragment.
      dut.io.fragIn.valid.poke(true.B)
      dut.clock.step()
      dut.io.fragIn.valid.poke(false.B)

      // Serve the two memory ports (core + bridge) against the shared model.
      var kResp = false; var kId = BigInt(0); var kData = BigInt(0)
      var wResp = false; var wId = BigInt(0); var wData = BigInt(0)
      var guard = 0
      var done = false
      while (!done && guard < 400) {
        dut.io.memResp.valid.poke(kResp)
        if (kResp) {
          dut.io.memResp.bits.transactionId.poke(kId.U)
          dut.io.memResp.bits.readData.poke(kData.U)
          dut.io.memResp.bits.fault.poke(false.B)
        }
        dut.io.wordMemResp.valid.poke(wResp)
        if (wResp) {
          dut.io.wordMemResp.bits.transactionId.poke(wId.U)
          dut.io.wordMemResp.bits.readData.poke(wData.U)
          dut.io.wordMemResp.bits.fault.poke(false.B)
        }
        val kFired = dut.io.memReq.valid.peek().litToBoolean &&
          dut.io.memReq.ready.peek().litToBoolean
        if (kFired) {
          val addr = dut.io.memReq.bits.address.peek().litValue.toLong
          val id = dut.io.memReq.bits.transactionId.peek().litValue
          val isWrite = dut.io.memReq.bits.isWrite.peek().litToBoolean
          if (isWrite) {
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
          val isWrite = dut.io.wordMemReq.bits.isWrite.peek().litToBoolean
          if (isWrite) {
            val wd = dut.io.wordMemReq.bits.writeData.peek().litValue
            val bm = dut.io.wordMemReq.bits.byteMask.peek().litValue
            mem.applyWrite(addr, wd, bm); wData = 0
          } else wData = mem.readLine(addr)
          wId = id; wResp = true
        } else wResp = false
        dut.clock.step()
        guard += 1
        done = dut.io.out.valid.peek().litToBoolean
      }
      assert(guard < 400, "fragment did not finish within a bounded time")
      dut.io.out.bits.x.expect(3.S)
      dut.io.out.bits.y.expect(4.S)
      dut.io.out.bits.depth.expect(0x20.S)
      dut.io.out.bits.color.r.expect(0xab.U)
      dut.io.out.bits.color.g.expect(0xcd.U)
      dut.io.out.bits.color.b.expect(0xef.U)
    }
  }

  it should "shade a fragment through a compute kernel adding a uniform" in {
    // Program: x10 = kernarg word0 (packed colour), x11 = word2 (uniform),
    // x10 += x11, store x10 to word1, halt.  Verifies a real (non-pass-through)
    // kernel: output colour = input colour + a per-draw uniform, checked against
    // a software reference.
    val config = GpuConfig(lanes = 4, warps = 2)
    simulate(new KernelFragStage(config)) { dut =>
      val mem = new MemModel
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      dut.io.fragIn.valid.poke(false.B)
      dut.io.fragIn.bits.x.poke(3.S)
      dut.io.fragIn.bits.y.poke(4.S)
      dut.io.fragIn.bits.depth.poke(0x20.S)
      dut.io.fragIn.bits.color.r.poke(0xab.U)
      dut.io.fragIn.bits.color.g.poke(0xcd.U)
      dut.io.fragIn.bits.color.b.poke(0xef.U)
      dut.io.out.ready.poke(true.B)
      dut.io.shaderPc.poke(0x1000.U)
      dut.io.kernargBase.poke(0x8000.U)
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

      // Shader program at 0x1000: lw x10,0(x1); lw x11,8(x1); add x10,x10,x11;
      // sw x10,4(x1); cease.
      val lw0 = BigInt("0000a503", 16)      // lw x10, 0(x1)
      val lw1 = BigInt("0080a583", 16)      // lw x11, 8(x1)
      val add = BigInt("00b50533", 16)      // add x10, x10, x11
      val sw = BigInt("00a0a223", 16)       // sw x10, 4(x1)
      val cease = BigInt("30500073", 16)
      mem.putWord(0x1000L, 0, lw0)
      mem.putWord(0x1000L, 1, lw1)
      mem.putWord(0x1000L, 2, add)
      mem.putWord(0x1000L, 3, sw)
      mem.putWord(0x1000L, 4, cease)

      // Per-draw uniform at kernarg word2 (kernargBase+8): +1 on the blue byte.
      val uniform = BigInt("00000100", 16)
      mem.putWord(0x8000L, 2, uniform)

      dut.io.fragIn.valid.poke(true.B)
      dut.clock.step()
      dut.io.fragIn.valid.poke(false.B)

      var kResp = false; var kId = BigInt(0); var kData = BigInt(0)
      var wResp = false; var wId = BigInt(0); var wData = BigInt(0)
      var guard = 0
      var done = false
      while (!done && guard < 400) {
        dut.io.memResp.valid.poke(kResp)
        if (kResp) {
          dut.io.memResp.bits.transactionId.poke(kId.U)
          dut.io.memResp.bits.readData.poke(kData.U)
          dut.io.memResp.bits.fault.poke(false.B)
        }
        dut.io.wordMemResp.valid.poke(wResp)
        if (wResp) {
          dut.io.wordMemResp.bits.transactionId.poke(wId.U)
          dut.io.wordMemResp.bits.readData.poke(wData.U)
          dut.io.wordMemResp.bits.fault.poke(false.B)
        }
        val kFired = dut.io.memReq.valid.peek().litToBoolean &&
          dut.io.memReq.ready.peek().litToBoolean
        if (kFired) {
          val addr = dut.io.memReq.bits.address.peek().litValue.toLong
          val id = dut.io.memReq.bits.transactionId.peek().litValue
          val isWrite = dut.io.memReq.bits.isWrite.peek().litToBoolean
          if (isWrite) {
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
          val isWrite = dut.io.wordMemReq.bits.isWrite.peek().litToBoolean
          if (isWrite) {
            val wd = dut.io.wordMemReq.bits.writeData.peek().litValue
            val bm = dut.io.wordMemReq.bits.byteMask.peek().litValue
            mem.applyWrite(addr, wd, bm); wData = 0
          } else wData = mem.readLine(addr)
          wId = id; wResp = true
        } else wResp = false
        dut.clock.step()
        guard += 1
        done = dut.io.out.valid.peek().litToBoolean
      }
      assert(guard < 400, "fragment did not finish within a bounded time")

      // Reference: packed colour (0xab,0xcd,0xef,0xff) + uniform (blue+1) with no
      // cross-byte carry -> (0xab,0xcd,0xf0).
      val packed = BigInt("abcdefff", 16) // r g b alpha
      val ref = packed + uniform
      val expectedR = (ref >> 24) & 0xff
      val expectedG = (ref >> 16) & 0xff
      val expectedB = (ref >> 8) & 0xff
      dut.io.out.bits.x.expect(3.S)
      dut.io.out.bits.y.expect(4.S)
      dut.io.out.bits.color.r.expect(expectedR.U)
      dut.io.out.bits.color.g.expect(expectedG.U)
      dut.io.out.bits.color.b.expect(expectedB.U)
      assert(expectedB == 0xf0, "test setup must produce a blue+1 reference")
    }
  }
}
