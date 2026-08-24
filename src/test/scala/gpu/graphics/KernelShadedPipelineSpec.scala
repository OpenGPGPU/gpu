package gpu.graphics

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import gpu.config.GpuConfig
import org.scalatest.flatspec.AnyFlatSpec

class KernelShadedPipelineSpec extends AnyFlatSpec {
  behavior of "KernelShadedPipeline"

  // Pass-through shader: copy kernarg word0 (packed colour) to word1, halt.
  private val lw = BigInt("0000a503", 16)   // lw x10, 0(x1)
  private val sw = BigInt("00a0a223", 16)   // sw x10, 4(x1)
  private val cease = BigInt("30500073", 16)

  // Unified word-addressed memory model (byte address -> 32-bit word).  The OM
  // (word port), the compute unit (line port) and the word->line bridge (line
  // port) all read and write the same model.
  private class Mem {
    val m = scala.collection.mutable.LongMap[Int]()
    def word(addr: Long): Long = m.getOrElse(addr, 0) & 0xffffffffL
    def wordWrite(addr: Long, data: Int): Unit = m(addr) = data & 0xffffffff
    def lineRead(lineAddr: Long): BigInt =
      (0 until 16).map { i => BigInt(word(lineAddr + i * 4)) << (i * 32) }
        .foldLeft(BigInt(0))(_ | _)
    private def readWord(addr: Long): Long = word(addr)
    def lineWrite(lineAddr: Long, writeData: BigInt, byteMask: BigInt): Unit = {
      for (wi <- 0 until 16) {
        var wo = readWord(lineAddr + wi * 4)
        val base = wi * 4
        for (b <- 0 until 4) {
          val byte = base + b
          if (((byteMask >> byte) & 1) != 0) {
            val wb = (((writeData >> (byte * 8)) & 0xff)).toLong
            wo = (wo & ~(0xffL << (b * 8))) | (wb << (b * 8))
          }
        }
        m(lineAddr + wi * 4) = wo.toInt & 0xffffffff
      }
    }
  }

  it should "rasterize, shade via a core kernel, and depth-test to the framebuffer" in {
    val gfx = GraphicsConfig(screenWidth = 16, screenHeight = 16, subPixelBits = 8)
    val cfg = GpuConfig(lanes = 4, warps = 2)
    val stride = 16 * 4
    val colorBase = 0x8000
    val depthBase = 0x9000
    val shaderPc = 0x1000
    val kernargBase = 0x4000

    simulate(new KernelShadedPipeline(cfg, gfx)) { dut =>
      val mem = new Mem
      for (i <- 0 until (16 * 16)) mem.wordWrite(depthBase + i * 4, 0xffffffff)

      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      dut.io.colorBase.poke(colorBase.U)
      dut.io.depthBase.poke(depthBase.U)
      dut.io.stride.poke(stride.U)
      dut.io.depthTestEnable.poke(true.B)
      dut.io.depthFunc.poke(0.U)
      dut.io.depthWriteEnable.poke(true.B)
      dut.io.cullMode.poke(0.U)
      dut.io.shaderPc.poke(shaderPc.U)
      dut.io.kernargBase.poke(kernargBase.U)
      dut.io.mem.req.ready.poke(true.B)
      dut.io.mem.resp.valid.poke(false.B)
      dut.io.kernelMemReq.ready.poke(true.B)
      dut.io.kernelMemResp.valid.poke(false.B)
      dut.io.kernelMemResp.bits.readData.poke(0.U)
      dut.io.kernelMemResp.bits.fault.poke(false.B)
      dut.io.kernelMemResp.bits.transactionId.poke(0.U)
      dut.io.kernelWordMemReq.ready.poke(true.B)
      dut.io.kernelWordMemResp.valid.poke(false.B)
      dut.io.kernelWordMemResp.bits.readData.poke(0.U)
      dut.io.kernelWordMemResp.bits.fault.poke(false.B)
      dut.io.kernelWordMemResp.bits.transactionId.poke(0.U)

      // Seed the shader program into the shared word memory.
      mem.wordWrite(shaderPc + 0, lw.toInt)
      mem.wordWrite(shaderPc + 4, sw.toInt)
      mem.wordWrite(shaderPc + 8, cease.toInt)

      // Uniform triangle: every vertex is the same colour, so every fragment
      // has that colour and the pass-through shader reproduces it exactly.
      val r = 0xab; val g = 0xcd; val b = 0xef
      for (i <- 0 until 3) {
        dut.io.colors(i).r.poke(r.U); dut.io.colors(i).g.poke(g.U); dut.io.colors(i).b.poke(b.U)
        dut.io.depths(i).poke(0x20.S)
      }
      dut.io.draw.valid.poke(true.B)
      dut.io.draw.bits.v0.x.poke(0.S); dut.io.draw.bits.v0.y.poke(0.S)
      dut.io.draw.bits.v1.x.poke((16 << 8).S); dut.io.draw.bits.v1.y.poke(0.S)
      dut.io.draw.bits.v2.x.poke(0.S); dut.io.draw.bits.v2.y.poke((16 << 8).S)
      dut.clock.step()
      dut.io.draw.valid.poke(false.B)

      // Deferred-response harnesses for the three memory ports, all against one model.
      var omR = false; var omId = 0L; var omD = 0L
      var kuR = false; var kuId = BigInt(0); var kuD = BigInt(0)
      var wuR = false; var wuId = BigInt(0); var wuD = BigInt(0)
      var guard = 0
      while (!dut.io.done.peek().litToBoolean && guard < 60000) {
        // OM word port
        dut.io.mem.req.ready.poke(true.B)
        if (omR) { dut.io.mem.resp.valid.poke(true.B); dut.io.mem.resp.bits.data.poke(omD.U); omR = false }
        else dut.io.mem.resp.valid.poke(false.B)
        if (dut.io.mem.req.valid.peek().litToBoolean && dut.io.mem.req.ready.peek().litToBoolean) {
          val a = dut.io.mem.req.bits.addr.peek().litValue.toLong
          val write = dut.io.mem.req.bits.write.peek().litToBoolean
          val data = dut.io.mem.req.bits.data.peek().litValue.toLong
          if (write) mem.wordWrite(a, data.toInt)
          else { omR = true; omD = mem.word(a).toLong & 0xffffffffL }
        }
        // Compute unit line port
        dut.io.kernelMemResp.valid.poke(kuR)
        if (kuR) {
          dut.io.kernelMemResp.bits.transactionId.poke(kuId.U)
          dut.io.kernelMemResp.bits.readData.poke(kuD.U)
          dut.io.kernelMemResp.bits.fault.poke(false.B)
        }
        if (dut.io.kernelMemReq.valid.peek().litToBoolean && dut.io.kernelMemReq.ready.peek().litToBoolean) {
          val a = dut.io.kernelMemReq.bits.address.peek().litValue.toLong
          val id = dut.io.kernelMemReq.bits.transactionId.peek().litValue
          val write = dut.io.kernelMemReq.bits.isWrite.peek().litToBoolean
          if (write) {
            mem.lineWrite(a, dut.io.kernelMemReq.bits.writeData.peek().litValue,
              dut.io.kernelMemReq.bits.byteMask.peek().litValue); kuD = 0
          } else kuD = mem.lineRead(a)
          kuId = id; kuR = true
        } else kuR = false
        // Word->line bridge line port
        dut.io.kernelWordMemResp.valid.poke(wuR)
        if (wuR) {
          dut.io.kernelWordMemResp.bits.transactionId.poke(wuId.U)
          dut.io.kernelWordMemResp.bits.readData.poke(wuD.U)
          dut.io.kernelWordMemResp.bits.fault.poke(false.B)
        }
        if (dut.io.kernelWordMemReq.valid.peek().litToBoolean && dut.io.kernelWordMemReq.ready.peek().litToBoolean) {
          val a = dut.io.kernelWordMemReq.bits.address.peek().litValue.toLong
          val id = dut.io.kernelWordMemReq.bits.transactionId.peek().litValue
          val write = dut.io.kernelWordMemReq.bits.isWrite.peek().litToBoolean
          if (write) {
            mem.lineWrite(a, dut.io.kernelWordMemReq.bits.writeData.peek().litValue,
              dut.io.kernelWordMemReq.bits.byteMask.peek().litValue); wuD = 0
          } else wuD = mem.lineRead(a)
          wuId = id; wuR = true
        } else wuR = false
        dut.clock.step()
        guard += 1
      }
      assert(guard < 60000, "pipeline did not drain within the time bound")

      def rgb(x: Int, y: Int): (Int, Int, Int) = {
        val c = mem.word(colorBase + (y * 16 + x) * 4).toInt
        (((c >> 24) & 0xff), ((c >> 16) & 0xff), ((c >> 8) & 0xff))
      }
      assert(rgb(2, 2) == (r, g, b),
        s"kernel-shaded pixel (2,2) should be ($r,$g,$b), got ${rgb(2, 2)}")
      assert(rgb(1, 2) == (r, g, b),
        s"kernel-shaded pixel (1,2) should be ($r,$g,$b), got ${rgb(1, 2)}")
    }
  }
}
